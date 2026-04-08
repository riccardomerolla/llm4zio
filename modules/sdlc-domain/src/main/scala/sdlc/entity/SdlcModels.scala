package sdlc.entity

import java.time.Instant

import activity.entity.ActivityEvent

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

enum TrendDirection:
  case Up
  case Down
  case Flat

final case class TrendIndicator(
  direction: TrendDirection,
  currentPeriodCount: Int,
  previousPeriodCount: Int,
  periodLabel: String,
)

final case class GovernanceOverview(
  passCount: Int,
  failCount: Int,
  passRate: Double,
  activePolicyCount: Int,
)

final case class DaemonHealthOverview(
  runningCount: Int,
  stoppedCount: Int,
  erroredCount: Int,
)

final case class RecentEvolution(
  proposalId: String,
  title: String,
  status: String,
  appliedAt: Instant,
)

final case class EvolutionOverview(
  pendingProposalCount: Int,
  recentlyApplied: List[RecentEvolution],
)

final case class SdlcSnapshot(
  generatedAt: Instant,
  thresholds: Thresholds,
  lifecycle: List[LifecycleStage],
  churnAlerts: List[ChurnAlert],
  stoppages: List[StoppageAlert],
  escalations: List[EscalationIndicator],
  agentPerformance: List[AgentPerformance],
  governance: GovernanceOverview,
  daemonHealth: DaemonHealthOverview,
  evolution: EvolutionOverview,
  recentActivity: List[ActivityEvent],
  specificationCount: Int,
  planCount: Int,
  issueCount: Int,
  pendingDecisionCount: Int,
  specificationTrend: TrendIndicator,
  planTrend: TrendIndicator,
  issueTrend: TrendIndicator,
  pendingDecisionTrend: TrendIndicator,
)
