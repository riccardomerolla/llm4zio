package checkpoint.control

import java.time.Instant

import scala.util.Try

import zio.*

import app.control.StateService
import checkpoint.entity.*
import conversation.entity.api.{ ConversationEntry, SenderType }
import db.ChatRepository
import decision.control.DecisionInbox
import decision.entity.DecisionUrgency
import issues.entity.{ AgentIssue, IssueFilter, IssueRepository, IssueWorkReport, IssueWorkReportProjection }
import orchestration.control.OrchestratorControlPlane
import orchestration.entity.{ AgentExecutionInfo, AgentExecutionState }
import shared.errors.{ ControlPlaneError, PersistenceError, StateError }
import shared.ids.Ids.{ IssueId, TaskRunId }
import taskrun.entity.CheckpointSnapshot
import workspace.control.{ GitService, RunSessionManager, WorkspaceRunService }
import workspace.entity.*

final case class CheckpointSnapshotReview(
  snapshot: CheckpointSnapshot,
  gitDiff: Option[String],
  testSignals: List[CheckpointTextEvidence],
  conversationExcerpt: List[CheckpointConversationExcerpt],
  proofOfWork: Option[IssueWorkReport],
)

final case class CheckpointRunReview(
  summary: CheckpointRunSummary,
  workspaceRun: Option[WorkspaceRun],
  issue: Option[AgentIssue],
  checkpoints: List[CheckpointSnapshot],
  selected: Option[CheckpointSnapshotReview],
  comparison: Option[CheckpointComparison],
)

trait CheckpointReviewService:
  def listActiveRuns: IO[CheckpointReviewError, List[CheckpointRunSummary]]
  def getRunReview(
    runId: String,
    selectedStep: Option[String] = None,
    compareBase: Option[String] = None,
    compareTarget: Option[String] = None,
  ): IO[CheckpointReviewError, CheckpointRunReview]
  def getSnapshotReview(runId: String, stepName: String): IO[CheckpointReviewError, Option[CheckpointSnapshotReview]]
  def compare(runId: String, leftStep: String, rightStep: String)
    : IO[CheckpointReviewError, Option[CheckpointComparison]]
  def act(
    runId: String,
    action: CheckpointOperatorAction,
    note: Option[String] = None,
  ): IO[CheckpointReviewError, CheckpointActionResult]

object CheckpointReviewService:
  val live
    : ZLayer[
      StateService & OrchestratorControlPlane & WorkspaceRepository & IssueRepository & IssueWorkReportProjection &
        ChatRepository & GitService & DecisionInbox & WorkspaceRunService & RunSessionManager,
      Nothing,
      CheckpointReviewService,
    ] =
    ZLayer.fromFunction(CheckpointReviewServiceLive.apply)

