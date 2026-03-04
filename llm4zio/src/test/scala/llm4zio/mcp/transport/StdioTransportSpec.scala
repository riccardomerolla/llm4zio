package llm4zio.mcp.transport

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

import llm4zio.mcp.jsonrpc.*
import llm4zio.mcp.server.McpError

object StdioTransportSpec extends ZIOSpecDefault:
  def spec = suite("StdioTransport")(
    suite("receive")(
      test("reads newline-delimited JSON-RPC requests from input stream") {
        val req1 = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "tools/list")
        val req2 = JsonRpcRequest(id = Some(RequestId.Num(2L)), method = "ping")
        val input = (req1.toJson + "\n" + req2.toJson + "\n").getBytes("UTF-8")
        val in    = new ByteArrayInputStream(input)
        val out   = new ByteArrayOutputStream()

        val transport = StdioTransport.fromStreams(in, out)
        for
          requests <- transport.receive.take(2).runCollect
        yield assertTrue(
          requests(0).method == "tools/list",
          requests(1).method == "ping",
        )
      },
      test("skips blank lines in input") {
        val req   = JsonRpcRequest(id = Some(RequestId.Num(1L)), method = "ping")
        val input = ("\n\n" + req.toJson + "\n").getBytes("UTF-8")
        val in    = new ByteArrayInputStream(input)
        val out   = new ByteArrayOutputStream()

        val transport = StdioTransport.fromStreams(in, out)
        for
          requests <- transport.receive.take(1).runCollect
        yield assertTrue(requests(0).method == "ping")
      },
      test("terminates stream on EOF") {
        val in        = new ByteArrayInputStream(Array.empty)
        val out       = new ByteArrayOutputStream()
        val transport = StdioTransport.fromStreams(in, out)
        for
          count <- transport.receive.runCount
        yield assertTrue(count == 0L)
      },
    ),
    suite("send")(
      test("writes response as newline-terminated JSON to output stream") {
        val in        = new ByteArrayInputStream(Array.empty)
        val out       = new ByteArrayOutputStream()
        val transport = StdioTransport.fromStreams(in, out)
        val resp      = JsonRpcResponse.success(RequestId.Num(1L), zio.json.ast.Json.Obj())
        for
          _      <- transport.send(resp)
          written = out.toString("UTF-8")
        yield assertTrue(written.endsWith("\n"), written.fromJson[JsonRpcResponse].isRight)
      },
    ),
    suite("notify")(
      test("writes notification as newline-terminated JSON to output stream") {
        val in        = new ByteArrayInputStream(Array.empty)
        val out       = new ByteArrayOutputStream()
        val transport = StdioTransport.fromStreams(in, out)
        val notif     = JsonRpcNotification(method = "notifications/initialized")
        for
          _      <- transport.notify(notif)
          written = out.toString("UTF-8")
        yield assertTrue(written.endsWith("\n"), written.contains("notifications/initialized"))
      },
    ),
  )
