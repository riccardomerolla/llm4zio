package llm4zio.observability

import zio.*
import zio.test.*
import llm4zio.core.TokenUsage

object LlmMetricsSpec extends ZIOSpecDefault:
  def spec = suite("LlmMetrics")(
    test("record request increments count") {
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordRequest
        _        <- metrics.recordRequest
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.requests == 2)
    }.provide(LlmMetrics.layer),
    test("record tokens tracks total") {
      val usage = TokenUsage(prompt = 10, completion = 5, total = 15)
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordTokens(usage)
        _        <- metrics.recordTokens(usage)
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.totalTokens == 30)
    }.provide(LlmMetrics.layer),
    test("record latency tracks total") {
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordLatency(100)
        _        <- metrics.recordLatency(200)
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.totalLatencyMs == 300)
    }.provide(LlmMetrics.layer),
    test("calculate average latency") {
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordRequest
        _        <- metrics.recordLatency(100)
        _        <- metrics.recordRequest
        _        <- metrics.recordLatency(200)
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.avgLatencyMs == 150.0)
    }.provide(LlmMetrics.layer),
    test("record errors increments count") {
      for {
        metrics  <- ZIO.service[LlmMetrics]
        _        <- metrics.recordError
        _        <- metrics.recordError
        snapshot <- metrics.snapshot
      } yield assertTrue(snapshot.errors == 2)
    }.provide(LlmMetrics.layer)
  )
