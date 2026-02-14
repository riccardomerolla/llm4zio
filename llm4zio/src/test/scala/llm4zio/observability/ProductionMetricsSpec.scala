package llm4zio.observability

import zio.*
import zio.test.*
import llm4zio.core.TokenUsage

object ProductionMetricsSpec extends ZIOSpecDefault:
  def spec = suite("ProductionMetrics")(
    test("collects counters, percentiles, and provider health") {
      for
        metrics <- MetricsCollector.inMemory()
        labels = RequestLabels(provider = "openai", model = "gpt-4o", agent = Some("cobolAnalyzer"))
        _ <- metrics.markRequestStarted(labels)
        _ <- metrics.recordCompleted(RequestMetrics(labels, Some(TokenUsage(100, 40, 140)), latencyMs = 100, success = true))
        _ <- metrics.markRequestStarted(labels)
        _ <- metrics.recordCompleted(RequestMetrics(labels, Some(TokenUsage(120, 60, 180)), latencyMs = 350, success = true))
        _ <- metrics.markRequestStarted(labels)
        _ <- metrics.recordCompleted(RequestMetrics(labels, None, latencyMs = 900, success = false, errorType = Some("Timeout")))
        snapshot <- metrics.snapshot
      yield assertTrue(
        snapshot.totalRequests == 3,
        snapshot.totalErrors == 1,
        snapshot.totalPromptTokens == 220,
        snapshot.totalCompletionTokens == 100,
        snapshot.totalTokens == 320,
        snapshot.p50LatencyMs > 0,
        snapshot.p95LatencyMs >= snapshot.p50LatencyMs,
        snapshot.byProvider.get("openai").exists(_.requestCount == 3),
        snapshot.byAgentRequests.get("cobolAnalyzer").contains(3L),
      )
    },
    test("estimates cost using pricing table") {
      for
        metrics <- MetricsCollector.inMemory()
        labels = RequestLabels(provider = "anthropic", model = "claude")
        _ <- metrics.markRequestStarted(labels)
        _ <- metrics.recordCompleted(
               RequestMetrics(
                 labels = labels,
                 tokenUsage = Some(TokenUsage(prompt = 1000, completion = 1000, total = 2000)),
                 latencyMs = 100,
                 success = true,
               )
             )
        snapshot <- metrics.snapshot
      yield assertTrue(
        snapshot.estimatedCostUsd > 0.0,
        snapshot.byProvider.get("anthropic").exists(_.estimatedCostUsd > 0.0),
      )
    },
    test("dashboard snapshot exposes aggregated fields") {
      for
        metrics <- MetricsCollector.inMemory()
        labels = RequestLabels(provider = "lmstudio", model = "qwen")
        _ <- metrics.markRequestStarted(labels)
        _ <- metrics.recordCompleted(RequestMetrics(labels, Some(TokenUsage(50, 25, 75)), latencyMs = 50, success = true))
        dashboard <- metrics.dashboardSnapshot
      yield assertTrue(
        dashboard.totalRequests == 1,
        dashboard.totalTokens == 75,
        dashboard.providerHealth.nonEmpty,
      )
    },
  )
