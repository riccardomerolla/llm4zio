package llm4zio.observability

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object LlmLogger:
  // Aspect that adds logging to LlmService operations
  def logged(service: LlmService): LlmService = new LlmService:
    override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
      ZStream.logInfo("LLM stream request") *>
        service.executeStream(prompt).tap { chunk =>
          if chunk.finishReason.isDefined then
            ZIO.logInfo(s"LLM stream complete (finish_reason=${chunk.finishReason.get})")
          else ZIO.unit
        }

    override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
      ZStream.logInfo(s"LLM stream history request (message_count=${messages.length})") *>
        service.executeStreamWithHistory(messages)

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      for {
        _      <- ZIO.logInfo(s"LLM tool request (tool_count=${tools.length})")
        result <- service.executeWithTools(prompt, tools)
        _      <- ZIO.logInfo(s"LLM tool response (tool_calls=${result.toolCalls.length})")
      } yield result

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      for {
        _      <- ZIO.logInfo("LLM structured request")
        result <- service.executeStructured(prompt, schema)
        _      <- ZIO.logInfo("LLM structured response")
      } yield result

    override def isAvailable: UIO[Boolean] =
      service.isAvailable
