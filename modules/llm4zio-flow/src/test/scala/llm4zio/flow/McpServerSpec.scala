package llm4zio.flow

import zio.ZIO
import zio.json.ast.Json
import zio.test.*

object McpServerSpec extends ZIOSpecDefault:

  private def parse(s: String): Json = Json.decoder.decodeJson(s).toOption.get

  private def at(j: Json, path: String*): Option[Json] =
    path.foldLeft(Option(j)) { (cur, key) =>
      cur.flatMap {
        case Json.Obj(fields) => fields.collectFirst { case (k, v) if k == key => v }
        case _                => None
      }
    }

  private def strAt(j: Json, path: String*): Option[String] = at(j, path*).collect { case Json.Str(s) => s }

  private val echo: Interaction = q => ZIO.succeed(s"you said: $q")
  private val tools             = List(McpServer.askUserTool(echo))

  private def req(method: String, params: String = "{}", id: String = "1"): Json =
    parse(s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

  def spec: Spec[Environment & TestEnvironment, Any] = suite("McpServer")(
    test("initialize echoes the client protocolVersion and advertises tools capability") {
      for resp <- McpServer.handle(req("initialize", """{"protocolVersion":"2025-03-26"}"""), tools)
      yield
        val r = resp.flatMap(j => at(j, "result")).get
        assertTrue(
          strAt(r, "protocolVersion").contains("2025-03-26"),
          at(r, "capabilities", "tools").isDefined,
          strAt(r, "serverInfo", "name").contains("llm4zio"),
        )
    },
    test("tools/list advertises ask_user with an inputSchema") {
      for resp <- McpServer.handle(req("tools/list"), tools)
      yield
        val arr = at(resp.get, "result", "tools").collect { case Json.Arr(xs) => xs.toList }.getOrElse(Nil)
        assertTrue(
          arr.exists(t => strAt(t, "name").contains("ask_user")),
          arr.exists(t => at(t, "inputSchema", "properties", "question").isDefined),
        )
    },
    test("tools/call ask_user bridges to the Interaction and returns its answer") {
      val params = """{"name":"ask_user","arguments":{"question":"deploy now?"}}"""
      for resp <- McpServer.handle(req("tools/call", params), tools)
      yield
        val content = at(resp.get, "result", "content").collect { case Json.Arr(xs) => xs.toList }.getOrElse(Nil)
        assertTrue(
          at(resp.get, "result").isDefined,
          content.exists(c => strAt(c, "text").contains("you said: deploy now?")),
          at(resp.get, "result", "isError").contains(Json.Bool(false)),
        )
    },
    test("a notification (no id) yields no response") {
      val notif = parse("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
      for resp <- McpServer.handle(notif, tools)
      yield assertTrue(resp.isEmpty)
    },
    test("unknown method returns a -32601 error") {
      for resp <- McpServer.handle(req("does/not/exist"), tools)
      yield assertTrue(at(resp.get, "error", "code").contains(Json.Num(-32601)))
    },
    test("unknown tool returns a -32602 error") {
      for resp <- McpServer.handle(req("tools/call", """{"name":"nope","arguments":{}}"""), tools)
      yield assertTrue(at(resp.get, "error", "code").contains(Json.Num(-32602)))
    },
    test("a failing tool is reported as an isError result, not a failed effect") {
      val failing: Interaction = _ => ZIO.fail(FlowError.Aborted("no human available"))
      val failingTools         = List(McpServer.askUserTool(failing))
      val params               = """{"name":"ask_user","arguments":{"question":"x?"}}"""
      for resp <- McpServer.handle(req("tools/call", params), failingTools)
      yield assertTrue(
        at(resp.get, "result", "isError").contains(Json.Bool(true)),
        strAt(
          at(resp.get, "result", "content").collect { case Json.Arr(xs) => xs.toList }.getOrElse(Nil).head,
          "text",
        ).exists(_.contains("no human available")),
      )
    },
  )
