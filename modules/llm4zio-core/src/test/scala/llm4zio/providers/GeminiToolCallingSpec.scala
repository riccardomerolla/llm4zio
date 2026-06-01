package llm4zio.providers

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.*

object GeminiToolCallingSpec extends ZIOSpecDefault:

  private val stubTool = Tool(
    name = "search",
    description = "Web search",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj("query" -> Json.Obj("type" -> Json.Str("string"))),
      "required"   -> Json.Arr(Json.Str("query")),
    ),
    execute = _ => ZIO.succeed(Json.Obj("results" -> Json.Arr())),
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
        GeminiGenerateContentResponseFull(
          candidates = List(
            GeminiCandidateFull(
              content = GeminiContentFull(parts =
                List(
                  GeminiPartFull(functionCall =
                    Some(GeminiFunctionCall(
                      name = "search",
                      args = Json.Obj("query" -> Json.Str("Scala")),
                    ))
                  )
                )
              ),
              finishReason = Some("STOP"),
            )
          )
        ).toJson
      )

  private val config = LlmConfig(
    provider = LlmProvider.GeminiApi,
    model = "gemini-2.0-flash",
    baseUrl = Some("https://generativelanguage.googleapis.com"),
    apiKey = Some("test-key"),
  )

  override def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Gemini tool calling")(
    test("executeWithTools returns ToolCallResponse with functionCall") {
      val service = GeminiApiProvider.make(config, toolCallHttpClient)
      for
        response <- service.executeWithTools("Search for Scala", List(stubTool))
      yield assertTrue(
        response.toolCalls.nonEmpty,
        response.toolCalls.head.name == "search",
        response.toolCalls.head.arguments.contains("Scala"),
      )
    }
  )
