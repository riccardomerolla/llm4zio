package llm4zio.providers

import zio.*
import zio.test.*
import zio.json.*
import llm4zio.core.*

object LmStudioProviderSpec extends ZIOSpecDefault:
  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then ZIO.succeed("""{"data":[{"id":"llama-2-7b","object":"model"}]}""")
      else ZIO.fail(LlmError.ProviderError("HTTP GET failed", None))

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then
        val response = ChatCompletionResponse(
          id = Some("chatcmpl-local-123"),
          choices = List(
            ChatChoice(
              index = 0,
              message = Some(ChatMessage(role = "assistant", content = "Test LM Studio response")),
              finish_reason = Some("stop")
            )
          ),
          usage = Some(OpenAITokenUsage(
            prompt_tokens = Some(10),
            completion_tokens = Some(5),
            total_tokens = Some(15)
          )),
          model = Some("llama-2-7b")
        )
        ZIO.succeed(response.toJson)
      else
        ZIO.fail(LlmError.ProviderError("HTTP POST failed", None))

  def spec = suite("LmStudioProvider")(
    test("execute should return response") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234/v1")
      )
      val httpClient = new MockHttpClient()
      val provider = LmStudioProvider.make(config, httpClient)

      for {
        response <- provider.execute("test prompt")
      } yield assertTrue(
        response.content == "Test LM Studio response",
        response.usage.isDefined,
        response.usage.get.total == 15,
        response.metadata("provider") == "lmstudio"
      )
    },
    test("execute should work without API key (local server)") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234/v1"),
        apiKey = None // No API key required for local LM Studio
      )
      val httpClient = new MockHttpClient()
      val provider = LmStudioProvider.make(config, httpClient)

      for {
        response <- provider.execute("test prompt")
      } yield assertTrue(
        response.content == "Test LM Studio response"
      )
    },
    test("execute should fail with missing baseUrl") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = None
      )
      val httpClient = new MockHttpClient()
      val provider = LmStudioProvider.make(config, httpClient)

      for {
        result <- provider.execute("test").exit
      } yield assertTrue(result.isFailure)
    },
    test("executeWithHistory should convert messages") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234/v1")
      )
      val httpClient = new MockHttpClient()
      val provider = LmStudioProvider.make(config, httpClient)

      val messages = List(
        Message(MessageRole.System, "You are a helpful assistant"),
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there")
      )

      for {
        response <- provider.executeWithHistory(messages)
      } yield assertTrue(
        response.content == "Test LM Studio response",
        response.usage.isDefined
      )
    },
    test("executeStructured should support JSON schema") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234/v1")
      )
      
      // Custom mock that returns valid JSON
      class JsonMockHttpClient extends HttpClient:
        override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          ZIO.succeed("""{"data":[]}""")
        
        override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          val response = ChatCompletionResponse(
            id = Some("chatcmpl-local-123"),
            choices = List(
              ChatChoice(
                index = 0,
                message = Some(ChatMessage(role = "assistant", content = """{"name":"Alice","age":25}""")),
                finish_reason = Some("stop")
              )
            ),
            usage = Some(OpenAITokenUsage(Some(10), Some(5), Some(15))),
            model = Some("llama-2-7b")
          )
          ZIO.succeed(response.toJson)

      val httpClient = new JsonMockHttpClient()
      val provider = LmStudioProvider.make(config, httpClient)

      case class Person(name: String, age: Int) derives JsonCodec

      for {
        response <- provider.executeStructured[Person]("Generate a person", zio.json.ast.Json.Obj())
      } yield assertTrue(
        response.name == "Alice",
        response.age == 25
      )
    },
    test("isAvailable should return true when LM Studio is accessible") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234/v1")
      )
      val httpClient = new MockHttpClient(shouldSucceed = true)
      val provider = LmStudioProvider.make(config, httpClient)

      for {
        available <- provider.isAvailable
      } yield assertTrue(available)
    },
    test("isAvailable should return false when LM Studio is not accessible") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234/v1")
      )
      val httpClient = new MockHttpClient(shouldSucceed = false)
      val provider = LmStudioProvider.make(config, httpClient)

      for {
        available <- provider.isAvailable
      } yield assertTrue(!available)
    },
    test("executeWithTools should fail as not yet supported") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234/v1")
      )
      val httpClient = new MockHttpClient()
      val provider = LmStudioProvider.make(config, httpClient)

      for {
        result <- provider.executeWithTools("test", List()).exit
      } yield assertTrue(result.isFailure)
    },
    test("should include API key in headers if provided") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234/v1"),
        apiKey = Some("optional-key")
      )
      
      class HeaderCheckingHttpClient extends HttpClient:
        override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          ZIO.succeed("""{"data":[]}""")
        
        override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          val hasAuthHeader = headers.get("Authorization").contains("Bearer optional-key")
          if hasAuthHeader then
            val response = ChatCompletionResponse(
              id = Some("chatcmpl-local-123"),
              choices = List(
                ChatChoice(
                  index = 0,
                  message = Some(ChatMessage(role = "assistant", content = "Authenticated")),
                  finish_reason = Some("stop")
                )
              ),
              model = Some("llama-2-7b")
            )
            ZIO.succeed(response.toJson)
          else
            ZIO.fail(LlmError.AuthenticationError("Missing auth header"))

      val httpClient = new HeaderCheckingHttpClient()
      val provider = LmStudioProvider.make(config, httpClient)

      for {
        response <- provider.execute("test prompt")
      } yield assertTrue(response.content == "Authenticated")
    }
  )
