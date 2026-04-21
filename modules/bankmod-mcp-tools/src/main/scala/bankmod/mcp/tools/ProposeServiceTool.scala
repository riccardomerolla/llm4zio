package bankmod.mcp.tools

import zio.*
import zio.schema.{ DeriveSchema, Schema }

import bankmod.graph.model.Schemas
import bankmod.graph.validate.{ GraphValidator, InvariantError }
import bankmod.mcp.GraphStore

object ProposeServiceTool:

  /** Structured outcome. When `committed == true`, `errors` is empty and `committedJson` contains the JSON-re-encoded
    * graph that was atomically swapped into the store. When `committed == false`, `committedJson` is empty and `errors`
    * carries either a single decode error or every accumulated validator violation; the store is untouched.
    */
  final case class Output(
    committed: Boolean,
    errors: List[ValidateEvolutionTool.ErrorItem],
    committedJson: String,
  )
  object Output:
    given Schema[Output] = DeriveSchema.gen

  /** Effectful: decode, then run the validator *inside* `GraphStore.update` so that validate-and-swap is atomic with
    * respect to concurrent writers. On decode failure we short-circuit with a non-committed Output — there is no point
    * attempting an update if the input is not a well-formed graph.
    */
  def handle(input: ProposeServiceInput): ZIO[GraphStore, Nothing, Output] =
    Schemas.graphCodec.decode(input.patchJson) match
      case Left(err)        =>
        ZIO.succeed(
          Output(
            committed = false,
            errors = List(ValidateEvolutionTool.ErrorItem("DecodeError", err.toString)),
            committedJson = "",
          )
        )
      case Right(candidate) =>
        ZIO
          .serviceWithZIO[GraphStore](_.update(_ => GraphValidator.validate(candidate)))
          .fold(
            errs =>
              Output(
                committed = false,
                errors = errs.toList.map(toErrorItem),
                committedJson = "",
              ),
            committed =>
              Output(
                committed = true,
                errors = Nil,
                committedJson = Schemas.graphCodec.encodeToString(committed),
              ),
          )

  private def toErrorItem(e: InvariantError): ValidateEvolutionTool.ErrorItem = e match
    case InvariantError.CycleDetected(path)                  =>
      ValidateEvolutionTool.ErrorItem("CycleDetected", s"Cycle through: ${path.map(_.value).mkString(" -> ")}")
    case InvariantError.TierViolation(from, to)              =>
      ValidateEvolutionTool.ErrorItem("TierViolation", s"Tier violation: ${from.value} -> ${to.value}")
    case InvariantError.OrphanEdge(edge)                     =>
      ValidateEvolutionTool.ErrorItem(
        "OrphanEdge",
        s"Edge to unknown service: ${edge.from.value} -> ${edge.to.value} on ${edge.port.value}",
      )
    case InvariantError.UnknownPort(service, port)           =>
      ValidateEvolutionTool.ErrorItem("UnknownPort", s"Unknown port on ${service.value}: ${port.value}")
    case InvariantError.PiiBoundaryCrossed(from, to)         =>
      ValidateEvolutionTool.ErrorItem("PiiBoundaryCrossed", s"PII boundary crossed: ${from.value} -> ${to.value}")
    case InvariantError.WeakConsistencyOnFinancialEdge(edge) =>
      ValidateEvolutionTool.ErrorItem(
        "WeakConsistencyOnFinancialEdge",
        s"Weak consistency on financial edge: ${edge.from.value} -> ${edge.to.value}",
      )
    case InvariantError.MissingPackedDecimalGuard(edge)      =>
      ValidateEvolutionTool.ErrorItem(
        "MissingPackedDecimalGuard",
        s"Missing packed-decimal guard: ${edge.from.value} -> ${edge.to.value}",
      )
