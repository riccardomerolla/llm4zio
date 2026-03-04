package llm4zio.mcp.jsonrpc

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

object JsonRpcSpec extends ZIOSpecDefault:
  def spec = suite("JsonRpc")(
    suite("RequestId")(
      test("encodes and decodes String id") {
        val id   = RequestId.Str("abc-123")
        val json = id.toJson
        assertTrue(json.fromJson[RequestId].contains(RequestId.Str("abc-123")))
      },
      test("encodes and decodes Long id") {
        val id   = RequestId.Num(42L)
        val json = id.toJson
        assertTrue(json.fromJson[RequestId].contains(RequestId.Num(42L)))
      },
    ),
    suite("JsonRpcRequest")(
      test("decodes request with string id") {
        val json = """{"jsonrpc":"2.0","id":"1","method":"tools/list","params":null}"""
        assertTrue(
          json.fromJson[JsonRpcRequest].map(r =>
            r.id.contains(RequestId.Str("1")) && r.method == "tools/list"
          ).contains(true)
        )
      },
      test("decodes notification without id") {
        val json = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        assertTrue(
          json.fromJson[JsonRpcRequest].map(r => r.id.isEmpty).contains(true)
        )
      },
      test("encodes request to valid JSON") {
        val req  = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "tools/list", params = None)
        val json = req.toJson
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""), json.contains("\"method\":\"tools/list\""))
      },
    ),
    suite("JsonRpcResponse")(
      test("encodes success response") {
        val resp = JsonRpcResponse.success(RequestId.Num(1L), """{"ok":true}""".fromJson[zio.json.ast.Json].toOption.get)
        val json = resp.toJson
        assertTrue(json.contains("\"result\""), !json.contains("\"error\""))
      },
      test("encodes error response") {
        val resp = JsonRpcResponse.error(RequestId.Num(1L), JsonRpcError.methodNotFound("tools/unknown"))
        val json = resp.toJson
        assertTrue(json.contains("\"error\""), !json.contains("\"result\""))
      },
      test("decodes success response") {
        val json = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""
        assertTrue(json.fromJson[JsonRpcResponse].isRight)
      },
    ),
    suite("JsonRpcError standard codes")(
      test("parse error is -32700") {
        assertTrue(JsonRpcError.parseError("bad json").code == -32700)
      },
      test("invalid request is -32600") {
        assertTrue(JsonRpcError.invalidRequest("missing method").code == -32600)
      },
      test("method not found is -32601") {
        assertTrue(JsonRpcError.methodNotFound("foo").code == -32601)
      },
      test("invalid params is -32602") {
        assertTrue(JsonRpcError.invalidParams("missing name").code == -32602)
      },
      test("internal error is -32603") {
        assertTrue(JsonRpcError.internalError("crash").code == -32603)
      },
    ),
    suite("JsonRpcNotification")(
      test("encodes notification without id") {
        val n    = JsonRpcNotification(method = "notifications/initialized", params = None)
        val json = n.toJson
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""), !json.contains("\"id\""))
      },
    ),
    suite("JsonRpcHandler")(
      test("routes method to handler and returns response") {
        val handler = JsonRpcHandler.of {
          case req if req.method == "ping" =>
            ZIO.succeed(JsonRpcResponse.success(req.id.getOrElse(RequestId.Num(0L)), zio.json.ast.Json.Obj()))
        }
        val req = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "ping", params = None)
        for
          resp <- handler.handle(req)
        yield assertTrue(resp.error.isEmpty)
      },
      test("returns method not found for unknown method") {
        val handler = JsonRpcHandler.empty
        val req     = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "unknown", params = None)
        for
          resp <- handler.handle(req)
        yield assertTrue(resp.error.exists(_.code == -32601))
      },
    ),
  )
