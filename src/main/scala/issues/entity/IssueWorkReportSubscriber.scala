package issues.entity

import zio.*

import orchestration.control.{ ParallelSessionEvent, WorkReportEventBus }
import shared.ids.Ids.{ IssueId, TaskRunId }
import taskrun.entity.*

/** Subscribes to domain event streams and updates `IssueWorkReportProjection` in real-time.
  *
  * Call `start` inside a `ZIO.scoped` block — it forks background fibers that run until the scope closes.
  */
final class IssueWorkReportSubscriber(
  bus: WorkReportEventBus,
  projection: IssueWorkReportProjection,
  issueRepo: IssueRepository,
):

  /** Fork all subscriber fibers. Must be called inside a Scope. */
  def start: URIO[Scope, Unit] =
    for
      _ <- ZIO.scoped(subscribeTaskRun).forkScoped.unit
      _ <- ZIO.scoped(subscribeIssueEvents).forkScoped.unit
      _ <- ZIO.scoped(subscribeParallelSessions).forkScoped.unit
    yield ()

  private def resolveIssueId(runId: TaskRunId): UIO[Option[IssueId]] =
    issueRepo
      .list(IssueFilter(runId = Some(runId), limit = 1))
      .map(_.headOption.map(_.id))
      .orElseSucceed(None)

  private def subscribeTaskRun: URIO[Scope, Unit] =
    bus.subscribeTaskRun.flatMap { queue =>
      queue.take.flatMap(handleTaskRunEvent).forever
    }

  private def subscribeIssueEvents: URIO[Scope, Unit] =
    bus.subscribeIssue.flatMap { queue =>
      queue.take.flatMap(handleIssueEvent).forever
    }

  private def subscribeParallelSessions: URIO[Scope, Unit] =
    bus.subscribeParallelSession.flatMap { queue =>
      queue.take.flatMap(handleParallelSessionEvent).forever
    }

  private def handleTaskRunEvent(event: TaskRunEvent): UIO[Unit] =
    resolveIssueId(event.runId).flatMap {
      case None          => ZIO.unit
      case Some(issueId) =>
        event match
          case e: TaskRunEvent.WalkthroughGenerated =>
            projection.updateWalkthrough(issueId, e.summary, e.occurredAt)
          case e: TaskRunEvent.PrLinked             =>
            projection.updatePrLink(issueId, e.prUrl, e.prStatus, e.occurredAt)
          case e: TaskRunEvent.CiStatusUpdated      =>
            projection.updateCiStatus(issueId, e.ciStatus, e.occurredAt)
          case e: TaskRunEvent.TokenUsageRecorded   =>
            projection.updateTokenUsage(
              issueId,
              TokenUsage(e.inputTokens, e.outputTokens, e.inputTokens + e.outputTokens),
              e.runtimeSeconds,
              e.occurredAt,
            )
          case e: TaskRunEvent.ReportAdded          =>
            projection.addReport(issueId, e.report, e.occurredAt)
          case e: TaskRunEvent.ArtifactAdded        =>
            projection.addArtifact(issueId, e.artifact, e.occurredAt)
          case _                                    => ZIO.unit
    }

  private def handleIssueEvent(event: issues.entity.IssueEvent): UIO[Unit] =
    event match
      case e: IssueEvent.Assigned  =>
        projection.updateAgentSummary(e.issueId, s"Assigned to agent ${e.agent.value}", e.occurredAt)
      case e: IssueEvent.Started   =>
        projection.updateAgentSummary(e.issueId, s"Agent ${e.agent.value} started work", e.occurredAt)
      case e: IssueEvent.Completed =>
        projection.updateAgentSummary(e.issueId, s"Completed by ${e.agent.value}: ${e.result}", e.occurredAt)
      case e: IssueEvent.Failed    =>
        projection.updateAgentSummary(e.issueId, s"Failed: ${e.errorMessage}", e.occurredAt)
      case _                       => ZIO.unit

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
                projection.updateDiffStats(issue.id, e.diffStats, e.occurredAt) *>
                  projection.updateAgentSummary(issue.id, e.summary, e.occurredAt)
          }
      case _                                              => ZIO.unit

object IssueWorkReportSubscriber:

  val layer: URLayer[WorkReportEventBus & IssueWorkReportProjection & IssueRepository, IssueWorkReportSubscriber] =
    ZLayer.fromZIO {
      for
        bus        <- ZIO.service[WorkReportEventBus]
        projection <- ZIO.service[IssueWorkReportProjection]
        issueRepo  <- ZIO.service[IssueRepository]
      yield IssueWorkReportSubscriber(bus, projection, issueRepo)
    }
