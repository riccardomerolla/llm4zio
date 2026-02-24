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

// Tool calling DTOs
case class AnthropicToolInputSchema(
  `type`: String = "object",
  properties: zio.json.ast.Json,
  required: Option[List[String]] = None,
) derives JsonCodec

case class AnthropicTool(
  name: String,
  description: String,
  input_schema: AnthropicToolInputSchema,
) derives JsonCodec

case class AnthropicContentBlockFull(
  `type`: String,
  text: Option[String] = None,
  id: Option[String] = None,
  name: Option[String] = None,
  input: Option[zio.json.ast.Json] = None,
) derives JsonCodec

case class AnthropicRequestWithTools(
  model: String,
  max_tokens: Int,
  messages: List[ChatMessage],
  tools: List[AnthropicTool],
  temperature: Option[Double] = None,
  system: Option[String] = None,
) derives JsonCodec

case class AnthropicResponseWithTools(
  id: Option[String] = None,
  content: List[AnthropicContentBlockFull],
  model: Option[String] = None,
  usage: Option[AnthropicUsage] = None,
  stop_reason: Option[String] = None,
) derives JsonCodec
