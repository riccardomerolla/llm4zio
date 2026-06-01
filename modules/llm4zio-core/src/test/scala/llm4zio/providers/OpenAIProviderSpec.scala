package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object OpenAIProviderSpec extends ZIOSpecDefault:
  // SSE chunk for success response
  private def sseChunkLine(content: String, finish: Option[String]): String =
    OpenAIChatChunk(
      id = Some("chatcmpl-123"),
      model = Some("gpt-4"),
      choices = List(
        OpenAIChatChunkChoice(
          index = 0,
          delta = Some(OpenAIChatChunkDelta(content = Some(content))),
          finish_reason = finish,
        )
      ),
    ).toJson

  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then ZIO.succeed("""{"data":[]}""")
      else ZIO.fail(LlmError.ProviderError("HTTP GET failed", None))

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
      : IO[LlmError, String] =
      if shouldSucceed then ZIO.succeed("{}")
      else ZIO.fail(LlmError.ProviderError("HTTP POST failed", None))

    override def postJsonStreamSSE(
      url: String,
      body: String,
      headers: Map[String, String],
      timeout: Duration,
    ): ZStream[Any, LlmError, String] =
      if shouldSucceed then
        ZStream.fromIterable(List(
          sseChunkLine("Test response", Some("stop"))
        ))
      else
        ZStream.fail(LlmError.ProviderError("HTTP POST failed", None))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("OpenAIProvider")(
    test("execute should return response") {
      val config     = LlmConfig(
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = Some("https://api.openai.com/v1"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient()
      val provider   = OpenAIProvider.make(config, httpClient)

      for {
        response <- Streaming.collect(provider.executeStream("test prompt"))
      } yield assertTrue(
        response.content == "Test response"
      )
    },
    test("execute should fail with missing apiKey") {
      val config     = LlmConfig(
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = Some("https://api.openai.com/v1"),
        apiKey = None,
      )
      val httpClient = new MockHttpClient()
      val provider   = OpenAIProvider.make(config, httpClient)

      for {
        result <- Streaming.collect(provider.executeStream("test")).exit
      } yield assertTrue(result.isFailure)
    },
    test("execute should fail with missing baseUrl") {
      val config     = LlmConfig(
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = None,
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient()
      val provider   = OpenAIProvider.make(config, httpClient)

      for {
        result <- Streaming.collect(provider.executeStream("test")).exit
      } yield assertTrue(result.isFailure)
    },
    test("executeWithHistory should convert messages") {
      val config     = LlmConfig(
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = Some("https://api.openai.com/v1"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient()
      val provider   = OpenAIProvider.make(config, httpClient)

      val messages = List(
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there"),
      )

      for {
        response <- Streaming.collect(provider.executeStreamWithHistory(messages))
      } yield assertTrue(response.content == "Test response")
    },
    test("healthCheck returns Healthy on success") {
      val config     = LlmConfig(
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = Some("https://api.openai.com/v1"),
        apiKey = Some("test-api-key"),
      )
      val httpClient = new MockHttpClient(shouldSucceed = true)
      val provider   = OpenAIProvider.make(config, httpClient)
      for status <- provider.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
  )
