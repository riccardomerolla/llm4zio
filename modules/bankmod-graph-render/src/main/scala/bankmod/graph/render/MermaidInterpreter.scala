package bankmod.graph.render

import bankmod.graph.model.*

/** Renders a [[Graph]] as a Mermaid diagram (graph LR) with subgraphs per tier.
  *
  * Format:
  *   - `graph LR` top-level directive.
  *   - One `subgraph tierN [...]` container per criticality tier.
  *   - Services sorted alphabetically by ServiceId within each subgraph.
  *   - Edges sorted by (fromService, toService, port) for deterministic output.
  *   - Edge labels: `-->|REST|`, `-->|gRPC|`, `-->|Event:topic.name|`, `-->|GraphQL|`, `-->|SOAP|`.
  */
object MermaidInterpreter:

  val interpreter: GraphInterpreter[String] = (g: Graph) => render(g)

  def render(g: Graph): String =
    val sb = new StringBuilder
    sb.append("graph LR\n")

    val byTier = g.services.values.toList.groupBy(_.tier)

    def renderTier(tier: Criticality, label: String): Unit =
      val services = byTier.getOrElse(tier, Nil).sortBy(_.id.value)
      if services.nonEmpty then
        sb.append(s"    subgraph $label\n")
        services.foreach { svc =>
          sb.append(s"        ${svc.id.value}[${svc.id.value}]\n")
        }
        sb.append("    end\n")

    renderTier(Criticality.Tier1, "tier1")
    renderTier(Criticality.Tier2, "tier2")
    renderTier(Criticality.Tier3, "tier3")

    // Collect all edges sorted by (fromId, toId, port)
    val allEdges = g.services.values.toList
      .sortBy(_.id.value)
      .flatMap { svc =>
        svc.outbound.toList
          .sortBy(e => (e.toService.value, e.fromPort.value))
          .map(e => (svc, e))
      }

    if allEdges.nonEmpty then sb.append("\n")

    allEdges.foreach { (svc, edge) =>
      val label = protocolLabel(edge.protocol)
      sb.append(s"    ${svc.id.value} -->|$label| ${edge.toService.value}\n")
    }

    sb.toString.stripTrailing

  private def protocolLabel(p: Protocol): String = p match
    case Protocol.Rest(_)      => "REST"
    case Protocol.Grpc(_)      => "gRPC"
    case Protocol.Event(topic) => s"Event:${topic.value}"
    case Protocol.Graphql(_)   => "GraphQL"
    case Protocol.Soap(_)      => "SOAP"
