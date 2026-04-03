package board.control

import java.time.Instant
import java.nio.file.Path

import zio.*
import zio.test.*

import board.entity.BoardError
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.AgentIssue
import project.control.ProjectStorageService
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, DecisionId, IssueId, ProjectId }
import workspace.entity.{ Workspace, WorkspaceEvent, WorkspaceRepository, WorkspaceRun, WorkspaceRunEvent }

object IssueApprovalServiceSpec extends ZIOSpecDefault:

  private val workspaceId = "ws-1"
  private val issueId     = BoardIssueId("issue-approve-1")
  private val projectId   = ProjectId("project-1")
  private val createdAt   = Instant.parse("2026-04-01T10:00:00Z")

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("IssueApprovalService")(
      suite("quickApprove")(
        test("resolves the review decision and delegates board approval using the project root") {
          for
            resolvedRef <- Ref.make(List.empty[(IssueId, DecisionResolutionKind, String, String)])
            approvedRef <- Ref.make(List.empty[(String, BoardIssueId)])
            service      = makeService(
                             workspaceOpt = Some(sampleWorkspace),
                             runs = List(
                               sampleRun("run-older", createdAt.plusSeconds(60)),
                               sampleRun("run-latest", createdAt.plusSeconds(120)),
                             ),
                             projectRoot = Path.of("/tmp/projects/project-1"),
                             onResolve = (issueId, resolutionKind, actor, summary) =>
                               resolvedRef.update(_ :+ ((issueId, resolutionKind, actor, summary))),
                             onApprove = (workspacePath, issueId) =>
                               approvedRef.update(_ :+ ((workspacePath, issueId))),
                           )
            _           <- service.quickApprove(workspaceId, issueId, "Looks good")
            resolved    <- resolvedRef.get
            approved    <- approvedRef.get
          yield assertTrue(
            resolved == List((IssueId(issueId.value), DecisionResolutionKind.Approved, "web", "Looks good")),
            approved == List(("/tmp/projects/project-1", issueId)),
          )
        },
        test("fails before resolving the decision when no run exists for the issue in the workspace") {
          for
            resolvedRef <- Ref.make(0)
            approvedRef <- Ref.make(0)
            service      = makeService(
                             workspaceOpt = Some(sampleWorkspace),
                             runs = Nil,
                             projectRoot = Path.of("/tmp/projects/project-1"),
                             onResolve = (_, _, _, _) => resolvedRef.update(_ + 1),
                             onApprove = (_, _) => approvedRef.update(_ + 1),
                           )
            result      <- service.quickApprove(workspaceId, issueId, "").either
            resolved    <- resolvedRef.get
            approved    <- approvedRef.get
          yield assertTrue(
            result == Left(BoardError.ParseError(s"latest run not found for issue '${issueId.value}'")),
            resolved == 0,
            approved == 0,
          )
        },
      )
    )

  private val sampleWorkspace = Workspace(
    id = workspaceId,
    projectId = projectId,
    name = "Workspace",
    localPath = "/tmp/workspace",
    defaultAgent = Some("code-agent"),
    description = Some("Workspace under test"),
    enabled = true,
    runMode = workspace.entity.RunMode.Host,
    cliTool = "codex",
    createdAt = createdAt,
    updatedAt = createdAt,
    defaultBranch = "main",
  )

  private def sampleRun(id: String, updatedAt: Instant): WorkspaceRun =
    WorkspaceRun(
      id = id,
      workspaceId = workspaceId,
      parentRunId = None,
      issueRef = issueId.value,
      agentName = "code-agent",
      prompt = "Implement the change",
      conversationId = "100",
      worktreePath = s"/tmp/worktrees/$id",
      branchName = s"agent/$id",
      status = workspace.entity.RunStatus.Completed,
      attachedUsers = Set.empty,
      controllerUserId = None,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )

  private def makeService(
    workspaceOpt: Option[Workspace],
    runs: List[WorkspaceRun],
    projectRoot: Path,
    onResolve: (IssueId, DecisionResolutionKind, String, String) => UIO[Unit],
    onApprove: (String, BoardIssueId) => UIO[Unit],
  ): IssueApprovalService =
    IssueApprovalServiceLive(
      boardOrchestrator = StubBoardOrchestrator(onApprove),
      decisionInbox = StubDecisionInbox(onResolve),
      workspaceRepository = StubWorkspaceRepository(workspaceOpt, runs),
      projectStorageService = StubProjectStorageService(projectRoot),
    )

  final private case class StubBoardOrchestrator(onApprove: (String, BoardIssueId) => UIO[Unit])
    extends BoardOrchestrator:
    override def dispatchCycle(workspacePath: String): IO[BoardError, DispatchResult] =
      ZIO.dieMessage("dispatchCycle unused in IssueApprovalServiceSpec")

    override def assignIssue(workspacePath: String, issueId: BoardIssueId, agentName: String): IO[BoardError, Unit] =
      ZIO.dieMessage("assignIssue unused in IssueApprovalServiceSpec")

    override def markIssueStarted(
      workspacePath: String,
      issueId: BoardIssueId,
      agentName: String,
      branchName: String,
    ): IO[BoardError, Unit] =
      ZIO.dieMessage("markIssueStarted unused in IssueApprovalServiceSpec")

    override def completeIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      success: Boolean,
      details: String,
    ): IO[BoardError, Unit] =
      ZIO.dieMessage("completeIssue unused in IssueApprovalServiceSpec")

    override def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
      onApprove(workspacePath, issueId)

  final private case class StubDecisionInbox(
    onResolve: (IssueId, DecisionResolutionKind, String, String) => UIO[Unit]
  ) extends DecisionInbox:
    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] =
      unsupported("openIssueReviewDecision")

    override def resolve(
      id: DecisionId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Decision] =
      unsupported("resolve")

    override def syncOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] =
      ZIO.none

    override def resolveOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] =
      onResolve(issueId, resolutionKind, actor, summary).as(None)

    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision] =
      unsupported("escalate")

    override def get(id: DecisionId): IO[PersistenceError, Decision] =
      unsupported("get")

    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]] =
      ZIO.succeed(Nil)

    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]] =
      ZIO.succeed(Nil)

    private def unsupported(operation: String): IO[PersistenceError, Decision] =
      ZIO.fail(PersistenceError.QueryFailed(operation, "unused"))

  final private case class StubWorkspaceRepository(
    workspaceOpt: Option[Workspace],
    runs: List[WorkspaceRun],
  ) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] = ZIO.unit

    override def list: IO[PersistenceError, List[Workspace]] =
      ZIO.succeed(workspaceOpt.toList)

    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]] =
      ZIO.succeed(workspaceOpt.filter(_.projectId == projectId).toList)

    override def get(id: String): IO[PersistenceError, Option[Workspace]] =
      ZIO.succeed(workspaceOpt.filter(_.id == id))

    override def delete(id: String): IO[PersistenceError, Unit] = ZIO.unit

    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] = ZIO.unit

    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(runs.filter(_.workspaceId == workspaceId))

    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(runs.filter(_.issueRef.trim.stripPrefix("#") == issueRef.trim.stripPrefix("#")))

    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
      ZIO.succeed(runs.find(_.id == id))

  final private case class StubProjectStorageService(projectRootPath: Path) extends ProjectStorageService:
    override def initProjectStorage(projectId: ProjectId): IO[PersistenceError, Path] =
      ZIO.succeed(projectRootPath)

    override def projectRoot(projectId: ProjectId): UIO[Path] =
      ZIO.succeed(projectRootPath)

    override def boardPath(projectId: ProjectId): UIO[Path] =
      ZIO.succeed(projectRootPath.resolve(".board"))

    override def workspaceAnalysisPath(projectId: ProjectId, workspaceId: String): UIO[Path] =
      ZIO.succeed(projectRootPath.resolve("analysis").resolve(workspaceId))
