package models

import zio.json.*
import zio.test.*

object DependencyGraphBuilderSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("DependencyGraphBuilderSpec")(
    test("toMermaid renders graph text") {
      val graph = sampleGraph
      val out   = DependencyGraphBuilder.toMermaid(graph)
      assertTrue(
        out.contains("graph TD"),
        out.contains("PROG1"),
        out.contains("-->|calls|"),
      )
    },
    test("toD3Json renders nodes and links JSON") {
      val graph  = sampleGraph
      val json   = DependencyGraphBuilder.toD3Json(graph)
      val parsed = json.fromJson[zio.json.ast.Json]
      assertTrue(
        parsed.isRight,
        json.contains("\"nodes\""),
        json.contains("\"links\""),
        json.contains("\"source\""),
        json.contains("\"target\""),
      )
    },
  )

  private val sampleGraph = DependencyGraph(
    nodes = List(
      DependencyNode("PROG1", "PROG1", NodeType.Program, 2),
      DependencyNode("COPY1", "COPY1", NodeType.Copybook, 0),
    ),
    edges = List(
      DependencyEdge("PROG1", "COPY1", EdgeType.Includes),
      DependencyEdge("PROG1", "PROG1", EdgeType.Calls),
    ),
    serviceCandidates = List("COPY1"),
  )
