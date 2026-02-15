package core

import zio.*

import models.{ AIProviderConfig, MigrationConfig, RateLimitError }

/** Token bucket rate limiter for Gemini requests */
trait RateLimiter:
  def acquire: ZIO[Any, RateLimitError, Unit]
  def tryAcquire: ZIO[Any, Nothing, Boolean]
  def metrics: ZIO[Any, Nothing, RateLimiterMetrics]
  def acquireFor(runId: String): ZIO[Any, RateLimitError, Unit]
  def tryAcquireFor(runId: String): ZIO[Any, Nothing, Boolean]
  def metricsByRun: ZIO[Any, Nothing, Map[String, RateLimiterMetrics]]

final case class RateLimiterConfig(
  requestsPerMinute: Int = 60,
  burstSize: Int = 10,
  acquireTimeout: Duration = 30.seconds,
)

object RateLimiterConfig:
  def fromAIProviderConfig(config: AIProviderConfig): RateLimiterConfig =
    RateLimiterConfig(
      requestsPerMinute = config.requestsPerMinute,
      burstSize = config.burstSize,
      acquireTimeout = config.acquireTimeout,
    )

  def fromMigrationConfig(config: MigrationConfig): RateLimiterConfig =
    fromAIProviderConfig(config.resolvedProviderConfig)

final case class RateLimiterMetrics(
  totalRequests: Long,
  throttledRequests: Long,
  currentTokens: Int,
)

