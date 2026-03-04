package llm4zio.mcp.server

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*

import llm4zio.mcp.jsonrpc.*
import llm4zio.mcp.protocol.*
import llm4zio.tools.{ Tool, ToolExecutionError, ToolRegistry }

object McpServerSpec extends ZIOSpecDefault:

  /** In-memory transport for testing: push requests in, collect responses out. */
  def makeTestTransport: UIO[(InMemoryTransport, McpTransport)] =
    for
      inQ  <- Queue.unbounded[JsonRpcRequest]
      outQ <- Queue.unbounded[Either[JsonRpcNotification, JsonRpcResponse]]
    yield
      val transport = new InMemoryTransport(inQ, outQ)
      (transport, transport)

  def spec = suite("McpServer")(
    suite("initialize")(
      test("responds with server capabilities including tools") {
        for
          (transport, mcpTransport) <- makeTestTransport
          registry                  <- ToolRegistry.make
          server                    <- McpServer.make(registry, mcpTransport)
          _                         <- server.start.fork
          req                        = JsonRpcRequest(
                                         id = Some(RequestId.Num(1L)),
                                         method = "initialize",
                                         params = Some(
                                           McpInitializeRequest(
                                             protocolVersion = "2024-11-05",
                                             clientInfo = ClientInfo("test", "1.0"),
                                           ).toJson.fromJson[Json].toOption.get
                                         ),
                                       )
          _                         <- transport.push(req)
          resp                      <- transport.nextResponse
        yield assertTrue(
          resp.error.isEmpty,
          resp.result.isDefined,
        )
      },
    ),
    suite("tools/list")(
      test("returns empty list when no tools registered") {
        for
          (transport, mcpTransport) <- makeTestTransport
          registry                  <- ToolRegistry.make
          server                    <- McpServer.make(registry, mcpTransport)
          _                         <- server.start.fork
          req                        = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "tools/list")
          _                         <- transport.push(req)
          resp                      <- transport.nextResponse
          result                    <- ZIO.fromEither(resp.result.get.as[McpToolsListResponse])
        yield assertTrue(result.tools.isEmpty)
      },
      test("returns registered tools") {
        for
          (transport, mcpTransport) <- makeTestTransport
          registry                  <- ToolRegistry.make
          _                         <- registry.register(
                                         Tool(
                                           name = "ping",
                                           description = "health check",
                                           parameters = Json.Obj(),
                                           execute = _ => ZIO.succeed(Json.Obj()),
                                         )
                                       )
          server                    <- McpServer.make(registry, mcpTransport)
          _                         <- server.start.fork
          req                        = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "tools/list")
          _                         <- transport.push(req)
          resp                      <- transport.nextResponse
          result                    <- ZIO.fromEither(resp.result.get.as[McpToolsListResponse])
        yield assertTrue(result.tools.exists(_.name == "ping"))
      },
    ),
    suite("tools/call")(
      test("executes registered tool and returns text content") {
        for
          (transport, mcpTransport) <- makeTestTransport
          registry                  <- ToolRegistry.make
          _                         <- registry.register(
                                         Tool(
                                           name = "echo",
                                           description = "echoes input",
                                           parameters = Json.Obj(
                                             "type"       -> Json.Str("object"),
                                             "properties" -> Json.Obj("msg" -> Json.Obj("type" -> Json.Str("string"))),
                                           ),
                                           execute = args =>
                                             ZIO.succeed(Json.Obj("echoed" -> args)),
                                         )
                                       )
          server                    <- McpServer.make(registry, mcpTransport)
          _                         <- server.start.fork
          callParams                 = McpToolCallRequest(
                                         name = "echo",
                                         arguments = Json.Obj("msg" -> Json.Str("hello")),
                                       ).toJson.fromJson[Json].toOption.get
          req                        = JsonRpcRequest(
                                         id = Some(RequestId.Num(1L)),
                                         method = "tools/call",
                                         params = Some(callParams),
                                       )
          _                         <- transport.push(req)
          resp                      <- transport.nextResponse
          result                    <- ZIO.fromEither(resp.result.get.as[McpToolCallResponse])
        yield assertTrue(!result.isError, result.content.nonEmpty)
      },
      test("returns error content for unknown tool") {
        for
          (transport, mcpTransport) <- makeTestTransport
          registry                  <- ToolRegistry.make
          server                    <- McpServer.make(registry, mcpTransport)
          _                         <- server.start.fork
          callParams                 = McpToolCallRequest(name = "unknown", arguments = Json.Obj())
                                         .toJson.fromJson[Json].toOption.get
          req                        = JsonRpcRequest(
                                         id = Some(RequestId.Num(1L)),
                                         method = "tools/call",
                                         params = Some(callParams),
                                       )
          _                         <- transport.push(req)
          resp                      <- transport.nextResponse
        yield assertTrue(resp.error.exists(_.code == -32602))
      },
    ),
    suite("unknown method")(
      test("returns method not found error") {
        for
          (transport, mcpTransport) <- makeTestTransport
          registry                  <- ToolRegistry.make
          server                    <- McpServer.make(registry, mcpTransport)
          _                         <- server.start.fork
          req                        = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "nonexistent")
          _                         <- transport.push(req)
          resp                      <- transport.nextResponse
        yield assertTrue(resp.error.exists(_.code == -32601))
      },
    ),
    suite("addTool")(
      test("registers tool and emits tools/list_changed notification") {
        for
          (transport, mcpTransport) <- makeTestTransport
          registry                  <- ToolRegistry.make
          server                    <- McpServer.make(registry, mcpTransport)
          _                         <- server.start.fork
          _                         <- server.addTool(
                                         Tool(
                                           name = "new_tool",
                                           description = "added at runtime",
                                           parameters = Json.Obj(),
                                           execute = _ => ZIO.succeed(Json.Obj()),
                                         )
                                       )
          notification              <- transport.nextNotification
        yield assertTrue(notification.method == "notifications/tools/list_changed")
      },
    ),
  )

/** Test double: in-memory McpTransport. */
class InMemoryTransport(
  inQ: Queue[JsonRpcRequest],
  outQ: Queue[Either[JsonRpcNotification, JsonRpcResponse]],
) extends McpTransport:

  def push(req: JsonRpcRequest): UIO[Unit] = inQ.offer(req).unit

  def nextResponse: UIO[JsonRpcResponse] =
    outQ.take.flatMap {
      case Right(resp) => ZIO.succeed(resp)
      case Left(_)     => nextResponse // skip notifications
    }

  def nextNotification: UIO[JsonRpcNotification] =
    outQ.take.flatMap {
      case Left(n) => ZIO.succeed(n)
      case Right(_) => nextNotification // skip responses
    }

  override def receive: ZStream[Any, McpError, JsonRpcRequest] =
    ZStream.fromQueue(inQ)

  override def send(response: JsonRpcResponse): IO[McpError, Unit] =
    outQ.offer(Right(response)).unit

  override def notify(notification: JsonRpcNotification): IO[McpError, Unit] =
    outQ.offer(Left(notification)).unit
