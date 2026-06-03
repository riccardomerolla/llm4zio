package llm4zio.flow

import zio.json.ast.Json
import zio.{ IO, UIO, ZIO }

/** One MCP tool: its declaration (for `tools/list`) plus the effect that runs it (for `tools/call`). `call` receives
  * the raw `arguments` JSON object and returns the text content to hand back to the agent.
  */
final case class McpTool(
  name: String,
  description: String,
  inputSchema: Json,
  call: Json => IO[FlowError, String],
)

/** A minimal, transport-free MCP (Model Context Protocol) JSON-RPC handler. [[handle]] maps one request to an optional
  * response (notifications get none). The runner binds this over HTTP and registers it with a CLI coding agent via
  * `--mcp-config`, exposing llm4zio's [[Interaction]] as an `ask_user` tool. Pure dispatch + a small tool registry, so
  * later tasks add tools (e.g. an approval gate) without touching the protocol layer.
  */
object McpServer:

  /** Negotiated MCP protocol version; we echo the client's if it sends one, else default to this. */
  val protocolVersion: String = "2025-06-18"
  val serverName: String      = "llm4zio"
  val serverVersion: String   = "1.0"

  /** The `ask_user` tool: bridges a coding agent's question to the human operator via [[Interaction]]. */
  def askUserTool(interaction: Interaction): McpTool =
    McpTool(
      name = "ask_user",
      description =
        "Ask the human operator a question and wait for their answer. Use when you need a decision, " +
          "clarification, or information you cannot determine on your own.",
      inputSchema = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj(
          "question" -> Json.Obj(
            "type"        -> Json.Str("string"),
            "description" -> Json.Str("The question to put to the human operator."),
          )
        ),
        "required"   -> Json.Arr(Json.Str("question")),
      ),
      call = args => interaction.ask(str(args, "question").getOrElse("")),
    )

  /** Handle one JSON-RPC request. Returns `None` for notifications (no `id`), `Some(response)` otherwise. Tool-call
    * failures are reported as MCP `isError` results rather than failing the effect, so the agent always gets a reply.
    */
  def handle(request: Json, tools: List[McpTool]): UIO[Option[Json]] =
    field(request, "id") match
      case None     => ZIO.none // notification (e.g. notifications/initialized) — no response
      case Some(id) =>
        str(request, "method").getOrElse("") match
          case "initialize" => ZIO.some(result(id, initializeResult(request)))
          case "tools/list" => ZIO.some(result(id, toolsListResult(tools)))
          case "tools/call" => toolsCall(id, request, tools).asSome
          case other        => ZIO.some(error(id, -32601, s"method not found: $other"))

  private def initializeResult(request: Json): Json =
    val pv = str(params(request), "protocolVersion").getOrElse(protocolVersion)
    Json.Obj(
      "protocolVersion" -> Json.Str(pv),
      "capabilities"    -> Json.Obj("tools" -> Json.Obj()),
      "serverInfo"      -> Json.Obj("name" -> Json.Str(serverName), "version" -> Json.Str(serverVersion)),
    )

  private def toolsListResult(tools: List[McpTool]): Json =
    Json.Obj(
      "tools" -> Json.Arr(
        tools.map { t =>
          Json.Obj("name" -> Json.Str(t.name), "description" -> Json.Str(t.description), "inputSchema" -> t.inputSchema)
        }*
      )
    )

  private def toolsCall(id: Json, request: Json, tools: List[McpTool]): UIO[Json] =
    val p    = params(request)
    val name = str(p, "name").getOrElse("")
    tools.find(_.name == name) match
      case None       => ZIO.succeed(error(id, -32602, s"unknown tool: $name"))
      case Some(tool) =>
        val args = field(p, "arguments").getOrElse(Json.Obj())
        tool.call(args).either.map {
          case Right(text) => result(id, toolContent(text, isError = false))
          case Left(err)   => result(id, toolContent(s"tool '$name' failed: $err", isError = true))
        }

  private def toolContent(text: String, isError: Boolean): Json =
    Json.Obj(
      "content" -> Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(text))),
      "isError" -> Json.Bool(isError),
    )

  private def result(id: Json, res: Json): Json =
    Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> id, "result" -> res)

  private def error(id: Json, code: Int, message: String): Json =
    Json.Obj(
      "jsonrpc" -> Json.Str("2.0"),
      "id"      -> id,
      "error"   -> Json.Obj("code" -> Json.Num(code), "message" -> Json.Str(message)),
    )

  private def params(request: Json): Json = field(request, "params").getOrElse(Json.Obj())

  private def field(j: Json, name: String): Option[Json] = j match
    case Json.Obj(fields) => fields.collectFirst { case (k, v) if k == name => v }
    case _                => None

  private def str(j: Json, name: String): Option[String] = field(j, name).collect { case Json.Str(s) => s }
