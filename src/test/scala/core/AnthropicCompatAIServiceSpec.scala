package core

import zio.*
import zio.json.*
import zio.test.*

import models.*

object AnthropicCompatAIServiceSpec extends ZIOSpecDefault:

  final private case class HttpCall(
    url: String,
    body: String,
    headers: Map[String, String],
    timeout: Duration,
  )

  private val providerConfig = AIProviderConfig(
    provider = AIProvider.Anthropic,
    model = "claude-sonnet-4-20250514",
    baseUrl = Some("https://api.anthropic.com"),
    apiKey = Some("secret-key"),
    timeout = 5.seconds,
    maxRetries = 0,
    temperature = Some(0.25),
    maxTokens = Some(900),
  )

  private val allowAllRateLimiter: ULayer[RateLimiter] =
    ZLayer.succeed(new RateLimiter {
      override def acquire: ZIO[Any, RateLimitError, Unit]        = ZIO.unit
      override def tryAcquire: ZIO[Any, Nothing, Boolean]         = ZIO.succeed(true)
      override def metrics: ZIO[Any, Nothing, RateLimiterMetrics] =
        ZIO.succeed(RateLimiterMetrics(0L, 0L, 10))
    })

  def spec: Spec[Any, Any] = suite("AnthropicCompatAIServiceSpec")(
    test("successful message response with headers and metadata") {
      val response = AnthropicResponse(
        id = Some("msg_1"),
        content = List(ContentBlock("text", Some("Hello from Claude"))),
        model = Some("claude-sonnet-4-20250514"),
        usage = Some(AnthropicUsage(input_tokens = Some(40), output_tokens = Some(20))),
        stop_reason = Some("end_turn"),
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
        result   <- AIService.execute("Analyze this").provide(
                      AnthropicCompatAIService.layer,
                      ZLayer.succeed(providerConfig),
                      allowAllRateLimiter,
                      httpLayer,
                    )
        calls    <- callsRef.get
        parsed   <- ZIO
                      .fromEither(calls.head.body.fromJson[AnthropicRequest])
                      .mapError(err => new RuntimeException(err))
                      .orDie
      yield assertTrue(
        calls.size == 1,
        calls.head.url == "https://api.anthropic.com/v1/messages",
        calls.head.headers.get("x-api-key").contains("secret-key"),
        calls.head.headers.get("anthropic-version").contains("2023-06-01"),
        calls.head.headers.get("Authorization").contains("Bearer secret-key"),
        parsed.temperature.contains(0.25),
        parsed.max_tokens == 900,
        result.output == "Hello from Claude",
        result.metadata.get("model").contains("claude-sonnet-4-20250514"),
        result.metadata.get("input_tokens").contains("40"),
        result.metadata.get("output_tokens").contains("20"),
        result.metadata.get("stop_reason").contains("end_turn"),
      )
    },
    test("concatenates multi-block text responses") {
      val response = AnthropicResponse(
        content = List(
          ContentBlock("text", Some("Part 1")),
          ContentBlock("tool_use", None),
          ContentBlock("text", Some("Part 2")),
        )
      ).toJson

      val httpLayer = ZLayer.succeed(new HttpAIClient {
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZIO[Any, AIError, String] = ZIO.succeed(response)
      })

      for
        result <- AIService.execute("x").provide(
                    AnthropicCompatAIService.layer,
                    ZLayer.succeed(providerConfig),
                    allowAllRateLimiter,
                    httpLayer,
                  )
      yield assertTrue(result.output == "Part 1\nPart 2")
    },
    test("handles Anthropic error body format") {
      val errorJson =
        """{"type":"error","error":{"type":"rate_limit_error","message":"too many requests"}}"""

      val httpLayer = ZLayer.succeed(new HttpAIClient {
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZIO[Any, AIError, String] = ZIO.succeed(errorJson)
      })

      for
        result <- AIService.execute("x").provide(
                    AnthropicCompatAIService.layer,
                    ZLayer.succeed(providerConfig),
                    allowAllRateLimiter,
                    httpLayer,
                  ).either
      yield assertTrue(result.left.exists {
        case AIError.RateLimitExceeded(_) => true
        case _                            => false
      })
    },
    test("LM Studio style response works with baseUrl only") {
      val response = """{"content":[{"type":"text","text":"Local response"}],"stop_reason":null}"""

      val httpLayer = ZLayer.succeed(new HttpAIClient {
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZIO[Any, AIError, String] = ZIO.succeed(response)
      })

      for
        result <- AIService.execute("x").provide(
                    AnthropicCompatAIService.layer,
                    ZLayer.succeed(providerConfig.copy(baseUrl = Some("http://localhost:1234"), apiKey = None)),
                    allowAllRateLimiter,
                    httpLayer,
                  )
      yield assertTrue(
        result.output == "Local response",
        result.metadata.get("model").contains("claude-sonnet-4-20250514"),
      )
    },
    test("propagates 401, 429, 500, 529 and timeout errors") {
      val scenarios = List[AIError](
        AIError.AuthenticationFailed("anthropic"),
        AIError.RateLimitExceeded(1.second),
        AIError.ProviderUnavailable("anthropic", "HTTP 500: boom"),
        AIError.ProviderUnavailable("anthropic", "HTTP 529: overloaded"),
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
                           AnthropicCompatAIService.layer,
                           ZLayer.succeed(providerConfig),
                           allowAllRateLimiter,
                           httpLayer,
                         ).either
                       )
      yield assertTrue(results.forall(_.isLeft))
    },
    test("retries on 529 and succeeds within maxRetries") {
      val successResponse = AnthropicResponse(
        content = List(ContentBlock("text", Some("ok")))
      ).toJson
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
                            if n == 1 then ZIO.fail(AIError.ProviderUnavailable("anthropic", "HTTP 529: overloaded"))
                            else ZIO.succeed(successResponse)
                          }
                      })
        result     <- AIService.execute("retry").provide(
                        AnthropicCompatAIService.layer,
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
    test("isAvailable true only when baseUrl is configured") {
      val httpLayer = ZLayer.succeed(new HttpAIClient {
        override def postJson(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZIO[Any, AIError, String] = ZIO.succeed("{}")
      })

      for
        availableConfigured <- AIService.isAvailable.provide(
                                 AnthropicCompatAIService.layer,
                                 ZLayer.succeed(providerConfig),
                                 allowAllRateLimiter,
                                 httpLayer,
                               )
        availableMissing    <- AIService.isAvailable.provide(
                                 AnthropicCompatAIService.layer,
                                 ZLayer.succeed(providerConfig.copy(baseUrl = None)),
                                 allowAllRateLimiter,
                                 httpLayer,
                               )
      yield assertTrue(availableConfigured, !availableMissing)
    },
  )
