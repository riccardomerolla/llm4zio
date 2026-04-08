package checkpoint.control

import java.nio.file.{ Path, Paths }
import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.{ MigrationConfig, WorkflowDefinition }
import app.control.StateService
import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType, SessionContextLink }
import db.ChatRepository
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.{ TokenUsage as IssueTokenUsage, * }
import orchestration.control.*
import orchestration.entity.*
import shared.errors.{ ControlPlaneError, PersistenceError, StateError }
import shared.ids.Ids.{ AgentId, DecisionId, IssueId, ReportId, TaskRunId }
import shared.testfixtures.*
import taskrun.entity.{ RunStatus as _, * }
import workspace.control.{ GitService, RunSessionManager, WorkspaceRunService }
import workspace.entity.*

object CheckpointReviewServiceSpec extends ZIOSpecDefault:

  private val runId          = "run-1"
  private val conversationId = "101"
  private val now            = Instant.parse("2026-03-26T10:00:00Z")
  private val discoveryAt    = now.minusSeconds(120)
  private val analysisAt     = now.minusSeconds(60)

  private val discoverySnapshot = CheckpointSnapshot(
    checkpoint = Checkpoint(runId, "discovery", discoveryAt, Map.empty, "sum-1"),
    state = TaskState(
      runId = runId,
      startedAt = now.minusSeconds(300),
      currentStep = "Discovery",
      completedSteps = Set.empty,
      artifacts = Map("tests.output" -> "1 passed", "plan" -> "baseline"),
      errors = Nil,
      config = MigrationConfig(Paths.get("src"), Paths.get("out")),
      workspace = None,
      status = TaskStatus.Running,
      lastCheckpoint = discoveryAt,
      taskRunId = Some(runId),
      currentStepName = Some("Discovery"),
    ),
  )

  private val analysisSnapshot = CheckpointSnapshot(
    checkpoint = Checkpoint(runId, "analysis", analysisAt, Map.empty, "sum-2"),
    state = TaskState(
      runId = runId,
      startedAt = now.minusSeconds(300),
      currentStep = "Analysis",
      completedSteps = Set("Discovery"),
      artifacts = Map("tests.output" -> "2 passed", "plan" -> "refined"),
      errors = List(TaskError("Analysis", "warning", analysisAt.minusSeconds(5))),
      config = MigrationConfig(Paths.get("src"), Paths.get("out")),
      workspace = None,
      status = TaskStatus.Running,
      lastCheckpoint = analysisAt,
      taskRunId = Some(runId),
      currentStepName = Some("Analysis"),
    ),
  )

  private val issue = AgentIssue(
    id = IssueId("issue-1"),
    runId = Some(TaskRunId(runId)),
    conversationId = None,
    title = "Improve checkpoint review",
    description = "Need run review",
    issueType = "feature",
    priority = "high",
    requiredCapabilities = Nil,
    state = IssueState.InProgress(AgentId("agent-1"), now.minusSeconds(200)),
    tags = Nil,
    contextPath = "",
    sourceFolder = "",
    workspaceId = Some("ws-1"),
  )

  private val report = IssueWorkReport(
    issueId = issue.id,
    walkthrough = Some("Validated proof-of-work"),
    agentSummary = Some("Agent is still executing"),
    diffStats = None,
    prLink = None,
    prStatus = None,
    ciStatus = Some(IssueCiStatus.Passed),
    tokenUsage = None,
    runtimeSeconds = None,
    reports = List(IssueReport(ReportId("report-1"), "analysis", "tests", "2 passed", analysisAt)),
    artifacts = Nil,
    lastUpdated = now,
  )

  private val workspaceRun = WorkspaceRun(
    id = runId,
    workspaceId = "ws-1",
    parentRunId = None,
    issueRef = issue.id.value,
    agentName = "codex",
    prompt = "do work",
    conversationId = conversationId,
    worktreePath = "/tmp/worktree",
    branchName = "agent/test",
    status = RunStatus.Running(RunSessionMode.Interactive),
    attachedUsers = Set.empty,
    controllerUserId = None,
    createdAt = now.minusSeconds(300),
    updatedAt = now,
  )

  private val monitorSnapshot = AgentMonitorSnapshot(
    generatedAt = now,
    agents = List(
      AgentExecutionInfo(
        agentName = "codex",
        state = AgentExecutionState.Executing,
        runId = Some(runId),
        step = Some("Analysis"),
        task = Some("checkpoint task"),
        conversationId = Some(conversationId),
        tokensUsed = 42L,
        latencyMs = 100L,
        cost = 0.0,
        lastUpdatedAt = now,
        startedAt = Some(now.minusSeconds(240)),
        message = Some("Running analysis"),
      )
    ),
  )

  final private class StubStateService extends StateService:
    override def saveState(state: TaskState): IO[StateError, Unit]                                               = ZIO.unit
    override def loadState(id: String): IO[StateError, Option[TaskState]]                                        =
      ZIO.succeed(Some(analysisSnapshot.state).filter(_ => id == runId))
    override def createCheckpoint(runId: String, stepName: String): IO[StateError, Unit]                         = ZIO.unit
    override def getLastCheckpoint(runId: String): IO[StateError, Option[String]]                                =
      ZIO.succeed(if runId == CheckpointReviewServiceSpec.runId then Some("analysis") else None)
    override def listCheckpoints(id: String): IO[StateError, List[Checkpoint]]                                   =
      ZIO.succeed(if id == runId then List(discoverySnapshot.checkpoint, analysisSnapshot.checkpoint) else Nil)
    override def listCheckpointSnapshots(id: String): IO[StateError, List[CheckpointSnapshot]]                   =
      ZIO.succeed(if id == runId then List(discoverySnapshot, analysisSnapshot) else Nil)
    override def getCheckpointSnapshot(id: String, stepName: String): IO[StateError, Option[CheckpointSnapshot]] =
      ZIO.succeed(if id == runId then List(discoverySnapshot, analysisSnapshot).find(_.checkpoint.step == stepName)
      else None)
    override def validateCheckpointIntegrity(runId: String): IO[StateError, Unit]                                = ZIO.unit
    override def listRuns(): IO[StateError, List[taskrun.entity.TaskRunSummary]]                                 = ZIO.succeed(Nil)
    override def getStateDirectory(runId: String): IO[StateError, Path]                                          = ZIO.succeed(Paths.get("/tmp"))

  final private class StubControlPlane(ref: Ref[List[String]]) extends OrchestratorControlPlane:
    override def startWorkflow(runId: String, workflowId: Long, definition: WorkflowDefinition)
      : ZIO[Any, ControlPlaneError, String] = ???
    override def routeStep(runId: String, step: TaskStep, capabilities: List[AgentCapability])
      : ZIO[Any, ControlPlaneError, String] = ???
    override def allocateResource(runId: String): ZIO[Any, ControlPlaneError, Int]                            = ???
    override def releaseResource(runId: String, slot: Int): ZIO[Any, ControlPlaneError, Unit]                 = ???
    override def publishEvent(event: ControlPlaneEvent): ZIO[Any, ControlPlaneError, Unit]                    = ???
    override def subscribeToEvents(runId: String): ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]            = ???
    override def subscribeAllEvents: ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]                          = ???
    override def getActiveRuns: ZIO[Any, ControlPlaneError, List[ActiveRun]]                                  =
      ZIO.fail(ControlPlaneError.ActiveRunNotFound(runId))
    override def getRunState(runId: String): ZIO[Any, ControlPlaneError, Option[ActiveRun]]                   = ZIO.none
    override def updateRunState(runId: String, newState: WorkflowRunState): ZIO[Any, ControlPlaneError, Unit] = ZIO.unit
    override def executeCommand(command: ControlCommand): ZIO[Any, ControlPlaneError, Unit]                   = ZIO.unit
    override def getResourceState: ZIO[Any, ControlPlaneError, ResourceAllocationState]                       =
      ZIO.fail(ControlPlaneError.ResourceAllocationFailed(runId, "unused"))
    override def getAgentMonitorSnapshot: ZIO[Any, ControlPlaneError, AgentMonitorSnapshot]                   =
      ZIO.succeed(monitorSnapshot)
    override def getAgentExecutionHistory(limit: Int): ZIO[Any, ControlPlaneError, List[AgentExecutionEvent]] =
      ZIO.succeed(Nil)
    override def pauseAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                    =
      ref.update(_ :+ s"pause:$agentName")
    override def resumeAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                   =
      ref.update(_ :+ s"resume:$agentName")
    override def abortAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                    =
      ref.update(_ :+ s"abort:$agentName")
    override def notifyWorkspaceAgent(
      agentName: String,
      state: AgentExecutionState,
      runId: Option[String],
      conversationId: Option[String],
      message: Option[String],
      tokenDelta: Long,
    ): UIO[Unit] = ZIO.unit

  final private class StubProjection extends IssueWorkReportProjection:
    override def get(issueId: IssueId): UIO[Option[IssueWorkReport]]                                          =
      ZIO.succeed(Some(report).filter(_.issueId == issueId))
    override def getAll: UIO[Map[IssueId, IssueWorkReport]]                                                   = ZIO.succeed(Map(issue.id -> report))
    override def updateWalkthrough(issueId: IssueId, summary: String, at: Instant): UIO[Unit]                 = ZIO.unit
    override def updateAgentSummary(issueId: IssueId, summary: String, at: Instant): UIO[Unit]                = ZIO.unit
    override def updateDiffStats(issueId: IssueId, stats: IssueDiffStats, at: Instant): UIO[Unit]             = ZIO.unit
    override def updatePrLink(issueId: IssueId, prUrl: String, status: IssuePrStatus, at: Instant): UIO[Unit] = ZIO.unit
    override def updateCiStatus(issueId: IssueId, status: IssueCiStatus, at: Instant): UIO[Unit]              = ZIO.unit
    override def updateTokenUsage(issueId: IssueId, usage: IssueTokenUsage, runtimeSeconds: Long, at: Instant)
      : UIO[Unit] = ZIO.unit
    override def addReport(issueId: IssueId, report: IssueReport, at: Instant): UIO[Unit]                     = ZIO.unit
    override def addArtifact(issueId: IssueId, artifact: IssueArtifact, at: Instant): UIO[Unit]               = ZIO.unit

  final private class StubChatRepository(ref: Ref[List[String]]) extends ChatRepository:
    override def createConversation(conversation: ChatConversation): IO[PersistenceError, Long]                   = ZIO.succeed(1L)
    override def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]]                        = ZIO.none
    override def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]]         =
      ZIO.succeed(Nil)
    override def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]]     =
      ZIO.succeed(Nil)
    override def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]]                = ZIO.succeed(Nil)
    override def updateConversation(conversation: ChatConversation): IO[PersistenceError, Unit]                   = ZIO.unit
    override def deleteConversation(id: Long): IO[PersistenceError, Unit]                                         = ZIO.unit
    override def addMessage(message: ConversationEntry): IO[PersistenceError, Long]                               =
      ref.update(_ :+ message.content).as(1L)
    override def getMessages(id: Long): IO[PersistenceError, List[ConversationEntry]]                             =
      ZIO.succeed(
        List(
          ConversationEntry(
            Some("1"),
            conversationId,
            "user",
            SenderType.User,
            "Before checkpoint",
            MessageType.Text,
            None,
            analysisAt.minusSeconds(5),
            analysisAt.minusSeconds(5),
          ),
          ConversationEntry(
            Some("2"),
            conversationId,
            "assistant",
            SenderType.Assistant,
            "After checkpoint",
            MessageType.Text,
            None,
            analysisAt.plusSeconds(5),
            analysisAt.plusSeconds(5),
          ),
        )
      )
    override def getMessagesSince(id: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]]        =
      getMessages(id).map(_.filter(message => !message.createdAt.isBefore(since)))
    override def upsertSessionContext(channelName: String, sessionKey: String, contextJson: String, updatedAt: Instant)
      : IO[PersistenceError, Unit] = ZIO.unit
    override def getSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Option[String]] =
      ZIO.none
    override def getSessionContextByConversation(conversationId: Long)
      : IO[PersistenceError, Option[SessionContextLink]] = ZIO.none
    override def getSessionContextByTaskRunId(taskRunId: Long): IO[PersistenceError, Option[SessionContextLink]]  =
      ZIO.none
    override def listSessionContexts: IO[PersistenceError, List[SessionContextLink]]                              = ZIO.succeed(Nil)
    override def deleteSessionContext(channelName: String, sessionKey: String): IO[PersistenceError, Unit]        = ZIO.unit

  final private class StubGitService extends GitService:
    override def status(repoPath: String): IO[GitError, GitStatus]                                            = ZIO.fail(GitError.NotAGitRepository(repoPath))
    override def diff(repoPath: String, staged: Boolean): IO[GitError, GitDiff]                               =
      ZIO.succeed(GitDiff(List(DiffFile("a.scala", 1, 0, "diff --git a/a.scala b/a.scala"))))
    override def diffStat(repoPath: String, staged: Boolean): IO[GitError, GitDiffStat]                       = ZIO.succeed(GitDiffStat(Nil))
    override def diffFile(repoPath: String, filePath: String, staged: Boolean): IO[GitError, String]          = ZIO.succeed("")
    override def log(repoPath: String, limit: Int): IO[GitError, List[GitLogEntry]]                           = ZIO.succeed(Nil)
    override def branchInfo(repoPath: String): IO[GitError, GitBranchInfo]                                    =
      ZIO.fail(GitError.NotAGitRepository(repoPath))
    override def showFile(repoPath: String, filePath: String, ref: String): IO[GitError, String]              = ZIO.succeed("")
    override def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind]                 =
      ZIO.fail(GitError.NotAGitRepository(repoPath))
    override def checkout(repoPath: String, branch: String): IO[GitError, Unit]                               = ZIO.unit
    override def add(repoPath: String, paths: List[String]): IO[GitError, Unit]                               = ZIO.unit
    override def mv(repoPath: String, from: String, to: String): IO[GitError, Unit]                           = ZIO.unit
    override def commit(repoPath: String, message: String): IO[GitError, String]                              = ZIO.succeed("sha")
    override def rm(repoPath: String, path: String, recursive: Boolean): IO[GitError, Unit]                   = ZIO.unit
    override def mergeNoFastForward(repoPath: String, branch: String, message: String): IO[GitError, Unit]    = ZIO.unit
    override def mergeAbort(repoPath: String): IO[GitError, Unit]                                             = ZIO.unit
    override def conflictedFiles(repoPath: String): IO[GitError, List[String]]                                = ZIO.succeed(Nil)
    override def headSha(repoPath: String): IO[GitError, String]                                              = ZIO.succeed("sha")
    override def showDiffStat(repoPath: String, ref: String): IO[GitError, GitDiffStat]                       = ZIO.succeed(GitDiffStat(Nil))
    override def diffStatVsBase(repoPath: String, baseBranch: String): IO[GitError, GitDiffStat]              =
      ZIO.succeed(GitDiffStat(Nil))
    override def diffFileVsBase(repoPath: String, filePath: String, baseBranch: String): IO[GitError, String] =
      ZIO.succeed("")

  final private class StubDecisionInbox(ref: Ref[List[String]]) extends DecisionInbox:
    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] = ZIO.dieMessage("unused")
    override def openManualDecision(
      title: String,
      context: String,
      referenceId: String,
      summary: String,
      urgency: DecisionUrgency,
      workspaceId: Option[String],
      issueId: Option[IssueId],
    ): IO[PersistenceError, Decision] =
      ref.update(_ :+ s"$title::$referenceId::$summary").as(
        Decision(
          DecisionId("decision-1"),
          title,
          context,
          DecisionAction.ManualEscalation,
          DecisionSource(DecisionSourceKind.Manual, referenceId, summary, workspaceId, issueId),
          urgency,
          DecisionStatus.Pending,
          None,
          None,
          None,
          None,
          now,
          now,
        )
      )
    override def resolve(id: DecisionId, resolutionKind: DecisionResolutionKind, actor: String, summary: String)
      : IO[PersistenceError, Decision] = ZIO.dieMessage("unused")
    override def syncOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def resolveOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision]   = ZIO.dieMessage("unused")
    override def get(id: DecisionId): IO[PersistenceError, Decision]                        = ZIO.dieMessage("unused")
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]         = ZIO.succeed(Nil)
    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]         = ZIO.succeed(Nil)

  final private class StubWorkspaceRunService(ref: Ref[List[String]]) extends WorkspaceRunService:
    override def assign(workspaceId: String, req: workspace.entity.AssignRunRequest)
      : IO[WorkspaceError, WorkspaceRun] = ZIO.dieMessage("unused")
    override def continueRun(runId: String, followUpPrompt: String, agentNameOverride: Option[String])
      : IO[WorkspaceError, WorkspaceRun] = ZIO.dieMessage("unused")
    override def cancelRun(runId: String): IO[WorkspaceError, Unit] = ref.update(_ :+ s"cancel:$runId")

  final private class StubRunSessionManager(ref: Ref[List[String]]) extends RunSessionManager:
    override def getSession(runId: String): IO[WorkspaceError, RunSession]                       =
      ZIO.succeed(RunSession(runId, workspaceRun.status, Set.empty, None))
    override def attach(runId: String, userId: String): IO[WorkspaceError, RunSession]           =
      ZIO.succeed(RunSession(runId, workspaceRun.status, Set(userId), Some(userId)))
    override def detach(runId: String, userId: String): IO[WorkspaceError, Unit]                 = ZIO.unit
    override def interrupt(runId: String, userId: String): IO[WorkspaceError, Unit]              =
      ref.update(_ :+ s"interrupt:$runId:$userId")
    override def resume(runId: String, userId: String, prompt: String): IO[WorkspaceError, Unit] =
      ref.update(_ :+ s"resume:$runId:$userId:$prompt")
    override def sendMessage(runId: String, userId: String, content: String)
      : IO[WorkspaceError, Either[RunInputRejected, RunInputAccepted]] =
      ref.update(_ :+ s"send:$runId:$userId:$content").as(Right(RunInputAccepted(runId, 1L)))

  private def makeService: UIO[(CheckpointReviewService, Ref[List[String]], Ref[List[String]])] =
    for
      controlRef  <- Ref.make(List.empty[String])
      chatRef     <- Ref.make(List.empty[String])
      decisionRef <- Ref.make(List.empty[String])
      sessionRef  <- Ref.make(List.empty[String])
      cancelRef   <- Ref.make(List.empty[String])
      service      = CheckpointReviewServiceLive(
                       new StubStateService,
                       new StubControlPlane(controlRef),
                       new StubWorkspaceRepository(Nil, List(workspaceRun)),
                       StubIssueRepository.of(issue),
                       new StubProjection,
                       new StubChatRepository(chatRef),
                       new StubGitService,
                       new StubDecisionInbox(decisionRef),
                       new StubWorkspaceRunService(cancelRef),
                       new StubRunSessionManager(sessionRef),
                     )
    yield (service, decisionRef, sessionRef)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CheckpointReviewServiceSpec")(
      test("getRunReview assembles snapshot evidence and comparison") {
        for
          tuple  <- makeService
          review <- tuple._1.getRunReview(runId, Some("analysis"), Some("discovery"), Some("analysis"))
        yield assertTrue(
          review.summary.checkpointCount == 2,
          review.selected.exists(_.conversationExcerpt.map(_.content) == List("Before checkpoint")),
          review.selected.exists(_.testSignals.exists(_.content.contains("2 passed"))),
          review.selected.flatMap(_.gitDiff).exists(_.contains("diff --git")),
          review.comparison.exists(_.artifactDeltas.exists(_.key == "plan")),
        )
      },
      test("redirect sends operator input to active workspace run") {
        for
          tuple  <- makeService
          result <- tuple._1.act(runId, CheckpointOperatorAction.Redirect, Some("Focus on the failing tests"))
          calls  <- tuple._3.get
        yield assertTrue(
          result.summary.contains("Redirect sent"),
          calls.exists(_.contains("Focus on the failing tests")),
        )
      },
      test("flag full review creates a manual decision") {
        for
          tuple     <- makeService
          result    <- tuple._1.act(runId, CheckpointOperatorAction.FlagFullReview, Some("Need a human decision"))
          decisions <- tuple._2.get
        yield assertTrue(
          result.summary.contains("Decision inbox item created"),
          decisions.exists(_.contains("Need a human decision")),
        )
      },
    )
