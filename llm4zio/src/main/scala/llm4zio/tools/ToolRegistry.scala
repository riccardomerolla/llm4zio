package llm4zio.tools

import zio.*
import zio.json.*
import zio.json.ast.Json
import llm4zio.core.*

trait ToolRegistry:
  def register[R, E, A](tool: Tool[R, E, A]): UIO[Unit]
  def get(name: String): IO[LlmError, Tool[Any, Any, Any]]
  def list: UIO[List[String]]
  def execute(call: ToolCall): IO[LlmError, ToolResult]

object ToolRegistry:
  def make: UIO[ToolRegistry] =
    for {
      tools <- Ref.make(Map.empty[String, Tool[Any, Any, Any]])
    } yield new ToolRegistry {
      override def register[R, E, A](tool: Tool[R, E, A]): UIO[Unit] =
        tools.update(_ + (tool.name -> tool.asInstanceOf[Tool[Any, Any, Any]]))

      override def get(name: String): IO[LlmError, Tool[Any, Any, Any]] =
        tools.get.flatMap { toolMap =>
          ZIO.fromOption(toolMap.get(name))
            .mapError(_ => LlmError.ToolError(name, s"Tool not found: $name"))
        }

      override def list: UIO[List[String]] =
        tools.get.map(_.keys.toList)

      override def execute(call: ToolCall): IO[LlmError, ToolResult] =
        for {
          tool <- get(call.name)
          args <- ZIO.fromEither(call.arguments.fromJson[Json])
                    .mapError(err => LlmError.ToolError(call.name, s"Invalid arguments: $err"))
          result <- tool.execute(args)
            .mapBoth(
              err => LlmError.ToolError(call.name, err.toString),
              res => Json.Str(res.toString)
            )
            .either
        } yield ToolResult(
          toolCallId = call.id,
          result = result.left.map(_.toString)
        )
    }

  val layer: ULayer[ToolRegistry] = ZLayer.fromZIO(make)
