package sdlc.control

import java.time.{ Duration, Instant }

import zio.*

import activity.entity.{ ActivityEvent, ActivityRepository }
import db.{ ConfigRepository, PersistenceError as DbPersistenceError }
import decision.control.DecisionInbox
import decision.entity.{ Decision, DecisionFilter, DecisionStatus, DecisionUrgency }
import issues.entity.*
import plan.entity.{ Plan, PlanRepository, PlanStatus }
import shared.errors.PersistenceError
import specification.entity.{ Specification, SpecificationRepository, SpecificationStatus }

trait SdlcDashboardService:
  def snapshot: IO[PersistenceError, SdlcDashboardService.Snapshot]

object SdlcDashboardService:

  final case class Thresholds(
    churnTransitions: Int,
    churnBounces: Int,
    stalledHours: Long,
    blockedHours: Long,
    reviewHours: Long,
    decisionHours: Long,
  )

  final case class LifecycleStage(
    key: String,
    label: String,
    count: Int,
    href: String,
    description: String,
  )

  final case class ChurnAlert(
    issueId: String,
    title: String,
    transitionCount: Int,
    bounceCount: Int,
    currentState: String,
    lastChangedAt: Instant,
  )

  final case class StoppageAlert(
    kind: String,
    issueId: String,
    title: String,
    currentState: String,
    ageHours: Long,
    blockedBy: List[String],
  )

  final case class EscalationIndicator(
    kind: String,
    referenceId: String,
    title: String,
    urgency: String,
    ageHours: Long,
    summary: String,
  )

  final case class AgentPerformance(
    agentName: String,
    throughput: Int,
    successRate: Double,
    averageCycleHours: Double,
    activeIssues: Int,
    costUsd: Double,
  )

  final case class Snapshot(
    generatedAt: Instant,
    thresholds: Thresholds,
    lifecycle: List[LifecycleStage],
    churnAlerts: List[ChurnAlert],
    stoppages: List[StoppageAlert],
    escalations: List[EscalationIndicator],
    agentPerformance: List[AgentPerformance],
    recentActivity: List[ActivityEvent],
    specificationCount: Int,
    planCount: Int,
    issueCount: Int,
    pendingDecisionCount: Int,
  )

  val live
    : ZLayer[
      SpecificationRepository & PlanRepository & IssueRepository & DecisionInbox & ActivityRepository & ConfigRepository &
        IssueWorkReportProjection,
      Nothing,
      SdlcDashboardService,
    ] =
    ZLayer.fromFunction(SdlcDashboardServiceLive.apply)

