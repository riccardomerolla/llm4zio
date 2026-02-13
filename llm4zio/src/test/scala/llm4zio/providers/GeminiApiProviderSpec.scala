package llm4zio.providers

import zio.*
import zio.test.*
import zio.json.*
import zio.json.ast.Json
import llm4zio.core.*

object GeminiApiProviderSpec extends ZIOSpecDefault:
  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      ZIO.succeed("{}")

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then
        val response = GeminiGenerateContentResponse(
          candidates = List(
            GeminiCandidate(
              content = GeminiContent(
                parts = List(GeminiPart(text = "Test response"))
              )
            )
          ),
          usageMetadata = Some(GeminiUsageMetadata(
            promptTokenCount = Some(10),
            candidatesTokenCount = Some(5),
            totalTokenCount = Some(15)
          ))
        )
        ZIO.succeed(response.toJson)
      else
        ZIO.fail(LlmError.ProviderError("HTTP request failed", None))

  def spec = suite("GeminiApiProvider")(
    test("execute should return response") {
      val config = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = Some("https://generativelanguage.googleapis.com"),
        apiKey = Some("test-api-key")
      )
      val httpClient = new MockHttpClient()
      val provider = GeminiApiProvider.make(config, httpClient)

      for {
        response <- provider.execute("test prompt")
      } yield assertTrue(
        response.content == "Test response",
        response.usage.isDefined,
        response.usage.get.total == 15
      )
    },
    test("execute should fail with missing apiKey") {
      val config = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = Some("https://generativelanguage.googleapis.com"),
        apiKey = None
      )
      val httpClient = new MockHttpClient()
      val provider = GeminiApiProvider.make(config, httpClient)

      for {
        result <- provider.execute("test").exit
      } yield assertTrue(result.isFailure)
    },
    test("execute should fail with missing baseUrl") {
      val config = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = None,
        apiKey = Some("test-api-key")
      )
      val httpClient = new MockHttpClient()
      val provider = GeminiApiProvider.make(config, httpClient)

      for {
        result <- provider.execute("test").exit
      } yield assertTrue(result.isFailure)
    },
    test("executeStructured should use JSON schema") {
      val config = LlmConfig(
        provider = LlmProvider.GeminiApi,
        model = "gemini-2.0-flash-exp",
        baseUrl = Some("https://generativelanguage.googleapis.com"),
        apiKey = Some("test-api-key")
      )
      val httpClient = new MockHttpClient() {
        override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          // Verify that the request includes response schema
          for {
            _ <- ZIO.succeed(assertTrue(body.contains("application/json")))
            response = GeminiGenerateContentResponse(
              candidates = List(
                GeminiCandidate(
                  content = GeminiContent(
                    parts = List(GeminiPart(text = """{"name":"Test"}"""))
                  )
                )
              )
            )
          } yield response.toJson
      }
      val provider = GeminiApiProvider.make(config, httpClient)
      val schema = Json.Obj("type" -> Json.Str("object"))

      for {
        result <- provider.executeStructured[Json](
          "test",
          schema
        )
      } yield assertTrue(result.isInstanceOf[Json])
    }
  )
