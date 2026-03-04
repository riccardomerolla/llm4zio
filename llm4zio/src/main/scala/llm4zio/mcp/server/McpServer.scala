package llm4zio.mcp.server

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

import llm4zio.mcp.jsonrpc.*
import llm4zio.mcp.protocol.*
import llm4zio.tools.{ Tool, ToolRegistry }

// ---------------------------------------------------------------------------
// McpError
// ---------------------------------------------------------------------------

enum McpError:
  case ProtocolError(message: String)
  case TransportError(message: String)
  case ToolExecutionError(message: String)

// ---------------------------------------------------------------------------
// McpTransport — transport-agnostic channel for JSON-RPC messages
// ---------------------------------------------------------------------------

trait McpTransport:
  def receive: ZStream[Any, McpError, JsonRpcRequest]
  def send(response: JsonRpcResponse): IO[McpError, Unit]
  def notify(notification: JsonRpcNotification): IO[McpError, Unit]

// ---------------------------------------------------------------------------
// McpServer
// ---------------------------------------------------------------------------

trait McpServer:
  def start: IO[McpError, Unit]
  def stop: UIO[Unit]
  def addTool(tool: Tool): UIO[Unit]
  def removeTool(name: String): UIO[Unit]

object McpServer:
  def make(registry: ToolRegistry, transport: McpTransport): UIO[McpServer] =
    for
      stopPromise <- Promise.make[Nothing, Unit]
    yield new McpServerLive(registry, transport, stopPromise)

  val layer: ZLayer[ToolRegistry & McpTransport, Nothing, McpServer] =
    ZLayer.fromZIO(
      for
        registry  <- ZIO.service[ToolRegistry]
        transport <- ZIO.service[McpTransport]
        server    <- make(registry, transport)
      yield server
    )

// ---------------------------------------------------------------------------
// McpServerLive
// ---------------------------------------------------------------------------

private class McpServerLive(
  registry: ToolRegistry,
  transport: McpTransport,
  stopPromise: Promise[Nothing, Unit],
) extends McpServer:

  override def start: IO[McpError, Unit] =
    val drain = transport.receive.mapZIO(handleRequest).runDrain
    drain.race(stopPromise.await.as(()))

  override def stop: UIO[Unit] = stopPromise.succeed(()).unit

  override def addTool(tool: Tool): UIO[Unit] =
    registry.register(tool).ignoreLogged *>
      transport.notify(McpNotifications.toolsListChanged).ignore

  override def removeTool(name: String): UIO[Unit] =
    transport.notify(McpNotifications.toolsListChanged).ignore

  private def handleRequest(req: JsonRpcRequest): IO[McpError, Unit] =
    val respondId = req.id.getOrElse(RequestId.Num(0L))
    req.method match
      case "initialize" =>
        val resp = JsonRpcResponse.success(
          respondId,
          McpInitializeResponse(
            protocolVersion = "2024-11-05",
            capabilities = ServerCapabilities(tools = Some(ToolsCapability())),
            serverInfo = ServerInfo("llm4zio-gateway", "1.0"),
          ).toJson.fromJson[Json].toOption.get,
        )
        transport.send(resp) <* transport.notify(McpNotifications.initialized)

      case "tools/list" =>
        registry.list.flatMap { tools =>
          val resp = JsonRpcResponse.success(
            respondId,
            McpToolsListResponse(tools.map(McpToolInfo.fromTool)).toJson.fromJson[Json].toOption.get,
          )
          transport.send(resp)
        }.mapError(e => McpError.TransportError(e.toString))

      case "tools/call" =>
        val callResult = for
          params <- ZIO
                      .fromOption(req.params)
                      .orElseFail(McpError.ProtocolError("Missing params for tools/call"))
          callReq <- ZIO
                       .fromEither(params.as[McpToolCallRequest])
                       .mapError(e => McpError.ProtocolError(s"Invalid tools/call params: $e"))
          tool    <- registry
                       .get(callReq.name)
                       .mapError(e => McpError.ProtocolError(e.toString))
          result  <- tool
                       .execute(callReq.arguments)
                       .mapError(e => McpError.ToolExecutionError(e.message))
          content  = List(McpContent.Text(result.toJson))
          resp     = JsonRpcResponse.success(
                       respondId,
                       McpToolCallResponse(content = content, isError = false)
                         .toJson.fromJson[Json].toOption.get,
                     )
          _       <- transport.send(resp)
        yield ()

        callResult.catchAll {
          case McpError.ProtocolError(msg) =>
            transport.send(
              JsonRpcResponse.error(respondId, JsonRpcError.invalidParams(msg))
            )
          case McpError.ToolExecutionError(msg) =>
            val errorContent = List(McpContent.Text(s"Tool execution failed: $msg"))
            transport.send(
              JsonRpcResponse.success(
                respondId,
                McpToolCallResponse(content = errorContent, isError = true)
                  .toJson.fromJson[Json].toOption.get,
              )
            )
          case e =>
            transport.send(
              JsonRpcResponse.error(respondId, JsonRpcError.internalError(e.toString))
            )
        }

      case "ping" =>
        transport.send(JsonRpcResponse.success(respondId, Json.Obj()))

      case unknown =>
        transport.send(
          JsonRpcResponse.error(respondId, JsonRpcError.methodNotFound(unknown))
        )
