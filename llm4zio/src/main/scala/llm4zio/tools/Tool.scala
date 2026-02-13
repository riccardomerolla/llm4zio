package llm4zio.tools

import zio.*
import zio.json.*
import zio.json.ast.Json

case class Tool[R, E, A](
  name: String,
  description: String,
  parameters: Json,
  execute: Json => ZIO[R, E, A],
)

// Type alias for tools used in LlmService API
type AnyTool = Tool[Any, Any, Any]

case class ToolResult(
  toolCallId: String,
  result: Either[String, Json],
) derives JsonCodec

type JsonSchema = Json
