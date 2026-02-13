package llm4zio.core

import zio.*
import zio.test.*
import zio.stream.*
import zio.json.*
import llm4zio.tools.{AnyTool, JsonSchema}

object LlmServiceSpec extends ZIOSpecDefault:
  // Mock implementation for testing
  class MockLlmService extends LlmService:
    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(content = s"Response to: $prompt"))

    override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
      ZStream(
        LlmChunk(delta = "Hello", finishReason = None),
        LlmChunk(delta = " world", finishReason = Some("stop"))
      )

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(content = s"Response to ${messages.length} messages"))

    override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
      ZStream(LlmChunk(delta = "Stream response", finishReason = Some("stop")))

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(content = Some("No tools needed"), toolCalls = List.empty, finishReason = "stop"))

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("Not implemented in mock"))

    override def isAvailable: UIO[Boolean] =
      ZIO.succeed(true)

  def spec = suite("LlmService")(
    test("execute should return response") {
      val service = new MockLlmService()
      for {
        response <- service.execute("test prompt")
      } yield assertTrue(response.content == "Response to: test prompt")
    },
    test("executeStream should return chunks") {
      val service = new MockLlmService()
      for {
        chunks <- service.executeStream("test").runCollect
      } yield assertTrue(
        chunks.size == 2,
        chunks(0).delta == "Hello",
        chunks(1).finishReason == Some("stop")
      )
    },
    test("executeWithHistory should handle messages") {
      val service = new MockLlmService()
      val messages = List(
        Message(MessageRole.User, "Hello"),
        Message(MessageRole.Assistant, "Hi there")
      )
      for {
        response <- service.executeWithHistory(messages)
      } yield assertTrue(response.content.contains("2 messages"))
    },
    test("isAvailable should return true for mock") {
      val service = new MockLlmService()
      for {
        available <- service.isAvailable
      } yield assertTrue(available)
    },
    test("ZIO service accessors should work") {
      for {
        response <- LlmService.execute("test")
      } yield assertTrue(response.content.nonEmpty)
    }.provide(ZLayer.succeed[LlmService](new MockLlmService()))
  )
