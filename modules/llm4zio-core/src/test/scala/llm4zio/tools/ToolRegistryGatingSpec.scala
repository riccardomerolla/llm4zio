package llm4zio.tools

import zio.*
import zio.json.JsonCodec
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

import llm4zio.core.*

/** Model-facing capability enforcement (issue #716): a tool declares what it `requires`; `ToolRegistry.execute` checks
  * the ambient [[Grants]] and returns denials in the value channel (the model probing a boundary is expected — the loop
  * must continue), with an optional denial budget that aborts a probe loop.
  */
object ToolRegistryGatingSpec extends ZIOSpecDefault:

  private val emptySchema = Json.Obj(
    "type"                 -> Json.Str("object"),
    "properties"           -> Json.Obj(),
    "required"             -> Json.Arr(),
    "additionalProperties" -> Json.Bool(false),
  )

  private def tool(name: String, requires: Set[Capability], ran: Ref[Int]): Tool =
    Tool(
      name = name,
      description = s"test tool $name",
      parameters = emptySchema,
      execute = _ => ran.update(_ + 1).as(Json.Str("done")),
      requires = requires,
    )

  /** Always asks for the same tool call — a model stuck probing a denied capability. */
  final private class ProbingService(calls: Ref[Int]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      calls.update(_ + 1).as(ToolCallResponse(None, List(ToolCall("1", "push", "{}")), "tool_calls"))
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  /** Asks for the denied tool once, then finishes. */
  final private class OnceThenDoneService(calls: Ref[Int]) extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      calls.getAndUpdate(_ + 1).map { n =>
        if n == 0 then ToolCallResponse(None, List(ToolCall("1", "push", "{}")), "tool_calls")
        else ToolCallResponse(Some("done"), Nil, "stop")
      }
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ToolRegistry gating")(
    test("a call requiring an ungranted capability is denied in the value channel; the tool body never runs") {
      for
        ran      <- Ref.make(0)
        registry <- ToolRegistry.make
        _        <- registry.register(tool("push", Set(Capability.GitPush), ran))
        result   <- Grants.restricted(Grants.none)(registry.execute(ToolCall("1", "push", "{}")))
        runs     <- ran.get
      yield assertTrue(
        result.denied,
        result.result.fold(msg => msg.contains("GitPush"), _ => false),
        runs == 0,
      )
    },
    test("a call whose requirements are granted executes normally") {
      for
        ran      <- Ref.make(0)
        registry <- ToolRegistry.make
        _        <- registry.register(tool("push", Set(Capability.GitPush), ran))
        result   <- registry.execute(ToolCall("1", "push", "{}"))
        runs     <- ran.get
      yield assertTrue(!result.denied, result.result.isRight, runs == 1)
    },
    test("an ungated tool runs even under Grants.none") {
      for
        ran      <- Ref.make(0)
        registry <- ToolRegistry.make
        _        <- registry.register(tool("free", Set.empty, ran))
        result   <- Grants.restricted(Grants.none)(registry.execute(ToolCall("1", "free", "{}")))
        runs     <- ran.get
      yield assertTrue(!result.denied, result.result.isRight, runs == 1)
    },
    test("the denial budget aborts a probe loop after N denials") {
      for
        ran      <- Ref.make(0)
        calls    <- Ref.make(0)
        registry <- ToolRegistry.make
        _        <- registry.register(tool("push", Set(Capability.GitPush), ran))
        theTool  <- registry.get("push")
        result   <- Grants.restricted(Grants.none) {
                      ToolCallingExecutor.run(
                        prompt = "go",
                        tools = List(theTool),
                        llmService = new ProbingService(calls),
                        registry = registry,
                        config = ToolLoopConfig(maxIterations = 10, maxDenials = Some(2)),
                      )
                    }.either
        runs     <- ran.get
      yield assertTrue(
        result.isLeft,
        result.left.toOption.exists(_.message.toLowerCase.contains("denial")),
        runs == 0,
      )
    },
    test("without a budget, a denial is reported to the model and the loop continues to completion") {
      for
        ran      <- Ref.make(0)
        calls    <- Ref.make(0)
        registry <- ToolRegistry.make
        _        <- registry.register(tool("push", Set(Capability.GitPush), ran))
        theTool  <- registry.get("push")
        result   <- Grants.restricted(Grants.none) {
                      ToolCallingExecutor.run(
                        prompt = "go",
                        tools = List(theTool),
                        llmService = new OnceThenDoneService(calls),
                        registry = registry,
                      )
                    }
        runs     <- ran.get
      yield assertTrue(result.content == "done", runs == 0)
    },
  )
