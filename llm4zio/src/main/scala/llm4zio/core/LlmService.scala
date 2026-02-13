package llm4zio.core

import zio.*
import zio.stream.*
import zio.json.*
import llm4zio.tools.{AnyTool, JsonSchema}

case class ToolCall(id: String, name: String, arguments: String) derives JsonCodec

case class ToolCallResponse(
  content: Option[String],
  toolCalls: List[ToolCall],
  finishReason: String,
) derives JsonCodec

trait LlmService:
  // Basic execution
  def execute(prompt: String): IO[LlmError, LlmResponse]

  // Streaming primitives
  def executeStream(prompt: String): Stream[LlmError, LlmChunk]

  // Conversation support
  def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse]
  def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]

  // Tool calling
  def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse]

  // Structured output
  def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]

  // Health check
  def isAvailable: UIO[Boolean]

object LlmService:
  // ZIO service accessors
  def execute(prompt: String): ZIO[LlmService, LlmError, LlmResponse] =
    ZIO.serviceWithZIO[LlmService](_.execute(prompt))

  def executeStream(prompt: String): ZStream[LlmService, LlmError, LlmChunk] =
    ZStream.serviceWithStream[LlmService](_.executeStream(prompt))

  def executeWithHistory(messages: List[Message]): ZIO[LlmService, LlmError, LlmResponse] =
    ZIO.serviceWithZIO[LlmService](_.executeWithHistory(messages))

  def executeStreamWithHistory(messages: List[Message]): ZStream[LlmService, LlmError, LlmChunk] =
    ZStream.serviceWithStream[LlmService](_.executeStreamWithHistory(messages))

  def executeWithTools(prompt: String, tools: List[AnyTool]): ZIO[LlmService, LlmError, ToolCallResponse] =
    ZIO.serviceWithZIO[LlmService](_.executeWithTools(prompt, tools))

  def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): ZIO[LlmService, LlmError, A] =
    ZIO.serviceWithZIO[LlmService](_.executeStructured(prompt, schema))

  def isAvailable: ZIO[LlmService, Nothing, Boolean] =
    ZIO.serviceWithZIO[LlmService](_.isAvailable)