object RateLimiter:
  final private case class BucketState(tokens: Double, lastRefillNanos: Long)
  final private case class TakeResult(acquired: Boolean, tokensAfter: Double, deficit: Double)

  def make(config: RateLimiterConfig): ZIO[Scope, Nothing, RateLimiter] =
    for
      now     <- Clock.nanoTime
      state   <- Ref.make(BucketState(config.burstSize.toDouble, now))
      metrics <- Ref.make(RateLimiterMetrics(0, 0, config.burstSize))
      byRun   <- Ref.make(Map.empty[String, RateLimiterMetrics])
    yield new RateLimiterLive(state, metrics, byRun, config)

  val live: ZLayer[RateLimiterConfig, Nothing, RateLimiter] =
    ZLayer.scoped {
      ZIO.serviceWithZIO[RateLimiterConfig](make)
    }

  final private class RateLimiterLive(
    state: Ref[BucketState],
    metricsRef: Ref[RateLimiterMetrics],
    runMetricsRef: Ref[Map[String, RateLimiterMetrics]],
    config: RateLimiterConfig,
  ) extends RateLimiter {
    private val nanosPerSecond = 1000000000.0

    override def acquire: ZIO[Any, RateLimitError, Unit] =
      acquireFor("global")

    override def acquireFor(runId: String): ZIO[Any, RateLimitError, Unit] =
      validateConfig *>
        metricsRef.update(m => m.copy(totalRequests = m.totalRequests + 1)) *>
        updateRunMetric(runId, totalDelta = 1, throttledDelta = 0, currentTokens = None) *>
        loop(runId = runId, throttled = false).timeoutFail(RateLimitError.AcquireTimeout(config.acquireTimeout))(
          config.acquireTimeout
        )

    override def tryAcquire: ZIO[Any, Nothing, Boolean] =
      tryAcquireFor("global")

    override def tryAcquireFor(runId: String): ZIO[Any, Nothing, Boolean] =
      if isConfigValid then
        for
          result <- takeToken
          _      <- updateCurrentTokens(result.tokensAfter)
          _      <- metricsRef.update { m =>
                      val throttled = if result.acquired then m.throttledRequests else m.throttledRequests + 1
                      m.copy(
                        totalRequests = m.totalRequests + 1,
                        throttledRequests = throttled,
                      )
                    }
          _      <- updateRunMetric(
                      runId,
                      totalDelta = 1,
                      throttledDelta = if result.acquired then 0 else 1,
                      currentTokens = Some(result.tokensAfter.toInt),
                    )
        yield result.acquired
      else
        ZIO.logWarning(s"Rate limiter config invalid: ${configErrorDetails}").as(false)

    override def metrics: ZIO[Any, Nothing, RateLimiterMetrics] =
      metricsRef.get

    override def metricsByRun: ZIO[Any, Nothing, Map[String, RateLimiterMetrics]] =
      runMetricsRef.get

    private def loop(runId: String, throttled: Boolean): ZIO[Any, RateLimitError, Unit] =
      for
        result <- takeToken
        _      <- updateCurrentTokens(result.tokensAfter)
        _      <- updateRunMetric(
                    runId,
                    totalDelta = 0,
                    throttledDelta = if result.acquired || throttled then 0 else 1,
                    currentTokens = Some(result.tokensAfter.toInt),
                  )
        _      <-
          if result.acquired then ZIO.unit
          else
            val markThrottled =
              if throttled then ZIO.unit
              else metricsRef.update(m => m.copy(throttledRequests = m.throttledRequests + 1))
            markThrottled *> waitForToken(result.deficit) *> loop(runId = runId, throttled = true)
      yield ()

    private def takeToken: UIO[TakeResult] =
      Clock.nanoTime.flatMap { now =>
        state.modify { current =>
          val (refilled, lastRefill) = refill(current, now)
          if refilled >= 1.0 then
            val remaining = refilled - 1.0
            val next      = BucketState(remaining, lastRefill)
            (TakeResult(acquired = true, tokensAfter = remaining, deficit = 0.0), next)
          else
            val next = BucketState(refilled, lastRefill)
            (TakeResult(acquired = false, tokensAfter = refilled, deficit = 1.0 - refilled), next)
        }
      }

    private def refill(state: BucketState, now: Long): (Double, Long) =
      val elapsedNanos = now - state.lastRefillNanos
      if elapsedNanos <= 0 then (state.tokens, state.lastRefillNanos)
      else
        val tokensToAdd = (elapsedNanos.toDouble / nanosPerSecond) * ratePerSecond
        if tokensToAdd <= 0 then (state.tokens, state.lastRefillNanos)
        else
          val updated = math.min(capacity, state.tokens + tokensToAdd)
          (updated, now)

    private def waitForToken(deficit: Double): ZIO[Any, RateLimitError, Unit] =
      if ratePerSecond <= 0 then ZIO.fail(RateLimitError.InvalidConfig(configErrorDetails))
      else
        val seconds  = deficit / ratePerSecond
        val nanos    = math.ceil(seconds * nanosPerSecond).toLong.max(0L)
        val duration = Duration.fromNanos(nanos)
        ZIO.sleep(duration)

    private def updateCurrentTokens(tokens: Double): UIO[Unit] =
      metricsRef.update(m => m.copy(currentTokens = tokens.toInt))

    private def updateRunMetric(
      runId: String,
      totalDelta: Long,
      throttledDelta: Long,
      currentTokens: Option[Int],
    ): UIO[Unit] =
      runMetricsRef.update { current =>
        val existing = current.getOrElse(
          runId,
          RateLimiterMetrics(totalRequests = 0, throttledRequests = 0, currentTokens = config.burstSize),
        )
        current.updated(
          runId,
          existing.copy(
            totalRequests = existing.totalRequests + totalDelta,
            throttledRequests = existing.throttledRequests + throttledDelta,
            currentTokens = currentTokens.getOrElse(existing.currentTokens),
          ),
        )
      }

    private def validateConfig: ZIO[Any, RateLimitError, Unit] =
      ZIO.fail(RateLimitError.InvalidConfig(configErrorDetails)).unless(isConfigValid).unit

    private def isConfigValid: Boolean =
      config.requestsPerMinute > 0 && config.burstSize > 0 && config.acquireTimeout.toMillis > 0

    private def configErrorDetails: String =
      s"requestsPerMinute=${config.requestsPerMinute}, burstSize=${config.burstSize}, acquireTimeout=${config.acquireTimeout}"

    private def capacity: Double =
      config.burstSize.toDouble

    private def ratePerSecond: Double =
      config.requestsPerMinute.toDouble / 60.0
  }
