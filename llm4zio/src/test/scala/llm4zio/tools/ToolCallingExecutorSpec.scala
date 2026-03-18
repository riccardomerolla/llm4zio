package llm4zio.tools

import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.*

object ToolCallingExecutorSpec extends ZIOSpecDefault:
  final private class LoopMockService extends LlmService:
    private val calls = new AtomicInteger(0)

    private def execute(prompt: String): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(prompt))

    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(execute(prompt).map(r => LlmChunk(r.content, finishReason = Some("stop"))))

    private def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(messages.map(_.content).mkString("\n")))

    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(executeWithHistory(messages).map(r => LlmChunk(r.content, finishReason = Some("stop"))))

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.attempt(calls.getAndIncrement()).orDie.flatMap { current =>
        if current == 0 then
          ZIO.succeed(
            ToolCallResponse(
              content = None,
              toolCalls = List(ToolCall(id = "call-1", name = "echo", arguments = """{"message":"hello"}""")),
              finishReason = "tool_calls",
            )
          )
        else
          ZIO.succeed(ToolCallResponse(content = Some("final answer"), toolCalls = Nil, finishReason = "stop"))
      }

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("not implemented"))

    override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  private val echoTool = Tool(
    name = "echo",
    description = "Echo input",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj("message" -> Json.Obj("type" -> Json.Str("string"))),
      "required"   -> Json.Arr(Chunk(Json.Str("message"))),
    ),
    execute = args => ZIO.succeed(args),
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ToolCallingExecutor")(
    test("execute multi-turn tool call loop until completion") {
      for
        registry <- ToolRegistry.make
        _        <- registry.register(echoTool)
        response <- ToolCallingExecutor.run(
                      prompt = "say hello",
                      tools = List(echoTool),
                      llmService = new LoopMockService,
                      registry = registry,
                    )
      yield assertTrue(response.content == "final answer")
    }
  )
