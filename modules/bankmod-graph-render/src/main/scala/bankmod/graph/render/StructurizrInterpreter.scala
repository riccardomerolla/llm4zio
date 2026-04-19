package bankmod.graph.render

import bankmod.graph.model.*

/** Renders a [[Graph]] as a Structurizr DSL workspace.
  *
  * Format:
  *   - One `softwareSystem` per service with a `tags` block carrying the tier label.
  *   - Variable names are camelCase transformations of the ServiceId (e.g. `accounts-api` → `accountsApi`).
  *   - Relationships carry the protocol as label.
  *   - Services within the model block are sorted alphabetically by ServiceId.
  *   - Relationships sorted by (fromId, toId, port) for deterministic output.
  *   - A single `systemLandscape` view with `autolayout lr`.
  */
object StructurizrInterpreter:

  val interpreter: GraphInterpreter[String] = (g: Graph) => render(g)

  def render(g: Graph): String =
    val sb = new StringBuilder

    sb.append("workspace \"bankmod-sample\" \"Bank modernization sample graph\" {\n")
    sb.append("    model {\n")

    val sortedServices = g.services.values.toList.sortBy(_.id.value)

    sortedServices.foreach { svc =>
      val varName = toCamelCase(svc.id.value)
      val tierTag = svc.tier match
        case Criticality.Tier1 => "Tier1"
        case Criticality.Tier2 => "Tier2"
        case Criticality.Tier3 => "Tier3"
      sb.append(s"""        $varName = softwareSystem "${svc.id.value}" {\n""")
      sb.append(s"""            tags "$tierTag"\n""")
      sb.append("        }\n")
    }

    sb.append("\n")

    // Emit relationships sorted by (fromId, toId, fromPort)
    val allEdges = sortedServices.flatMap { svc =>
      svc.outbound.toList
        .sortBy(e => (e.toService.value, e.fromPort.value))
        .map(e => (svc, e))
    }

    allEdges.foreach { (svc, edge) =>
      val fromVar = toCamelCase(svc.id.value)
      val toVar   = toCamelCase(edge.toService.value)
      val label   = protocolLabel(edge.protocol)
      sb.append(s"""        $fromVar -> $toVar "$label"\n""")
    }

    sb.append("    }\n")
    sb.append("\n")
    sb.append("    views {\n")
    sb.append("        systemLandscape \"landscape\" {\n")
    sb.append("            include *\n")
    sb.append("            autolayout lr\n")
    sb.append("        }\n")
    sb.append("    }\n")
    sb.append("}")

    sb.toString

  /** Converts a kebab-case ServiceId string to camelCase. */
  private def toCamelCase(s: String): String =
    val parts = s.split('-').filter(_.nonEmpty)
    parts.headOption.getOrElse("") + parts.tail.map(p => p.head.toUpper.toString + p.tail).mkString

  private def protocolLabel(p: Protocol): String = p match
    case Protocol.Rest(_)      => "REST"
    case Protocol.Grpc(_)      => "gRPC"
    case Protocol.Event(topic) => s"Event:${topic.value}"
    case Protocol.Graphql(_)   => "GraphQL"
    case Protocol.Soap(_)      => "SOAP"
