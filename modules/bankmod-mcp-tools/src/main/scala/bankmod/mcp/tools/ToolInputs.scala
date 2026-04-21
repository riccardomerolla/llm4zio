package bankmod.mcp.tools

import zio.schema.{ DeriveSchema, Schema }

/** Input for the renderDiagram tool.
  *
  * @param scope
  *   one of "full" (the whole graph) or "service:<id>" (single-service subgraph)
  * @param format
  *   one of "mermaid", "d2", "structurizr", "json"
  */
final case class RenderDiagramInput(scope: String, format: String)
object RenderDiagramInput:
  given Schema[RenderDiagramInput] = DeriveSchema.gen

/** Input for the queryDependencies tool.
  *
  * @param serviceId
  *   unqualified service id (validated as ServiceId at handler time)
  * @param depth
  *   BFS depth; 1..5 enforced at handler time
  */
final case class QueryDependenciesInput(serviceId: String, depth: Int)
object QueryDependenciesInput:
  given Schema[QueryDependenciesInput] = DeriveSchema.gen

/** Input for the validateEvolution tool.
  *
  * The `patchJson` is a full Graph JSON — MVP does NOT support partial patches. The handler decodes, runs the
  * validator, and returns a structured outcome without committing.
  */
final case class ValidateEvolutionInput(patchJson: String)
object ValidateEvolutionInput:
  given Schema[ValidateEvolutionInput] = DeriveSchema.gen

/** Input for the proposeService tool. Same shape as validateEvolution but commits on success. */
final case class ProposeServiceInput(patchJson: String)
object ProposeServiceInput:
  given Schema[ProposeServiceInput] = DeriveSchema.gen

/** Input for the explainInvariantViolation tool.
  *
  * The error is identified by a kind tag ("CycleDetected", "TierViolation", ...) plus the JSON-encoded error payload.
  * This is a thin surface — the real LLM-facing explanation is a pattern match in the tool handler.
  */
final case class ExplainInvariantViolationInput(errorKind: String, errorJson: String)
object ExplainInvariantViolationInput:
  given Schema[ExplainInvariantViolationInput] = DeriveSchema.gen

/** Input for the listInvariants tool. No parameters — returns the full invariant catalog. */
final case class ListInvariantsInput()
object ListInvariantsInput:
  given Schema[ListInvariantsInput] = DeriveSchema.gen
