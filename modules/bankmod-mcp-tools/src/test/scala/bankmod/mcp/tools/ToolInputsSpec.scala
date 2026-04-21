package bankmod.mcp.tools

import zio.test.*
import zio.schema.Schema
import zio.schema.codec.JsonCodec

object ToolInputsSpec extends ZIOSpecDefault:

  private def roundTrip[A: Schema](value: A): TestResult =
    val codec  = JsonCodec.schemaBasedBinaryCodec[A]
    val bytes  = codec.encode(value)
    val result = codec.decode(bytes)
    assertTrue(result == Right(value))

  def spec = suite("ToolInputs schema derivation")(
    test("RenderDiagramInput round-trips") {
      roundTrip(RenderDiagramInput("full", "mermaid"))
    },
    test("QueryDependenciesInput round-trips") {
      roundTrip(QueryDependenciesInput("payments-api", 2))
    },
    test("ValidateEvolutionInput round-trips") {
      roundTrip(ValidateEvolutionInput("""{"services":{}}"""))
    },
    test("ProposeServiceInput round-trips") {
      roundTrip(ProposeServiceInput("""{"services":{}}"""))
    },
    test("ExplainInvariantViolationInput round-trips") {
      roundTrip(ExplainInvariantViolationInput("CycleDetected", """{"path":[]}"""))
    },
    test("ListInvariantsInput round-trips") {
      roundTrip(ListInvariantsInput())
    },
  )
