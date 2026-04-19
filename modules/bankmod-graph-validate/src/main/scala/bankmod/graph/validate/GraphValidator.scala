package bankmod.graph.validate

import zio.NonEmptyChunk

import bankmod.graph.model.{ EdgeRef, Graph, ServiceId }

/** External configuration for the three context-dependent invariant checks.
  *
  * The M2 graph ADT carries no PII annotation, no financial-transaction tag, and no packed-decimal type markers. The
  * bank architect supplies these sets out-of-band; they are treated as validation policy inputs, not graph schema
  * facts.
  *
  * All fields default to empty — a `ValidationConfig.empty` check runs only the three context-free checks (cycles,
  * tier, structural).
  */
final case class ValidationConfig(
  piiServices: Set[ServiceId] = Set.empty,
  allowedPiiSinks: Set[ServiceId] = Set.empty,
  financialEdges: Set[EdgeRef] = Set.empty,
  packedEdges: Set[EdgeRef] = Set.empty,
  guardedEdges: Set[EdgeRef] = Set.empty,
)

object ValidationConfig:
  val empty: ValidationConfig = ValidationConfig()

/** Pure graph validator.
  *
  * Both overloads run ALL checks and accumulate every violation into the error channel — no short-circuiting. The
  * result is `Right(g)` only when the error list is empty.
  */
object GraphValidator:

  /** Run the three context-free checks: cycle detection, tier monotonicity, and structural validity.
    *
    * To also run the three context-dependent checks (PII boundary, strong-consistency on financial edges,
    * packed-decimal guards), use the two-argument overload with a [[ValidationConfig]].
    */
  def validate(g: Graph): Either[NonEmptyChunk[InvariantError], Graph] =
    validate(g, ValidationConfig.empty)

  /** Run all six invariant checks against `g`, using `cfg` to supply the context-dependent sets.
    *
    * Errors from every check are accumulated; the result is `Left(nec)` as soon as any error exists.
    */
  def validate(g: Graph, cfg: ValidationConfig): Either[NonEmptyChunk[InvariantError], Graph] =
    val errors: List[InvariantError] =
      Checks.detectCycles(g) ++
        Checks.tierMonotonicity(g) ++
        Checks.structural(g) ++
        Checks.piiBoundary(g, cfg.piiServices, cfg.allowedPiiSinks) ++
        Checks.strongConsistencyOnFinancialTxns(g, cfg.financialEdges) ++
        Checks.packedDecimalGuard(g, cfg.packedEdges, cfg.guardedEdges)

    NonEmptyChunk.fromIterableOption(errors) match
      case Some(nec) => Left(nec)
      case None      => Right(g)
