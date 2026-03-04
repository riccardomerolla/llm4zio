package mcp

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.mcp.jsonrpc.JsonRpcRequest
import llm4zio.mcp.transport.SseTransport

/** HTTP routes for the MCP SSE transport.
  *
  * - `GET  /mcp/sse`            — open SSE stream; creates a session, sends outbound messages
  * - `POST /mcp?sessionId=<id>` — receive a JSON-RPC request from the client
  *
  * Authentication: if an API key is configured, the client must supply it via the `X-Api-Key` header.
  */
final class McpController(transport: SseTransport):

  val routes: Routes[Any, Response] = Routes(
    Method.GET / "mcp" / "sse" -> handler { (req: Request) => sseHandler(req) },
    Method.POST / "mcp"        -> handler { (req: Request) => postHandler(req) },
  )

  // ── POST /mcp ─────────────────────────────────────────────────────────────

  private def postHandler(req: Request): UIO[Response] =
    val providedKey = req.rawHeader("X-Api-Key")
    if !transport.validateKey(providedKey) then
      ZIO.succeed(Response.status(Status.Unauthorized))
    else
      req.queryParam("sessionId") match
        case None            => ZIO.succeed(Response.status(Status.BadRequest))
        case Some(sessionId) =>
          transport.sessions.exists(sessionId).flatMap {
            case false => ZIO.succeed(Response.status(Status.NotFound))
            case true  =>
              req.body.asString.orDie.flatMap { body =>
                body.fromJson[JsonRpcRequest] match
                  case Left(_)    => ZIO.succeed(Response.status(Status.BadRequest))
                  case Right(rpc) => transport.accept(rpc).as(Response.status(Status.Accepted))
              }
          }

  // ── GET /mcp/sse ──────────────────────────────────────────────────────────

  private def sseHandler(req: Request): UIO[Response] =
    val providedKey = req.rawHeader("X-Api-Key")
    if !transport.validateKey(providedKey) then
      ZIO.succeed(Response.status(Status.Unauthorized))
    else
      transport.sessions.create.map { sessionId =>
        val stream: ZStream[Any, Nothing, String] = transport.sessions
          .stream(sessionId)
          .map {
            case Left(notif) => s"event: message\ndata: ${notif.toJson}\n\n"
            case Right(resp) => s"event: message\ndata: ${resp.toJson}\n\n"
          }
        Response(
          status = Status.Ok,
          headers = Headers(
            Header.ContentType(MediaType.text.`event-stream`),
            Header.CacheControl.NoCache,
            Header.Custom("Connection", "keep-alive"),
          ),
          body = Body.fromCharSequenceStreamChunked(stream),
        )
      }
