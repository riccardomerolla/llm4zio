package bankmod.mcp.prompts

import zio.schema.{ DeriveSchema, Schema }

/** MCP prompt-generation primitives. Pure functions that turn typed parameters into a list of `PromptMessage` values
  * the client LLM uses to launch a templated workflow.
  *
  * MVP design: we don't depend on `com.jamesward.zio-http-mcp`'s `McpPrompt` class here — the server-assembly step
  * wraps these into library values. Testing the logic here means we can verify prompt quality without booting a server.
  */
object BankmodPrompts:

  enum Role:
    case User, Assistant

  object Role:
    given Schema[Role] = DeriveSchema.gen

  final case class PromptMessage(role: Role, content: String)
  object PromptMessage:
    given Schema[PromptMessage] = DeriveSchema.gen

  final case class Prompt(name: String, description: String, messages: List[PromptMessage])
  object Prompt:
    given Schema[Prompt] = DeriveSchema.gen

  // ── addService ─────────────────────────────────────────────────────────────

  final case class AddServiceParams(
    serviceId: String,
    tier: String,  // "Tier1" | "Tier2" | "Tier3"
    owner: String, // "Platform" | "Product" | ...
    reason: String, // why we're adding it
  )
  object AddServiceParams:
    given Schema[AddServiceParams] = DeriveSchema.gen

  def addService(p: AddServiceParams): Prompt =
    Prompt(
      name = "addService",
      description = "Propose adding a new service to the bankmod graph.",
      messages = List(
        PromptMessage(
          Role.User,
          s"""I want to add a new service to the bankmod services graph.
             |
             |Proposed service id: ${p.serviceId}
             |Tier: ${p.tier}
             |Owner: ${p.owner}
             |Reason: ${p.reason}
             |
             |Please:
             |1. Read the current graph via the resource graph://full.
             |2. Propose a JSON patch (full new Graph) introducing this service with reasonable
             |   SLA defaults and no outbound edges yet.
             |3. Call the tool validateEvolution with your patch — fix any invariant violations.
             |4. Once validation passes, call proposeService to commit.""".stripMargin,
        )
      ),
    )

  // ── migrateEndpoint ────────────────────────────────────────────────────────

  final case class MigrateEndpointParams(
    serviceId: String,
    oldPort: String,
    newPort: String,
    protocol: String, // "rest" | "grpc" | "event" | "graphql" | "soap"
  )
  object MigrateEndpointParams:
    given Schema[MigrateEndpointParams] = DeriveSchema.gen

  def migrateEndpoint(p: MigrateEndpointParams): Prompt =
    Prompt(
      name = "migrateEndpoint",
      description = "Migrate a service's endpoint from one port to another.",
      messages = List(
        PromptMessage(
          Role.User,
          s"""I want to migrate the endpoint of service '${p.serviceId}' from port '${p.oldPort}' to '${p.newPort}' (protocol: ${p.protocol}).
             |
             |Please:
             |1. Read graph://service/${p.serviceId}.
             |2. Read graph://slice/${p.serviceId}/1 to identify callers and callees affected by the change.
             |3. Propose a graph patch that (a) declares the new inbound port, (b) updates every caller's outbound edge, (c) keeps the old port declared during the transition.
             |4. Call validateEvolution with the patch. Pay particular attention to UnknownPort errors — they indicate a caller you missed.
             |5. Once green, call proposeService.""".stripMargin,
        )
      ),
    )

  // ── introduceEvent ─────────────────────────────────────────────────────────

  final case class IntroduceEventParams(
    fromService: String,
    toService: String,
    topic: String,
    reason: String,
  )
  object IntroduceEventParams:
    given Schema[IntroduceEventParams] = DeriveSchema.gen

  def introduceEvent(p: IntroduceEventParams): Prompt =
    Prompt(
      name = "introduceEvent",
      description = "Replace or augment a synchronous dependency with an event-driven edge.",
      messages = List(
        PromptMessage(
          Role.User,
          s"""I want to introduce an event-driven edge from '${p.fromService}' to '${p.toService}' on topic '${p.topic}'.
             |
             |Rationale: ${p.reason}
             |
             |Please:
             |1. Read graph://service/${p.fromService} and graph://service/${p.toService}.
             |2. If a synchronous edge already exists between these services, decide whether to keep it, replace it, or run both in parallel during migration.
             |3. Propose a patch introducing the event edge (Protocol.Event, Consistency.Eventual is usually fine unless this is a financial transaction).
             |4. Call validateEvolution. If this involves a cycle-breaking migration, confirm there are no remaining CycleDetected errors.
             |5. Call proposeService to commit.""".stripMargin,
        )
      ),
    )

  /** Discovery list — used at server assembly to register all three prompts. */
  val all: List[String] = List("addService", "migrateEndpoint", "introduceEvent")
