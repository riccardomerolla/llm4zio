package bankmod.graph.render

import zio.test.*
import zio.test.Assertion.*

object MermaidInterpreterSpec extends ZIOSpecDefault:

  private val rendered: String = MermaidInterpreter.render(SampleGraph.sample)

  def spec: Spec[Any, Nothing] = suite("MermaidInterpreter")(
    test("output starts with 'graph LR'") {
      assertTrue(rendered.startsWith("graph LR"))
    },
    test("output contains tier1 subgraph") {
      assertTrue(rendered.contains("subgraph tier1"))
    },
    test("output contains tier2 subgraph") {
      assertTrue(rendered.contains("subgraph tier2"))
    },
    test("output contains tier3 subgraph") {
      assertTrue(rendered.contains("subgraph tier3"))
    },
    test("output contains event edge label") {
      assertTrue(rendered.contains("Event:ledger.posted"))
    },
    test("render(sample) matches golden file") {
      val expected = scala.io.Source.fromResource("golden/sample.mmd").mkString
      assert(rendered.strip)(equalTo(expected.strip))
    },
  )
