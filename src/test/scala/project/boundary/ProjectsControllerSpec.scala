package project.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import _root_.config.entity.*
import analysis.control.WorkspaceAnalysisScheduler
import analysis.entity.{ AnalysisType, WorkspaceAnalysisState, WorkspaceAnalysisStatus }
import issues.entity.{ AgentIssue, IssueState }
import orchestration.control.AgentRegistry
import project.control.ProjectStorageService
import project.entity.{ Project, ProjectEvent, ProjectRepository, ProjectSettings }
import shared.errors.PersistenceError
import shared.ids.Ids.{ IssueId, ProjectId }
import shared.testfixtures.*
import taskrun.entity.TaskStep
import workspace.entity.*

object ProjectsControllerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T10:00:00Z")

  private val sampleProject = Project(
    id = ProjectId("proj-1"),
    name = "Platform",
    description = Some("Shared platform work"),
    settings = ProjectSettings(defaultAgent = Some("task-planner")),
    createdAt = now,
    updatedAt = now,
  )

  private val sampleWorkspace = Workspace(
    id = "ws-1",
    projectId = ProjectId("proj-1"),
    name = "gateway",
    localPath = "/tmp/gateway",
    defaultAgent = Some("codex"),
    description = Some("Gateway workspace"),
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "codex",
    createdAt = now,
    updatedAt = now,
  )

  private val sampleIssue = AgentIssue(
    id = IssueId("issue-1"),
    runId = None,
    conversationId = None,
    title = "Implement feature",
    description = "Build the feature",
    issueType = "task",
    priority = "high",
    requiredCapabilities = List("scala"),
    state = IssueState.InProgress(shared.ids.Ids.AgentId("codex"), now),
    tags = List("planner"),
    contextPath = "",
    sourceFolder = "",
    workspaceId = Some("ws-1"),
    externalRef = None,
    externalUrl = None,
  )

  final private class StubProjectRepository(ref: Ref[Map[String, Project]]) extends ProjectRepository:
    override def append(event: ProjectEvent): IO[PersistenceError, Unit] =
      ref.update { current =>
        event match
          case e: ProjectEvent.ProjectCreated =>
            current.updated(
              e.projectId.value,
              Project(
                id = e.projectId,
                name = e.name,
                description = e.description,
                settings = ProjectSettings(),
                createdAt = e.occurredAt,
                updatedAt = e.occurredAt,
              ),
            )
          case e: ProjectEvent.ProjectUpdated =>
            current.updatedWith(e.projectId.value)(_.map(_.copy(
              name = e.name,
              description = e.description,
              settings = e.settings,
              updatedAt = e.occurredAt,
            )))
          case e: ProjectEvent.ProjectDeleted =>
            current - e.projectId.value
      }

    override def list: IO[PersistenceError, List[Project]] =
      ref.get.map(_.values.toList.sortBy(_.name.toLowerCase))

    override def get(id: ProjectId): IO[PersistenceError, Option[Project]] =
      ref.get.map(_.get(id.value))

    override def delete(id: ProjectId): IO[PersistenceError, Unit] =
      ref.update(_ - id.value)

  final private class StubWorkspaceRepository(ref: Ref[Map[String, Workspace]]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                    = ref.get.map(_.values.toList)
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]     =
      ref.get.map(_.values.filter(_.projectId == projectId).toList)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                       = ref.get.map(_.get(id))
    override def delete(id: String): IO[PersistenceError, Unit]                                 = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 = ZIO.succeed(None)

  private object StubAgentRegistry extends AgentRegistry:
    override def registerAgent(r: RegisterAgentRequest): UIO[AgentInfo]                  =
      ZIO.succeed(AgentInfo(r.name, r.name, r.displayName, r.description, r.agentType, r.usesAI, r.tags))
    override def findByName(name: String): UIO[Option[AgentInfo]]                        = ZIO.succeed(None)
    override def findAgents(q: AgentQuery): UIO[List[AgentInfo]]                         = ZIO.succeed(Nil)
    override def getAllAgents: UIO[List[AgentInfo]]                                      =
      ZIO.succeed(List(AgentInfo("task-planner", "task-planner", "Task Planner", "", AgentType.BuiltIn, true, Nil)))
    override def findAgentsWithSkill(skill: String): UIO[List[AgentInfo]]                = ZIO.succeed(Nil)
    override def findAgentsForStep(step: TaskStep): UIO[List[AgentInfo]]                 = ZIO.succeed(Nil)
    override def findAgentsForTransformation(i: String, o: String): UIO[List[AgentInfo]] = ZIO.succeed(Nil)
    override def recordInvocation(name: String, ok: Boolean, ms: Long): UIO[Unit]        = ZIO.unit
    override def updateHealth(name: String, ok: Boolean, msg: Option[String]): UIO[Unit] = ZIO.unit
    override def setAgentEnabled(name: String, enabled: Boolean): UIO[Unit]              = ZIO.unit
    override def getMetrics(name: String): UIO[Option[AgentMetrics]]                     = ZIO.succeed(None)
    override def getHealth(name: String): UIO[Option[AgentHealth]]                       = ZIO.succeed(None)
    override def loadCustomAgents(customAgents: List[CustomAgentRow]): UIO[Int]          = ZIO.succeed(0)
    override def getRankedAgents(q: AgentQuery): UIO[List[AgentInfo]]                    = ZIO.succeed(Nil)

  private object StubAnalysisScheduler extends WorkspaceAnalysisScheduler:
    override def triggerForWorkspaceEvent(workspaceId: String): UIO[Unit]                                     = ZIO.unit
    override def triggerManual(workspaceId: String): UIO[Unit]                                                = ZIO.unit
    override def statusForWorkspace(workspaceId: String): IO[PersistenceError, List[WorkspaceAnalysisStatus]] =
      ZIO.succeed(
        List(
          WorkspaceAnalysisStatus(
            workspaceId = workspaceId,
            analysisType = AnalysisType.CodeReview,
            state = WorkspaceAnalysisState.Completed,
            completedAt = Some(now),
            lastUpdatedAt = now,
          ),
          WorkspaceAnalysisStatus(
            workspaceId = workspaceId,
            analysisType = AnalysisType.Architecture,
            state = WorkspaceAnalysisState.Completed,
            completedAt = Some(now),
            lastUpdatedAt = now,
          ),
          WorkspaceAnalysisStatus(
            workspaceId = workspaceId,
            analysisType = AnalysisType.Security,
            state = WorkspaceAnalysisState.Running,
            startedAt = Some(now),
            lastUpdatedAt = now,
          ),
        )
      )

  private object StubProjectStorageService extends ProjectStorageService:
    override def initProjectStorage(projectId: ProjectId): IO[PersistenceError, java.nio.file.Path]        =
      ZIO.succeed(java.nio.file.Paths.get("/tmp", "projects", projectId.value))
    override def projectRoot(projectId: ProjectId): UIO[java.nio.file.Path]                                =
      ZIO.succeed(java.nio.file.Paths.get("/tmp", "projects", projectId.value))
    override def boardPath(projectId: ProjectId): UIO[java.nio.file.Path]                                  =
      ZIO.succeed(java.nio.file.Paths.get("/tmp", "projects", projectId.value, ".board"))
    override def workspaceAnalysisPath(projectId: ProjectId, workspaceId: String): UIO[java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get("/tmp", "projects", projectId.value, "workspaces", workspaceId))

  private def makeRoutes(
    projectRef: Ref[Map[String, Project]],
    workspaceRef: Ref[Map[String, Workspace]],
    issues: List[AgentIssue] = List(sampleIssue),
  ): Routes[Any, Response] =
    ProjectsController.make(
      new StubProjectRepository(projectRef),
      new StubWorkspaceRepository(workspaceRef),
      new StubIssueRepository(issues),
      StubAgentRegistry,
      StubAnalysisScheduler,
      StubProjectStorageService,
    ).routes

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ProjectsControllerSpec")(
      test("GET /projects renders list with quick-create and detail link") {
        for
          projectRef   <- Ref.make(Map(sampleProject.id.value -> sampleProject))
          workspaceRef <- Ref.make(Map(sampleWorkspace.id -> sampleWorkspace))
          routes        = makeRoutes(projectRef, workspaceRef)
          resp         <- routes.runZIO(Request.get(URL(Path.decode("/projects"))))
          body         <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("Quick Create"),
          body.contains("/projects/proj-1"),
          body.contains("Issues"),
        )
      },
      test("POST /projects creates project and redirects to detail page") {
        for
          projectRef   <- Ref.make(Map.empty[String, Project])
          workspaceRef <- Ref.make(Map.empty[String, Workspace])
          routes        = makeRoutes(projectRef, workspaceRef, Nil)
          req           = Request(
                            method = Method.POST,
                            url = URL(Path.decode("/projects")),
                            body = Body.fromString("name=New+Project&description=Fresh"),
                          )
          resp         <- routes.runZIO(req)
          projects     <- projectRef.get
          location      = resp.headers.header(Header.Location).map(_.renderedValue)
        yield assertTrue(
          resp.status == Status.SeeOther,
          projects.values.exists(_.name == "New Project"),
          location.exists(_.startsWith("/projects/")),
        )
      },
      test("GET /projects/:id?tab=analysis renders analysis breadcrumb and coverage") {
        for
          projectRef   <- Ref.make(Map(sampleProject.id.value -> sampleProject))
          workspaceRef <- Ref.make(Map(sampleWorkspace.id -> sampleWorkspace))
          routes        = makeRoutes(projectRef, workspaceRef)
          req           = Request.get(URL.decode("/projects/proj-1?tab=analysis").getOrElse(URL.root))
          resp         <- routes.runZIO(req)
          body         <- resp.body.asString
        yield assertTrue(
          resp.status == Status.Ok,
          body.contains("Projects"),
          body.contains("Analysis Coverage"),
          body.contains("2/3 complete"),
        )
      },
      test("POST /projects/:id/settings updates settings and redirects") {
        for
          projectRef   <- Ref.make(Map(sampleProject.id.value -> sampleProject))
          workspaceRef <- Ref.make(Map(sampleWorkspace.id -> sampleWorkspace))
          routes        = makeRoutes(projectRef, workspaceRef)
          req           = Request(
                            method = Method.POST,
                            url = URL(Path.decode("/projects/proj-1/settings")),
                            body = Body.fromString(
                              "name=Platform+Core&description=Updated&default_agent=task-planner&require_ci=on&ci_command=sbt+test&analysis_schedule_minutes=30&prompt_template_defaults=task%3Dship+it"
                            ),
                          )
          resp         <- routes.runZIO(req)
          projectOpt   <- projectRef.get.map(_.get("proj-1"))
        yield assertTrue(
          resp.status == Status.SeeOther,
          projectOpt.exists(_.name == "Platform Core"),
          projectOpt.exists(_.settings.mergePolicy.requireCi),
          projectOpt.flatMap(_.settings.analysisSchedule).exists(_.toMinutes == 30L),
          projectOpt.exists(_.settings.promptTemplateDefaults.get("task").contains("ship it")),
        )
      },
    )
