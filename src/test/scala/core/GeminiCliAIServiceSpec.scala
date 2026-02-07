package core

import zio.*
import zio.test.*

import models.*

object GeminiCliAIServiceSpec extends ZIOSpecDefault:

  private val providerConfig = AIProviderConfig(
    provider = AIProvider.GeminiCli,
    model = "gemini-2.5-flash",
    timeout = 3.seconds,
    maxRetries = 0,
  )

  private val allowAllRateLimiter: ULayer[RateLimiter] =
    ZLayer.succeed(new RateLimiter {
      override def acquire: ZIO[Any, RateLimitError, Unit]        = ZIO.unit
      override def tryAcquire: ZIO[Any, Nothing, Boolean]         = ZIO.succeed(true)
      override def metrics: ZIO[Any, Nothing, RateLimiterMetrics] =
        ZIO.succeed(RateLimiterMetrics(0L, 0L, 10))
    })

  private val timeoutRateLimiter: ULayer[RateLimiter] =
    ZLayer.succeed(new RateLimiter {
      override def acquire: ZIO[Any, RateLimitError, Unit]        = ZIO.fail(RateLimitError.AcquireTimeout(2.seconds))
      override def tryAcquire: ZIO[Any, Nothing, Boolean]         = ZIO.succeed(false)
      override def metrics: ZIO[Any, Nothing, RateLimiterMetrics] =
        ZIO.succeed(RateLimiterMetrics(0L, 1L, 0))
    })

  def spec: Spec[Any, Any] = suite("GeminiCliAIServiceSpec")(
    test("maps CLI output into AIResponse metadata") {
      val executor = new GeminiCliExecutor {
        override def checkGeminiInstalled: ZIO[Any, AIError, Unit] = ZIO.unit
        override def runGeminiProcess(
          prompt: String,
          providerConfig: AIProviderConfig,
        ): ZIO[Any, AIError, (String, Int)] =
          ZIO.succeed((s"ok:$prompt", 0))
      }

      for
        response <- AIService.execute("hello").provide(
                      GeminiCliAIService.layerWithExecutor(ZLayer.succeed(executor)),
                      ZLayer.succeed(providerConfig),
                      allowAllRateLimiter,
                    )
      yield assertTrue(
        response.output == "ok:hello",
        response.metadata.get("provider").contains("gemini-cli"),
        response.metadata.get("model").contains("gemini-2.5-flash"),
        response.metadata.get("exitCode").contains("0"),
      )
    },
    test("maps rate limiter timeout to AIError.RateLimitExceeded") {
      val executor = new GeminiCliExecutor {
        override def checkGeminiInstalled: ZIO[Any, AIError, Unit] = ZIO.unit
        override def runGeminiProcess(
          prompt: String,
          providerConfig: AIProviderConfig,
        ): ZIO[Any, AIError, (String, Int)] =
          ZIO.succeed(("unused", 0))
      }

      for
        result <- AIService.execute("hello").provide(
                    GeminiCliAIService.layerWithExecutor(ZLayer.succeed(executor)),
                    ZLayer.succeed(providerConfig),
                    timeoutRateLimiter,
                  ).either
      yield assertTrue(result == Left(AIError.RateLimitExceeded(2.seconds)))
    },
    test("returns NonZeroExit when process exits with non-zero code") {
      val executor = new GeminiCliExecutor {
        override def checkGeminiInstalled: ZIO[Any, AIError, Unit] = ZIO.unit
        override def runGeminiProcess(
          prompt: String,
          providerConfig: AIProviderConfig,
        ): ZIO[Any, AIError, (String, Int)] =
          ZIO.fail(AIError.NonZeroExit(2, "boom"))
      }

      for
        result <- AIService.execute("hello").provide(
                    GeminiCliAIService.layerWithExecutor(ZLayer.succeed(executor)),
                    ZLayer.succeed(providerConfig),
                    allowAllRateLimiter,
                  ).either
      yield assertTrue(result == Left(AIError.NonZeroExit(2, "boom")))
    },
  )
