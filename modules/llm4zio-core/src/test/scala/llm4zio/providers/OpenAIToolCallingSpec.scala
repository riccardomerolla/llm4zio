package llm4zio.providers

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.*

object OpenAIToolCallingSpec extends ZIOSpecDefault:

  private val stubTool = Tool(
    name = "get_time",
    description = "Returns the current time",
    parameters = Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj(), "required" -> Json.Arr()),
    execute = _ => ZIO.succeed(Json.Obj("time" -> Json.Str("12:00"))),
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
        ChatCompletionResponseWithTools(
          id = Some("test-id"),
          choices = List(
            ChatChoiceWithTools(
              message = Some(ChatMessageWithTools(
                role = "assistant",
                content = None,
                tool_calls = Some(List(
                  OpenAIToolCall(
                    id = "call_1",
                    `type` = "function",
                    function = OpenAIToolCallFunction(name = "get_time", arguments = "{}"),
                  )
                )),
              )),
              finish_reason = Some("tool_calls"),
            )
          ),
          usage = None,
          model = Some("gpt-4o"),
        ).toJson
      )

  private val config = LlmConfig(
    provider = LlmProvider.OpenAI,
    model = "gpt-4o",
    baseUrl = Some("https://api.openai.com/v1"),
    apiKey = Some("sk-test"),
  )

  override def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("OpenAI tool calling")(
    test("executeWithTools returns ToolCallResponse with tool calls") {
      val service = OpenAIProvider.make(config, toolCallHttpClient)
      for
        response <- service.executeWithTools("what time is it?", List(stubTool))
      yield assertTrue(
        response.toolCalls.nonEmpty,
        response.toolCalls.head.name == "get_time",
        response.toolCalls.head.id == "call_1",
        response.finishReason == "tool_calls",
      )
    }
  )
