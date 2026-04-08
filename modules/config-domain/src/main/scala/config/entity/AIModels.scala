package config.entity

import zio.json.*
import zio.json.ast.Json

enum ModelCapability derives JsonCodec:
  case Chat, Streaming, ToolCalling, StructuredOutput, Embeddings

case class AIModel(
  provider: AIProvider,
  modelId: String,
  displayName: String,
  contextWindow: Int,
  capabilities: Set[ModelCapability] = Set(ModelCapability.Chat),
  costPerMillionInputUsd: Option[Double] = None,
  costPerMillionOutputUsd: Option[Double] = None,
) derives JsonCodec

case class ModelRef(
  provider: Option[AIProvider],
  modelId: String,
) derives JsonCodec

case class ModelFallbackChain(models: List[ModelRef]) derives JsonCodec:
  def nonEmpty: Boolean = models.nonEmpty

object ModelFallbackChain:
  val empty: ModelFallbackChain = ModelFallbackChain(Nil)

case class AgentModelOverride(
  agentName: String,
  model: ModelRef,
) derives JsonCodec

// Structured output schema
case class ResponseSchema(
  name: String,
  schema: Json,
)

object ResponseSchema:
  given JsonCodec[ResponseSchema] = DeriveJsonCodec.gen[ResponseSchema]

// OpenAI structured output types
case class JsonSchemaSpec(
  name: String,
  schema: Json,
  strict: Option[Boolean] = Some(true),
) derives JsonCodec

case class ResponseFormat(
  `type`: String,
  json_schema: Option[JsonSchemaSpec] = None,
) derives JsonCodec

// OpenAI-compatible
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

// Gemini API-compatible
case class GeminiGenerationConfig(
  responseMimeType: Option[String] = None,
  responseSchema: Option[Json] = None,
) derives JsonCodec

case class GeminiGenerateContentRequest(
  contents: List[GeminiContent],
  generationConfig: Option[GeminiGenerationConfig] = None,
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
