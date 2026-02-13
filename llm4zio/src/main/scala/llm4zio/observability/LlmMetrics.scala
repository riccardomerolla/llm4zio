package llm4zio.observability

import zio.*
import llm4zio.core.TokenUsage

case class LlmMetrics(
  requestCount: Ref[Long],
  totalTokens: Ref[Long],
  totalLatencyMs: Ref[Long],
  errorCount: Ref[Long],
):
  def recordRequest: UIO[Unit] = requestCount.update(_ + 1)

  def recordTokens(usage: TokenUsage): UIO[Unit] =
    totalTokens.update(_ + usage.total)

  def recordLatency(ms: Long): UIO[Unit] =
    totalLatencyMs.update(_ + ms)

  def recordError: UIO[Unit] = errorCount.update(_ + 1)

  def snapshot: UIO[MetricsSnapshot] =
    for {
      requests <- requestCount.get
      tokens   <- totalTokens.get
      latency  <- totalLatencyMs.get
      errors   <- errorCount.get
    } yield MetricsSnapshot(requests, tokens, latency, errors)

case class MetricsSnapshot(
  requests: Long,
  totalTokens: Long,
  totalLatencyMs: Long,
  errors: Long,
):
  def avgLatencyMs: Double =
    if (requests == 0) 0.0 else totalLatencyMs.toDouble / requests

object LlmMetrics:
  val layer: ULayer[LlmMetrics] = ZLayer.fromZIO {
    for {
      requestCount   <- Ref.make(0L)
      totalTokens    <- Ref.make(0L)
      totalLatencyMs <- Ref.make(0L)
      errorCount     <- Ref.make(0L)
    } yield LlmMetrics(requestCount, totalTokens, totalLatencyMs, errorCount)
  }
