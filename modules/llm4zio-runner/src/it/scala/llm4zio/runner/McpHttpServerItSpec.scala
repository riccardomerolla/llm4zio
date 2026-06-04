package llm4zio.runner

import zio.*
import zio.http.*
import zio.test.*

import llm4zio.flow.McpServer

/** Boots the real MCP HTTP server on an ephemeral localhost port and drives one JSON-RPC `tools/call` over an actual
  * HTTP client — the round trip a CLI coding agent makes through `--mcp-config`.
  */
object McpHttpServerItSpec extends ZIOSpecDefault:

  private val tools = List(McpServer.askUserTool(q => ZIO.succeed(s"echo:$q")))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("McpHttpServer (real socket)")(
      test("serves a JSON-RPC tools/call over a real HTTP round trip") {
        val rpc =
          """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
            """"params":{"name":"ask_user","arguments":{"question":"ping"}}}"""
        ZIO.scoped {
          for
            port <- McpHttpServer.serve(tools)
            url  <- ZIO.fromEither(URL.decode(s"http://127.0.0.1:$port/mcp")).orDie
            resp <- Client.batched(Request.post(url, Body.fromString(rpc)))
            body <- resp.body.asString
          yield assertTrue(resp.status == Status.Ok, body.contains("echo:ping"))
        }
      }
    ).provide(Client.default) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