final case class SdlcDashboardServiceLive(
  specificationRepository: SpecificationRepository,
  planRepository: PlanRepository,
  issueRepository: IssueRepository,
  decisionInbox: DecisionInbox,
  activityRepository: ActivityRepository,
  configRepository: ConfigRepository,
  workReportProjection: IssueWorkReportProjection,
) extends SdlcDashboardService:
  import SdlcDashboardService.*

  override def snapshot: IO[PersistenceError, Snapshot] =
    for
      now            <- Clock.instant
      thresholds     <- loadThresholds
      specifications <- specificationRepository.list
      plans          <- planRepository.list
      issues         <- issueRepository.list(issues.entity.IssueFilter(limit = Int.MaxValue))
      histories      <- ZIO.foreachPar(issues)(issue =>
                          issueRepository.history(issue.id).map(events => issue.id -> events)
                        ).map(_.toMap)
      decisions      <- decisionInbox.list(DecisionFilter(limit = Int.MaxValue))
      activity       <- activityRepository.listEvents(limit = 8).mapError(mapActivityError)
      workReports    <- workReportProjection.getAll
      lifecycle       = buildLifecycle(specifications, plans, issues)
      churn           = buildChurnAlerts(issues, histories, thresholds)
      stoppages       = buildStoppages(now, issues, histories, thresholds)
      escalations     = buildEscalations(now, issues, histories, decisions, thresholds)
      agentPerf       = buildAgentPerformance(issues, histories, workReports)
    yield Snapshot(
      generatedAt = now,
      thresholds = thresholds,
      lifecycle = lifecycle,
      churnAlerts = churn,
      stoppages = stoppages,
      escalations = escalations,
      agentPerformance = agentPerf,
      recentActivity = activity,
      specificationCount = specifications.size,
      planCount = plans.size,
      issueCount = issues.size,
      pendingDecisionCount = decisions.count(_.status == DecisionStatus.Pending),
    )

  private def loadThresholds: IO[PersistenceError, Thresholds] =
    for
      churnTransitions <- loadInt("sdlc.thresholds.churn.transitions", 6)
      churnBounces     <- loadInt("sdlc.thresholds.churn.bounces", 2)
      stalledHours     <- loadLong("sdlc.thresholds.stalledHours", 24L)
      blockedHours     <- loadLong("sdlc.thresholds.blockedHours", 12L)
      reviewHours      <- loadLong("sdlc.thresholds.reviewHours", 8L)
      decisionHours    <- loadLong("sdlc.thresholds.decisionHours", 4L)
    yield Thresholds(
      churnTransitions = churnTransitions,
      churnBounces = churnBounces,
      stalledHours = stalledHours,
      blockedHours = blockedHours,
      reviewHours = reviewHours,
      decisionHours = decisionHours,
    )

  private def loadInt(key: String, default: Int): IO[PersistenceError, Int] =
    configRepository
      .getSetting(key)
      .mapError(err => PersistenceError.QueryFailed(s"config_get:$key", err.toString))
      .map(_.flatMap(row => Option(row.value).map(_.trim).filter(_.nonEmpty).flatMap(_.toIntOption)).getOrElse(default))

  private def loadLong(key: String, default: Long): IO[PersistenceError, Long] =
    configRepository
      .getSetting(key)
      .mapError(err => PersistenceError.QueryFailed(s"config_get:$key", err.toString))
      .map(
        _.flatMap(row => Option(row.value).map(_.trim).filter(_.nonEmpty).flatMap(_.toLongOption)).getOrElse(default)
      )

  private def buildLifecycle(
    specifications: List[Specification],
    plans: List[Plan],
    issues: List[AgentIssue],
  ): List[LifecycleStage] =
    List(
      LifecycleStage(
        key = "idea",
        label = "Idea",
        count = specifications.count(spec =>
          spec.status != SpecificationStatus.Approved && spec.status != SpecificationStatus.Superseded
        ),
        href = "/specifications",
        description = "Draft and refinement specs before approval.",
      ),
      LifecycleStage(
        key = "spec",
        label = "Spec",
        count = specifications.count(_.status == SpecificationStatus.Approved),
        href = "/specifications",
        description = "Approved specifications ready for planning.",
      ),
      LifecycleStage(
        key = "plan",
        label = "Plan",
        count = plans.count(plan => plan.status == PlanStatus.Draft || plan.status == PlanStatus.Validated),
        href = "/plans",
        description = "Plans waiting for or passing validation.",
      ),
      LifecycleStage(
        key = "tasks",
        label = "Tasks",
        count = issues.count(issue => isTaskQueueState(issue.state)),
        href = "/issues/board",
        description = "Queued work not yet actively executing.",
      ),
      LifecycleStage(
        key = "in-progress",
        label = "In Progress",
        count = issues.count(issue =>
          issue.state.isInstanceOf[IssueState.InProgress] || issue.state.isInstanceOf[IssueState.Rework]
        ),
        href = "/issues/board?status=in_progress",
        description = "Active execution and immediate rework.",
      ),
      LifecycleStage(
        key = "review",
        label = "Review",
        count = issues.count(_.state.isInstanceOf[IssueState.HumanReview]),
        href = "/issues/board?status=human_review",
        description = "Human review and approval queue.",
      ),
      LifecycleStage(
        key = "merge",
        label = "Merge",
        count = issues.count(_.state.isInstanceOf[IssueState.Merging]),
        href = "/issues/board?status=merging",
        description = "Merge and CI completion stage.",
      ),
      LifecycleStage(
        key = "done",
        label = "Done",
        count = issues.count(issue =>
          issue.state.isInstanceOf[IssueState.Done] || issue.state.isInstanceOf[IssueState.Completed]
        ),
        href = "/issues/board?status=done",
        description = "Closed work items.",
      ),
    )

  private def isTaskQueueState(state: IssueState): Boolean =
    state match
      case _: IssueState.Backlog | _: IssueState.Open | _: IssueState.Todo | _: IssueState.Assigned => true
      case _                                                                                        => false

  private def buildChurnAlerts(
    issues: List[AgentIssue],
    histories: Map[shared.ids.Ids.IssueId, List[IssueEvent]],
    thresholds: Thresholds,
  ): List[ChurnAlert] =
    issues.flatMap { issue =>
      val metrics = summarizeIssueHistory(issue, histories.getOrElse(issue.id, Nil))
      Option.when(
        metrics.transitionCount >= thresholds.churnTransitions || metrics.bounceCount >= thresholds.churnBounces
      )(
        ChurnAlert(
          issueId = issue.id.value,
          title = issue.title,
          transitionCount = metrics.transitionCount,
          bounceCount = metrics.bounceCount,
          currentState = formatState(issue.state),
          lastChangedAt = metrics.lastChangedAt,
        )
      )
    }.sortBy(alert => (-alert.transitionCount, -alert.bounceCount, -alert.lastChangedAt.toEpochMilli)).take(8)

  private def buildStoppages(
    now: Instant,
    issues: List[AgentIssue],
    histories: Map[shared.ids.Ids.IssueId, List[IssueEvent]],
    thresholds: Thresholds,
  ): List[StoppageAlert] =
    issues.flatMap { issue =>
      if isTerminal(issue.state) then Nil
      else
        val metrics  = summarizeIssueHistory(issue, histories.getOrElse(issue.id, Nil))
        val ageHours = elapsedHours(metrics.lastChangedAt, now)
        val blocked  = issue.blockedBy.nonEmpty && ageHours >= thresholds.blockedHours
        val stalled  = !blocked && ageHours >= thresholds.stalledHours
        if blocked || stalled then
          List(
            StoppageAlert(
              kind = if blocked then "Blocked" else "Stalled",
              issueId = issue.id.value,
              title = issue.title,
              currentState = formatState(issue.state),
              ageHours = ageHours,
              blockedBy = issue.blockedBy.map(_.value),
            )
          )
        else Nil
    }.sortBy(alert => (-alert.ageHours, alert.issueId)).take(10)

  private def buildEscalations(
    now: Instant,
    issues: List[AgentIssue],
    histories: Map[shared.ids.Ids.IssueId, List[IssueEvent]],
    decisions: List[Decision],
    thresholds: Thresholds,
  ): List[EscalationIndicator] =
    val reviewAging = issues.flatMap { issue =>
      issue.state match
        case review: IssueState.HumanReview =>
          val reviewAt = histories
            .getOrElse(issue.id, Nil)
            .collect { case event: IssueEvent.MovedToHumanReview => event.movedAt }
            .lastOption
            .getOrElse(review.reviewAt)
          val ageHours = elapsedHours(reviewAt, now)
          Option.when(ageHours >= thresholds.reviewHours)(
            EscalationIndicator(
              kind = "Human Review",
              referenceId = issue.id.value,
              title = issue.title,
              urgency = issue.priority.trim.toLowerCase,
              ageHours = ageHours,
              summary = s"Awaiting review for ${ageHours}h in ${formatState(issue.state).toLowerCase}.",
            )
          )
        case _                              => None
    }
    val pending     = decisions.flatMap { decision =>
      Option.when(decision.status == DecisionStatus.Pending) {
        val ageHours = elapsedHours(decision.createdAt, now)
        EscalationIndicator(
          kind = "Decision",
          referenceId = decision.id.value,
          title = decision.title,
          urgency = formatUrgency(decision.urgency),
          ageHours = ageHours,
          summary = decision.source.summary,
        )
      }
    }
    (reviewAging ++ pending)
      .filter(alert =>
        alert.kind != "Decision" || alert.ageHours >= thresholds.decisionHours || isHighUrgency(alert.urgency)
      )
      .sortBy(alert => escalationSortKey(alert))
      .take(10)

  private def buildAgentPerformance(
    issues: List[AgentIssue],
    histories: Map[shared.ids.Ids.IssueId, List[IssueEvent]],
    workReports: Map[shared.ids.Ids.IssueId, IssueWorkReport],
  ): List[AgentPerformance] =
    val rows = issues.flatMap { issue =>
      agentSummary(issue, histories.getOrElse(issue.id, Nil), workReports.get(issue.id))
    }
    rows
      .groupBy(_.agentName)
      .view
      .map {
        case (agentName, values) =>
          val successEvents   = values.count(_.successful)
          val failedEvents    = values.count(value => value.attempted && !value.successful && value.cycleHours.nonEmpty)
          val attemptedEvents = successEvents + failedEvents
          val avgCycleHours   = if values.flatMap(_.cycleHours).isEmpty then 0.0
          else values.flatMap(_.cycleHours).sum / values.flatMap(_.cycleHours).size
          agentName -> AgentPerformance(
            agentName = agentName,
            throughput = successEvents,
            successRate = if attemptedEvents == 0 then 0.0 else successEvents.toDouble / attemptedEvents.toDouble,
            averageCycleHours = avgCycleHours,
            activeIssues = values.count(_.active),
            costUsd = values.map(_.costUsd).sum,
          )
      }
      .toList
      .map(_._2)
      .sortBy(metric => (-metric.throughput, -metric.successRate, metric.agentName))
      .take(10)

  private def summarizeIssueHistory(issue: AgentIssue, events: List[IssueEvent]): IssueHistorySummary =
    val ordered         = events.sortBy(_.occurredAt)
    val transitionCount = ordered.count(isTransitionEvent)
    val bounceCount     = ordered.count(isBounceEvent)
    val lastChangedAt   = ordered.lastOption.map(_.occurredAt).getOrElse(timestampFromState(issue.state))
    IssueHistorySummary(transitionCount, bounceCount, lastChangedAt)

  private def agentSummary(
    issue: AgentIssue,
    events: List[IssueEvent],
    workReport: Option[IssueWorkReport],
  ): Option[AgentIssueSummary] =
    val ordered     = events.sortBy(_.occurredAt)
    val latestAgent = ordered.foldLeft(Option.empty[String]) {
      case (_, event: IssueEvent.Assigned)  => Some(event.agent.value)
      case (_, event: IssueEvent.Started)   => Some(event.agent.value)
      case (_, event: IssueEvent.Completed) => Some(event.agent.value)
      case (_, event: IssueEvent.Failed)    => Some(event.agent.value)
      case (current, _)                     => current
    }
    val cycleStart  = ordered.collectFirst {
      case event: IssueEvent.Started  => event.startedAt
      case event: IssueEvent.Assigned => event.assignedAt
    }
    val cycleEnd    = ordered.reverse.collectFirst {
      case event: IssueEvent.Completed      => event.completedAt
      case event: IssueEvent.MarkedDone     => event.doneAt
      case event: IssueEvent.Failed         => event.failedAt
      case event: IssueEvent.MovedToRework  => event.movedAt
      case event: IssueEvent.MergeSucceeded => event.mergedAt
      case event: IssueEvent.MergeFailed    => event.failedAt
    }
    val successful  = ordered.lastOption.exists {
      case _: IssueEvent.Completed | _: IssueEvent.MarkedDone | _: IssueEvent.MergeSucceeded => true
      case _                                                                                 => false
    } || issue.state.isInstanceOf[IssueState.Done] || issue.state.isInstanceOf[IssueState.Completed]
    val attempted   = ordered.exists {
      case _: IssueEvent.Assigned | _: IssueEvent.Started | _: IssueEvent.Completed | _: IssueEvent.Failed => true
      case _                                                                                               => false
    }
    val costUsd     = workReport.flatMap(_.tokenUsage).map(_.totalTokens.toDouble * 0.000001d).getOrElse(0.0d)
    latestAgent.map { agentName =>
      AgentIssueSummary(
        agentName = agentName,
        successful = successful,
        attempted = attempted,
        cycleHours =
          for
            start <- cycleStart
            end   <- cycleEnd
          yield hoursBetween(start, end),
        active = issue.state.isInstanceOf[IssueState.Assigned] || issue.state.isInstanceOf[IssueState.InProgress],
        costUsd = costUsd,
      )
    }

  private def isTransitionEvent(event: IssueEvent): Boolean =
    event match
      case _: IssueEvent.Assigned | _: IssueEvent.Started | _: IssueEvent.MovedToBacklog | _: IssueEvent.MovedToTodo |
           _: IssueEvent.MovedToHumanReview | _: IssueEvent.MovedToRework | _: IssueEvent.MovedToMerging |
           _: IssueEvent.MarkedDone | _: IssueEvent.Completed | _: IssueEvent.Failed | _: IssueEvent.Canceled |
           _: IssueEvent.Duplicated | _: IssueEvent.Skipped | _: IssueEvent.Reopened => true
      case _ => false

  private def isBounceEvent(event: IssueEvent): Boolean =
    event match
      case _: IssueEvent.MovedToBacklog | _: IssueEvent.MovedToTodo | _: IssueEvent.MovedToRework | _: IssueEvent.Reopened =>
        true
      case _                                                                                                               => false

  private def isTerminal(state: IssueState): Boolean =
    state match
      case _: IssueState.Done | _: IssueState.Completed | _: IssueState.Canceled | _: IssueState.Duplicated |
           _: IssueState.Skipped | _: IssueState.Failed => true
      case _ => false

  private def formatState(state: IssueState): String =
    state match
      case _: IssueState.Backlog     => "Backlog"
      case _: IssueState.Todo        => "Todo"
      case _: IssueState.Open        => "Open"
      case _: IssueState.Assigned    => "Assigned"
      case _: IssueState.InProgress  => "In Progress"
      case _: IssueState.HumanReview => "Human Review"
      case _: IssueState.Rework      => "Rework"
      case _: IssueState.Merging     => "Merging"
      case _: IssueState.Done        => "Done"
      case _: IssueState.Canceled    => "Canceled"
      case _: IssueState.Duplicated  => "Duplicated"
      case _: IssueState.Completed   => "Completed"
      case _: IssueState.Failed      => "Failed"
      case _: IssueState.Skipped     => "Skipped"

  private def formatUrgency(urgency: DecisionUrgency): String =
    urgency match
      case DecisionUrgency.Low      => "Low"
      case DecisionUrgency.Medium   => "Medium"
      case DecisionUrgency.High     => "High"
      case DecisionUrgency.Critical => "Critical"

  private def isHighUrgency(value: String): Boolean =
    value.equalsIgnoreCase("high") || value.equalsIgnoreCase("critical")

  private def escalationSortKey(alert: EscalationIndicator): (Int, Long) =
    val urgencyRank = alert.urgency.toLowerCase match
      case "critical" => 0
      case "high"     => 1
      case "medium"   => 2
      case _          => 3
    (urgencyRank, -alert.ageHours)

  private def elapsedHours(from: Instant, to: Instant): Long =
    Duration.between(from, to).toHours.max(0L)

  private def hoursBetween(from: Instant, to: Instant): Double =
    Duration.between(from, to).toMillis.max(0L).toDouble / 3600000.0d

  private def timestampFromState(state: IssueState): Instant =
    state match
      case IssueState.Backlog(createdAt)           => createdAt
      case IssueState.Todo(readyAt)                => readyAt
      case IssueState.Open(createdAt)              => createdAt
      case IssueState.Assigned(_, assignedAt)      => assignedAt
      case IssueState.InProgress(_, startedAt)     => startedAt
      case IssueState.HumanReview(reviewAt)        => reviewAt
      case IssueState.Rework(reworkAt, _)          => reworkAt
      case IssueState.Merging(mergingAt)           => mergingAt
      case IssueState.Done(doneAt, _)              => doneAt
      case IssueState.Canceled(canceledAt, _)      => canceledAt
      case IssueState.Duplicated(duplicatedAt, _)  => duplicatedAt
      case IssueState.Completed(_, completedAt, _) => completedAt
      case IssueState.Failed(_, failedAt, _)       => failedAt
      case IssueState.Skipped(skippedAt, _)        => skippedAt

  private def mapActivityError(error: DbPersistenceError): PersistenceError =
    error match
      case DbPersistenceError.ConnectionFailed(cause) => PersistenceError.StoreUnavailable(cause)
      case DbPersistenceError.QueryFailed(op, cause)  => PersistenceError.QueryFailed(op, cause)
      case DbPersistenceError.NotFound(entity, id)    => PersistenceError.NotFound(entity, id.toString)
      case DbPersistenceError.SchemaInitFailed(cause) => PersistenceError.SerializationFailed("activity", cause)

  final private case class IssueHistorySummary(
    transitionCount: Int,
    bounceCount: Int,
    lastChangedAt: Instant,
  )

  final private case class AgentIssueSummary(
    agentName: String,
    successful: Boolean,
    attempted: Boolean,
    cycleHours: Option[Double],
    active: Boolean,
    costUsd: Double,
  )