final case class CheckpointReviewServiceLive(
  stateService: StateService,
  controlPlane: OrchestratorControlPlane,
  workspaceRepository: WorkspaceRepository,
  issueRepository: issues.entity.IssueRepository,
  workReportProjection: IssueWorkReportProjection,
  chatRepository: ChatRepository,
  gitService: GitService,
  decisionInbox: DecisionInbox,
  workspaceRunService: WorkspaceRunService,
  runSessionManager: RunSessionManager,
) extends CheckpointReviewService:
  private val operatorUser = "checkpoint-review"

  override def listActiveRuns: IO[CheckpointReviewError, List[CheckpointRunSummary]] =
    for
      snapshot  <- controlPlane.getAgentMonitorSnapshot.mapError(CheckpointReviewError.fromControl)
      summaries <- ZIO.foreach(snapshot.agents.filter(_.runId.exists(_.trim.nonEmpty)))(buildSummary)
    yield summaries.sortBy(item => (item.lastCheckpointAt.map(_.toEpochMilli).getOrElse(0L), item.runId)).reverse

  override def getRunReview(
    runId: String,
    selectedStep: Option[String],
    compareBase: Option[String],
    compareTarget: Option[String],
  ): IO[CheckpointReviewError, CheckpointRunReview] =
    for
      context     <- loadRunContext(runId)
      checkpoints <- stateService.listCheckpointSnapshots(runId).mapError(CheckpointReviewError.fromState)
      _           <- ZIO.fail(CheckpointReviewError.NotFound(runId)).when(context.monitor.isEmpty && checkpoints.isEmpty)
      summary     <- context.summary(checkpoints)
      selectedName = selectedStep.filter(_.trim.nonEmpty).orElse(checkpoints.lastOption.map(_.checkpoint.step))
      selected    <- ZIO.foreach(selectedName)(step => buildSnapshotReview(context, checkpoints, step)).map(_.flatten)
      comparison  <- (compareBase.filter(_.trim.nonEmpty), compareTarget.filter(_.trim.nonEmpty)) match
                       case (Some(left), Some(right)) => compare(runId, left, right)
                       case _                         => ZIO.none
    yield CheckpointRunReview(summary, context.workspaceRun, context.issue, checkpoints, selected, comparison)

  override def getSnapshotReview(runId: String, stepName: String)
    : IO[CheckpointReviewError, Option[CheckpointSnapshotReview]] =
    for
      context     <- loadRunContext(runId)
      checkpoints <- stateService.listCheckpointSnapshots(runId).mapError(CheckpointReviewError.fromState)
      review      <- buildSnapshotReview(context, checkpoints, stepName)
    yield review

  override def compare(runId: String, leftStep: String, rightStep: String)
    : IO[CheckpointReviewError, Option[CheckpointComparison]] =
    for
      checkpoints <- stateService.listCheckpointSnapshots(runId).mapError(CheckpointReviewError.fromState)
      left         = checkpoints.find(_.checkpoint.step == leftStep)
      right        = checkpoints.find(_.checkpoint.step == rightStep)
      compared    <- ZIO.succeed(for
                       leftSnapshot  <- left
                       rightSnapshot <- right
                     yield buildComparison(leftSnapshot, rightSnapshot))
    yield compared

  override def act(
    runId: String,
    action: CheckpointOperatorAction,
    note: Option[String],
  ): IO[CheckpointReviewError, CheckpointActionResult] =
    for
      context <- loadRunContext(runId)
      agent   <- ZIO
                   .fromOption(context.monitor)
                   .orElseFail(CheckpointReviewError.NotFound(runId))
      result  <- action match
                   case CheckpointOperatorAction.ApproveContinue =>
                     approveContinue(runId, context.workspaceRun, agent, note)
                   case CheckpointOperatorAction.Redirect        => redirect(runId, context.workspaceRun, agent, note)
                   case CheckpointOperatorAction.Pause           => pause(runId, context.workspaceRun, agent)
                   case CheckpointOperatorAction.Abort           => abort(runId, context.workspaceRun, agent)
                   case CheckpointOperatorAction.FlagFullReview  => flagFullReview(runId, context, note)
    yield result

  final private case class RunContext(
    monitor: Option[AgentExecutionInfo],
    workspaceRun: Option[WorkspaceRun],
    issue: Option[AgentIssue],
    proofOfWork: Option[IssueWorkReport],
    conversationId: Option[String],
    worktreePath: Option[String],
  ):
    def summary(checkpoints: List[CheckpointSnapshot]): IO[CheckpointReviewError, CheckpointRunSummary] =
      val agent = monitor.map(_.agentName).getOrElse(workspaceRun.map(_.agentName).getOrElse("unknown-agent"))
      val stage = monitor.map(stageName).getOrElse("IDLE")
      val step  = checkpoints.lastOption
        .flatMap(s => s.state.currentStepName.filter(_.trim.nonEmpty).orElse(Some(s.state.currentStep.toString)))
        .orElse(monitor.flatMap(_.step.map(_.toString)))
        .getOrElse("No checkpoint yet")
      ZIO.succeed(
        CheckpointRunSummary(
          runId = monitor.flatMap(_.runId).orElse(workspaceRun.map(_.id)).getOrElse("unknown-run"),
          agentName = agent,
          stage = stage,
          currentStepLabel = step,
          conversationId = conversationId,
          workspaceId = workspaceRun.map(_.workspaceId).orElse(issue.flatMap(_.workspaceId)),
          issueId = issue.map(_.id.value),
          checkpointCount = checkpoints.size,
          lastCheckpointAt = checkpoints.lastOption.map(_.checkpoint.createdAt),
          statusMessage = monitor.flatMap(_.message),
        )
      )

  private def loadRunContext(runId: String): IO[CheckpointReviewError, RunContext] =
    for
      monitorSnapshot <- controlPlane.getAgentMonitorSnapshot.mapError(CheckpointReviewError.fromControl)
      workspaceRun    <- workspaceRepository.getRun(runId).mapError(CheckpointReviewError.fromPersistence)
      state           <- stateService.loadState(runId).mapError(CheckpointReviewError.fromState)
      issue           <- resolveIssue(runId, workspaceRun, state)
      proofOfWork     <- ZIO.foreach(issue)(issueValue => workReportProjection.get(issueValue.id)).map(_.flatten)
      conversationId   = workspaceRun.map(_.conversationId)
                           .orElse(monitorSnapshot.agents.find(_.runId.contains(runId)).flatMap(_.conversationId))
      worktreePath     = workspaceRun.map(_.worktreePath).orElse(state.flatMap(_.workspace.map(_.workspaceRoot.toString)))
    yield RunContext(
      monitor = monitorSnapshot.agents.find(_.runId.contains(runId)),
      workspaceRun = workspaceRun,
      issue = issue,
      proofOfWork = proofOfWork,
      conversationId = conversationId,
      worktreePath = worktreePath,
    )

  private def resolveIssue(
    runId: String,
    workspaceRun: Option[WorkspaceRun],
    state: Option[taskrun.entity.TaskState],
  ): IO[CheckpointReviewError, Option[AgentIssue]] =
    workspaceRun match
      case Some(run) if run.issueRef.trim.nonEmpty =>
        issueRepository
          .get(IssueId(run.issueRef.trim.stripPrefix("#")))
          .map(Some(_))
          .catchSome { case PersistenceError.NotFound(_, _) => ZIO.none }
          .mapError(CheckpointReviewError.fromPersistence)
      case _                                       =>
        state.flatMap(_.taskRunId.filter(_.trim.nonEmpty)) match
          case Some(taskRunId) =>
            issueRepository
              .list(IssueFilter(runId = Some(TaskRunId(taskRunId)), limit = 1))
              .map(_.headOption)
              .mapError(CheckpointReviewError.fromPersistence)
          case None            =>
            issueRepository
              .list(IssueFilter(runId = Some(TaskRunId(runId)), limit = 1))
              .map(_.headOption)
              .mapError(CheckpointReviewError.fromPersistence)

  private def buildSummary(info: AgentExecutionInfo): IO[CheckpointReviewError, CheckpointRunSummary] =
    val runId = info.runId.getOrElse("")
    for
      checkpoints  <-
        if runId.trim.nonEmpty then
          stateService.listCheckpointSnapshots(runId).mapError(CheckpointReviewError.fromState)
        else ZIO.succeed(Nil)
      workspaceRun <-
        if runId.trim.nonEmpty then
          workspaceRepository.getRun(runId).mapError(CheckpointReviewError.fromPersistence)
        else ZIO.none
      issue        <- resolveIssue(runId, workspaceRun, None)
      proofOfWork  <- ZIO.foreach(issue)(item => workReportProjection.get(item.id)).map(_.flatten)
    yield CheckpointRunSummary(
      runId = runId,
      agentName = info.agentName,
      stage = stageName(info),
      currentStepLabel = checkpoints.lastOption
        .flatMap(snapshot =>
          snapshot.state.currentStepName.filter(_.trim.nonEmpty).orElse(Some(snapshot.state.currentStep.toString))
        )
        .orElse(info.step.map(_.toString))
        .getOrElse("No checkpoint yet"),
      conversationId = info.conversationId.orElse(workspaceRun.map(_.conversationId)),
      workspaceId = workspaceRun.map(_.workspaceId).orElse(issue.flatMap(_.workspaceId)),
      issueId = issue.map(_.id.value),
      checkpointCount = checkpoints.size,
      lastCheckpointAt = checkpoints.lastOption.map(_.checkpoint.createdAt),
      statusMessage = info.message.orElse(proofOfWork.flatMap(_.agentSummary)),
    )

  private def buildSnapshotReview(
    context: RunContext,
    checkpoints: List[CheckpointSnapshot],
    stepName: String,
  ): IO[CheckpointReviewError, Option[CheckpointSnapshotReview]] =
    checkpoints.find(_.checkpoint.step == stepName) match
      case None           => ZIO.none
      case Some(snapshot) =>
        for
          gitDiff      <- loadGitDiff(context.worktreePath)
          conversation <- loadConversationExcerpt(context.conversationId, snapshot.checkpoint.createdAt)
        yield Some(
          CheckpointSnapshotReview(
            snapshot = snapshot,
            gitDiff = gitDiff,
            testSignals = collectTestSignals(snapshot, context.proofOfWork),
            conversationExcerpt = conversation,
            proofOfWork = context.proofOfWork,
          )
        )

  private def loadGitDiff(worktreePath: Option[String]): IO[CheckpointReviewError, Option[String]] =
    worktreePath match
      case Some(path) if path.trim.nonEmpty =>
        gitService
          .diff(path)
          .mapError(CheckpointReviewError.fromGit)
          .map(renderGitDiff)
          .catchSome { case _: CheckpointReviewError.Git => ZIO.none }
      case _                                => ZIO.none

  private def renderGitDiff(diff: GitDiff): Option[String] =
    val rendered = diff.files.flatMap { file =>
      val content = Option(file.content).map(_.trim).getOrElse("")
      if content.nonEmpty then Some(content) else None
    }.mkString("\n\n")
    Option(rendered).map(_.trim).filter(_.nonEmpty)

  private def loadConversationExcerpt(
    conversationId: Option[String],
    createdAt: Instant,
  ): IO[CheckpointReviewError, List[CheckpointConversationExcerpt]] =
    conversationId.flatMap(id => Try(id.toLong).toOption) match
      case Some(numericId) =>
        chatRepository
          .getMessages(numericId)
          .mapError(CheckpointReviewError.fromPersistence)
          .map(
            _.filter(msg => !msg.createdAt.isAfter(createdAt))
              .takeRight(6)
              .map(toExcerpt)
          )
      case None            => ZIO.succeed(Nil)

  private def toExcerpt(message: ConversationEntry): CheckpointConversationExcerpt =
    CheckpointConversationExcerpt(
      sender = message.sender,
      senderType = senderTypeLabel(message.senderType),
      content = message.content,
      createdAt = message.createdAt,
    )

  private def collectTestSignals(
    snapshot: CheckpointSnapshot,
    proofOfWork: Option[IssueWorkReport],
  ): List[CheckpointTextEvidence] =
    val artifactSignals = snapshot.state.artifacts.toList.collect {
      case (key, value) if looksLikeTestSignal(key) && value.trim.nonEmpty =>
        CheckpointTextEvidence(key, value)
    }
    val reportSignals   = proofOfWork.toList.flatMap { report =>
      val reports   = report.reports.collect {
        case item if looksLikeTestSignal(item.reportType) || looksLikeTestSignal(item.stepName) =>
          CheckpointTextEvidence(item.reportType, item.content)
      }
      val artifacts = report.artifacts.collect {
        case item if looksLikeTestSignal(item.key) =>
          CheckpointTextEvidence(item.key, item.value)
      }
      val ciStatus  = report.ciStatus.map(status => CheckpointTextEvidence("ci-status", status.toString)).toList
      reports ++ artifacts ++ ciStatus
    }
    (artifactSignals ++ reportSignals).distinctBy(signal => signal.label -> signal.content)

  private def buildComparison(left: CheckpointSnapshot, right: CheckpointSnapshot): CheckpointComparison =
    val artifactKeys   = (left.state.artifacts.keySet ++ right.state.artifacts.keySet).toList.sorted
    val artifactDeltas = artifactKeys.flatMap { key =>
      val before = left.state.artifacts.get(key)
      val after  = right.state.artifacts.get(key)
      if before == after then None else Some(CheckpointArtifactDelta(key, before, after))
    }
    val leftErrors     = left.state.errors.map(err => s"${err.stepName}: ${err.message}").toSet
    val rightErrors    = right.state.errors.map(err => s"${err.stepName}: ${err.message}").toSet
    CheckpointComparison(
      leftStep = left.checkpoint.step,
      rightStep = right.checkpoint.step,
      currentStepChanged =
        left.state.currentStep != right.state.currentStep || left.state.currentStepName != right.state.currentStepName,
      completedStepsAdded = (right.state.completedSteps -- left.state.completedSteps).toList.sorted,
      completedStepsRemoved = (left.state.completedSteps -- right.state.completedSteps).toList.sorted,
      artifactDeltas = artifactDeltas,
      errorsAdded = (rightErrors -- leftErrors).toList.sorted,
      errorsResolved = (leftErrors -- rightErrors).toList.sorted,
    )

  private def approveContinue(
    runId: String,
    workspaceRun: Option[WorkspaceRun],
    agent: AgentExecutionInfo,
    note: Option[String],
  ): IO[CheckpointReviewError, CheckpointActionResult] =
    workspaceRun match
      case Some(run) if run.status == RunStatus.Running(RunSessionMode.Paused) =>
        val prompt = note.map(_.trim).filter(_.nonEmpty).getOrElse("Continue from the latest approved checkpoint.")
        runSessionManager
          .attach(runId, operatorUser)
          .ignore *>
          runSessionManager.resume(runId, operatorUser, prompt)
            .mapError(CheckpointReviewError.fromWorkspace)
            .as(CheckpointActionResult(
              CheckpointOperatorAction.ApproveContinue,
              runId,
              "Run resumed from checkpoint review.",
            ))
      case _                                                                   =>
        controlPlane
          .resumeAgentExecution(agent.agentName)
          .mapError(CheckpointReviewError.fromControl)
          .as(CheckpointActionResult(CheckpointOperatorAction.ApproveContinue, runId, "Execution resumed."))

  private def redirect(
    runId: String,
    workspaceRun: Option[WorkspaceRun],
    agent: AgentExecutionInfo,
    note: Option[String],
  ): IO[CheckpointReviewError, CheckpointActionResult] =
    val message = note.map(_.trim).filter(_.nonEmpty).getOrElse("Please adjust course based on checkpoint review.")
    workspaceRun match
      case Some(run) if run.status == RunStatus.Running(RunSessionMode.Paused) =>
        runSessionManager
          .attach(runId, operatorUser)
          .ignore *>
          runSessionManager.resume(runId, operatorUser, message)
            .mapError(CheckpointReviewError.fromWorkspace)
            .as(CheckpointActionResult(
              CheckpointOperatorAction.Redirect,
              runId,
              "Redirect sent and paused run resumed.",
            ))
      case Some(run)
           if run.status == RunStatus.Completed || run.status == RunStatus.Failed || run.status == RunStatus.Cancelled =>
        workspaceRunService
          .continueRun(runId, message)
          .mapError(CheckpointReviewError.fromWorkspace)
          .as(CheckpointActionResult(
            CheckpointOperatorAction.Redirect,
            runId,
            "New continuation run started with redirect note.",
          ))
      case Some(_)                                                             =>
        runSessionManager
          .attach(runId, operatorUser)
          .ignore *>
          runSessionManager
            .sendMessage(runId, operatorUser, message)
            .mapError(CheckpointReviewError.fromWorkspace)
            .map {
              case Right(_)    =>
                CheckpointActionResult(CheckpointOperatorAction.Redirect, runId, "Redirect sent to active run.")
              case Left(value) =>
                CheckpointActionResult(
                  CheckpointOperatorAction.Redirect,
                  runId,
                  s"Redirect queued for continuation: ${value.reason}",
                )
            }
      case None                                                                =>
        appendConversationNote(agent.conversationId, message).as(
          CheckpointActionResult(CheckpointOperatorAction.Redirect, runId, "Redirect note recorded on conversation.")
        )

  private def pause(
    runId: String,
    workspaceRun: Option[WorkspaceRun],
    agent: AgentExecutionInfo,
  ): IO[CheckpointReviewError, CheckpointActionResult] =
    workspaceRun match
      case Some(_) =>
        runSessionManager
          .attach(runId, operatorUser)
          .ignore *>
          runSessionManager.interrupt(runId, operatorUser)
            .mapError(CheckpointReviewError.fromWorkspace)
            .orElse(
              controlPlane.pauseAgentExecution(agent.agentName).mapError(CheckpointReviewError.fromControl)
            )
            .as(CheckpointActionResult(CheckpointOperatorAction.Pause, runId, "Execution paused."))
      case None    =>
        controlPlane
          .pauseAgentExecution(agent.agentName)
          .mapError(CheckpointReviewError.fromControl)
          .as(CheckpointActionResult(CheckpointOperatorAction.Pause, runId, "Execution paused."))

  private def abort(
    runId: String,
    workspaceRun: Option[WorkspaceRun],
    agent: AgentExecutionInfo,
  ): IO[CheckpointReviewError, CheckpointActionResult] =
    workspaceRun match
      case Some(_) =>
        workspaceRunService
          .cancelRun(runId)
          .mapError(CheckpointReviewError.fromWorkspace)
          .as(CheckpointActionResult(CheckpointOperatorAction.Abort, runId, "Run cancelled."))
      case None    =>
        controlPlane
          .abortAgentExecution(agent.agentName)
          .mapError(CheckpointReviewError.fromControl)
          .as(CheckpointActionResult(CheckpointOperatorAction.Abort, runId, "Execution aborted."))

  private def flagFullReview(
    runId: String,
    context: RunContext,
    note: Option[String],
  ): IO[CheckpointReviewError, CheckpointActionResult] =
    val summary = note.map(_.trim).filter(_.nonEmpty).getOrElse("Checkpoint flagged for full human review.")
    decisionInbox
      .openManualDecision(
        title = s"Checkpoint review required for run $runId",
        context =
          context.monitor.flatMap(_.message).getOrElse("Operator requested full review from checkpoint surface."),
        referenceId = runId,
        summary = summary,
        urgency = DecisionUrgency.High,
        workspaceId = context.workspaceRun.map(_.workspaceId).orElse(context.issue.flatMap(_.workspaceId)),
        issueId = context.issue.map(_.id),
      )
      .mapError(CheckpointReviewError.fromPersistence)
      .as(CheckpointActionResult(CheckpointOperatorAction.FlagFullReview, runId, "Decision inbox item created."))

  private def appendConversationNote(conversationId: Option[String], content: String): IO[CheckpointReviewError, Unit] =
    conversationId.flatMap(id => Try(id.toLong).toOption) match
      case Some(numericId) =>
        Clock.instant.flatMap { now =>
          chatRepository
            .addMessage(
              ConversationEntry(
                conversationId = numericId.toString,
                sender = operatorUser,
                senderType = SenderType.User,
                content = content,
                createdAt = now,
                updatedAt = now,
              )
            )
            .mapError(CheckpointReviewError.fromPersistence)
            .unit
        }
      case None            =>
        ZIO.fail(CheckpointReviewError.InvalidAction("unknown", "redirect", "Run has no web conversation to annotate"))

  private def looksLikeTestSignal(value: String): Boolean =
    val normalized = value.trim.toLowerCase
    normalized.contains("test") || normalized.contains("ci") || normalized.contains("spec")

  private def stageName(info: AgentExecutionInfo): String =
    info.state match
      case AgentExecutionState.Idle           => "IDLE"
      case AgentExecutionState.Executing      => "EXEC"
      case AgentExecutionState.WaitingForTool => "TOOL"
      case AgentExecutionState.Paused         => "PAUSED"
      case AgentExecutionState.Aborted        => "ABORT"
      case AgentExecutionState.Failed         => "FAIL"

  private def senderTypeLabel(senderType: SenderType): String =
    senderType match
      case SenderType.User      => "user"
      case SenderType.Assistant => "assistant"
      case SenderType.System    => "system"
