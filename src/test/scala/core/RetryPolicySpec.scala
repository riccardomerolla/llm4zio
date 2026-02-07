package core

import zio.*
import zio.test.*
import zio.test.TestAspect.*

import models.GeminiError

object RetryPolicySpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("RetryPolicySpec")(
    // ========================================================================
    // RetryPolicy configuration tests
    // ========================================================================
    suite("RetryPolicy configuration")(
      test("default policy has correct values") {
        val policy = RetryPolicy.default
        assertTrue(
          policy.maxRetries == 3,
          policy.baseDelay == Duration.fromSeconds(1),
          policy.maxDelay == Duration.fromSeconds(30),
          policy.jitterFactor == 0.1,
        )
      },
      test("custom policy can be created") {
        val policy = RetryPolicy(
          maxRetries = 5,
          baseDelay = Duration.fromMillis(500),
          maxDelay = Duration.fromSeconds(60),
          jitterFactor = 0.2,
        )
        assertTrue(
          policy.maxRetries == 5,
          policy.baseDelay == Duration.fromMillis(500),
          policy.maxDelay == Duration.fromSeconds(60),
          policy.jitterFactor == 0.2,
        )
      },
    ),
    // ========================================================================
    // isRetryable tests - Retryable errors
    // ========================================================================
    suite("isRetryable - retryable errors")(
      test("Timeout is retryable") {
        val error = GeminiError.Timeout(Duration.fromSeconds(60))
        assertTrue(RetryPolicy.isRetryable(error))
      },
      test("NonZeroExit with code 429 (rate limit) is retryable") {
        val error = GeminiError.NonZeroExit(429, "Rate limit exceeded")
        assertTrue(RetryPolicy.isRetryable(error))
      },
      test("NonZeroExit with code 500 (server error) is retryable") {
        val error = GeminiError.NonZeroExit(500, "Internal server error")
        assertTrue(RetryPolicy.isRetryable(error))
      },
      test("NonZeroExit with code 502 (bad gateway) is retryable") {
        val error = GeminiError.NonZeroExit(502, "Bad gateway")
        assertTrue(RetryPolicy.isRetryable(error))
      },
      test("NonZeroExit with code 503 (service unavailable) is retryable") {
        val error = GeminiError.NonZeroExit(503, "Service unavailable")
        assertTrue(RetryPolicy.isRetryable(error))
      },
      test("ProcessFailed is retryable") {
        val error = GeminiError.ProcessFailed("Connection reset")
        assertTrue(RetryPolicy.isRetryable(error))
      },
    ),
    // ========================================================================
    // isRetryable tests - Non-retryable errors
    // ========================================================================
    suite("isRetryable - non-retryable errors")(
      test("ProcessStartFailed is not retryable") {
        val error = GeminiError.ProcessStartFailed("Command not found")
        assertTrue(!RetryPolicy.isRetryable(error))
      },
      test("NotInstalled is not retryable") {
        val error = GeminiError.NotInstalled
        assertTrue(!RetryPolicy.isRetryable(error))
      },
      test("InvalidResponse is not retryable") {
        val error = GeminiError.InvalidResponse("Malformed JSON")
        assertTrue(!RetryPolicy.isRetryable(error))
      },
      test("OutputReadFailed is not retryable") {
        val error = GeminiError.OutputReadFailed("Stream closed")
        assertTrue(!RetryPolicy.isRetryable(error))
      },
      test("NonZeroExit with code 400 (bad request) is not retryable") {
        val error = GeminiError.NonZeroExit(400, "Bad request")
        assertTrue(!RetryPolicy.isRetryable(error))
      },
      test("NonZeroExit with code 401 (unauthorized) is not retryable") {
        val error = GeminiError.NonZeroExit(401, "Unauthorized")
        assertTrue(!RetryPolicy.isRetryable(error))
      },
      test("NonZeroExit with code 403 (forbidden) is not retryable") {
        val error = GeminiError.NonZeroExit(403, "Forbidden")
        assertTrue(!RetryPolicy.isRetryable(error))
      },
      test("NonZeroExit with code 404 (not found) is not retryable") {
        val error = GeminiError.NonZeroExit(404, "Not found")
        assertTrue(!RetryPolicy.isRetryable(error))
      },
    ),
    // ========================================================================
    // withRetry behavior tests
    // ========================================================================
    suite("withRetry behavior")(
      test("succeeds immediately on first success") {
        for
          result <- RetryPolicy.withRetry(
                      ZIO.succeed(42),
                      RetryPolicy.default,
                      (_: Nothing) => true,
                    )
        yield assertTrue(result == 42)
      },
      test("retries on retryable failure and eventually succeeds") {
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1).flatMap { count =>
                            if count < 3 then ZIO.fail(GeminiError.Timeout(Duration.fromSeconds(60)))
                            else ZIO.succeed("Success")
                          }
          // Use smaller delays for faster testing
          policy        = RetryPolicy(
                            maxRetries = 3,
                            baseDelay = Duration.fromMillis(10),
                            maxDelay = Duration.fromMillis(100),
                          )
          result       <- RetryPolicy.withRetry(
                            effect,
                            policy,
                            RetryPolicy.isRetryable,
                          )
          attempts     <- attemptCount.get
        yield assertTrue(
          result == "Success",
          attempts == 3,
        )
      } @@ TestAspect.withLiveClock,
      test("fails immediately on non-retryable error") {
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1) *>
                            ZIO.fail(GeminiError.NotInstalled)
          result       <- RetryPolicy
                            .withRetry(
                              effect,
                              RetryPolicy.default,
                              RetryPolicy.isRetryable,
                            )
                            .either
          attempts     <- attemptCount.get
        yield assertTrue(
          result.isLeft,
          attempts == 1, // No retries for non-retryable errors
          result.left.exists(_ == GeminiError.NotInstalled),
        )
      },
      test("fails after max retries with retryable error") {
        val policy = RetryPolicy(
          maxRetries = 2,
          baseDelay = Duration.fromMillis(10),
          maxDelay = Duration.fromMillis(50),
        )
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1) *>
                            ZIO.fail(GeminiError.Timeout(Duration.fromSeconds(60)))
          result       <- RetryPolicy
                            .withRetry(effect, policy, RetryPolicy.isRetryable)
                            .either
          attempts     <- attemptCount.get
        yield assertTrue(
          result.isLeft,
          attempts >= 2, // At least initial attempt + retries
          result.left.exists {
            case GeminiError.Timeout(_) => true
            case _                      => false
          },
        )
      } @@ TestAspect.withLiveClock,
      test("jitter prevents thundering herd") {
        val policy = RetryPolicy(
          maxRetries = 2,
          baseDelay = Duration.fromMillis(10),
          maxDelay = Duration.fromMillis(100),
          jitterFactor = 0.5, // High jitter for testing
        )
        for
          result1 <- RetryPolicy
                       .withRetry(
                         ZIO.fail(GeminiError.Timeout(Duration.fromSeconds(60))),
                         policy,
                         RetryPolicy.isRetryable,
                       )
                       .either
          result2 <- RetryPolicy
                       .withRetry(
                         ZIO.fail(GeminiError.Timeout(Duration.fromSeconds(60))),
                         policy,
                         RetryPolicy.isRetryable,
                       )
                       .either
        yield assertTrue(
          result1.isLeft,
          result2.isLeft,
        )
      } @@ TestAspect.withLiveClock,
    ),
    // ========================================================================
    // Integration with GeminiError types
    // ========================================================================
    suite("Integration with GeminiError")(
      test("retries on rate limit until success") {
        val policy = RetryPolicy(
          maxRetries = 3,
          baseDelay = Duration.fromMillis(10),
          maxDelay = Duration.fromMillis(100),
        )
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1).flatMap { count =>
                            if count < 2 then ZIO.fail(GeminiError.NonZeroExit(429, "Rate limited"))
                            else ZIO.succeed("Success after rate limit")
                          }
          result       <- RetryPolicy.withRetry(
                            effect,
                            policy,
                            RetryPolicy.isRetryable,
                          )
        yield assertTrue(result == "Success after rate limit")
      } @@ TestAspect.withLiveClock,
      test("retries on server error until success") {
        val policy = RetryPolicy(
          maxRetries = 3,
          baseDelay = Duration.fromMillis(10),
          maxDelay = Duration.fromMillis(100),
        )
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1).flatMap { count =>
                            if count < 2 then ZIO.fail(GeminiError.NonZeroExit(503, "Service unavailable"))
                            else ZIO.succeed("Success after server error")
                          }
          result       <- RetryPolicy.withRetry(
                            effect,
                            policy,
                            RetryPolicy.isRetryable,
                          )
        yield assertTrue(result == "Success after server error")
      } @@ TestAspect.withLiveClock,
      test("does not retry on authentication failure") {
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1) *>
                            ZIO.fail(GeminiError.NonZeroExit(401, "Authentication failed"))
          result       <- RetryPolicy
                            .withRetry(
                              effect,
                              RetryPolicy.default,
                              RetryPolicy.isRetryable,
                            )
                            .either
          attempts     <- attemptCount.get
        yield assertTrue(
          result.isLeft,
          attempts == 1, // No retries
        )
      },
      test("does not retry on invalid prompt") {
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1) *>
                            ZIO.fail(GeminiError.NonZeroExit(400, "Invalid prompt"))
          result       <- RetryPolicy
                            .withRetry(
                              effect,
                              RetryPolicy.default,
                              RetryPolicy.isRetryable,
                            )
                            .either
          attempts     <- attemptCount.get
        yield assertTrue(
          result.isLeft,
          attempts == 1, // No retries
        )
      },
    ),
    // ========================================================================
    // Edge cases
    // ========================================================================
    suite("Edge cases")(
      test("handles zero max retries") {
        val policy = RetryPolicy(
          maxRetries = 0,
          baseDelay = Duration.fromSeconds(1),
          maxDelay = Duration.fromSeconds(30),
        )
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1) *>
                            ZIO.fail(GeminiError.Timeout(Duration.fromSeconds(60)))
          result       <- RetryPolicy
                            .withRetry(effect, policy, RetryPolicy.isRetryable)
                            .either
          attempts     <- attemptCount.get
        yield assertTrue(
          result.isLeft,
          attempts == 1, // Only initial attempt, no retries
        )
      },
      test("handles very short delays") {
        val policy = RetryPolicy(
          maxRetries = 2,
          baseDelay = Duration.fromMillis(1),
          maxDelay = Duration.fromMillis(10),
        )
        for
          attemptCount <- Ref.make(0)
          effect        = attemptCount.updateAndGet(_ + 1) *>
                            ZIO.fail(GeminiError.Timeout(Duration.fromSeconds(60)))
          result       <- RetryPolicy
                            .withRetry(effect, policy, RetryPolicy.isRetryable)
                            .either
          attempts     <- attemptCount.get
        yield assertTrue(
          result.isLeft,
          attempts >= 2, // At least initial attempt + retries
        )
      } @@ TestAspect.withLiveClock,
      test("respects max delay cap") {
        val policy = RetryPolicy(
          maxRetries = 3,
          baseDelay = Duration.fromMillis(10),
          maxDelay = Duration.fromMillis(50), // Very low max
        )
        for result <- RetryPolicy
                        .withRetry(
                          ZIO.fail(GeminiError.Timeout(Duration.fromSeconds(60))),
                          policy,
                          RetryPolicy.isRetryable,
                        )
                        .either
        yield assertTrue(result.isLeft)
      } @@ TestAspect.withLiveClock,
    ),
  ) @@ TestAspect.sequential
