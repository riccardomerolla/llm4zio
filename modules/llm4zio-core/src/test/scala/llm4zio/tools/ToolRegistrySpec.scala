package llm4zio.tools

import zio.*
import zio.json.ast.Json
import zio.test.*

import llm4zio.core.*

object ToolRegistrySpec extends ZIOSpecDefault:
  private val addTool =
    Tool(
      name = "add",
      description = "Add two integers",
      parameters = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "a" -> Json.Obj("type" -> Json.Str("integer")),
          "b" -> Json.Obj("type" -> Json.Str("integer")),
        ),
        "required"   -> Json.Arr(Chunk(Json.Str("a"), Json.Str("b"))),
      ),
      tags = Set("math", "sum"),
      execute = args =>
        args match
          case Json.Obj(fields) =>
            val map = fields.toMap
            (map.get("a"), map.get("b")) match
              case (Some(Json.Num(a)), Some(Json.Num(b))) => ZIO.succeed(Json.Num(a.add(b)))
              case _                                      => ZIO.fail(ToolExecutionError.InvalidParameters("a and b are required integers"))
          case _                => ZIO.fail(ToolExecutionError.InvalidParameters("arguments must be an object")),
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ToolRegistry")(
    test("register and get tool") {
      for
        registry  <- ToolRegistry.make
        _         <- registry.register(addTool)
        retrieved <- registry.get("add")
      yield assertTrue(retrieved.name == "add")
    },
    test("list registered tools") {
      val echoTool = Tool(
        name = "echo",
        description = "Echo payload",
        parameters = Json.Obj("type" -> Json.Str("object")),
        execute = args => ZIO.succeed(args),
      )

      for
        registry <- ToolRegistry.make
        _        <- registry.register(addTool)
        _        <- registry.register(echoTool)
        tools    <- registry.list
      yield assertTrue(tools.map(_.name).toSet == Set("add", "echo"))
    },
    test("execute tool call returns JSON result") {
      val call = ToolCall(id = "1", name = "add", arguments = """{"a": 1, "b": 2}""")

      for
        registry <- ToolRegistry.make
        _        <- registry.register(addTool)
        result   <- registry.execute(call)
      yield assertTrue(
        result.toolCallId == "1",
        result.result == Right(Json.Num(3)),
      )
    },
    test("validate required parameter errors") {
      val call = ToolCall(id = "2", name = "add", arguments = """{"a": 1}""")

      for
        registry <- ToolRegistry.make
        _        <- registry.register(addTool)
        result   <- registry.execute(call).either
      yield assertTrue(result == Left(LlmError.ToolError("add", "Missing required parameters: b")))
    },
    test("select dynamically based on prompt") {
      val fileTool = Tool(
        name = "read_file",
        description = "Read content from workspace file",
        parameters = Json.Obj("type" -> Json.Str("object")),
        tags = Set("file", "read"),
        execute = _ => ZIO.succeed(Json.Str("ok")),
      )

      for
        registry <- ToolRegistry.make
        _        <- registry.register(addTool)
        _        <- registry.register(fileTool)
        selected <- registry.select("please read file and summarize")
      yield assertTrue(selected.headOption.exists(_.name == "read_file"))
    },
    test("fail when tool not found") {
      for
        registry <- ToolRegistry.make
        result   <- registry.get("missing").either
      yield assertTrue(result == Left(LlmError.ToolError("missing", "Tool not found: missing")))
    },
  )
