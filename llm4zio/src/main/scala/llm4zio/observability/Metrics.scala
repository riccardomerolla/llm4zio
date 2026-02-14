package llm4zio.observability

import zio.*
import zio.json.*
import llm4zio.core.TokenUsage

case class LlmPricing(
  inputUsdPer1k: Double,
  outputUsdPer1k: Double,
) derives JsonCodec

case class RequestLabels(
  provider: String,
  model: String,
  agent: Option[String] = None,
  runId: Option[String] = None,
  workflowStep: Option[String] = None,
) derives JsonCodec

case class RequestMetrics(
  labels: RequestLabels,
  tokenUsage: Option[TokenUsage],
  latencyMs: Long,
  success: Boolean,
  errorType: Option[String] = None,
  estimatedCostUsd: Double = 0.0,
) derives JsonCodec

case class ProviderHealth(
  provider: String,
  requestCount: Long,
  successRate: Double,
  avgLatencyMs: Double,
  p95LatencyMs: Double,
  estimatedCostUsd: Double,
) derives JsonCodec

case class ObservabilitySnapshot(
  totalRequests: Long,
  totalErrors: Long,
  activeRequests: Long,
  totalPromptTokens: Long,
  totalCompletionTokens: Long,
  totalTokens: Long,
  estimatedCostUsd: Double,
  p50LatencyMs: Double,
  p95LatencyMs: Double,
  p99LatencyMs: Double,
  byProvider: Map[String, ProviderHealth],
  byAgentRequests: Map[String, Long],
) derives JsonCodec

case class DashboardMetricsSnapshot(
  providerHealth: List[ProviderHealth],
  totalTokens: Long,
  totalCostUsd: Double,
  totalRequests: Long,
  errorRate: Double,
) derives JsonCodec

trait MetricsCollector:
  def markRequestStarted(labels: RequestLabels): UIO[Unit]
  def recordCompleted(metrics: RequestMetrics): UIO[Unit]
  def snapshot: UIO[ObservabilitySnapshot]
  def dashboardSnapshot: UIO[DashboardMetricsSnapshot]

object MetricsCollector:
  val defaultPricing: Map[String, LlmPricing] = Map(
    "openai" -> LlmPricing(inputUsdPer1k = 0.005, outputUsdPer1k = 0.015),
    "anthropic" -> LlmPricing(inputUsdPer1k = 0.003, outputUsdPer1k = 0.015),
    "gemini-api" -> LlmPricing(inputUsdPer1k = 0.00075, outputUsdPer1k = 0.003),
    "gemini-cli" -> LlmPricing(inputUsdPer1k = 0.0, outputUsdPer1k = 0.0),
    "lmstudio" -> LlmPricing(inputUsdPer1k = 0.0, outputUsdPer1k = 0.0),
    "ollama" -> LlmPricing(inputUsdPer1k = 0.0, outputUsdPer1k = 0.0),
    "unknown" -> LlmPricing(inputUsdPer1k = 0.0, outputUsdPer1k = 0.0),
  )

  def inMemory(pricing: Map[String, LlmPricing] = defaultPricing): UIO[MetricsCollector] =
    for
      activeRequests <- Ref.make(0L)
      requests <- Ref.make(0L)
      errors <- Ref.make(0L)
      promptTokens <- Ref.make(0L)
      completionTokens <- Ref.make(0L)
      latencies <- Ref.make(List.empty[Long])
      costs <- Ref.make(0.0)
      providerRows <- Ref.make(List.empty[RequestMetrics])
      byAgent <- Ref.make(Map.empty[String, Long])
    yield InMemoryMetricsCollector(
      activeRequests,
      requests,
      errors,
      promptTokens,
      completionTokens,
      latencies,
      costs,
      providerRows,
      byAgent,
      pricing,
    )

  val inMemoryLayer: ULayer[MetricsCollector] = ZLayer.fromZIO(inMemory())

  def estimateCost(usage: TokenUsage, pricing: LlmPricing): Double =
    (usage.prompt.toDouble / 1000.0 * pricing.inputUsdPer1k) +
      (usage.completion.toDouble / 1000.0 * pricing.outputUsdPer1k)

