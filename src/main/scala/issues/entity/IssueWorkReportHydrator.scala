package issues.entity

import zio.*

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
              case IssueState.Assigned(agent, assignedAt)           =>
                Some(s"Assigned to agent ${agent.value}")
              case IssueState.InProgress(agent, startedAt)          =>
                Some(s"Agent ${agent.value} working on issue")
              case IssueState.Completed(agent, completedAt, result) =>
                Some(s"Completed by ${agent.value}: $result")
              case IssueState.Failed(agent, failedAt, msg)          =>
                Some(s"Failed: $msg")
              case _                                                => None

            for
              _ <- run.walkthrough.fold(ZIO.unit)(w => projection.updateWalkthrough(issue.id, w, at))
              _ <- run.prLink.fold(ZIO.unit) { url =>
                     projection.updatePrLink(issue.id, url, run.prStatus.getOrElse(taskrun.entity.PrStatus.Open), at)
                   }
              _ <- run.ciStatus.fold(ZIO.unit)(ci => projection.updateCiStatus(issue.id, ci, at))
              _ <- run.tokenUsage.fold(ZIO.unit) { usage =>
                     projection.updateTokenUsage(issue.id, usage, run.runtimeSeconds.getOrElse(0L), at)
                   }
              _ <- ZIO.foreachDiscard(run.reports)(r => projection.addReport(issue.id, r, at))
              _ <- ZIO.foreachDiscard(run.artifacts)(a => projection.addArtifact(issue.id, a, at))
              _ <- agentSummary.fold(ZIO.unit)(s => projection.updateAgentSummary(issue.id, s, at))
            yield ()
    }

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
