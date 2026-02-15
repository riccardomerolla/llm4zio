package core

import zio.*
import zio.test.*

import models.RateLimitError

object RateLimiterSpec extends ZIOSpecDefault:

  private def withLimiter[A](config: RateLimiterConfig)(effect: RateLimiter => ZIO[Any, Any, A]): ZIO[Any, Any, A] =
    ZIO.scoped {
      RateLimiter.make(config).flatMap { limiter =>
        TestClock.adjust(1.nanos) *> effect(limiter)
      }
    }

  def spec: Spec[Any, Any] = suite("RateLimiterSpec")(
    test("tryAcquire respects burst size") {
      val config = RateLimiterConfig(requestsPerMinute = 60, burstSize = 2, acquireTimeout = 2.seconds)
      withLimiter(config) { limiter =>
        for
          first  <- limiter.tryAcquire
          second <- limiter.tryAcquire
          third  <- limiter.tryAcquire
          stats  <- limiter.metrics
        yield assertTrue(
          first,
          second,
          !third,
          stats.totalRequests == 3,
          stats.throttledRequests == 1,
        )
      }
    },
    test("acquire blocks until token is refilled") {
      val config = RateLimiterConfig(requestsPerMinute = 60, burstSize = 1, acquireTimeout = 5.seconds)
      withLimiter(config) { limiter =>
        for
          _     <- limiter.acquire
          fiber <- limiter.acquire.fork
          _     <- TestClock.adjust(900.millis)
          poll1 <- fiber.poll
          _     <- TestClock.adjust(200.millis)
          _     <- fiber.join
        yield assertTrue(poll1.isEmpty)
      }
    },
    test("acquire times out when no token becomes available") {
      val config = RateLimiterConfig(requestsPerMinute = 60, burstSize = 1, acquireTimeout = 500.millis)
      withLimiter(config) { limiter =>
        for
          _      <- limiter.acquire
          fiber  <- limiter.acquire.either.fork
          _      <- TestClock.adjust(600.millis)
          result <- fiber.join
        yield assertTrue(
          result.left.exists {
            case RateLimitError.AcquireTimeout(_) => true
            case _                                => false
          }
        )
      }
    },
    test("metrics track throttled requests") {
      val config = RateLimiterConfig(requestsPerMinute = 60, burstSize = 1, acquireTimeout = 5.seconds)
      withLimiter(config) { limiter =>
        for
          _     <- limiter.acquire
          fiber <- limiter.acquire.fork
          _     <- TestClock.adjust(1.millis)
          stats <- limiter.metrics
          _     <- TestClock.adjust(1.second)
          _     <- fiber.join
        yield assertTrue(
          stats.totalRequests == 2,
          stats.throttledRequests == 1,
        )
      }
    },
    test("global rate limiter pool tracks per-run metrics") {
      val config = RateLimiterConfig(requestsPerMinute = 120, burstSize = 2, acquireTimeout = 2.seconds)
      withLimiter(config) { limiter =>
        for
          _     <- limiter.tryAcquireFor("run-a")
          _     <- limiter.tryAcquireFor("run-a")
          third <- limiter.tryAcquireFor("run-b")
          byRun <- limiter.metricsByRun
          runA   = byRun.getOrElse("run-a", RateLimiterMetrics(0, 0, 0))
          runB   = byRun.getOrElse("run-b", RateLimiterMetrics(0, 0, 0))
        yield assertTrue(
          !third,
          runA.totalRequests == 2,
          runA.throttledRequests == 0,
          runB.totalRequests == 1,
          runB.throttledRequests == 1,
        )
      }
    },
  )
