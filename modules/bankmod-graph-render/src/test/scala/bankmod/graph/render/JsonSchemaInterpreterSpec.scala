package bankmod.graph.render

import zio.test.*

object JsonSchemaInterpreterSpec extends ZIOSpecDefault:

  private val schema: String = JsonSchemaInterpreter.jsonSchema

  def spec: Spec[Any, Nothing] = suite("JsonSchemaInterpreter")(
    test("schema string is non-empty") {
      assertTrue(schema.nonEmpty)
    },
    test("schema string contains 'type' key") {
      assertTrue(schema.contains("\"type\""))
    },
    test("schema string contains 'properties' key") {
      assertTrue(schema.contains("\"properties\""))
    },
    test("interpreter.render ignores the runtime Graph value and returns the precomputed schema") {
      val out1 = JsonSchemaInterpreter.interpreter.render(SampleGraph.sample)
      val out2 = JsonSchemaInterpreter.jsonSchema
      assertTrue(out1 == out2)
    },
  )
