package llm4zio.providers

import zio.*
import zio.test.*
import zio.json.*
import llm4zio.core.*

object OllamaProviderSpec extends ZIOSpecDefault:
  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then
        val response = OllamaModelsResponse(
          models = List(
            OllamaModelInfo(
              name = "llama2",
              modified_at = "2024-01-01T00:00:00Z",
              size = 3825819519L
            )
          )
        )
        ZIO.succeed(response.toJson)
      else ZIO.fail(LlmError.ProviderError("HTTP GET failed", None))

    override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then
        // Check if it's a chat or generate request based on URL
        if url.endsWith("/api/chat") then
          val response = OllamaChatResponse(
            model = "llama2",
            message = OllamaMessage(role = "assistant", content = "Test chat response"),
            done = true,
            prompt_eval_count = Some(10),
            eval_count = Some(5)
          )
          ZIO.succeed(response.toJson)
        else
          val response = OllamaGenerateResponse(
            model = "llama2",
            response = "Test generate response",
            done = true,
            prompt_eval_count = Some(10),
            eval_count = Some(5)
          )
          ZIO.succeed(response.toJson)
      else
        ZIO.fail(LlmError.ProviderError("HTTP POST failed", None))

  def spec = suite("OllamaProvider")(
    test("execute should return response") {
      val config = LlmConfig(
        provider = LlmProvider.Ollama,
        model = "llama2",
        baseUrl = Some("http://localhost:11434")
      )
      val httpClient = new MockHttpClient()
      val provider = OllamaProvider.make(config, httpClient)

      for {
        response <- provider.execute("test prompt")
      } yield assertTrue(
        response.content == "Test generate response",
        response.usage.isDefined,
        response.usage.get.total == 15
      )
    },
    test("execute should fail with missing baseUrl") {
      val config = LlmConfig(
        provider = LlmProvider.Ollama,
        model = "llama2",
        baseUrl = None
      )
      val httpClient = new MockHttpClient()
      val provider = OllamaProvider.make(config, httpClient)

      for {
        result <- provider.execute("test").exit
      } yield assertTrue(result.isFailure)
    },
    test("executeWithHistory should convert messages and use chat endpoint") {
      val config = LlmConfig(
        provider = LlmProvider.Ollama,
        model = "llama2",
        baseUrl = Some("http://localhost:11434")
      )
      val httpClient = new MockHttpClient()
      val provider = OllamaProvider.make(config, httpClient)

      val messages = List(
        Message(MessageRole.System, "You are a helpful assistant"),
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there")
      )

      for {
        response <- provider.executeWithHistory(messages)
      } yield assertTrue(
        response.content == "Test chat response",
        response.usage.isDefined,
        response.metadata("provider") == "ollama"
      )
    },
    test("executeStructured should request JSON format") {
      val config = LlmConfig(
        provider = LlmProvider.Ollama,
        model = "llama2",
        baseUrl = Some("http://localhost:11434")
      )
      
      // Custom mock that returns valid JSON
      class JsonMockHttpClient extends HttpClient:
        override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          ZIO.succeed("""{"models":[]}""")
        
        override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          val response = OllamaGenerateResponse(
            model = "llama2",
            response = """{"name":"John","age":30}""",
            done = true
          )
          ZIO.succeed(response.toJson)

      val httpClient = new JsonMockHttpClient()
      val provider = OllamaProvider.make(config, httpClient)

      case class Person(name: String, age: Int) derives JsonCodec

      for {
        response <- provider.executeStructured[Person]("Generate a person", zio.json.ast.Json.Obj())
      } yield assertTrue(
        response.name == "John",
        response.age == 30
      )
    },
    test("isAvailable should return true when Ollama is accessible") {
      val config = LlmConfig(
        provider = LlmProvider.Ollama,
        model = "llama2",
        baseUrl = Some("http://localhost:11434")
      )
      val httpClient = new MockHttpClient(shouldSucceed = true)
      val provider = OllamaProvider.make(config, httpClient)

      for {
        available <- provider.isAvailable
      } yield assertTrue(available)
    },
    test("isAvailable should return false when Ollama is not accessible") {
      val config = LlmConfig(
        provider = LlmProvider.Ollama,
        model = "llama2",
        baseUrl = Some("http://localhost:11434")
      )
      val httpClient = new MockHttpClient(shouldSucceed = false)
      val provider = OllamaProvider.make(config, httpClient)

      for {
        available <- provider.isAvailable
      } yield assertTrue(!available)
    },
    test("executeWithTools should fail as not supported") {
      val config = LlmConfig(
        provider = LlmProvider.Ollama,
        model = "llama2",
        baseUrl = Some("http://localhost:11434")
      )
      val httpClient = new MockHttpClient()
      val provider = OllamaProvider.make(config, httpClient)

      for {
        result <- provider.executeWithTools("test", List()).exit
      } yield assertTrue(result.isFailure)
    }
  )
