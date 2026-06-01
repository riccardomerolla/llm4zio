package llm4zio.providers

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.*

object AnthropicToolCallingSpec extends ZIOSpecDefault:

  private val stubTool = Tool(
    name = "get_weather",
    description = "Get weather for a location",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj("location" -> Json.Obj("type" -> Json.Str("string"))),
      "required"   -> Json.Arr(Json.Str("location")),
    ),
    execute = _ => ZIO.succeed(Json.Obj("temp" -> Json.Num(22))),
  )

  private val toolCallHttpClient: HttpClient = new HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: Duration): ZIO[Any, LlmError, String] =
      ZIO.succeed("[]")
    override def postJson(
      url: String,
      body: String,
      headers: Map[String, String],
      timeout: Duration,
    ): ZIO[Any, LlmError, String] =
      ZIO.succeed(
        AnthropicResponseWithTools(
          id = Some("msg_1"),
          content = List(
            AnthropicContentBlockFull(
              `type` = "tool_use",
              id = Some("toolu_1"),
              name = Some("get_weather"),
              input = Some(Json.Obj("location" -> Json.Str("Rome"))),
            )
          ),
          stop_reason = Some("tool_use"),
        ).toJson
      )

  private val config = LlmConfig(
    provider = LlmProvider.Anthropic,
    model = "claude-3-5-sonnet-20241022",
    baseUrl = Some("https://api.anthropic.com"),
    apiKey = Some("sk-ant-test"),
  )

  override def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Anthropic tool calling")(
    test("executeWithTools returns ToolCallResponse with tool_use blocks") {
      val service = AnthropicProvider.make(config, toolCallHttpClient)
      for
        response <- service.executeWithTools("What's the weather in Rome?", List(stubTool))
      yield assertTrue(
        response.toolCalls.nonEmpty,
        response.toolCalls.head.name == "get_weather",
        response.toolCalls.head.id == "toolu_1",
        response.finishReason == "tool_use",
      )
    }
  )
