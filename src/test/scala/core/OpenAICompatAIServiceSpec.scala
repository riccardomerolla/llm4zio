package core

import zio.*
import zio.json.*
import zio.test.*

import models.*

object OpenAICompatAIServiceSpec extends ZIOSpecDefault:

  final private case class HttpCall(
    url: String,
    body: String,
    headers: Map[String, String],
    timeout: Duration,
  )

  private val providerConfig = AIProviderConfig(
    provider = AIProvider.OpenAi,
    model = "gpt-4o",
    baseUrl = Some("https://api.openai.com/v1"),
    apiKey = Some("secret-key"),
    timeout = 5.seconds,
    maxRetries = 0,
    temperature = Some(0.3),
    maxTokens = Some(1200),
  )

  private val allowAllRateLimiter: ULayer[RateLimiter] =
    ZLayer.succeed(new RateLimiter {
      override def acquire: ZIO[Any, RateLimitError, Unit]        = ZIO.unit
      override def tryAcquire: ZIO[Any, Nothing, Boolean]         = ZIO.succeed(true)
      override def metrics: ZIO[Any, Nothing, RateLimiterMetrics] =
        ZIO.succeed(RateLimiterMetrics(0L, 0L, 10))
    })

  def spec: Spec[Any, Any] = suite("OpenAICompatAIServiceSpec")(
    test("successful completion with metadata and non-streaming request") {
      val response = ChatCompletionResponse(
        id = Some("chatcmpl-1"),
        choices = List(
          ChatChoice(
            index = 0,
            message = Some(ChatMessage("assistant", "Hello")),
            finish_reason = Some("stop"),
          )
        ),
        usage = Some(
          TokenUsage(
            prompt_tokens = Some(10),
            completion_tokens = Some(5),
            total_tokens = Some(15),
          )
        ),
        model = Some("gpt-4o"),
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

                      override def get(
                        url: String,
                        headers: Map[String, String],
                        timeout: Duration,
                      ): ZIO[Any, AIError, String] = ZIO.succeed("{}")
                    })
        result   <- AIService.execute("Say hello").provide(
                      OpenAICompatAIService.layer,
                      ZLayer.succeed(providerConfig),
                      allowAllRateLimiter,
                      httpLayer,
                    )
        calls    <- callsRef.get
        parsed   <- ZIO
                      .fromEither(calls.head.body.fromJson[ChatCompletionRequest])
                      .mapError(err => new RuntimeException(err))
                      .orDie
      yield assertTrue(
        calls.size == 1,
        calls.head.url == "https://api.openai.com/v1/chat/completions",
        calls.head.headers.get("Authorization").contains("Bearer secret-key"),
        calls.head.timeout == 5.seconds,
        parsed.stream.contains(false),
        parsed.max_tokens.contains(1200),
        parsed.temperature.contains(0.3),
        result.output == "Hello",
        result.metadata.get("model").contains("gpt-4o"),
        result.metadata.get("prompt_tokens").contains("10"),
        result.metadata.get("completion_tokens").contains("5"),
        result.metadata.get("total_tokens").contains("15"),
        result.metadata.get("finish_reason").contains("stop"),
      )
    },
    test("supports LM Studio style response without usage and model") {
      val response =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":"LM says hi"},"finish_reason":null}]}"""

      val httpLayer = ZLayer.succeed(new HttpAIClient {
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZIO[Any, AIError, String] = ZIO.succeed(response)
      })

      for
        result <- AIService.execute("hello").provide(
                    OpenAICompatAIService.layer,
                    ZLayer.succeed(
                      providerConfig.copy(
                        baseUrl = Some("http://localhost:1234/v1"),
                        apiKey = None,
                      )
                    ),
                    allowAllRateLimiter,
                    httpLayer,
                  )
      yield assertTrue(
        result.output == "LM says hi",
        result.metadata.get("model").contains("gpt-4o"),
        result.metadata.get("prompt_tokens").isEmpty,
      )
    },
    test("handles malformed response") {
      val httpLayer = ZLayer.succeed(new HttpAIClient {
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZIO[Any, AIError, String] = ZIO.succeed("{not-json")
      })

      for
        result <- AIService.execute("hello").provide(
                    OpenAICompatAIService.layer,
                    ZLayer.succeed(providerConfig),
                    allowAllRateLimiter,
                    httpLayer,
                  ).either
      yield assertTrue(result.left.exists {
        case AIError.InvalidResponse(message) => message.contains("decode OpenAI response")
        case _                                => false
      })
    },
    test("propagates 401, 429, 500 and timeout errors") {
      val scenarios = List[AIError](
        AIError.AuthenticationFailed("openai"),
        AIError.RateLimitExceeded(1.second),
        AIError.ProviderUnavailable("openai", "HTTP 500: boom"),
        AIError.Timeout(100.millis),
      )

      for
        failuresRef <- Ref.make(scenarios)
        httpLayer    = ZLayer.succeed(new HttpAIClient {
                         override def postJson(
                           url: String,
                           body: String,
                           headers: Map[String, String],
                           timeout: Duration,
                         ): ZIO[Any, AIError, String] =
                           failuresRef.modify {
                             case head :: tail => (ZIO.fail(head), tail)
                             case Nil          => (ZIO.succeed("{}"), Nil)
                           }.flatten
                       })
        results     <- ZIO.foreach(scenarios.indices.toList)(_ =>
                         AIService.execute("x").provide(
                           OpenAICompatAIService.layer,
                           ZLayer.succeed(providerConfig),
                           allowAllRateLimiter,
                           httpLayer,
                         ).either
                       )
      yield assertTrue(results.forall(_.isLeft))
    },
    test("retries on 429 and succeeds within maxRetries") {
      val successResponse = """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}"""
      val retryConfig     = providerConfig.copy(maxRetries = 2)

      for
        attemptRef <- Ref.make(0)
        httpLayer   = ZLayer.succeed(new HttpAIClient {
                        override def postJson(
                          url: String,
                          body: String,
                          headers: Map[String, String],
                          timeout: Duration,
                        ): ZIO[Any, AIError, String] =
                          attemptRef.updateAndGet(_ + 1).flatMap { n =>
                            if n == 1 then ZIO.fail(AIError.RateLimitExceeded(1.second))
                            else ZIO.succeed(successResponse)
                          }
                      })
        result     <- AIService.execute("retry").provide(
                        OpenAICompatAIService.layer,
                        ZLayer.succeed(retryConfig),
                        allowAllRateLimiter,
                        httpLayer,
                      )
        attempts   <- attemptRef.get
      yield assertTrue(
        result.output == "ok",
        attempts == 2,
      )
    } @@ TestAspect.withLiveClock,
    test("isAvailable checks /models and falls back to true when endpoint fails") {
      for
        callsRef  <- Ref.make(List.empty[String])
        httpLayer  = ZLayer.succeed(new HttpAIClient {
                       override def get(
                         url: String,
                         headers: Map[String, String],
                         timeout: Duration,
                       ): ZIO[Any, AIError, String] =
                         callsRef.update(url :: _) *> ZIO.fail(AIError.HttpError(404, "not found"))

                       override def postJson(
                         url: String,
                         body: String,
                         headers: Map[String, String],
                         timeout: Duration,
                       ): ZIO[Any, AIError, String] = ZIO.succeed("{}")
                     })
        available <- AIService.isAvailable.provide(
                       OpenAICompatAIService.layer,
                       ZLayer.succeed(providerConfig.copy(baseUrl = Some("http://localhost:1234/v1"))),
                       allowAllRateLimiter,
                       httpLayer,
                     )
        calls     <- callsRef.get
      yield assertTrue(
        available,
        calls.headOption.contains("http://localhost:1234/v1/models"),
      )
    },
  )
