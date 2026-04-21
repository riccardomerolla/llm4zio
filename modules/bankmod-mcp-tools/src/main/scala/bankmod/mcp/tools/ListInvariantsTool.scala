package bankmod.mcp.tools

import zio.*
import zio.schema.{ DeriveSchema, Schema }

object ListInvariantsTool:

  final case class InvariantEntry(kind: String, shortDescription: String)
  object InvariantEntry:
    given Schema[InvariantEntry] = DeriveSchema.gen

  final case class Output(invariants: List[InvariantEntry])
  object Output:
    given Schema[Output] = DeriveSchema.gen

  val catalog: List[InvariantEntry] = List(
    InvariantEntry("CycleDetected", "No directed cycles in the service graph."),
    InvariantEntry("TierViolation", "Synchronous edges must not cross from higher to lower criticality tier."),
    InvariantEntry("OrphanEdge", "Outbound edges must target a service that exists in the graph."),
    InvariantEntry("UnknownPort", "Outbound edges must target a declared inbound port on the callee."),
    InvariantEntry(
      "PiiBoundaryCrossed",
      "PII-bearing services may only route to explicitly allowed sinks.",
    ),
    InvariantEntry(
      "WeakConsistencyOnFinancialEdge",
      "Financial-transaction edges require Strong consistency.",
    ),
    InvariantEntry(
      "MissingPackedDecimalGuard",
      "Packed-decimal edges must have a declared conversion guard.",
    ),
  )

  def run(@annotation.unused input: ListInvariantsInput): Output = Output(catalog)

  def handle(input: ListInvariantsInput): ZIO[Any, Nothing, Output] =
    ZIO.succeed(run(input))
