package llm4zio.core

import zio.*
import zio.json.*
import zio.test.*

object ModelsSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Models")(
    test("LlmProvider should serialize to JSON") {
      val provider = LlmProvider.OpenAI
      val json     = provider.toJson
      assertTrue(json == "\"OpenAI\"")
    },
    test("MessageRole should serialize to JSON") {
      val role = MessageRole.User
      val json = role.toJson
      assertTrue(json == "\"User\"")
    },
    test("Message should serialize and deserialize") {
      val message = Message(MessageRole.User, "Hello")
      val json    = message.toJson
      val decoded = json.fromJson[Message]
      assertTrue(decoded == Right(message))
    },
    test("LlmResponse should serialize and deserialize") {
      val response = LlmResponse(
        content = "Hello world",
        usage = Some(TokenUsage(prompt = 10, completion = 5, total = 15)),
        metadata = Map("model" -> "gpt-4"),
      )
      val json     = response.toJson
      val decoded  = json.fromJson[LlmResponse]
      assertTrue(decoded == Right(response))
    },
    test("LlmChunk should serialize and deserialize") {
      val chunk   = LlmChunk(delta = "Hello", finishReason = Some("stop"))
      val json    = chunk.toJson
      val decoded = json.fromJson[LlmChunk]
      assertTrue(decoded == Right(chunk))
    },
    test("LlmConfig should have defaults") {
      val config = LlmConfig(
        provider = LlmProvider.GeminiCli,
        model = "gemini-2.5-flash",
      )
      assertTrue(
        config.temperature.isEmpty,
        config.maxTokens.isEmpty,
        config.baseUrl.isEmpty,
      )
    },
    test("LlmError types should be distinct") {
      val providerError: LlmError  = LlmError.ProviderError("test")
      val rateLimitError: LlmError = LlmError.RateLimitError(Some(10.seconds))
      val authError: LlmError      = LlmError.AuthenticationError("invalid key")

      assertTrue(
        providerError.isInstanceOf[LlmError.ProviderError],
        rateLimitError.isInstanceOf[LlmError.RateLimitError],
        authError.isInstanceOf[LlmError.AuthenticationError],
      )
    },
  )
