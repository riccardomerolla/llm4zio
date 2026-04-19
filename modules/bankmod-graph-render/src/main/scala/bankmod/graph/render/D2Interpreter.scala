package bankmod.graph.render

import bankmod.graph.model.*

/** Renders a [[Graph]] as D2 diagram source.
  *
  * Format:
  *   - One container per criticality tier (`tier1: { shape: rectangle; ... }`).
  *   - Services declared as empty maps inside the tier container.
  *   - Services sorted alphabetically by ServiceId within each container.
  *   - Edges use fully-qualified paths: `tier1.accounts-api -> tier1.ledger-core`.
  *   - Synchronous protocols (REST, gRPC, GraphQL, SOAP): plain arrow with protocol label.
  *   - Async protocol (Event): dashed arrow (`style.stroke-dash: 5`) with `"Event:topic"` label.
  *   - Edges sorted by (fromId, toId, port) for deterministic output.
  */
object D2Interpreter:

  val interpreter: GraphInterpreter[String] = (g: Graph) => render(g)

  def render(g: Graph): String =
    val sb = new StringBuilder

    val byTier = g.services.values.toList.groupBy(_.tier)

    def tierKey(tier: Criticality): String = tier match
      case Criticality.Tier1 => "tier1"
      case Criticality.Tier2 => "tier2"
      case Criticality.Tier3 => "tier3"

    def renderTierBlock(tier: Criticality): Unit =
      val services = byTier.getOrElse(tier, Nil).sortBy(_.id.value)
      if services.nonEmpty then
        val key = tierKey(tier)
        sb.append(s"$key: {\n")
        sb.append("    shape: rectangle\n")
        services.foreach { svc =>
          sb.append(s"    ${svc.id.value}: {}\n")
        }
        sb.append("}\n")

    renderTierBlock(Criticality.Tier1)
    renderTierBlock(Criticality.Tier2)
    renderTierBlock(Criticality.Tier3)

    // Collect all edges sorted by (fromId, toId, fromPort)
    val allEdges = g.services.values.toList
      .sortBy(_.id.value)
      .flatMap { svc =>
        svc.outbound.toList
          .sortBy(e => (e.toService.value, e.fromPort.value))
          .map(e => (svc, e))
      }

    if allEdges.nonEmpty then sb.append("\n")

    allEdges.foreach { (svc, edge) =>
      val fromTier = tierKey(svc.tier)
      val toSvc    = g.services.get(edge.toService)
      val toTier   = toSvc.map(s => tierKey(s.tier)).getOrElse("unknown")
      val from     = s"$fromTier.${svc.id.value}"
      val to       = s"$toTier.${edge.toService.value}"

      edge.protocol match
        case Protocol.Event(topic) =>
          sb.append(s"""$from -> $to: "Event:${topic.value}" {\n""")
          sb.append("    style.stroke-dash: 5\n")
          sb.append("}\n")
        case other                 =>
          val label = protocolLabel(other)
          sb.append(s"$from -> $to: $label\n")
    }

    sb.toString.stripTrailing

  private def protocolLabel(p: Protocol): String = p match
    case Protocol.Rest(_)      => "REST"
    case Protocol.Grpc(_)      => "gRPC"
    case Protocol.Event(topic) => s"Event:${topic.value}"
    case Protocol.Graphql(_)   => "GraphQL"
    case Protocol.Soap(_)      => "SOAP"
