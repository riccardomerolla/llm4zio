package models

import zio.json.*
import zio.test.*

object AIModelsSpec extends ZIOSpecDefault:

  private def roundTripTest[A: JsonEncoder: JsonDecoder](name: String, value: A): Spec[Any, Nothing] =
    test(s"$name round-trip") {
      val json    = value.toJson
      val decoded = json.fromJson[A]
      assertTrue(decoded == Right(value))
    }

  def spec: Spec[Any, Nothing] = suite("AIModelsSpec")(
    roundTripTest(
      "ChatCompletionRequest",
      ChatCompletionRequest(
        model = "gpt-4o",
        messages = List(
          ChatMessage("system", "You are helpful"),
          ChatMessage("user", "Say hello"),
        ),
        temperature = Some(0.2),
        max_tokens = Some(1024),
        max_completion_tokens = Some(2048),
        stream = Some(false),
      ),
    ),
    roundTripTest(
      "ChatCompletionResponse",
      ChatCompletionResponse(
        id = Some("chatcmpl-1"),
        choices = List(
          ChatChoice(
            index = 0,
            message = Some(ChatMessage("assistant", "Hello")),
            text = None,
            finish_reason = Some("stop"),
          )
        ),
        usage = Some(
          TokenUsage(
            prompt_tokens = Some(10),
            completion_tokens = Some(5),
            total_tokens = Some(15),
          )
        ),
        model = Some("gpt-4o"),
      ),
    ),
    roundTripTest(
      "AnthropicRequest",
      AnthropicRequest(
        model = "claude-sonnet-4-20250514",
        max_tokens = 2048,
        messages = List(ChatMessage("user", "Summarize this")),
        temperature = Some(0.3),
      ),
    ),
    roundTripTest(
      "AnthropicResponse",
      AnthropicResponse(
        id = "msg-1",
        content = List(ContentBlock("text", "Summary")),
        model = "claude-sonnet-4-20250514",
        usage = Some(AnthropicUsage(120, 45)),
      ),
    ),
    roundTripTest(
      "GeminiGenerateContentRequest",
      GeminiGenerateContentRequest(
        contents = List(
          GeminiContent(
            parts = List(GeminiPart("Explain this code"))
          )
        )
      ),
    ),
    roundTripTest(
      "GeminiGenerateContentResponse",
      GeminiGenerateContentResponse(
        candidates = List(
          GeminiCandidate(
            GeminiContent(
              parts = List(GeminiPart("Here is the explanation"))
            )
          )
        ),
        usageMetadata = Some(
          GeminiUsageMetadata(
            promptTokenCount = Some(100),
            candidatesTokenCount = Some(40),
            totalTokenCount = Some(140),
          )
        ),
      ),
    ),
  )
