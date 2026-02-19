package memory

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object EmbeddingServiceSpec extends ZIOSpecDefault:

  private val mockLlmLayer: ULayer[LlmService] =
    ZLayer.succeed(
      new LlmService:
        override def execute(prompt: String): IO[LlmError, LlmResponse] =
          if prompt.contains("second") then ZIO.succeed(LlmResponse(content = "[0.3,0.4]"))
          else ZIO.succeed(LlmResponse(content = "[0.1,0.2]"))

        override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
          ZIO.succeed(LlmResponse(content = "history"))

        override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
          ZStream.empty

        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.succeed(ToolCallResponse(content = Some("ok"), toolCalls = Nil, finishReason = "stop"))

        override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          ZIO.fail(LlmError.InvalidRequestError("unused in test"))

        override def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    )

  private val embeddingLayer: ULayer[EmbeddingService] =
    mockLlmLayer >>> EmbeddingService.live

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("EmbeddingServiceSpec")(
      test("embed parses vector payload") {
        for
          service <- ZIO.service[EmbeddingService]
          vector  <- service.embed("first text")
        yield assertTrue(vector == Vector(0.1f, 0.2f))
      },
      test("embedBatch returns one embedding per text") {
        for
          service <- ZIO.service[EmbeddingService]
          vectors <- service.embedBatch(List("first text", "second text"))
        yield assertTrue(
          vectors.length == 2,
          vectors.head == Vector(0.1f, 0.2f),
          vectors(1) == Vector(0.3f, 0.4f),
        )
      },
    ).provideLayer(embeddingLayer)
