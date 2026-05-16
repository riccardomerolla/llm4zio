package app.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import _root_.config.entity.ConfigRepository
import project.entity.{ ProjectEvent, ProjectRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import workspace.entity.{ RunMode, WorkspaceEvent, WorkspaceRepository }

/** Phase 5 (big-review R10): /onboarding wizard controller.
  *
  * GET /onboarding renders the four-step form pre-filled with defaults
  * read from the current config (so the page can serve as edit-state too).
  *
  * POST /onboarding writes:
  *   - `ai.provider`, `ai.model`, `ai.apiKey`
  *   - `telegram.enabled`, `telegram.botToken`, `telegram.supervisorChatId`
  *   - `onboarding.completedAt` (sentinel for future first-run gating)
  * and creates one Project + one Workspace (linked to that project).
  */
trait OnboardingController:
  def routes: Routes[Any, Response]

object OnboardingController:

  def routes: ZIO[OnboardingController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[OnboardingController](_.routes)

  val live: ZLayer[
    ConfigRepository & ProjectRepository & WorkspaceRepository & TelegramTokenTester,
    Nothing,
    OnboardingController,
  ] =
    ZLayer.fromFunction(OnboardingControllerLive.apply)

final case class OnboardingControllerLive(
  configRepository: ConfigRepository,
  projectRepository: ProjectRepository,
  workspaceRepository: WorkspaceRepository,
  tokenTester: TelegramTokenTester,
) extends OnboardingController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "onboarding"                    -> handler {
      show.catchAll(err => ZIO.succeed(persistErr(err)))
    },
    Method.POST / "onboarding"                   -> handler { (req: Request) =>
      submit(req).catchAll(err => ZIO.succeed(persistErr(err)))
    },
    Method.POST / "onboarding" / "test-telegram" -> handler { (req: Request) =>
      testTelegram(req).catchAllCause(cause =>
        ZIO.succeed(testResult(TelegramTokenTester.Result(ok = false, "Unexpected error checking the token.", Some(cause.prettyPrint.take(140)))))
      )
    },
  )

  private def show: IO[PersistenceError, Response] =
    for
      rows <- configRepository.getAllSettings
      map   = rows.map(r => r.key -> r.value).toMap
      defaults = OnboardingView.Defaults(
                   aiProvider = map.getOrElse("ai.provider", "Anthropic"),
                   aiModel = map.getOrElse("ai.model", "claude-sonnet-4-6"),
                   telegramEnabled = map.get("telegram.enabled").exists(_.equalsIgnoreCase("true")),
                   telegramBotToken = map.getOrElse("telegram.botToken", ""),
                   telegramSupervisorChatId = map.getOrElse("telegram.supervisorChatId", ""),
                   projectName = "My Project",
                   workspaceName = "main",
                   workspaceLocalPath = "",
                 )
      done = map.get("onboarding.completedAt").exists(_.trim.nonEmpty)
    yield html(
      OnboardingView.page(
        defaults,
        flash = if done then Some("You've already completed onboarding — these are your current values.") else None,
      )
    )

  private def submit(req: Request): IO[PersistenceError, Response] =
    for
      form        <- parseForm(req)
      projectName  = trimmedRequired(form, "projectName", "Project name")
      workspaceN   = trimmedRequired(form, "workspaceName", "Workspace name")
      localPath    = trimmedRequired(form, "workspaceLocalPath", "Local git path")
      result      <- (projectName, workspaceN, localPath) match
                       case (Right(pn), Right(wn), Right(lp)) =>
                         finish(form, pn, wn, lp).map(Right(_))
                       case _                                  =>
                         val first = List(projectName, workspaceN, localPath).collectFirst { case Left(msg) => msg }
                         renderWithError(form, first.getOrElse("Missing required field")).map(Left(_))
    yield result.fold(identity, identity)

  private def finish(
    form: Map[String, String],
    projectName: String,
    workspaceName: String,
    workspaceLocalPath: String,
  ): IO[PersistenceError, Response] =
    for
      now         <- Clock.instant
      _           <- saveConfig(form, now)
      projectId    = ProjectId(java.util.UUID.randomUUID().toString)
      _           <- projectRepository.append(
                       ProjectEvent.ProjectCreated(
                         projectId = projectId,
                         name = projectName,
                         description = None,
                         occurredAt = now,
                       )
                     )
      workspaceId  = java.util.UUID.randomUUID().toString
      _           <- workspaceRepository.append(
                       WorkspaceEvent.Created(
                         workspaceId = workspaceId,
                         projectId = projectId,
                         name = workspaceName,
                         localPath = workspaceLocalPath,
                         defaultAgent = None,
                         description = None,
                         cliTool = "claude",
                         runMode = RunMode.Host,
                         occurredAt = now,
                       )
                     )
    yield Response.redirect(URL.decode("/").toOption.get, isPermanent = false)

  private def saveConfig(form: Map[String, String], now: java.time.Instant): IO[PersistenceError, Unit] =
    val pairs = List(
      "ai.provider"                -> form.getOrElse("aiProvider", "Anthropic").trim,
      "ai.model"                   -> form.getOrElse("aiModel", "").trim,
      "ai.apiKey"                  -> form.getOrElse("aiApiKey", "").trim,
      "telegram.enabled"           -> (if form.get("telegramEnabled").contains("true") then "true" else "false"),
      "telegram.botToken"          -> form.getOrElse("telegramBotToken", "").trim,
      "telegram.supervisorChatId"  -> form.getOrElse("telegramSupervisorChatId", "").trim,
      "onboarding.completedAt"     -> now.toString,
    )
    ZIO.foreachDiscard(pairs) { case (key, value) => configRepository.upsertSetting(key, value) }

  private def testTelegram(req: Request): UIO[Response] =
    for
      form  <- parseForm(req).orElseSucceed(Map.empty[String, String])
      token  = form.getOrElse("telegramBotToken", "").trim
      result <-
        if token.isEmpty then
          ZIO.succeed(TelegramTokenTester.Result(ok = false, "Paste a bot token first.", None))
        else if !token.matches("""\d+:[A-Za-z0-9_-]{10,}""") then
          ZIO.succeed(
            TelegramTokenTester.Result(
              ok = false,
              "That doesn't look like a bot token (expected like 123456:ABC…).",
              None,
            )
          )
        else tokenTester.test(token)
    yield testResult(result)

  private def testResult(result: TelegramTokenTester.Result): Response =
    val tone =
      if result.ok then "border-emerald-500/40 bg-emerald-500/10 text-emerald-200"
      else "border-rose-500/40 bg-rose-500/10 text-rose-200"
    val icon = if result.ok then "✓" else "✗"
    val detailHtml =
      result.detail.filter(_.trim.nonEmpty).map { d =>
        val safe = d.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        s"""<div class="mt-1 text-xs text-slate-400">$safe</div>"""
      }.getOrElse("")
    val safeMessage = result.message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val body =
      s"""<div class="rounded-md border $tone px-3 py-2 text-sm"><span class="mr-1 font-semibold">$icon</span>$safeMessage$detailHtml</div>"""
    Response.text(body).contentType(MediaType.text.html)

  private def renderWithError(form: Map[String, String], message: String): IO[PersistenceError, Response] =
    val defaults = OnboardingView.Defaults(
      aiProvider = form.getOrElse("aiProvider", "Anthropic"),
      aiModel = form.getOrElse("aiModel", ""),
      telegramEnabled = form.get("telegramEnabled").contains("true"),
      telegramBotToken = form.getOrElse("telegramBotToken", ""),
      telegramSupervisorChatId = form.getOrElse("telegramSupervisorChatId", ""),
      projectName = form.getOrElse("projectName", ""),
      workspaceName = form.getOrElse("workspaceName", ""),
      workspaceLocalPath = form.getOrElse("workspaceLocalPath", ""),
    )
    ZIO.succeed(html(OnboardingView.page(defaults, error = Some(message))))

  private def trimmedRequired(form: Map[String, String], key: String, label: String): Either[String, String] =
    form.get(key).map(_.trim).filter(_.nonEmpty).toRight(s"$label is required")

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .mapError(err => PersistenceError.QueryFailed("onboarding_form", err.toString))
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap(_.split("=", 2) match
            case Array(k, v) => Some(decode(k) -> decode(v))
            case Array(k)    => Some(decode(k) -> "")
            case _           => None
          )
          .toMap
      }

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def html(body: String): Response =
    Response.text(body).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Store unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Onboarding failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Serialization failed: $cause").status(Status.InternalServerError)