private final case class InMemoryMetricsCollector(
  activeRequests: Ref[Long],
  requests: Ref[Long],
  errors: Ref[Long],
  promptTokens: Ref[Long],
  completionTokens: Ref[Long],
  latencies: Ref[List[Long]],
  costs: Ref[Double],
  providerRows: Ref[List[RequestMetrics]],
  byAgent: Ref[Map[String, Long]],
  pricing: Map[String, LlmPricing],
) extends MetricsCollector:

  override def markRequestStarted(labels: RequestLabels): UIO[Unit] =
    activeRequests.update(_ + 1) *>
      byAgent.update { current =>
        labels.agent match
          case Some(agent) => current.updated(agent, current.getOrElse(agent, 0L) + 1L)
          case None        => current
      }

  override def recordCompleted(metrics: RequestMetrics): UIO[Unit] =
    val providerPricing = pricing.getOrElse(metrics.labels.provider, pricing.getOrElse("unknown", LlmPricing(0.0, 0.0)))
    val resolvedCost = metrics.tokenUsage match
      case Some(usage) if metrics.estimatedCostUsd <= 0.0 => MetricsCollector.estimateCost(usage, providerPricing)
      case _                                               => metrics.estimatedCostUsd

    val normalized = metrics.copy(estimatedCostUsd = resolvedCost)

    activeRequests.update(current => (current - 1L).max(0L)) *>
      requests.update(_ + 1L) *>
      errors.update(current => if normalized.success then current else current + 1L) *>
      promptTokens.update(current => current + normalized.tokenUsage.map(_.prompt.toLong).getOrElse(0L)) *>
      completionTokens.update(current => current + normalized.tokenUsage.map(_.completion.toLong).getOrElse(0L)) *>
      latencies.update(current => (normalized.latencyMs :: current).take(20000)) *>
      costs.update(_ + resolvedCost) *>
      providerRows.update(current => (normalized :: current).take(20000))

  override def snapshot: UIO[ObservabilitySnapshot] =
    for
      active <- activeRequests.get
      reqs <- requests.get
      errs <- errors.get
      prompt <- promptTokens.get
      completion <- completionTokens.get
      latencyValues <- latencies.get
      totalCost <- costs.get
      rows <- providerRows.get
      byAgentCount <- byAgent.get
      p50 = percentile(latencyValues, 50)
      p95 = percentile(latencyValues, 95)
      p99 = percentile(latencyValues, 99)
      providerHealth = summarizeProviders(rows)
    yield ObservabilitySnapshot(
      totalRequests = reqs,
      totalErrors = errs,
      activeRequests = active,
      totalPromptTokens = prompt,
      totalCompletionTokens = completion,
      totalTokens = prompt + completion,
      estimatedCostUsd = totalCost,
      p50LatencyMs = p50,
      p95LatencyMs = p95,
      p99LatencyMs = p99,
      byProvider = providerHealth,
      byAgentRequests = byAgentCount,
    )

  override def dashboardSnapshot: UIO[DashboardMetricsSnapshot] =
    snapshot.map { snap =>
      DashboardMetricsSnapshot(
        providerHealth = snap.byProvider.values.toList.sortBy(_.provider),
        totalTokens = snap.totalTokens,
        totalCostUsd = snap.estimatedCostUsd,
        totalRequests = snap.totalRequests,
        errorRate = if snap.totalRequests == 0 then 0.0 else snap.totalErrors.toDouble / snap.totalRequests.toDouble,
      )
    }

  private def summarizeProviders(rows: List[RequestMetrics]): Map[String, ProviderHealth] =
    rows.groupBy(_.labels.provider).map { case (provider, grouped) =>
      val reqCount = grouped.length.toLong
      val successCount = grouped.count(_.success).toDouble
      val successRate = if reqCount == 0 then 0.0 else successCount / reqCount.toDouble
      val latency = grouped.map(_.latencyMs)
      val avgLatency = if latency.isEmpty then 0.0 else latency.sum.toDouble / latency.length.toDouble
      val p95 = percentile(latency, 95)
      val totalCost = grouped.map(_.estimatedCostUsd).sum

      provider -> ProviderHealth(
        provider = provider,
        requestCount = reqCount,
        successRate = successRate,
        avgLatencyMs = avgLatency,
        p95LatencyMs = p95,
        estimatedCostUsd = totalCost,
      )
    }

  private def percentile(values: List[Long], p: Int): Double =
    if values.isEmpty then 0.0
    else
      val sorted = values.sorted
      val rank = (((p.toDouble / 100.0) * sorted.length.toDouble).ceil.toInt - 1).max(0).min(sorted.length - 1)
      sorted(rank).toDouble
