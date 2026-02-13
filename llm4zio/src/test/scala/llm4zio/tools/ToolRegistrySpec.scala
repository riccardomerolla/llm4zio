package llm4zio.tools

import zio.*
import zio.test.*
import zio.json.*
import zio.json.ast.Json
import llm4zio.core.*

object ToolRegistrySpec extends ZIOSpecDefault:
  def spec = suite("ToolRegistry")(
    test("register and get tool") {
      val tool = Tool(
        name = "greet",
        description = "Greet a user",
        parameters = Json.Obj(),
        execute = (args: Json) => ZIO.succeed("Hello!")
      )

      for {
        registry <- ToolRegistry.make
        _        <- registry.register(tool)
        retrieved <- registry.get("greet")
      } yield assertTrue(retrieved.name == "greet")
    },
    test("list registered tools") {
      val tool1 = Tool("tool1", "desc1", Json.Obj(), (_: Json) => ZIO.succeed("result1"))
      val tool2 = Tool("tool2", "desc2", Json.Obj(), (_: Json) => ZIO.succeed("result2"))

      for {
        registry <- ToolRegistry.make
        _        <- registry.register(tool1)
        _        <- registry.register(tool2)
        tools    <- registry.list
      } yield assertTrue(tools.toSet == Set("tool1", "tool2"))
    },
    test("execute tool call") {
      val tool = Tool(
        name = "add",
        description = "Add two numbers",
        parameters = Json.Obj(),
        execute = (args: Json) => ZIO.succeed(42)
      )
      val call = ToolCall(id = "1", name = "add", arguments = """{"a": 1, "b": 2}""")

      for {
        registry <- ToolRegistry.make
        _        <- registry.register(tool)
        result   <- registry.execute(call)
      } yield assertTrue(result.toolCallId == "1", result.result.isRight)
    },
    test("fail when tool not found") {
      for {
        registry <- ToolRegistry.make
        result   <- registry.get("nonexistent").either
      } yield assertTrue(result.isLeft)
    }
  )
