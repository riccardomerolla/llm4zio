package llm4zio.runner

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.http.*
import zio.json.ast.Json
import zio.{ Scope, ZIO, ZLayer }

import llm4zio.flow.{ McpServer, McpTool }

/** Binds the transport-free [[McpServer]] over HTTP so a CLI coding agent (claude) can reach llm4zio's in-process tools
  * via `--mcp-config`. A single JSON-RPC POST endpoint at `/mcp`; notifications (no `id`) get `202 Accepted`. The
  * server runs in-process (so `ask_user` can call straight into the live terminal [[llm4zio.flow.Interaction]]) and is
  * scoped — it shuts down when the surrounding scope closes.
  */
object McpHttpServer:

  /** Claude namespaces MCP tools as `mcp__<server>__<tool>`; this is what `--allowedTools` expects. */
  def allowedToolName(server: String, tool: String): String = s"mcp__${server}__$tool"

  /** The reference to pass to claude's `--permission-prompt-tool` for the `approve` tool of this server. */
  def permissionPromptToolName(server: String): String = allowedToolName(server, "approve")

  /** The `--mcp-config` payload registering this HTTP server under `server` at the given localhost `port`. */
  def mcpConfigJson(server: String, port: Int): String =
    Json
      .Obj(
        "mcpServers" -> Json.Obj(
          server -> Json.Obj("type" -> Json.Str("http"), "url" -> Json.Str(s"http://127.0.0.1:$port/mcp"))
        )
      )
      .toString

  /** Write [[mcpConfigJson]] to a temp file and return its path (for `--mcp-config <file>`). */
  def writeMcpConfig(server: String, port: Int): ZIO[Any, Throwable, Path] =
    ZIO.attemptBlocking {
      val path = Files.createTempFile("llm4zio-mcp-", ".json")
      Files.write(path, mcpConfigJson(server, port).getBytes(StandardCharsets.UTF_8))
    }

  /** The route table: JSON-RPC over POST `/mcp`; GET `/mcp` is unused (no server→client SSE stream needed). */
  def routes(tools: List[McpTool]): Routes[Any, Response] =
    Routes(
      Method.POST / "mcp" -> handler { (req: Request) =>
        req.body.asString.orDie.flatMap { body =>
          Json.decoder.decodeJson(body) match
            case Left(_)     => ZIO.succeed(Response.json(parseError)) // JSON-RPC -32700
            case Right(json) =>
              McpServer.handle(json, tools).map {
                case Some(j) => Response.json(j.toString)
                case None    => Response.status(Status.Accepted)
              }
        }
      },
      Method.GET / "mcp"  -> handler(Response.status(Status.MethodNotAllowed)),
    )

  /** JSON-RPC parse-error response (id null), for a body that isn't valid JSON. */
  private val parseError: String =
    Json
      .Obj(
        "jsonrpc" -> Json.Str("2.0"),
        "id"      -> Json.Null,
        "error"   -> Json.Obj("code" -> Json.Num(-32700), "message" -> Json.Str("Parse error")),
      )
      .toString

  /** Start the server on an ephemeral localhost port; returns the bound port. Scoped — the server is allocated into the
    * caller's scope and stays up until it closes.
    */
  def serve(tools: List[McpTool]): ZIO[Scope, Throwable, Int] =
    for
      env  <- serverLayer.build
      port <- Server.install(routes(tools)).provideEnvironment(env)
    yield port

  private val serverLayer: ZLayer[Any, Throwable, Server] =
    ZLayer.succeed(Server.Config.default.binding("127.0.0.1", 0)) >>> Server.live
