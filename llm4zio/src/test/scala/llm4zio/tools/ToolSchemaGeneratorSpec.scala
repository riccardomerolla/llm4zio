package llm4zio.tools

import zio.*
import zio.json.ast.Json
import zio.test.*

object ToolSchemaGeneratorSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ToolSchemaGenerator")(
    test("generate schema from scala method signature") {
      val signature = "def transform(code: String, retries: Int, dryRun: Boolean = false): String"

      for
        schema <- ToolSchemaGenerator.fromMethodSignature(signature)
      yield schema match
        case Json.Obj(fields) =>
          val map        = fields.toMap
          val properties = map("properties").asInstanceOf[Json.Obj].fields.toMap
          val required   = map("required").asInstanceOf[Json.Arr].elements.toList
          assertTrue(
            map.get("type").contains(Json.Str("object")),
            properties.get("code").contains(Json.Obj("type" -> Json.Str("string"))),
            properties.get("retries").contains(Json.Obj("type" -> Json.Str("integer"))),
            properties.get("dryRun").contains(Json.Obj("type" -> Json.Str("boolean"))),
            required.contains(Json.Str("code")),
            required.contains(Json.Str("retries")),
            !required.contains(Json.Str("dryRun")),
          )
        case _                => assertTrue(false)
    },
    test("fail on invalid signature") {
      for
        result <- ToolSchemaGenerator.fromMethodSignature("val notAMethod = 1").either
      yield assertTrue(result.isLeft)
    },
  )
