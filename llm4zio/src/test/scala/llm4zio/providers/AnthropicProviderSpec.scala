package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object AnthropicProviderSpec extends ZIOSpecDefault:
  private def sseChunkLine(text: String): String =
    AnthropicStreamChunk(
      `type` = "content_block_delta",
      delta = Some(AnthropicStreamChunkDelta(`type` = "text_delta", text = Some(text), stop_reason = None)),
    ).toJson

  private val sseStopLine: String =
    AnthropicStreamChunk(
      `type` = "message_delta",
      delta = Some(AnthropicStreamChunkDelta(`type` = "message_delta", text = None, stop_reason = Some("end_turn"))),
    ).toJson

  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      ZIO.succeed("{}")

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
      : IO[LlmError, String] =
      if shouldSucceed then ZIO.succeed("{}") else ZIO.fail(LlmError.ProviderError("HTTP request failed", None))

    override def postJsonStreamSSE(
      url: String,
      body: String,
      headers: Map[String, String],
      timeout: Duration,
    ): ZStream[Any, LlmError, String] =
      if shouldSucceed then
        ZStream.fromIterable(List(sseChunkLine("Test response"), sseStopLine))
      else
        ZStream.fail(LlmError.ProviderError("HTTP request failed", None))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AnthropicProvider")(
    test("execute should return response") {
      val config     = LlmConfig(
        provider = LlmProvider.Anthropic,
        model = "claude-3-5-sonnet-20241022",
        baseUrl = Some("https://api.anthropic.com/v1"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient()
      val provider   = AnthropicProvider.make(config, httpClient)

      for {
        response <- Streaming.collect(provider.executeStream("test prompt"))
      } yield assertTrue(
        response.content == "Test response"
      )
    },
    test("execute should fail with missing apiKey") {
      val config     = LlmConfig(
        provider = LlmProvider.Anthropic,
        model = "claude-3-5-sonnet-20241022",
        baseUrl = Some("https://api.anthropic.com/v1"),
        apiKey = None,
      )
      val httpClient = new MockHttpClient()
      val provider   = AnthropicProvider.make(config, httpClient)

      for {
        result <- Streaming.collect(provider.executeStream("test")).exit
      } yield assertTrue(result.isFailure)
    },
    test("executeWithHistory should handle system messages") {
      val config     = LlmConfig(
        provider = LlmProvider.Anthropic,
        model = "claude-3-5-sonnet-20241022",
        baseUrl = Some("https://api.anthropic.com/v1"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient()
      val provider   = AnthropicProvider.make(config, httpClient)

      val messages = List(
        Message(MessageRole.System, "You are a helpful assistant"),
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there"),
      )

      for {
        response <- Streaming.collect(provider.executeStreamWithHistory(messages))
      } yield assertTrue(response.content == "Test response")
    },
  )
