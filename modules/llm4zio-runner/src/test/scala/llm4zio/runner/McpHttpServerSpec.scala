package llm4zio.runner

import zio.http.*
import zio.test.*
import zio.{ Scope, ZIO }

import llm4zio.flow.McpServer

object McpHttpServerSpec extends ZIOSpecDefault:

  private val tools = List(McpServer.askUserTool(q => ZIO.succeed(s"you said: $q")))

  private def post(body: String) =
    McpHttpServer.routes(tools).runZIO(Request.post(URL.root / "mcp", Body.fromString(body)))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("McpHttpServer")(
    test("mcpConfigJson registers an http server with the bound port") {
      val js = McpHttpServer.mcpConfigJson("llm4zio", 54321)
      assertTrue(js.contains("\"type\":\"http\""), js.contains("http://127.0.0.1:54321/mcp"))
    },
    test("allowedToolName uses claude's mcp__server__tool namespacing") {
      assertTrue(McpHttpServer.allowedToolName("llm4zio", "ask_user") == "mcp__llm4zio__ask_user")
    },
    test("POST /mcp tools/call ask_user returns the human answer over HTTP") {
      val rpc =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
          """"params":{"name":"ask_user","arguments":{"question":"go?"}}}"""
      for
        resp <- post(rpc)
        body <- resp.body.asString.orDie
      yield assertTrue(resp.status == Status.Ok, body.contains("you said: go?"))
    },
    test("POST /mcp notification (no id) yields 202 Accepted with no body") {
      for resp <- post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
      yield assertTrue(resp.status == Status.Accepted)
    },
    test("GET /mcp is not allowed (no server→client stream)") {
      for resp <- McpHttpServer.routes(tools).runZIO(Request.get(URL.root / "mcp"))
      yield assertTrue(resp.status == Status.MethodNotAllowed)
    },
  )
