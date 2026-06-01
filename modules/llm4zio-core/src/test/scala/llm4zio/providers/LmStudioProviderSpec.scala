package llm4zio.providers

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object LmStudioProviderSpec extends ZIOSpecDefault:
  // Mock HTTP client for testing
  class MockHttpClient(shouldSucceed: Boolean = true) extends HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
      if shouldSucceed then
        ZIO.succeed("""{"models":[{"id":"llama-2-7b","path":"/models/llama-2-7b","type":"llama","loaded":true}]}""")
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
          OpenAIChatChunk(
            id = Some("chatcmpl-123"),
            model = Some("llama-2-7b"),
            choices = List(OpenAIChatChunkChoice(
              delta = Some(OpenAIChatChunkDelta(content = Some("Test LM Studio native response"))),
              finish_reason = Some("stop"),
            )),
          ).toJson
        ))
      else
        ZStream.fail(LlmError.ProviderError("HTTP POST failed", None))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("LmStudioProvider")(
    test("execute should return response") {
      val config     = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
      )
      val httpClient = new MockHttpClient()
      val provider   = LmStudioProvider.make(config, httpClient)

      for {
        response <- Streaming.collect(provider.executeStream("test prompt"))
      } yield assertTrue(
        response.content == "Test LM Studio native response",
        response.metadata("provider") == "lmstudio",
      )
    },
    test("execute should work without API key (local server)") {
      val config     = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
        apiKey = None, // No API key required for local LM Studio
      )
      val httpClient = new MockHttpClient()
      val provider   = LmStudioProvider.make(config, httpClient)

      for {
        response <- Streaming.collect(provider.executeStream("test prompt"))
      } yield assertTrue(
        response.content == "Test LM Studio native response"
      )
    },
    test("execute should fail with missing baseUrl") {
      val config     = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = None,
      )
      val httpClient = new MockHttpClient()
      val provider   = LmStudioProvider.make(config, httpClient)

      for {
        result <- Streaming.collect(provider.executeStream("test")).exit
      } yield assertTrue(result.isFailure)
    },
    test("executeWithHistory should convert messages") {
      val config     = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
      )
      val httpClient = new MockHttpClient()
      val provider   = LmStudioProvider.make(config, httpClient)

      val messages = List(
        Message(MessageRole.System, "You are a helpful assistant"),
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there"),
      )

      for {
        response <- Streaming.collect(provider.executeStreamWithHistory(messages))
      } yield assertTrue(
        response.content == "Test LM Studio native response"
      )
    },
    test("executeStructured should support JSON schema") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
      )

      // Custom mock that returns valid JSON
      class JsonMockHttpClient extends HttpClient:
        override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          ZIO.succeed("""{"models":[]}""")

        override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
          : IO[LlmError, String] =
          val response = LmStudioChatResponse(
            model_instance_id = "inst-local-123",
            output = List(
              LmStudioOutputItem(
                `type` = "message",
                content = Some("""{"name":"Alice","age":25}"""),
              )
            ),
            stats = Some(LmStudioStats(
              input_tokens = Some(10),
              total_output_tokens = Some(5),
            )),
          )
          ZIO.succeed(response.toJson)

      val httpClient = new JsonMockHttpClient()
      val provider   = LmStudioProvider.make(config, httpClient)

      case class Person(name: String, age: Int) derives JsonCodec

      for {
        response <- provider.executeStructured[Person]("Generate a person", zio.json.ast.Json.Obj())
      } yield assertTrue(
        response.name == "Alice",
        response.age == 25,
      )
    },
    test("isAvailable should return true when LM Studio is accessible") {
      val config     = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
      )
      val httpClient = new MockHttpClient(shouldSucceed = true)
      val provider   = LmStudioProvider.make(config, httpClient)

      for {
        available <- provider.isAvailable
      } yield assertTrue(available)
    },
    test("isAvailable should return false when LM Studio is not accessible") {
      val config     = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
      )
      val httpClient = new MockHttpClient(shouldSucceed = false)
      val provider   = LmStudioProvider.make(config, httpClient)

      for {
        available <- provider.isAvailable
      } yield assertTrue(!available)
    },
    test("executeWithTools should fail as not yet supported") {
      val config     = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
      )
      val httpClient = new MockHttpClient()
      val provider   = LmStudioProvider.make(config, httpClient)

      for {
        result <- provider.executeWithTools("test", List()).exit
      } yield assertTrue(result.isFailure)
    },
    test("healthCheck returns Healthy on success") {
      val config     = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
      )
      val httpClient = new MockHttpClient(shouldSucceed = true)
      val provider   = LmStudioProvider.make(config, httpClient)
      for status <- provider.healthCheck
      yield assertTrue(status.availability == Availability.Healthy)
    },
    test("should include API key in headers if provided") {
      val config = LlmConfig(
        provider = LlmProvider.LmStudio,
        model = "llama-2-7b",
        baseUrl = Some("http://localhost:1234"),
        apiKey = Some("optional-key"),
      )

      class HeaderCheckingHttpClient extends HttpClient:
        override def get(url: String, headers: Map[String, String], timeout: Duration): IO[LlmError, String] =
          ZIO.succeed("""{"models":[]}""")

        override def postJson(url: String, body: String, headers: Map[String, String], timeout: Duration)
          : IO[LlmError, String] =
          ZIO.succeed("{}")

        override def postJsonStreamSSE(
          url: String,
          body: String,
          headers: Map[String, String],
          timeout: Duration,
        ): ZStream[Any, LlmError, String] =
          val hasAuthHeader = headers.get("Authorization").contains("Bearer optional-key")
          if hasAuthHeader then
            ZStream.fromIterable(List(
              OpenAIChatChunk(
                id = Some("chatcmpl-auth"),
                model = Some("llama-2-7b"),
                choices = List(OpenAIChatChunkChoice(
                  delta = Some(OpenAIChatChunkDelta(content = Some("Authenticated"))),
                  finish_reason = Some("stop"),
                )),
              ).toJson
            ))
          else
            ZStream.fail(LlmError.AuthenticationError("Missing auth header"))

      val httpClient = new HeaderCheckingHttpClient()
      val provider   = LmStudioProvider.make(config, httpClient)

      for {
        response <- Streaming.collect(provider.executeStream("test prompt"))
      } yield assertTrue(response.content == "Authenticated")
    },
  )
