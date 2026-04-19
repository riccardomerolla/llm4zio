package bankmod.graph.render

import zio.test.*

object D2InterpreterSpec extends ZIOSpecDefault:

  private val rendered: String = D2Interpreter.render(SampleGraph.sample)

  def spec: Spec[Any, Nothing] = suite("D2Interpreter")(
    test("output contains 'tier1: {'") {
      assertTrue(rendered.contains("tier1: {"))
    },
    test("output contains 'tier2: {'") {
      assertTrue(rendered.contains("tier2: {"))
    },
    test("output contains 'tier3: {'") {
      assertTrue(rendered.contains("tier3: {"))
    },
    test("event edges use dashed style") {
      assertTrue(rendered.contains("style.stroke-dash: 5"))
    },
    test("render(sample) matches golden file") {
      val expected = scala.io.Source.fromResource("golden/sample.d2").mkString
      assertTrue(rendered.strip == expected.strip)
    },
  )
