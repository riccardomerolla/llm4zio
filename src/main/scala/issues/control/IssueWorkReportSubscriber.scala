package issues.control

import zio.*

import issues.entity.*
import orchestration.control.{ ParallelSessionEvent, WorkReportEventBus }
import shared.ids.Ids.{ IssueId, TaskRunId }
import taskrun.entity.TaskRunEvent

/** Subscribes to domain event streams and updates `IssueWorkReportProjection` in real-time.
  *
  * Call `start` inside a `ZIO.scoped` block — it forks background fibers that run until the scope closes.
  */
final class IssueWorkReportSubscriber(
  bus: WorkReportEventBus,
  projection: IssueWorkReportProjection,
  issueRepo: IssueRepository,
):

  /** Subscribe to all hubs and fork consumer fibers. Subscriptions are registered synchronously before any fibers are
    * forked, so events published after `start` returns are guaranteed to be received.
    */
  def start: URIO[Scope, Unit] =
    for
      taskRunQueue         <- bus.subscribeTaskRun
      issueQueue           <- bus.subscribeIssue
      parallelSessionQueue <- bus.subscribeParallelSession
      _                    <- taskRunQueue.take.flatMap(handleTaskRunEvent).forever.forkScoped.unit
      _                    <- issueQueue.take.flatMap(handleIssueEvent).forever.forkScoped.unit
      _                    <- parallelSessionQueue.take.flatMap(handleParallelSessionEvent).forever.forkScoped.unit
    yield ()

  private def resolveIssueId(runId: TaskRunId): UIO[Option[IssueId]] =
    issueRepo
      .list(IssueFilter(runId = Some(runId), limit = 1))
      .map(_.headOption.map(_.id))
      .orElseSucceed(None)

  private def handleTaskRunEvent(event: TaskRunEvent): UIO[Unit] =
    resolveIssueId(event.runId).flatMap {
      case None          => ZIO.unit
      case Some(issueId) =>
        event match
          case e: TaskRunEvent.WalkthroughGenerated =>
            projection.updateWalkthrough(issueId, e.summary, e.occurredAt)
          case e: TaskRunEvent.PrLinked             =>
            projection.updatePrLink(issueId, e.prUrl, mapPrStatus(e.prStatus), e.occurredAt)
          case e: TaskRunEvent.CiStatusUpdated      =>
            projection.updateCiStatus(issueId, mapCiStatus(e.ciStatus), e.occurredAt)
          case e: TaskRunEvent.TokenUsageRecorded   =>
            projection.updateTokenUsage(
              issueId,
              TokenUsage(e.inputTokens, e.outputTokens, e.inputTokens + e.outputTokens),
              e.runtimeSeconds,
              e.occurredAt,
            )
          case e: TaskRunEvent.ReportAdded          =>
            projection.addReport(issueId, mapReport(e.report), e.occurredAt)
          case e: TaskRunEvent.ArtifactAdded        =>
            projection.addArtifact(issueId, mapArtifact(e.artifact), e.occurredAt)
          case _                                    => ZIO.unit
    }

  private def handleIssueEvent(event: issues.entity.IssueEvent): UIO[Unit] =
    event match
      case e: IssueEvent.Assigned           =>
        projection.updateAgentSummary(e.issueId, s"Assigned to agent ${e.agent.value}", e.occurredAt)
      case e: IssueEvent.Started            =>
        projection.updateAgentSummary(e.issueId, s"Agent ${e.agent.value} started work", e.occurredAt)
      case e: IssueEvent.MovedToTodo        =>
        projection.updateAgentSummary(e.issueId, "Ready in Todo", e.occurredAt)
      case e: IssueEvent.MovedToHumanReview =>
        projection.updateAgentSummary(e.issueId, "Waiting for human review", e.occurredAt)
      case e: IssueEvent.MovedToRework      =>
        projection.updateAgentSummary(e.issueId, s"Moved to rework: ${e.reason}", e.occurredAt)
      case e: IssueEvent.MovedToMerging     =>
        projection.updateAgentSummary(e.issueId, "Approved and moving to merge", e.occurredAt)
      case e: IssueEvent.MarkedDone         =>
        projection.updateAgentSummary(e.issueId, s"Done: ${e.result}", e.occurredAt)
      case e: IssueEvent.Canceled           =>
        projection.updateAgentSummary(e.issueId, s"Canceled: ${e.reason}", e.occurredAt)
      case e: IssueEvent.Duplicated         =>
        projection.updateAgentSummary(e.issueId, s"Duplicated: ${e.reason}", e.occurredAt)
      case e: IssueEvent.Completed          =>
        projection.updateAgentSummary(e.issueId, s"Completed by ${e.agent.value}: ${e.result}", e.occurredAt)
      case e: IssueEvent.Failed             =>
        projection.updateAgentSummary(e.issueId, s"Failed: ${e.errorMessage}", e.occurredAt)
      case _                                => ZIO.unit

  private def handleParallelSessionEvent(event: ParallelSessionEvent): UIO[Unit] =
    event match
      case e: ParallelSessionEvent.WorktreeAgentCompleted =>
        // Resolve the issue linked to this session. We list all issues without a runId filter
        // since parallel sessions don't carry runId directly. Use the first issue found in the list
        // for the current workspace context (best-effort).
        issueRepo
          .list(IssueFilter(limit = 1000))
          .orElseSucceed(Nil)
          .flatMap { issues =>
            issues.headOption match
              case None        => ZIO.unit
              case Some(issue) =>
                projection.updateDiffStats(
                  issue.id,
                  IssueDiffStats(e.diffStats.filesChanged, e.diffStats.linesAdded, e.diffStats.linesRemoved),
                  e.occurredAt,
                ) *>
                  projection.updateAgentSummary(issue.id, e.summary, e.occurredAt)
          }
      case _                                              => ZIO.unit

  private def mapPrStatus(s: taskrun.entity.PrStatus): IssuePrStatus = s match
    case taskrun.entity.PrStatus.Open   => IssuePrStatus.Open
    case taskrun.entity.PrStatus.Merged => IssuePrStatus.Merged
    case taskrun.entity.PrStatus.Closed => IssuePrStatus.Closed
    case taskrun.entity.PrStatus.Draft  => IssuePrStatus.Draft

  private def mapCiStatus(s: taskrun.entity.CiStatus): IssueCiStatus = s match
    case taskrun.entity.CiStatus.Pending => IssueCiStatus.Pending
    case taskrun.entity.CiStatus.Running => IssueCiStatus.Running
    case taskrun.entity.CiStatus.Passed  => IssueCiStatus.Passed
    case taskrun.entity.CiStatus.Failed  => IssueCiStatus.Failed

  private def mapReport(r: taskrun.entity.TaskReport): IssueReport =
    IssueReport(r.id, r.stepName, r.reportType, r.content, r.createdAt)

  private def mapArtifact(a: taskrun.entity.TaskArtifact): IssueArtifact =
    IssueArtifact(a.id, a.stepName, a.key, a.value, a.createdAt)

object IssueWorkReportSubscriber:

  val layer: URLayer[WorkReportEventBus & IssueWorkReportProjection & IssueRepository, IssueWorkReportSubscriber] =
    ZLayer.fromZIO {
      for
        bus        <- ZIO.service[WorkReportEventBus]
        projection <- ZIO.service[IssueWorkReportProjection]
        issueRepo  <- ZIO.service[IssueRepository]
      yield IssueWorkReportSubscriber(bus, projection, issueRepo)
    }
