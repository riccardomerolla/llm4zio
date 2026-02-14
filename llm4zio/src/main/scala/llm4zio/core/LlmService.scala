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

  // Factory layer that creates LlmService based on LlmConfig
  val fromConfig: ZLayer[LlmConfig & llm4zio.providers.HttpClient & llm4zio.providers.GeminiCliExecutor, Nothing, LlmService] =
    ZLayer.fromZIO {
      for
        config   <- ZIO.service[LlmConfig]
        http     <- ZIO.service[llm4zio.providers.HttpClient]
        cliExec  <- ZIO.service[llm4zio.providers.GeminiCliExecutor]
      yield new LlmService {
        private def buildProvider(cfg: LlmConfig): LlmService =
          import llm4zio.providers.*
          cfg.provider match
            case LlmProvider.GeminiCli => GeminiCliProvider.make(cfg, cliExec)
            case LlmProvider.GeminiApi => GeminiApiProvider.make(cfg, http)
            case LlmProvider.OpenAI    => OpenAIProvider.make(cfg, http)
            case LlmProvider.Anthropic => AnthropicProvider.make(cfg, http)
            case LlmProvider.LmStudio  => LmStudioProvider.make(cfg, http)
            case LlmProvider.Ollama    => OllamaProvider.make(cfg, http)
            case LlmProvider.OpenCode  => OpenCodeProvider.make(cfg, http)

        private val provider = buildProvider(config)

        override def execute(prompt: String): IO[LlmError, LlmResponse] =
          provider.execute(prompt)

        override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
          provider.executeStream(prompt)

        override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
          provider.executeWithHistory(messages)

        override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
          provider.executeStreamWithHistory(messages)

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          provider.executeWithTools(prompt, tools)

        override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          provider.executeStructured(prompt, schema)

        override def isAvailable: UIO[Boolean] =
          provider.isAvailable
      }
    }
