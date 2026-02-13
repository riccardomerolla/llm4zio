package llm4zio.providers

import zio.*
import zio.test.*
import zio.json.*
import llm4zio.core.*

object AnthropicProviderSpec extends ZIOSpecDefault:
  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      ZIO.succeed("{}")

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then
        val response = AnthropicResponse(
          id = Some("msg_123"),
          content = List(
            ContentBlock(`type` = "text", text = Some("Test response"))
          ),
          usage = Some(AnthropicUsage(
            input_tokens = Some(10),
            output_tokens = Some(5)
          )),
          model = Some("claude-3-5-sonnet-20241022"),
          stop_reason = Some("end_turn")
        )
        ZIO.succeed(response.toJson)
      else
        ZIO.fail(LlmError.ProviderError("HTTP request failed", None))

  def spec = suite("AnthropicProvider")(
    test("execute should return response") {
      val config = LlmConfig(
        provider = LlmProvider.Anthropic,
        model = "claude-3-5-sonnet-20241022",
        baseUrl = Some("https://api.anthropic.com/v1"),
        apiKey = Some("test-api-key")
      )
      val httpClient = new MockHttpClient()
      val provider = AnthropicProvider.make(config, httpClient)

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
        provider = LlmProvider.Anthropic,
        model = "claude-3-5-sonnet-20241022",
        baseUrl = Some("https://api.anthropic.com/v1"),
        apiKey = None
      )
      val httpClient = new MockHttpClient()
      val provider = AnthropicProvider.make(config, httpClient)

      for {
        result <- provider.execute("test").exit
      } yield assertTrue(result.isFailure)
    },
    test("executeWithHistory should handle system messages") {
      val config = LlmConfig(
        provider = LlmProvider.Anthropic,
        model = "claude-3-5-sonnet-20241022",
        baseUrl = Some("https://api.anthropic.com/v1"),
        apiKey = Some("test-api-key")
      )
      val httpClient = new MockHttpClient()
      val provider = AnthropicProvider.make(config, httpClient)

      val messages = List(
        Message(MessageRole.System, "You are a helpful assistant"),
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there")
      )

      for {
        response <- provider.executeWithHistory(messages)
      } yield assertTrue(response.content == "Test response")
    }
  )
