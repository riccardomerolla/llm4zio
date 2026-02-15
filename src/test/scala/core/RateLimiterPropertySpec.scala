package core

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import models.{ AIProvider, AIProviderConfig, RateLimitError }

/** Property-based tests for RateLimiter and RateLimiterConfig. */
object RateLimiterPropertySpec extends ZIOSpecDefault:

  private def makeLimiter(config: RateLimiterConfig): ZIO[Scope, Nothing, RateLimiter] =
    RateLimiter.make(config) <* TestClock.adjust(1.nanos)

  def spec: Spec[Any, Any] = suite("RateLimiterPropertySpec")(
    suite("RateLimiterConfig")(
      test("fromAIProviderConfig maps all fields correctly") {
        check(Gen.int(1, 600), Gen.int(1, 100), Gen.long(1000L, 300000L)) { (rpm, burst, timeoutMs) =>
          val providerConfig    = AIProviderConfig(
            provider = AIProvider.OpenAi,
            model = "gpt-4.1",
            requestsPerMinute = rpm,
            burstSize = burst,
            acquireTimeout = Duration.fromMillis(timeoutMs),
          )
          val rateLimiterConfig = RateLimiterConfig.fromAIProviderConfig(providerConfig)
          assertTrue(
            rateLimiterConfig.requestsPerMinute == rpm,
            rateLimiterConfig.burstSize == burst,
            rateLimiterConfig.acquireTimeout == Duration.fromMillis(timeoutMs),
          )
        }
      },
      test("fromMigrationConfig maps all fields correctly") {
        check(Gen.int(1, 600), Gen.int(1, 100), Gen.long(1000L, 300000L)) { (rpm, burst, timeoutMs) =>
          val migrationConfig   = models.MigrationConfig(
            sourceDir = java.nio.file.Paths.get("/tmp/src"),
            outputDir = java.nio.file.Paths.get("/tmp/out"),
            geminiRequestsPerMinute = rpm,
            geminiBurstSize = burst,
            geminiAcquireTimeout = Duration.fromMillis(timeoutMs),
          )
          val rateLimiterConfig = RateLimiterConfig.fromMigrationConfig(migrationConfig)
          assertTrue(
            rateLimiterConfig.requestsPerMinute == rpm,
            rateLimiterConfig.burstSize == burst,
            rateLimiterConfig.acquireTimeout == Duration.fromMillis(timeoutMs),
          )
        }
      },
      test("fromMigrationConfig uses aiProvider when present") {
        val migrationConfig = models.MigrationConfig(
          sourceDir = java.nio.file.Paths.get("/tmp/src"),
          outputDir = java.nio.file.Paths.get("/tmp/out"),
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.Anthropic,
              model = "claude-3-5-sonnet",
              requestsPerMinute = 42,
              burstSize = 7,
              acquireTimeout = 12.seconds,
            )
          ),
          geminiRequestsPerMinute = 111,
          geminiBurstSize = 22,
          geminiAcquireTimeout = 25.seconds,
        )

        val rateLimiterConfig = RateLimiterConfig.fromMigrationConfig(migrationConfig)
        assertTrue(
          rateLimiterConfig.requestsPerMinute == 42,
          rateLimiterConfig.burstSize == 7,
          rateLimiterConfig.acquireTimeout == 12.seconds,
        )
      },
      test("default config has sensible values") {
        val config = RateLimiterConfig()
        assertTrue(
          config.requestsPerMinute == 60,
          config.burstSize == 10,
          config.acquireTimeout == 30.seconds,
        )
      },
    ),
    suite("RateLimiter.make")(
      test("acquire succeeds within burst size") {
        check(Gen.int(1, 20)) { burstSize =>
          ZIO.scoped {
            for
              limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 600, burstSize = burstSize))
              results <- ZIO.foreach(1 to burstSize)(_ => limiter.acquire.either)
            yield assertTrue(results.forall(_.isRight))
          }
        }
      },
      test("tryAcquire returns true within burst") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 600, burstSize = 5))
            results <- ZIO.foreach(1 to 5)(_ => limiter.tryAcquire)
          yield assertTrue(results.forall(_ == true))
        }
      },
      test("tryAcquire returns false when burst exhausted") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 600, burstSize = 2))
            _       <- limiter.acquire
            _       <- limiter.acquire
            result  <- limiter.tryAcquire
          yield assertTrue(!result)
        }
      },
      test("metrics track total requests") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 600, burstSize = 10))
            _       <- limiter.acquire
            _       <- limiter.acquire
            _       <- limiter.acquire
            m       <- limiter.metrics
          yield assertTrue(m.totalRequests == 3L)
        }
      },
      test("metrics track throttled requests via tryAcquire") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 600, burstSize = 1))
            _       <- limiter.tryAcquire // succeeds
            _       <- limiter.tryAcquire // throttled
            m       <- limiter.metrics
          yield assertTrue(m.throttledRequests == 1L)
        }
      },
    ),
    suite("RateLimiter invalid config")(
      test("acquire fails with InvalidConfig for zero requestsPerMinute") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 0, burstSize = 10))
            result  <- limiter.acquire.either
          yield assertTrue(result.left.exists {
            case RateLimitError.InvalidConfig(_) => true
            case _                               => false
          })
        }
      },
      test("acquire fails with InvalidConfig for zero burstSize") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 60, burstSize = 0))
            result  <- limiter.acquire.either
          yield assertTrue(result.left.exists {
            case RateLimitError.InvalidConfig(_) => true
            case _                               => false
          })
        }
      },
      test("tryAcquire returns false for invalid config") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 0, burstSize = 0))
            result  <- limiter.tryAcquire
          yield assertTrue(!result)
        }
      },
    ),
    suite("RateLimiter with TestClock")(
      test("tokens refill over time") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(RateLimiterConfig(requestsPerMinute = 60, burstSize = 2))
            // Exhaust burst
            _       <- limiter.acquire
            _       <- limiter.acquire
            // Advance time to allow refill (1 req/sec at 60 rpm)
            _       <- TestClock.adjust(2.seconds)
            // Should be able to acquire again
            result  <- limiter.tryAcquire
          yield assertTrue(result)
        }
      },
      test("acquire times out when no tokens available and timeout expires") {
        ZIO.scoped {
          for
            limiter <- makeLimiter(
                         RateLimiterConfig(
                           requestsPerMinute = 6, // 0.1 per second - very slow refill
                           burstSize = 1,
                           acquireTimeout = 100.millis,
                         )
                       )
            // Exhaust the burst
            _       <- limiter.acquire
            // Try to acquire - should start waiting
            fiber   <- limiter.acquire.either.fork
            // Advance past the timeout
            _       <- TestClock.adjust(200.millis)
            result  <- fiber.join
          yield assertTrue(result.left.exists {
            case RateLimitError.AcquireTimeout(_) => true
            case _                                => false
          })
        }
      },
    ),
    suite("RateLimiter.live layer")(
      test("provides limiter via ZLayer") {
        val config = RateLimiterConfig(requestsPerMinute = 600, burstSize = 5)
        ZIO.scoped {
          for
            limiter <- ZIO.service[RateLimiter].provide(ZLayer.succeed(config) >>> RateLimiter.live)
            _       <- limiter.acquire
            m       <- limiter.metrics
          yield assertTrue(m.totalRequests == 1L)
        }
      }
    ),
  )
