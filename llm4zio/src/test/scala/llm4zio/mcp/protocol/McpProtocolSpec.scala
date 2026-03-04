package llm4zio.mcp.protocol

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

import llm4zio.tools.{ Tool, ToolExecutionError }

object McpProtocolSpec extends ZIOSpecDefault:
  def spec = suite("McpProtocol")(
    suite("McpInitializeRequest")(
      test("decodes client capabilities") {
        val json =
          """{"protocolVersion":"2024-11-05","capabilities":{"roots":{"listChanged":true}},"clientInfo":{"name":"claude-code","version":"1.0"}}"""
        assertTrue(json.fromJson[McpInitializeRequest].isRight)
      },
    ),
    suite("McpInitializeResponse")(
      test("encodes server capabilities with tools support") {
        val resp = McpInitializeResponse(
          protocolVersion = "2024-11-05",
          capabilities = ServerCapabilities(tools = Some(ToolsCapability())),
          serverInfo = ServerInfo("llm4zio-gateway", "1.0"),
        )
        val json = resp.toJson
        assertTrue(json.contains("\"tools\""), json.contains("\"llm4zio-gateway\""))
      },
    ),
    suite("McpToolsListResponse")(
      test("encodes tool list with schema") {
        val schema  = Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj())
        val tools   = List(McpToolInfo(name = "ping", description = "health check", inputSchema = schema))
        val resp    = McpToolsListResponse(tools)
        val encoded = resp.toJson
        assertTrue(encoded.contains("\"ping\""), encoded.contains("\"health check\""))
      },
      test("McpToolInfo.fromTool converts Tool to McpToolInfo") {
        val schema = Json.Obj("type" -> Json.Str("object"))
        val tool   = Tool(name = "my_tool", description = "does stuff", parameters = schema, execute = _ => ZIO.fail(ToolExecutionError.ExecutionFailed("test")))
        val info   = McpToolInfo.fromTool(tool)
        assertTrue(info.name == "my_tool", info.description == "does stuff", info.inputSchema == schema)
      },
    ),
    suite("McpToolCallRequest / McpToolCallResponse")(
      test("decodes tool call request") {
        val json = """{"name":"ping","arguments":{}}"""
        assertTrue(json.fromJson[McpToolCallRequest].map(_.name).contains("ping"))
      },
      test("McpToolCallRequest.toToolCall converts to ToolCall") {
        val req  = McpToolCallRequest(name = "run_agent", arguments = Json.Obj("prompt" -> Json.Str("hello")))
        val call = req.toToolCall
        assertTrue(call.name == "run_agent")
      },
      test("encodes text content response") {
        val resp = McpToolCallResponse(content = List(McpContent.Text("result")), isError = false)
        assertTrue(resp.toJson.contains("\"result\""))
      },
    ),
    suite("McpContent")(
      test("TextContent encodes with type field") {
        val c = McpContent.Text("hello")
        assertTrue(c.toJson.contains("\"text\""), c.toJson.contains("\"hello\""))
      },
    ),
    suite("McpNotifications")(
      test("initialized notification has correct method") {
        assertTrue(McpNotifications.initialized.method == "notifications/initialized")
      },
      test("tools list changed notification has correct method") {
        assertTrue(McpNotifications.toolsListChanged.method == "notifications/tools/list_changed")
      },
    ),
  )
