package models

import zio.json.*

// OpenAI-compatible
case class ChatCompletionRequest(
  model: String,
  messages: List[ChatMessage],
  temperature: Option[Double] = None,
  max_tokens: Option[Int] = None,
  max_completion_tokens: Option[Int] = None,
  stream: Option[Boolean] = None,
) derives JsonCodec

case class ChatMessage(role: String, content: String) derives JsonCodec

case class ChatCompletionResponse(
  id: Option[String] = None,
  choices: List[ChatChoice],
  usage: Option[TokenUsage] = None,
  model: Option[String] = None,
) derives JsonCodec

case class ChatChoice(
  index: Int = 0,
  message: Option[ChatMessage] = None,
  text: Option[String] = None,
  finish_reason: Option[String] = None,
) derives JsonCodec

case class TokenUsage(
  prompt_tokens: Option[Int] = None,
  completion_tokens: Option[Int] = None,
  total_tokens: Option[Int] = None,
) derives JsonCodec

// Anthropic-compatible
case class AnthropicRequest(
  model: String,
  max_tokens: Int,
  messages: List[ChatMessage],
  temperature: Option[Double] = None,
) derives JsonCodec

case class AnthropicResponse(
  id: String,
  content: List[ContentBlock],
  model: String,
  usage: Option[AnthropicUsage] = None,
) derives JsonCodec

case class ContentBlock(`type`: String, text: String) derives JsonCodec

case class AnthropicUsage(
  input_tokens: Int,
  output_tokens: Int,
) derives JsonCodec

// Gemini API-compatible
case class GeminiGenerateContentRequest(
  contents: List[GeminiContent]
) derives JsonCodec

case class GeminiGenerateContentResponse(
  candidates: List[GeminiCandidate],
  usageMetadata: Option[GeminiUsageMetadata] = None,
) derives JsonCodec

case class GeminiCandidate(content: GeminiContent) derives JsonCodec

case class GeminiContent(parts: List[GeminiPart]) derives JsonCodec

case class GeminiPart(text: String) derives JsonCodec

case class GeminiUsageMetadata(
  promptTokenCount: Option[Int] = None,
  candidatesTokenCount: Option[Int] = None,
  totalTokenCount: Option[Int] = None,
) derives JsonCodec
