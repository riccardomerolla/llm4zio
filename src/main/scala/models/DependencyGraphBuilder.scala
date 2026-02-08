package models

import zio.json.*

object DependencyGraphBuilder:
  def toMermaid(graph: DependencyGraph): String =
    val nodes = graph.nodes.map { node =>
      val label = s"${node.name} (${node.nodeType})"
      s"""  ${node.id}["$label"]"""
    }
    val edges = graph.edges.map { edge =>
      val label = edge.edgeType match
        case EdgeType.Includes => "includes"
        case EdgeType.Calls    => "calls"
        case EdgeType.Uses     => "uses"
      s"  ${edge.from} -->|$label| ${edge.to}"
    }
    (List("graph TD") ++ nodes ++ edges).mkString("\n")

  def toD3Json(graph: DependencyGraph): String =
    D3Graph(
      nodes = graph.nodes.map(node => D3Node(node.id, node.name, node.nodeType.toString, node.complexity)),
      links = graph.edges.map(edge => D3Link(edge.from, edge.to, edge.edgeType.toString)),
    ).toJsonPretty

  private case class D3Node(
    id: String,
    label: String,
    nodeType: String,
    complexity: Int,
  ) derives JsonCodec

  private case class D3Link(
    source: String,
    target: String,
    edgeType: String,
  ) derives JsonCodec

  private case class D3Graph(
    nodes: List[D3Node],
    links: List[D3Link],
  ) derives JsonCodec
