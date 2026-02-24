package llm4zio.tools

import zio.*
import zio.json.ast.Json
import zio.test.*

import llm4zio.core.LlmProvider

object ToolProviderMapperSpec extends ZIOSpecDefault:
  private val sampleTool = Tool(
    name = "lookup_customer",
    description = "Lookup customer by id",
    parameters = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj("customerId" -> Json.Obj("type" -> Json.Str("string"))),
      "required"   -> Json.Arr(Chunk(Json.Str("customerId"))),
    ),
    execute = _ => ZIO.succeed(Json.Obj("status" -> Json.Str("ok"))),
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ToolProviderMapper")(
    test("map tools to OpenAI format") {
      val mapped = ToolProviderMapper.toProviderFormat(LlmProvider.OpenAI, List(sampleTool))
      mapped match
        case Json.Arr(values) =>
          val first = values.head.asInstanceOf[Json.Obj].fields.toMap
          assertTrue(
            first.get("type").contains(Json.Str("function"))
          )
        case _                => assertTrue(false)
    },
    test("map tools to Anthropic format") {
      val mapped = ToolProviderMapper.toProviderFormat(LlmProvider.Anthropic, List(sampleTool))
      mapped match
        case Json.Arr(values) =>
          val first = values.head.asInstanceOf[Json.Obj].fields.toMap
          assertTrue(
            first("name") == Json.Str("lookup_customer"),
            first.contains("input_schema"),
          )
        case _                => assertTrue(false)
    },
    test("map tools to Gemini format") {
      val mapped = ToolProviderMapper.toProviderFormat(LlmProvider.GeminiApi, List(sampleTool))
      mapped match
        case Json.Obj(fields) =>
          assertTrue(fields.toMap.contains("functionDeclarations"))
        case _                => assertTrue(false)
    },
  )
