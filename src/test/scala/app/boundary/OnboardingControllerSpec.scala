package app.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowRow }
import project.control.ProjectStorageService
import project.entity.{ Project, ProjectEvent, ProjectRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import workspace.entity.{ Workspace, WorkspaceEvent, WorkspaceRun, WorkspaceRunEvent, WorkspaceRepository }

object OnboardingControllerSpec extends ZIOSpecDefault:

  /** Minimal in-memory ConfigRepository — only the bits the onboarding flow touches. */
  final private class StubConfigRepo(ref: Ref[Map[String, String]]) extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
      ref.get.map(_.toList.map { case (k, v) => SettingRow(k, v, Instant.EPOCH) })
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
      ref.get.map(_.get(key).map(value => SettingRow(key, value, Instant.EPOCH)))
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
      ref.update(_.updated(key, value))
    override def deleteSetting(key: String): IO[PersistenceError, Unit] = ref.update(_ - key)
    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
      ref.update(_.filterNot(_._1.startsWith(prefix)))
    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]          =
      ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "unused"))
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflow", "unused"))
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflowByName", "unused"))
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                     =
      ZIO.fail(PersistenceError.QueryFailed("listWorkflows", "unused"))
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]          =
      ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "unused"))
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                       =
      ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "unused"))
    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             =
      ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "unused"))
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgent", "unused"))
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgentByName", "unused"))
    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     =
      ZIO.fail(PersistenceError.QueryFailed("listCustomAgents", "unused"))
    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             =
      ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "unused"))
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          =
      ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "unused"))

  final private class StubProjectRepo(ref: Ref[List[ProjectEvent]]) extends ProjectRepository:
    override def append(event: ProjectEvent): IO[PersistenceError, Unit] = ref.update(_ :+ event)
    override def get(id: ProjectId): IO[PersistenceError, Option[Project]] = ZIO.succeed(None)
    override def list: IO[PersistenceError, List[Project]]                 = ZIO.succeed(Nil)
    override def delete(id: ProjectId): IO[PersistenceError, Unit]         = ZIO.unit

  final private class StubWorkspaceRepo(ref: Ref[List[WorkspaceEvent]]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] = ref.update(_ :+ event)
    override def list: IO[PersistenceError, List[Workspace]]                                               = ZIO.succeed(Nil)
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]               = ZIO.succeed(Nil)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                                  = ZIO.succeed(None)
    override def delete(id: String): IO[PersistenceError, Unit]                                            = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                           = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]                   = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]]            = ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                            = ZIO.succeed(None)

  private object StubProjectStorage extends ProjectStorageService:
    override def initProjectStorage(projectId: ProjectId): IO[PersistenceError, java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get("/tmp/onboarding-test"))
    override def projectRoot(projectId: ProjectId): UIO[java.nio.file.Path]                          =
      ZIO.succeed(java.nio.file.Paths.get("/tmp/onboarding-test"))
    override def boardPath(projectId: ProjectId): UIO[java.nio.file.Path]                            =
      ZIO.succeed(java.nio.file.Paths.get("/tmp/onboarding-test/.board"))
    override def workspaceAnalysisPath(projectId: ProjectId, workspaceId: String): UIO[java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get("/tmp/onboarding-test/workspaces").resolve(workspaceId))

  private val stubTester: TelegramTokenTester = (_: String) =>
    ZIO.succeed(TelegramTokenTester.Result(ok = false, "stub", None))

  private def mkController: UIO[(OnboardingController, Ref[Map[String, String]], Ref[List[ProjectEvent]], Ref[List[WorkspaceEvent]])] =
    for
      configRef    <- Ref.make(Map.empty[String, String])
      projectRef   <- Ref.make(List.empty[ProjectEvent])
      workspaceRef <- Ref.make(List.empty[WorkspaceEvent])
      controller    = OnboardingControllerLive(
                        StubConfigRepo(configRef),
                        StubProjectRepo(projectRef),
                        StubWorkspaceRepo(workspaceRef),
                        StubProjectStorage,
                        stubTester,
                      )
    yield (controller, configRef, projectRef, workspaceRef)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OnboardingController")(
    test("GET /onboarding renders the four-step wizard with default values") {
      for
        tuple             <- mkController
        (controller, _, _, _) = tuple
        response          <- controller.routes.runZIO(Request.get(URL.decode("/onboarding").toOption.get))
        body              <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Welcome to your agentic software house"),
        body.contains("Connect your LLM"),
        body.contains("Connect Telegram"),
        body.contains("Your default roster"),
        body.contains("Your first workspace"),
        // All five roster employees on the page
        body.contains("Pat") && body.contains("Alex") && body.contains("Ben") &&
          body.contains("Dana") && body.contains("Rex"),
        // Body must be raw HTML, not entity-escaped (regression guard: the
        // view used to call .render on a String, double-escaping the page).
        body.startsWith("<!DOCTYPE html>"),
        !body.contains("&lt;!DOCTYPE"),
      )
    },
    test("POST /onboarding persists config + creates project + creates workspace + redirects") {
      val body = "aiProvider=Anthropic&aiModel=claude-sonnet-4-6&aiApiKey=sk-test-123" +
        "&telegramEnabled=true&telegramBotToken=bot-token-xyz&telegramSupervisorChatId=42" +
        "&projectName=My+Project&workspaceName=main&workspaceLocalPath=%2Ftmp%2Fcode"
      for
        tuple                       <- mkController
        (controller, cfg, proj, ws)  = tuple
        request                      = Request.post(URL.decode("/onboarding").toOption.get, Body.fromString(body))
        response                    <- controller.routes.runZIO(request)
        cfgMap                      <- cfg.get
        projectEvents               <- proj.get
        workspaceEvents             <- ws.get
      yield assertTrue(
        // 303 See Other so the browser converts POST→GET on /; 307 would
        // re-POST to / where no POST handler exists, producing a 404.
        response.status == Status.SeeOther,
        response.headers.get("Location").contains("/"),
        cfgMap.get("ai.provider").contains("Anthropic"),
        cfgMap.get("ai.model").contains("claude-sonnet-4-6"),
        cfgMap.get("ai.apiKey").contains("sk-test-123"),
        cfgMap.get("telegram.enabled").contains("true"),
        cfgMap.get("telegram.mode").contains("Polling"),
        cfgMap.get("telegram.botToken").contains("bot-token-xyz"),
        cfgMap.get("telegram.supervisorChatId").contains("42"),
        cfgMap.get("onboarding.completedAt").exists(_.nonEmpty),
        projectEvents.size == 1,
        projectEvents.head.isInstanceOf[ProjectEvent.ProjectCreated],
        workspaceEvents.size == 1,
        workspaceEvents.head.isInstanceOf[WorkspaceEvent.Created],
        workspaceEvents.collect { case e: WorkspaceEvent.Created => e }.head.localPath == "/tmp/code",
      )
    },
    test("POST /onboarding/test-telegram with empty token renders a 'paste a bot token' notice") {
      for
        tuple                <- mkController
        (controller, _, _, _) = tuple
        request               = Request.post(
                                  URL.decode("/onboarding/test-telegram").toOption.get,
                                  Body.fromString("telegramBotToken="),
                                )
        response             <- controller.routes.runZIO(request)
        bodyStr              <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        bodyStr.contains("Paste a bot token first."),
      )
    },
    test("POST /onboarding/test-telegram with malformed token rejects without hitting the network") {
      for
        tuple                <- mkController
        (controller, _, _, _) = tuple
        request               = Request.post(
                                  URL.decode("/onboarding/test-telegram").toOption.get,
                                  Body.fromString("telegramBotToken=not-a-bot-token"),
                                )
        response             <- controller.routes.runZIO(request)
        bodyStr              <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        bodyStr.contains("doesn't look like a bot token"),
      )
    },
    test("POST /onboarding/test-telegram with a well-formed token delegates to the tester") {
      for
        configRef    <- Ref.make(Map.empty[String, String])
        projectRef   <- Ref.make(List.empty[ProjectEvent])
        workspaceRef <- Ref.make(List.empty[WorkspaceEvent])
        capturedRef  <- Ref.make(Option.empty[String])
        tester        = new TelegramTokenTester:
                          override def test(token: String): UIO[TelegramTokenTester.Result] =
                            capturedRef
                              .set(Some(token))
                              .as(TelegramTokenTester.Result(ok = true, "Connected to @demo_bot", Some("https://t.me/demo_bot")))
        controller    = OnboardingControllerLive(
                          StubConfigRepo(configRef),
                          StubProjectRepo(projectRef),
                          StubWorkspaceRepo(workspaceRef),
                          StubProjectStorage,
                          tester,
                        )
        request       = Request.post(
                          URL.decode("/onboarding/test-telegram").toOption.get,
                          Body.fromString("telegramBotToken=123456%3AABC-DEF1234ghIkl-zyx57W2v1u123ew11"),
                        )
        response     <- controller.routes.runZIO(request)
        bodyStr      <- response.body.asString
        captured     <- capturedRef.get
      yield assertTrue(
        response.status == Status.Ok,
        bodyStr.contains("Connected to @demo_bot"),
        captured.contains("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"),
      )
    },
    test("POST /onboarding with missing localPath re-renders the page with the error") {
      val body = "aiProvider=Anthropic&aiModel=claude-sonnet-4-6&aiApiKey=" +
        "&telegramEnabled=false&telegramBotToken=&telegramSupervisorChatId=" +
        "&projectName=Proj&workspaceName=ws&workspaceLocalPath="
      for
        tuple             <- mkController
        (controller, cfg, proj, ws) = tuple
        request            = Request.post(URL.decode("/onboarding").toOption.get, Body.fromString(body))
        response          <- controller.routes.runZIO(request)
        bodyStr           <- response.body.asString
        cfgMap            <- cfg.get
        projectEvents     <- proj.get
        workspaceEvents   <- ws.get
      yield assertTrue(
        response.status == Status.Ok,
        bodyStr.contains("Local git path is required"),
        // No persistence side-effects when validation fails
        cfgMap.isEmpty,
        projectEvents.isEmpty,
        workspaceEvents.isEmpty,
      )
    },
  )
