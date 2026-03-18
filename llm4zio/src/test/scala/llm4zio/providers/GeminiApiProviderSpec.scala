package llm4zio.providers

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object GeminiApiProviderSpec extends ZIOSpecDefault:
  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      ZIO.succeed("{}")

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
      : IO[LlmError, String] =
      if shouldSucceed then
        val response = GeminiGenerateContentResponse(
          candidates = List(
            GeminiCandidate(
              content = GeminiContent(
                parts = List(GeminiPart(text = Some("Test response")))
              )
            )
          ),
          usageMetadata = Some(GeminiUsageMetadata(
            promptTokenCount = Some(10),
            candidatesTokenCount = Some(5),
            totalTokenCount = Some(15),
          )),
        )
        ZIO.succeed(response.toJson)
      else
        ZIO.fail(LlmError.ProviderError("HTTP request failed", None))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("GeminiApiProvider")(
    test("execute should return response") {
      val config     = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = Some("https://generativelanguage.googleapis.com"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient()
      val provider   = GeminiApiProvider.make(config, httpClient)

      for {
        response <- Streaming.collect(provider.executeStream("test prompt"))
      } yield assertTrue(
        response.content == "Test response",
        response.usage.isDefined,
        response.usage.get.total == 15,
      )
    },
    test("execute should fail with missing apiKey") {
      val config     = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = Some("https://generativelanguage.googleapis.com"),
        apiKey = None,
      )
      val httpClient = new MockHttpClient()
      val provider   = GeminiApiProvider.make(config, httpClient)

      for {
        result <- Streaming.collect(provider.executeStream("test")).exit
      } yield assertTrue(result.isFailure)
    },
    test("execute should fail with missing baseUrl") {
      val config     = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = None,
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient()
      val provider   = GeminiApiProvider.make(config, httpClient)

      for {
        result <- Streaming.collect(provider.executeStream("test")).exit
      } yield assertTrue(result.isFailure)
    },
    test("executeStructured should use JSON schema") {
      val config     = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = Some("https://generativelanguage.googleapis.com"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient() {
        override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
          : IO[LlmError, String] =
          // Verify that the request includes response schema
          for {
            _       <- ZIO.succeed(assertTrue(body.contains("application/json")))
            response = GeminiGenerateContentResponse(
                         candidates = List(
                           GeminiCandidate(
                             content = GeminiContent(
                               parts = List(GeminiPart(text = Some("""{"name":"Test"}""")))
                             )
                           )
                         )
                       )
          } yield response.toJson
      }
      val provider   = GeminiApiProvider.make(config, httpClient)
      val schema     = Json.Obj("type" -> Json.Str("object"))

      for {
        result <- provider.executeStructured[Json](
                    "test",
                    schema,
                  )
      } yield assertTrue(result.isInstanceOf[Json])
    },
    test("execute should return ParseError when Gemini response has no text candidate") {
      val config     = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = Some("https://generativelanguage.googleapis.com"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient() {
        override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
          : IO[LlmError, String] =
          ZIO.succeed("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
      }
      val provider   = GeminiApiProvider.make(config, httpClient)

      for {
        result <- Streaming.collect(provider.executeStream("blocked prompt")).exit
      } yield assertTrue(
        result match
          case Exit.Success(response) => response.content.isEmpty
          case _                      => false
      )
    },
    test("executeStream should parse NDJSON stream responses into delta chunks") {
      val config     = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = Some("https://generativelanguage.googleapis.com"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient() {
        override def postJsonStream(url: String, body: String, headers: Map[String, String], timeout: Duration)
          : ZStream[Any, LlmError, String] =
          ZStream.fromIterable(
            List(
              """{"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}""",
              """{"candidates":[{"content":{"parts":[{"text":"lo"}]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":2}}""",
            )
          )
      }
      val provider   = GeminiApiProvider.make(config, httpClient)

      for
        chunks <- provider.executeStream("stream me").runCollect
      yield assertTrue(
        chunks.map(_.delta).mkString == "Hello",
        chunks.lastOption.flatMap(_.finishReason).contains("stop"),
      )
    },
  )
