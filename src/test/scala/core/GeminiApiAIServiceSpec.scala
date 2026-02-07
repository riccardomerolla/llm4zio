package core

import zio.*
import zio.json.*
import zio.test.*

import models.*

object GeminiApiAIServiceSpec extends ZIOSpecDefault:

  final private case class HttpCall(
    url: String,
    body: String,
    headers: Map[String, String],
    timeout: Duration,
  )

  private val providerConfig = AIProviderConfig(
    provider = AIProvider.GeminiApi,
    model = "gemini-2.5-flash",
    baseUrl = Some("https://generativelanguage.googleapis.com"),
    apiKey = Some("secret-key"),
    timeout = 5.seconds,
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
      override def acquire: ZIO[Any, RateLimitError, Unit]        = ZIO.fail(RateLimitError.AcquireTimeout(1.second))
      override def tryAcquire: ZIO[Any, Nothing, Boolean]         = ZIO.succeed(false)
      override def metrics: ZIO[Any, Nothing, RateLimiterMetrics] =
        ZIO.succeed(RateLimiterMetrics(0L, 1L, 0))
    })

  def spec: Spec[Any, Any] = suite("GeminiApiAIServiceSpec")(
    test("builds correct request and parses Gemini API response") {
      val response = GeminiGenerateContentResponse(
        candidates = List(GeminiCandidate(GeminiContent(List(GeminiPart("Hello from Gemini"))))),
        usageMetadata = Some(
          GeminiUsageMetadata(
            promptTokenCount = Some(11),
            candidatesTokenCount = Some(7),
            totalTokenCount = Some(18),
          )
        ),
      ).toJson

      for
        callsRef <- Ref.make(List.empty[HttpCall])
        httpLayer = ZLayer.succeed(new HttpAIClient {
                      override def postJson(
                        url: String,
                        body: String,
                        headers: Map[String, String],
                        timeout: Duration,
                      ): ZIO[Any, AIError, String] =
                        callsRef.update(HttpCall(url, body, headers, timeout) :: _) *> ZIO.succeed(response)
                    })
        result   <- AIService.execute("Say hello").provide(
                      GeminiApiAIService.layer,
                      ZLayer.succeed(providerConfig),
                      allowAllRateLimiter,
                      httpLayer,
                    )
        calls    <- callsRef.get
        parsed   <- ZIO
                      .fromEither(calls.head.body.fromJson[GeminiGenerateContentRequest])
                      .mapError(err => new RuntimeException(err))
                      .orDie
      yield assertTrue(
        calls.size == 1,
        calls.head.url ==
          "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
        calls.head.headers.get("x-goog-api-key").contains("secret-key"),
        calls.head.timeout == 5.seconds,
        parsed.contents.head.parts.head.text == "Say hello",
        result.output == "Hello from Gemini",
        result.metadata.get("provider").contains("gemini-api"),
        result.metadata.get("model").contains("gemini-2.5-flash"),
        result.metadata.get("promptTokenCount").contains("11"),
        result.metadata.get("totalTokenCount").contains("18"),
      )
    },
    test("fails with InvalidResponse when response has no text") {
      val emptyResponse = GeminiGenerateContentResponse(candidates = List.empty).toJson

      val httpLayer = ZLayer.succeed(new HttpAIClient {
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZIO[Any, AIError, String] =
          ZIO.succeed(emptyResponse)
      })

      for
        result <- AIService.execute("hello").provide(
                    GeminiApiAIService.layer,
                    ZLayer.succeed(providerConfig),
                    allowAllRateLimiter,
                    httpLayer,
                  ).either
      yield assertTrue(result.left.exists {
        case AIError.InvalidResponse(message) =>
          message.contains("missing candidates")
        case _                                => false
      })
    },
    test("maps rate limiter timeout to AIError.RateLimitExceeded") {
      val httpLayer = ZLayer.succeed(new HttpAIClient {
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZIO[Any, AIError, String] =
          ZIO.succeed("{}")
      })

      for
        result <- AIService.execute("hello").provide(
                    GeminiApiAIService.layer,
                    ZLayer.succeed(providerConfig),
                    timeoutRateLimiter,
                    httpLayer,
                  ).either
      yield assertTrue(result == Left(AIError.RateLimitExceeded(1.second)))
    },
  )
