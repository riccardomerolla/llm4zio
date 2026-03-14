package orchestration.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import orchestration.control.{ PlannerAgentError, PlannerAgentService, PlannerIssueDraft, PlannerPlanPreview }
import shared.web.PlannerView
import workspace.entity.WorkspaceRepository

trait PlannerController:
  def routes: Routes[Any, Response]

object PlannerController:
  val live: ZLayer[PlannerAgentService & WorkspaceRepository, Nothing, PlannerController] =
    ZLayer.fromFunction(PlannerControllerLive.apply)

final case class PlannerControllerLive(
  plannerAgentService: PlannerAgentService,
  workspaceRepository: WorkspaceRepository,
) extends PlannerController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "planner"                                      -> handler { (_: Request) =>
      workspaceRepository.list
        .mapError(mapWorkspaceError)
        .map(workspaces => html(PlannerView.startPage(workspaces.map(ws => ws.id -> ws.name))))
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
    Method.POST / "planner"                                     -> handler { (req: Request) =>
      (for
        form           <- parseMultiForm(req)
        request        <- required(form, "request")
        workspaceId     = optional(form, "workspace_id").filterNot(_ == "chat")
        conversationId <- plannerAgentService.startSession(request, workspaceId)
      yield Response.redirect(URL.decode(s"/planner/$conversationId").toOption.getOrElse(URL.root)))
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
    Method.GET / "planner" / long("id")                         -> handler { (conversationId: Long, _: Request) =>
      (for
        preview    <- plannerAgentService.getPreview(conversationId)
        workspaces <- workspaceRepository.list.mapError(mapWorkspaceError)
      yield html(PlannerView.detailPage(preview, workspaces.map(ws => ws.id -> ws.name))))
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
    Method.POST / "planner" / long("id") / "chat"               -> handler { (conversationId: Long, req: Request) =>
      (for
        form <- parseMultiForm(req)
        msg  <- required(form, "message")
        _    <- plannerAgentService.appendUserMessage(conversationId, msg)
      yield Response.redirect(URL.decode(s"/planner/$conversationId").toOption.getOrElse(URL.root)))
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
    Method.POST / "planner" / long("id") / "refresh"            -> handler { (conversationId: Long, _: Request) =>
      plannerAgentService.regeneratePreview(conversationId)
        .as(Response.redirect(URL.decode(s"/planner/$conversationId").toOption.getOrElse(URL.root)))
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
    Method.POST / "planner" / long("id") / "preview"            -> handler { (conversationId: Long, req: Request) =>
      (for
        form    <- parseMultiForm(req)
        preview <- parsePreviewForm(form)
        _       <- plannerAgentService.updatePreview(conversationId, preview)
      yield Response.redirect(URL.decode(s"/planner/$conversationId").toOption.getOrElse(URL.root)))
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
    Method.POST / "planner" / long("id") / "preview" / "add"    -> handler { (conversationId: Long, _: Request) =>
      plannerAgentService.addBlankIssue(conversationId)
        .as(Response.redirect(URL.decode(s"/planner/$conversationId").toOption.getOrElse(URL.root)))
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
    Method.POST / "planner" / long("id") / "preview" / "remove" -> handler { (conversationId: Long, req: Request) =>
      (for
        form    <- parseMultiForm(req)
        draftId <- required(form, "draft_id")
        _       <- plannerAgentService.removeIssue(conversationId, draftId)
      yield Response.redirect(URL.decode(s"/planner/$conversationId").toOption.getOrElse(URL.root)))
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
    Method.POST / "planner" / long("id") / "confirm"            -> handler { (conversationId: Long, _: Request) =>
      plannerAgentService.confirmPlan(conversationId)
        .map(result =>
          Response.redirect(
            URL.decode(s"/board?mode=list&plannerCreated=${result.issueIds.size}").toOption.getOrElse(URL.root)
          )
        )
        .catchAll(error => ZIO.succeed(renderPlannerError(error)))
    },
  )

  private def parsePreviewForm(
    form: Map[String, List[String]]
  ): IO[PlannerAgentError, PlannerPlanPreview] =
    val summary      = optional(form, "summary").getOrElse("")
    val draftIds     = values(form, "draft_id")
    val titles       = values(form, "title")
    val descriptions = values(form, "description")
    val issueTypes   = values(form, "issue_type")
    val priorities   = values(form, "priority")
    val capabilities = values(form, "required_capabilities")
    val dependencies = values(form, "dependency_draft_ids")
    val acceptance   = values(form, "acceptance_criteria")
    val prompts      = values(form, "prompt_template")
    val skills       = values(form, "kaizen_skills")
    val proof        = values(form, "proof_of_work_requirements")
    val sizes        = List(
      draftIds.size,
      titles.size,
      descriptions.size,
      issueTypes.size,
      priorities.size,
      capabilities.size,
      dependencies.size,
      acceptance.size,
      prompts.size,
      skills.size,
      proof.size,
    ).distinct

    if sizes.size > 1 then
      ZIO.fail(PlannerAgentError.IssueDraftInvalid("Planner preview form fields were incomplete"))
    else
      ZIO.succeed(
        PlannerPlanPreview(
          summary = summary,
          issues = draftIds.indices.toList.map { idx =>
            PlannerIssueDraft(
              draftId = draftIds(idx),
              title = titles(idx),
              description = descriptions(idx),
              issueType = issueTypes(idx),
              priority = priorities(idx),
              requiredCapabilities = splitCsv(capabilities(idx)),
              dependencyDraftIds = splitCsv(dependencies(idx)),
              acceptanceCriteria = acceptance(idx),
              promptTemplate = prompts(idx),
              kaizenSkills = splitCsv(skills(idx)),
              proofOfWorkRequirements = splitCsv(proof(idx)),
            )
          },
        )
      )

  private def parseMultiForm(req: Request): IO[PlannerAgentError, Map[String, List[String]]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap {
            _.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .groupMap(_._1)(_._2)
      }
      .mapError(err => PlannerAgentError.PersistenceFailure("parse_form", err.getMessage))

  private def required(form: Map[String, List[String]], key: String): IO[PlannerAgentError, String] =
    ZIO
      .fromOption(optional(form, key))
      .orElseFail(PlannerAgentError.IssueDraftInvalid(s"Missing required field: $key"))

  private def optional(form: Map[String, List[String]], key: String): Option[String] =
    form.get(key).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  private def values(form: Map[String, List[String]], key: String): List[String] =
    form.getOrElse(key, Nil).map(_.trim)

  private def splitCsv(raw: String): List[String] =
    raw.split(",").toList.map(_.trim).filter(_.nonEmpty).distinct

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def renderPlannerError(error: PlannerAgentError): Response =
    Response.text(error.message).status(Status.BadRequest)

  private def mapWorkspaceError(error: shared.errors.PersistenceError): PlannerAgentError =
    PlannerAgentError.PersistenceFailure("list_workspaces", error.toString)
