package llm4zio.providers

import zio.*
import zio.test.*
import zio.json.*
import llm4zio.core.*

object OpenAIProviderSpec extends ZIOSpecDefault:
  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then ZIO.succeed("""{"data":[]}""")
      else ZIO.fail(LlmError.ProviderError("HTTP GET failed", None))

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then
        val response = ChatCompletionResponse(
          id = Some("chatcmpl-123"),
          choices = List(
            ChatChoice(
              index = 0,
              message = Some(ChatMessage(role = "assistant", content = "Test response")),
              finish_reason = Some("stop")
            )
          ),
          usage = Some(OpenAITokenUsage(
            prompt_tokens = Some(10),
            completion_tokens = Some(5),
            total_tokens = Some(15)
          )),
          model = Some("gpt-4")
        )
        ZIO.succeed(response.toJson)
      else
        ZIO.fail(LlmError.ProviderError("HTTP POST failed", None))

  def spec = suite("OpenAIProvider")(
    test("execute should return response") {
      val config = LlmConfig(
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = Some("https://api.openai.com/v1"),
        apiKey = Some("test-api-key")
      )
      val httpClient = new MockHttpClient()
      val provider = OpenAIProvider.make(config, httpClient)

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
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = Some("https://api.openai.com/v1"),
        apiKey = None
      )
      val httpClient = new MockHttpClient()
      val provider = OpenAIProvider.make(config, httpClient)

      for {
        result <- provider.execute("test").exit
      } yield assertTrue(result.isFailure)
    },
    test("execute should fail with missing baseUrl") {
      val config = LlmConfig(
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = None,
        apiKey = Some("test-api-key")
      )
      val httpClient = new MockHttpClient()
      val provider = OpenAIProvider.make(config, httpClient)

      for {
        result <- provider.execute("test").exit
      } yield assertTrue(result.isFailure)
    },
    test("executeWithHistory should convert messages") {
      val config = LlmConfig(
        provider = LlmProvider.OpenAI,
        model = "gpt-4",
        baseUrl = Some("https://api.openai.com/v1"),
        apiKey = Some("test-api-key")
      )
      val httpClient = new MockHttpClient()
      val provider = OpenAIProvider.make(config, httpClient)

      val messages = List(
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there")
      )

      for {
        response <- provider.executeWithHistory(messages)
      } yield assertTrue(response.content == "Test response")
    }
  )
