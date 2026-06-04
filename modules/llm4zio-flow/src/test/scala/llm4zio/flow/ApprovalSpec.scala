package llm4zio.flow

import zio.ZIO
import zio.json.ast.Json
import zio.test.*

object ApprovalSpec extends ZIOSpecDefault:

  private def parse(s: String): Json = Json.decoder.decodeJson(s).toOption.get

  private def at(j: Json, path: String*): Option[Json] =
    path.foldLeft(Option(j)) { (cur, key) =>
      cur.flatMap {
        case Json.Obj(fields) => fields.collectFirst { case (k, v) if k == key => v }
        case _                => None
      }
    }

  private def strAt(j: Json, path: String*): Option[String] = at(j, path*).collect { case Json.Str(s) => s }

  /** Run the `approve` tool through the full JSON-RPC path and return the verdict text claude would receive. */
  private def verdict(policy: ApprovalPolicy, toolName: String, input: String) =
    val rpc = parse(
      """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
        s""""params":{"name":"approve","arguments":{"tool_name":"$toolName","input":$input}}}"""
    )
    McpServer.handle(rpc, List(McpServer.approvalTool(policy))).map { resp =>
      at(resp.get, "result", "content").collect { case Json.Arr(xs) => xs.toList }.getOrElse(Nil).headOption
        .flatMap(c => strAt(c, "text"))
        .map(parse)
    }

  def spec: Spec[Environment & TestEnvironment, Any] = suite("Approval")(
    test("autoApprove allows everything") {
      for d <- ApprovalPolicy.autoApprove.decide("Bash", parse("""{"command":"ls"}"""))
      yield assertTrue(d == ApprovalDecision.Allow)
    },
    test("interactive allows on yes and denies otherwise") {
      val yes = ApprovalPolicy.interactive(_ => ZIO.succeed("y"))
      val no  = ApprovalPolicy.interactive(_ => ZIO.succeed("nope"))
      for
        a <- yes.decide("Bash", parse("""{"command":"ls"}"""))
        b <- no.decide("Bash", parse("""{"command":"rm -rf /"}"""))
      yield assertTrue(a == ApprovalDecision.Allow, b.isInstanceOf[ApprovalDecision.Deny])
    },
    test("approvalTool emits claude's allow verdict, echoing updatedInput") {
      for v <- verdict(ApprovalPolicy.autoApprove, "Bash", """{"command":"ls"}""")
      yield assertTrue(
        v.flatMap(strAt(_, "behavior")).contains("allow"),
        v.flatMap(j => strAt(j, "updatedInput", "command")).contains("ls"),
      )
    },
    test("approvalTool emits claude's deny verdict with a message") {
      val deny = ApprovalPolicy.interactive(_ => ZIO.succeed("n"))
      for v <- verdict(deny, "Bash", """{"command":"rm -rf /"}""")
      yield assertTrue(
        v.flatMap(strAt(_, "behavior")).contains("deny"),
        v.flatMap(strAt(_, "message")).exists(_.contains("denied")),
      )
    },
  )
