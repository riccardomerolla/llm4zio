package llm4zio.mcp.transport

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import llm4zio.mcp.jsonrpc.*

object SseTransportSpec extends ZIOSpecDefault:
  def spec = suite("SseTransport")(
    suite("session management")(
      test("creates a new session and returns a session id") {
        for
          sessions <- SseSessionRegistry.make
          id       <- sessions.create
        yield assertTrue(id.nonEmpty)
      },
      test("different sessions get different ids") {
        for
          sessions <- SseSessionRegistry.make
          id1      <- sessions.create
          id2      <- sessions.create
        yield assertTrue(id1 != id2)
      },
      test("closed session is removed from registry") {
        for
          sessions <- SseSessionRegistry.make
          id       <- sessions.create
          _        <- sessions.close(id)
          exists   <- sessions.exists(id)
        yield assertTrue(!exists)
      },
    ),
    suite("send")(
      test("enqueues response to the correct session queue") {
        for
          transport <- SseTransport.make(apiKey = None)
          id        <- transport.sessions.create
          resp       = JsonRpcResponse.success(RequestId.Num(1L), Json.Obj())
          _         <- transport.sendToSession(id, resp)
          outbound  <- transport.sessions.take(id)
        yield assertTrue(outbound.contains(Right(resp)))
      },
    ),
    suite("notify")(
      test("broadcasts notification to all active sessions") {
        for
          transport <- SseTransport.make(apiKey = None)
          id1       <- transport.sessions.create
          id2       <- transport.sessions.create
          notif      = JsonRpcNotification(method = "notifications/tools/list_changed")
          _         <- transport.notify(notif)
          out1      <- transport.sessions.take(id1)
          out2      <- transport.sessions.take(id2)
        yield assertTrue(
          out1.contains(Left(notif)),
          out2.contains(Left(notif)),
        )
      },
    ),
    suite("API key validation")(
      test("accepts request when no API key is configured") {
        for
          transport <- SseTransport.make(apiKey = None)
        yield assertTrue(transport.validateKey(None))
      },
      test("rejects request when key is configured but not provided") {
        for
          transport <- SseTransport.make(apiKey = Some("secret"))
        yield assertTrue(!transport.validateKey(None))
      },
      test("accepts request when correct key is provided") {
        for
          transport <- SseTransport.make(apiKey = Some("secret"))
        yield assertTrue(transport.validateKey(Some("secret")))
      },
    ),
  )
