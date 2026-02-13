package llm4zio.providers

import zio.json.*

// Anthropic API request/response models
case class AnthropicRequest(
  model: String,
  max_tokens: Int,
  messages: List[ChatMessage],
  temperature: Option[Double] = None,
  system: Option[String] = None,
) derives JsonCodec

case class AnthropicResponse(
  id: Option[String] = None,
  content: List[ContentBlock],
  model: Option[String] = None,
  usage: Option[AnthropicUsage] = None,
  stop_reason: Option[String] = None,
) derives JsonCodec

case class ContentBlock(`type`: String, text: Option[String] = None) derives JsonCodec

case class AnthropicUsage(
  input_tokens: Option[Int] = None,
  output_tokens: Option[Int] = None,
) derives JsonCodec
