package llm4zio.providers

import zio.json.*
import zio.json.ast.Json

// OpenAI API request/response models
case class JsonSchemaSpec(
  name: String,
  schema: Json,
) derives JsonCodec

case class ResponseFormat(
  `type`: String,
  json_schema: Option[JsonSchemaSpec] = None,
) derives JsonCodec

case class ChatCompletionRequest(
  model: String,
  messages: List[ChatMessage],
  temperature: Option[Double] = None,
  max_tokens: Option[Int] = None,
  max_completion_tokens: Option[Int] = None,
  stream: Option[Boolean] = None,
  response_format: Option[ResponseFormat] = None,
) derives JsonCodec

case class ChatMessage(role: String, content: String) derives JsonCodec

case class ChatCompletionResponse(
  id: Option[String] = None,
  choices: List[ChatChoice],
  usage: Option[OpenAITokenUsage] = None,
  model: Option[String] = None,
) derives JsonCodec

case class ChatChoice(
  index: Int = 0,
  message: Option[ChatMessage] = None,
  text: Option[String] = None,
  finish_reason: Option[String] = None,
) derives JsonCodec

case class OpenAITokenUsage(
  prompt_tokens: Option[Int] = None,
  completion_tokens: Option[Int] = None,
  total_tokens: Option[Int] = None,
) derives JsonCodec

// Tool calling DTOs
case class OpenAIFunction(
  name: String,
  description: String,
  parameters: zio.json.ast.Json,
) derives JsonCodec

case class OpenAITool(
  `type`: String = "function",
  function: OpenAIFunction,
) derives JsonCodec

case class OpenAIToolCall(
  id: String,
  `type`: String,
  function: OpenAIToolCallFunction,
) derives JsonCodec

case class OpenAIToolCallFunction(
  name: String,
  arguments: String,
) derives JsonCodec

case class ChatMessageWithTools(
  role: String,
  content: Option[String] = None,
  tool_calls: Option[List[OpenAIToolCall]] = None,
) derives JsonCodec

case class ChatChoiceWithTools(
  index: Int = 0,
  message: Option[ChatMessageWithTools] = None,
  finish_reason: Option[String] = None,
) derives JsonCodec

case class ChatCompletionResponseWithTools(
  id: Option[String] = None,
  choices: List[ChatChoiceWithTools],
  usage: Option[OpenAITokenUsage] = None,
  model: Option[String] = None,
) derives JsonCodec

case class ChatCompletionRequestWithTools(
  model: String,
  messages: List[ChatMessage],
  tools: List[OpenAITool],
  temperature: Option[Double] = None,
  max_tokens: Option[Int] = None,
  stream: Option[Boolean] = Some(false),
) derives JsonCodec
