package llm4zio.observability

import zio.*

import llm4zio.core.TokenUsage

case class LlmMetrics(
  requestCount: Ref[Long],
  promptTokens: Ref[Long],
  completionTokens: Ref[Long],
  totalLatencyMs: Ref[Long],
  errorCount: Ref[Long],
):
  def recordRequest: UIO[Unit] = requestCount.update(_ + 1)

  def recordTokens(usage: TokenUsage): UIO[Unit] =
    promptTokens.update(_ + usage.prompt) *>
      completionTokens.update(_ + usage.completion)

  def recordLatency(ms: Long): UIO[Unit] =
    totalLatencyMs.update(_ + ms)

  def recordError: UIO[Unit] = errorCount.update(_ + 1)

  def snapshot: UIO[MetricsSnapshot] =
    for {
      requests   <- requestCount.get
      prompt     <- promptTokens.get
      completion <- completionTokens.get
      latency    <- totalLatencyMs.get
      errors     <- errorCount.get
    } yield MetricsSnapshot(requests, prompt, completion, latency, errors)

case class MetricsSnapshot(
  requests: Long,
  promptTokens: Long,
  completionTokens: Long,
  totalLatencyMs: Long,
  errors: Long,
):
  def totalTokens: Long    = promptTokens + completionTokens
  def avgLatencyMs: Double =
    if requests == 0 then 0.0 else totalLatencyMs.toDouble / requests

object LlmMetrics:
  val layer: ULayer[LlmMetrics] = ZLayer.fromZIO {
    for {
      requestCount     <- Ref.make(0L)
      promptTokens     <- Ref.make(0L)
      completionTokens <- Ref.make(0L)
      totalLatencyMs   <- Ref.make(0L)
      errorCount       <- Ref.make(0L)
    } yield LlmMetrics(requestCount, promptTokens, completionTokens, totalLatencyMs, errorCount)
  }
