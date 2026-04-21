package bankmod.mcp.tools

import zio.*
import zio.schema.{ DeriveSchema, Schema }

object ExplainInvariantViolationTool:

  final case class Output(
    kind: String,
    explanation: String,
    fixSuggestions: List[String],
    rawErrorJson: String,
  )
  object Output:
    given Schema[Output] = DeriveSchema.gen

  def run(input: ExplainInvariantViolationInput): Output =
    val (explanation, fixes) = lookupKind(input.errorKind)
    Output(
      kind = input.errorKind,
      explanation = explanation,
      fixSuggestions = fixes,
      rawErrorJson = input.errorJson,
    )

  def handle(input: ExplainInvariantViolationInput): ZIO[Any, Nothing, Output] =
    ZIO.succeed(run(input))

  private def lookupKind(kind: String): (String, List[String]) = kind match
    case "CycleDetected"                  =>
      "A directed cycle exists among services. Cycles block correct startup ordering, make blast-radius analysis intractable, and usually indicate that synchronous calls should be replaced with events." ->
        List(
          "Introduce an asynchronous event edge to break the sync dependency",
          "Extract the shared responsibility into a new upstream service",
          "Split the offending service into command/query halves",
        )
    case "TierViolation"                  =>
      "A high-criticality service synchronously depends on a lower-criticality one. If the low-tier service degrades, the high-tier guarantee cannot be met." ->
        List(
          "Downgrade the critical tier (if the true SLA is lower than declared)",
          "Replace the sync edge with an event edge and a local cache",
          "Elevate the callee to match the caller tier",
        )
    case "OrphanEdge"                     =>
      "An outbound edge points at a service that is not present in the graph. This is either a typo, a stale reference, or a missing service declaration." ->
        List(
          "Add the missing target service to the graph",
          "Remove the outbound edge if it is stale",
          "Fix the target service id typo",
        )
    case "UnknownPort"                    =>
      "An edge targets a port that is not declared in the target service's inbound set. The contract between caller and callee is broken." ->
        List(
          "Declare the expected inbound port on the target service",
          "Correct the port name on the caller side",
        )
    case "PiiBoundaryCrossed"             =>
      "A service carrying PII routes to a service not on the configured allow-list. This is a GDPR / data-residency concern." ->
        List(
          "Add the target to the PII allow-list if the data-sharing agreement permits it",
          "Route the data through a pseudonymizer service first",
          "Remove the edge if no valid reason to send PII exists",
        )
    case "WeakConsistencyOnFinancialEdge" =>
      "A financial-transaction edge declares eventual consistency. Money movements require strong consistency to avoid duplicate or lost postings." ->
        List(
          "Upgrade the edge's consistency declaration to Strong",
          "Introduce an idempotency key + ledger reconciliation if the edge must remain eventual",
        )
    case "MissingPackedDecimalGuard"      =>
      "A packed-decimal edge has no declared conversion guard. IBM packed-decimal has edge cases (negative sign nibble, overflow) that must be explicitly handled at each modernization seam." ->
        List(
          "Add the edge to the guardedEdges config once a guard is in place",
          "Introduce a conversion adapter with round-trip tests",
        )
    case other                            =>
      s"Unknown invariant kind: '$other'. Call listInvariants to see the full catalog." -> Nil
