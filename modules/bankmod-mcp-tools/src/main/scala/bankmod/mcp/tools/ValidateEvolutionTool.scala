package bankmod.mcp.tools

import zio.*
import zio.schema.{ DeriveSchema, Schema }

import bankmod.graph.model.Schemas
import bankmod.graph.validate.{ GraphValidator, InvariantError }
import bankmod.mcp.GraphStore

object ValidateEvolutionTool:

  /** Flattened error representation for MCP wire format. The `kind` tag identifies which
    * `InvariantError` variant this came from; `detail` is a human-readable description.
    */
  final case class ErrorItem(kind: String, detail: String)
  object ErrorItem:
    given Schema[ErrorItem] = DeriveSchema.gen

  /** Structured outcome. When `accepted == true`, `errors` is empty and `previewJson` contains the JSON-re-encoded graph
    * (round-trip proves the input is well-formed). When `accepted == false`, `previewJson` is empty and `errors` lists
    * every accumulated violation.
    */
  final case class Output(accepted: Boolean, errors: List[ErrorItem], previewJson: String)
  object Output:
    given Schema[Output] = DeriveSchema.gen

  /** A decode failure is reported as a single error item with kind == "DecodeError". */
  private val DecodeKind = "DecodeError"

  /** Pure: decode patchJson, run validator, produce outcome. Never throws. */
  def run(input: ValidateEvolutionInput): Output =
    Schemas.graphCodec.decode(input.patchJson) match
      case Left(err) =>
        Output(accepted = false, errors = List(ErrorItem(DecodeKind, err.toString)), previewJson = "")
      case Right(graph) =>
        GraphValidator.validate(graph) match
          case Left(errs) =>
            Output(
              accepted = false,
              errors = errs.toList.map(toErrorItem),
              previewJson = "",
            )
          case Right(g) =>
            Output(
              accepted = true,
              errors = Nil,
              previewJson = Schemas.graphCodec.encodeToString(g),
            )

  /** Effectful wrapper. The GraphStore dependency is declared for future use (e.g. diff rendering against the current
    * graph); for MVP the validation runs on the posted graph in isolation.
    */
  def handle(input: ValidateEvolutionInput): ZIO[GraphStore, Nothing, Output] =
    ZIO.succeed(run(input))

  private def toErrorItem(e: InvariantError): ErrorItem = e match
    case InvariantError.CycleDetected(path)                  =>
      ErrorItem("CycleDetected", s"Cycle through: ${path.map(_.value).mkString(" -> ")}")
    case InvariantError.TierViolation(from, to)              =>
      ErrorItem("TierViolation", s"Tier violation: ${from.value} -> ${to.value}")
    case InvariantError.OrphanEdge(edge)                     =>
      ErrorItem(
        "OrphanEdge",
        s"Edge to unknown service: ${edge.from.value} -> ${edge.to.value} on ${edge.port.value}",
      )
    case InvariantError.UnknownPort(service, port)           =>
      ErrorItem("UnknownPort", s"Unknown port on ${service.value}: ${port.value}")
    case InvariantError.PiiBoundaryCrossed(from, to)         =>
      ErrorItem("PiiBoundaryCrossed", s"PII boundary crossed: ${from.value} -> ${to.value}")
    case InvariantError.WeakConsistencyOnFinancialEdge(edge) =>
      ErrorItem(
        "WeakConsistencyOnFinancialEdge",
        s"Weak consistency on financial edge: ${edge.from.value} -> ${edge.to.value}",
      )
    case InvariantError.MissingPackedDecimalGuard(edge)      =>
      ErrorItem(
        "MissingPackedDecimalGuard",
        s"Missing packed-decimal guard: ${edge.from.value} -> ${edge.to.value}",
      )
