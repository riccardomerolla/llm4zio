package llm4zio.mcp.protocol

import zio.json.*
import zio.json.ast.Json

import llm4zio.core.ToolCall
import llm4zio.mcp.jsonrpc.JsonRpcNotification
import llm4zio.tools.Tool

// ---------------------------------------------------------------------------
// Initialize
// ---------------------------------------------------------------------------

case class ClientInfo(name: String, version: String) derives JsonCodec
case class ClientCapabilities(roots: Option[Json] = None) derives JsonCodec

case class McpInitializeRequest(
  protocolVersion: String,
  capabilities: ClientCapabilities = ClientCapabilities(),
  clientInfo: ClientInfo,
) derives JsonCodec

case class ToolsCapability(listChanged: Boolean = true) derives JsonCodec
case class ResourcesCapability(subscribe: Boolean = false) derives JsonCodec
case class PromptsCapability(listChanged: Boolean = false) derives JsonCodec

case class ServerCapabilities(
  tools: Option[ToolsCapability] = None,
  resources: Option[ResourcesCapability] = None,
  prompts: Option[PromptsCapability] = None,
) derives JsonCodec

case class ServerInfo(name: String, version: String) derives JsonCodec

case class McpInitializeResponse(
  protocolVersion: String,
  capabilities: ServerCapabilities,
  serverInfo: ServerInfo,
) derives JsonCodec

// ---------------------------------------------------------------------------
// Tools list
// ---------------------------------------------------------------------------

case class McpToolInfo(
  name: String,
  description: String,
  inputSchema: Json,
) derives JsonCodec

object McpToolInfo:
  def fromTool(tool: Tool): McpToolInfo =
    McpToolInfo(name = tool.name, description = tool.description, inputSchema = tool.parameters)

case class McpToolsListResponse(tools: List[McpToolInfo]) derives JsonCodec

// ---------------------------------------------------------------------------
// Tool call
// ---------------------------------------------------------------------------

case class McpToolCallRequest(name: String, arguments: Json = Json.Obj()) derives JsonCodec

object McpToolCallRequest:
  extension (req: McpToolCallRequest)
    def toToolCall: ToolCall =
      ToolCall(id = s"mcp-${req.name}", name = req.name, arguments = req.arguments.toJson)

// ---------------------------------------------------------------------------
// Content types
// ---------------------------------------------------------------------------

enum McpContent:
  case Text(text: String)
  case Image(data: String, mimeType: String)
  case Resource(uri: String, text: String)

object McpContent:
  given JsonEncoder[McpContent] = JsonEncoder[Json].contramap {
    case McpContent.Text(t)       => Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(t))
    case McpContent.Image(d, m)   =>
      Json.Obj("type" -> Json.Str("image"), "data" -> Json.Str(d), "mimeType" -> Json.Str(m))
    case McpContent.Resource(u, t) =>
      Json.Obj("type" -> Json.Str("resource"), "uri" -> Json.Str(u), "text" -> Json.Str(t))
  }
  given JsonDecoder[McpContent] = JsonDecoder[Json].mapOrFail {
    case Json.Obj(fields) =>
      val m = fields.toMap
      m.get("type") match
        case Some(Json.Str("text"))     =>
          m.get("text").collect { case Json.Str(t) => McpContent.Text(t) }
            .toRight("Missing 'text' field")
        case Some(Json.Str("image"))    =>
          for
            d <- m.get("data").collect { case Json.Str(s) => s }.toRight("Missing 'data'")
            mt <- m.get("mimeType").collect { case Json.Str(s) => s }.toRight("Missing 'mimeType'")
          yield McpContent.Image(d, mt)
        case Some(Json.Str("resource")) =>
          for
            u <- m.get("uri").collect { case Json.Str(s) => s }.toRight("Missing 'uri'")
            t <- m.get("text").collect { case Json.Str(s) => s }.toRight("Missing 'text'")
          yield McpContent.Resource(u, t)
        case _ => Left("Unknown McpContent type")
    case _ => Left("McpContent must be a JSON object")
  }

case class McpToolCallResponse(content: List[McpContent], isError: Boolean = false) derives JsonCodec

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

object McpNotifications:
  val initialized: JsonRpcNotification =
    JsonRpcNotification(method = "notifications/initialized")

  val toolsListChanged: JsonRpcNotification =
    JsonRpcNotification(method = "notifications/tools/list_changed")

  def progress(progressToken: String, current: Int, total: Int): JsonRpcNotification =
    JsonRpcNotification(
      method = "notifications/progress",
      params = Some(Json.Obj(
        "progressToken" -> Json.Str(progressToken),
        "progress"      -> Json.Num(BigDecimal(current)),
        "total"         -> Json.Num(BigDecimal(total)),
      )),
    )
