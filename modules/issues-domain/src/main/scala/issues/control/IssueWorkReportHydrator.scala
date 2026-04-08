package issues.control

import zio.*

import issues.entity.*
import taskrun.entity.{ TaskRun, TaskRunFilter, TaskRunRepository, TaskRunState }

/** Hydrates `IssueWorkReportProjection` from historical state on application startup.
  *
  * Reads all `TaskRun`s and `AgentIssue`s, matches them by `runId`, and populates the projection with any proof-of-work
  * signals already present in the aggregate state.
  *
  * Only creates projection entries when at least one proof-of-work signal is present — avoids bloating the projection
  * with empty entries for runs that have no evidence yet.
  */
final class IssueWorkReportHydrator(projection: IssueWorkReportProjection):

  def hydrate(issues: List[AgentIssue], runs: List[TaskRun]): UIO[Unit] =
    val runById = runs.map(r => r.id -> r).toMap
    ZIO.foreachDiscard(issues) { issue =>
      issue.runId.flatMap(runById.get) match
        case None      => ZIO.unit
        case Some(run) =>
          val hasAnySignal =
            run.walkthrough.isDefined ||
            run.prLink.isDefined ||
            run.ciStatus.isDefined ||
            run.tokenUsage.isDefined ||
            run.reports.nonEmpty ||
            run.artifacts.nonEmpty

          if !hasAnySignal then ZIO.unit
          else
            val at           = run.state match
              case TaskRunState.Completed(_, completedAt, _) => completedAt
              case TaskRunState.Failed(_, failedAt, _)       => failedAt
              case TaskRunState.Running(startedAt, _)        => startedAt
              case TaskRunState.Pending(createdAt)           => createdAt
              case TaskRunState.Cancelled(cancelledAt, _)    => cancelledAt
            val agentSummary = issue.state match
              case IssueState.Todo(_)                               =>
                Some("Ready in Todo")
              case IssueState.Assigned(agent, assignedAt)           =>
                Some(s"Assigned to agent ${agent.value}")
              case IssueState.InProgress(agent, startedAt)          =>
                Some(s"Agent ${agent.value} working on issue")
              case IssueState.HumanReview(_)                        =>
                Some("Waiting for human review")
              case IssueState.Rework(_, reason)                     =>
                Some(s"Moved to rework: $reason")
              case IssueState.Merging(_)                            =>
                Some("Approved and moving to merge")
              case IssueState.Done(_, result)                       =>
                Some(s"Done: $result")
              case IssueState.Canceled(_, reason)                   =>
                Some(s"Canceled: $reason")
              case IssueState.Duplicated(_, reason)                 =>
                Some(s"Duplicated: $reason")
              case IssueState.Completed(agent, completedAt, result) =>
                Some(s"Completed by ${agent.value}: $result")
              case IssueState.Failed(agent, failedAt, msg)          =>
                Some(s"Failed: $msg")
              case _                                                => None

            for
              _ <- run.walkthrough.fold(ZIO.unit)(w => projection.updateWalkthrough(issue.id, w, at))
              _ <- run.prLink.fold(ZIO.unit) { url =>
                     val status = run.prStatus.map(mapPrStatus).getOrElse(IssuePrStatus.Open)
                     projection.updatePrLink(issue.id, url, status, at)
                   }
              _ <- run.ciStatus.fold(ZIO.unit)(ci => projection.updateCiStatus(issue.id, mapCiStatus(ci), at))
              _ <- run.tokenUsage.fold(ZIO.unit) { usage =>
                     projection.updateTokenUsage(
                       issue.id,
                       TokenUsage(usage.inputTokens, usage.outputTokens, usage.totalTokens),
                       run.runtimeSeconds.getOrElse(0L),
                       at,
                     )
                   }
              _ <- ZIO.foreachDiscard(run.reports)(r => projection.addReport(issue.id, mapReport(r), at))
              _ <- ZIO.foreachDiscard(run.artifacts)(a => projection.addArtifact(issue.id, mapArtifact(a), at))
              _ <- agentSummary.fold(ZIO.unit)(s => projection.updateAgentSummary(issue.id, s, at))
            yield ()
    }

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

object IssueWorkReportHydrator:

  /** Run hydration as part of application startup. Errors are logged and swallowed so a hydration failure never
    * prevents the application from starting.
    */
  def runStartup(
    projection: IssueWorkReportProjection,
    issueRepo: IssueRepository,
    taskRunRepo: TaskRunRepository,
  ): UIO[Unit] =
    (for
      issues <- issueRepo.list(IssueFilter(limit = Int.MaxValue))
      runs   <- taskRunRepo.list(TaskRunFilter(limit = Int.MaxValue))
      _      <- ZIO.logInfo(s"Hydrating IssueWorkReportProjection from ${issues.size} issues, ${runs.size} runs")
      _      <- IssueWorkReportHydrator(projection).hydrate(issues, runs)
      _      <- ZIO.logInfo("IssueWorkReportProjection hydration complete")
    yield ()).catchAll(err => ZIO.logWarning(s"IssueWorkReportProjection hydration failed (non-fatal): $err"))

  val layer: URLayer[IssueWorkReportProjection, IssueWorkReportHydrator] =
    ZLayer.fromFunction(IssueWorkReportHydrator.apply)
