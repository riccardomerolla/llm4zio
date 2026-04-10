package agent.entity.api

import java.time.Instant

import zio.json.JsonCodec

import agent.entity.{ Agent, TrustLevel }

final case class AgentUpsertRequest(
  name: String,
  description: String,
  cliTool: String,
  capabilities: List[String] = Nil,
  defaultModel: Option[String] = None,
  systemPrompt: Option[String] = None,
  maxConcurrentRuns: Int = 1,
  envVars: Map[String, String] = Map.empty,
  dockerMemoryLimit: Option[String] = None,
  dockerCpuLimit: Option[String] = None,
  trustLevel: TrustLevel = TrustLevel.Standard,
  maxEstimatedTokens: Option[Long] = None,
  timeout: String = "PT30M",
  enabled: Boolean = true,
) derives JsonCodec

final case class AgentRunHistoryItem(
  runId: String,
  workspaceId: String,
  issueRef: String,
  status: String,
  updatedAt: Instant,
) derives JsonCodec

final case class AgentMetricsSummary(
  totalRuns: Int,
  completedRuns: Int,
  failedRuns: Int,
  activeRuns: Int,
  successRate: Double,
  totalRuns7d: Int = 0,
  totalRuns30d: Int = 0,
  averageDurationSeconds: Long = 0L,
  issuesResolvedCount: Int = 0,
) derives JsonCodec

final case class AgentActiveRun(
  runId: String,
  workspaceId: String,
  issueRef: String,
  conversationId: String,
  status: String,
  startedAt: Instant,
) derives JsonCodec

final case class AgentMetricsHistoryPoint(
  date: String,
  totalRuns: Int,
  completedRuns: Int,
  failedRuns: Int,
  successRate: Double,
  averageDurationSeconds: Long,
  issuesResolvedCount: Int,
) derives JsonCodec

final case class AgentMetricsView(
  summary: AgentMetricsSummary,
  activeRuns: List[AgentActiveRun] = Nil,
) derives JsonCodec

final case class AgentMetricsOverviewItem(
  agentId: String,
  agentName: String,
  metrics: AgentMetricsSummary,
) derives JsonCodec

final case class AgentDetailResponse(
  agent: Agent,
  metrics: AgentMetricsSummary,
  history: List[AgentMetricsHistoryPoint] = Nil,
  activeRuns: List[AgentActiveRun] = Nil,
) derives JsonCodec

final case class AgentMatchSuggestion(
  agentId: String,
  agentName: String,
  capabilities: List[String],
  score: Double,
  overlapCount: Int,
  requiredCount: Int,
  activeRuns: Int,
) derives JsonCodec
