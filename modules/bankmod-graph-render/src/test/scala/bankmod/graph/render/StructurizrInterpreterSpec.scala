package bankmod.graph.render

import zio.test.*

object StructurizrInterpreterSpec extends ZIOSpecDefault:

  private val rendered: String = StructurizrInterpreter.render(SampleGraph.sample)

  def spec: Spec[Any, Nothing] = suite("StructurizrInterpreter")(
    test("output contains 'workspace'") {
      assertTrue(rendered.contains("workspace"))
    },
    test("output contains softwareSystem declarations") {
      assertTrue(rendered.contains("softwareSystem"))
    },
    test("output contains autolayout lr") {
      assertTrue(rendered.contains("autolayout lr"))
    },
    test("service ids are converted to camelCase variable names") {
      assertTrue(
        rendered.contains("accountsApi") &&
        rendered.contains("ledgerCore") &&
        rendered.contains("paymentsEngine") &&
        rendered.contains("statementService")
      )
    },
    test("render(sample) matches golden file") {
      val expected = scala.io.Source.fromResource("golden/sample.dsl").mkString
      assertTrue(rendered.strip == expected.strip)
    },
  )
