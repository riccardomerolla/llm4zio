package core

import zio.*
import zio.test.*

import models.GeminiError

/** Property-based tests for RetryPolicy.
  *
  * Tests retry behavior, isRetryable classification, and policy configuration properties.
  */
object RetryPolicyPropertySpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("RetryPolicyPropertySpec")(
    suite("isRetryable classification")(
      test("Timeout is always retryable") {
        check(Gen.long(1L, 600000L)) { millis =>
          val error = GeminiError.Timeout(Duration.fromMillis(millis))
          assertTrue(RetryPolicy.isRetryable(error))
        }
      },
      test("exit code 429 (rate limited) is retryable") {
        check(Gen.alphaNumericStringBounded(0, 20)) { output =>
          val error = GeminiError.NonZeroExit(429, output)
          assertTrue(RetryPolicy.isRetryable(error))
        }
      },
      test("exit codes >= 500 (server errors) are retryable") {
        check(Gen.int(500, 599)) { code =>
          val error = GeminiError.NonZeroExit(code, "server error")
          assertTrue(RetryPolicy.isRetryable(error))
        }
      },
      test("ProcessFailed is retryable") {
        check(Gen.alphaNumericStringBounded(1, 30)) { cause =>
          val error = GeminiError.ProcessFailed(cause)
          assertTrue(RetryPolicy.isRetryable(error))
        }
      },
      test("NotInstalled is not retryable") {
        assertTrue(!RetryPolicy.isRetryable(GeminiError.NotInstalled))
      },
      test("ProcessStartFailed is not retryable") {
        check(Gen.alphaNumericStringBounded(1, 30)) { cause =>
          val error = GeminiError.ProcessStartFailed(cause)
          assertTrue(!RetryPolicy.isRetryable(error))
        }
      },
      test("OutputReadFailed is not retryable") {
        check(Gen.alphaNumericStringBounded(1, 30)) { cause =>
          val error = GeminiError.OutputReadFailed(cause)
          assertTrue(!RetryPolicy.isRetryable(error))
        }
      },
      test("InvalidResponse is not retryable") {
        check(Gen.alphaNumericStringBounded(1, 30)) { output =>
          val error = GeminiError.InvalidResponse(output)
          assertTrue(!RetryPolicy.isRetryable(error))
        }
      },
      test("client error codes (4xx except 429) are not retryable") {
        check(Gen.int(400, 499).filter(_ != 429)) { code =>
          val error = GeminiError.NonZeroExit(code, "client error")
          assertTrue(!RetryPolicy.isRetryable(error))
        }
      },
      test("low exit codes (1-127) are retryable (transient CLI crashes)") {
        check(Gen.int(1, 127)) { code =>
          val error = GeminiError.NonZeroExit(code, "CLI crash")
          assertTrue(RetryPolicy.isRetryable(error))
        }
      },
      test("exit codes 128-399 (signal kills and misc) are not retryable") {
        check(Gen.int(128, 399)) { code =>
          val error = GeminiError.NonZeroExit(code, "other error")
          assertTrue(!RetryPolicy.isRetryable(error))
        }
      },
    ),
    suite("RetryPolicy defaults")(
      test("default policy has sensible values") {
        val default = RetryPolicy.default
        assertTrue(
          default.maxRetries == 3,
          default.baseDelay == Duration.fromSeconds(1),
          default.maxDelay == Duration.fromSeconds(30),
          default.jitterFactor == 0.1,
        )
      },
      test("custom policy preserves all values") {
        check(Gen.int(0, 10), Gen.long(100L, 5000L), Gen.long(5000L, 60000L)) {
          (retries, baseMs, maxMs) =>
            val policy = RetryPolicy(
              maxRetries = retries,
              baseDelay = Duration.fromMillis(baseMs),
              maxDelay = Duration.fromMillis(maxMs),
              jitterFactor = 0.2,
            )
            assertTrue(
              policy.maxRetries == retries,
              policy.baseDelay == Duration.fromMillis(baseMs),
              policy.maxDelay == Duration.fromMillis(maxMs),
              policy.jitterFactor == 0.2,
            )
        }
      },
    ),
    suite("withRetry behavior")(
      test("succeeds immediately if effect succeeds") {
        val policy = RetryPolicy(maxRetries = 3, baseDelay = 1.second, maxDelay = 5.seconds)
        for
          ref   <- Ref.make(0)
          fiber <- RetryPolicy
                     .withRetry(
                       ref.update(_ + 1),
                       policy,
                       (_: Nothing) => true,
                     )
                     .fork
          // Advance clock in case Schedule infrastructure touches time
          _     <- TestClock.adjust(1.second)
          _     <- fiber.join
          count <- ref.get
        yield assertTrue(count == 1)
      },
      test("retries on retryable errors up to maxRetries") {
        val policy = RetryPolicy(maxRetries = 2, baseDelay = 10.millis, maxDelay = 50.millis, jitterFactor = 0.0)
        for
          ref    <- Ref.make(0)
          fiber  <- RetryPolicy
                      .withRetry(
                        ref.updateAndGet(_ + 1).flatMap { count =>
                          if count <= 3 then ZIO.fail("transient")
                          else ZIO.succeed(count)
                        },
                        policy,
                        (_: String) => true,
                      )
                      .either
                      .fork
          // Advance clock enough for exponential backoff sleeps: 10ms, 20ms, 40ms
          _      <- TestClock.adjust(100.millis)
          _      <- TestClock.adjust(100.millis)
          _      <- TestClock.adjust(100.millis)
          result <- fiber.join
          count  <- ref.get
        yield assertTrue(
          result.isLeft,
          count == 3, // initial + 2 retries
        )
      },
      test("does not retry non-retryable errors") {
        val policy = RetryPolicy(maxRetries = 5, baseDelay = 10.millis, maxDelay = 50.millis, jitterFactor = 0.0)
        for
          ref    <- Ref.make(0)
          fiber  <- RetryPolicy
                      .withRetry(
                        ref.update(_ + 1) *> ZIO.fail("permanent"),
                        policy,
                        (_: String) => false,
                      )
                      .either
                      .fork
          // Advance clock so it doesn't hang
          _      <- TestClock.adjust(1.second)
          result <- fiber.join
          count  <- ref.get
        yield assertTrue(
          result.isLeft,
          count == 1, // no retries
        )
      },
    ),
  )
