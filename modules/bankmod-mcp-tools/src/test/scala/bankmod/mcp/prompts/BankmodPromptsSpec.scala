package bankmod.mcp.prompts

import zio.test.*

import bankmod.mcp.prompts.BankmodPrompts.*

object BankmodPromptsSpec extends ZIOSpecDefault:

  private def joined(prompt: Prompt): String =
    prompt.messages.map(_.content).mkString("\n")

  private def mentionsAnyTool(body: String): Boolean =
    body.contains("validateEvolution") || body.contains("proposeService")

  def spec = suite("BankmodPrompts")(
    // ── addService ──────────────────────────────────────────────────────────
    test("addService echoes params and has the expected name") {
      val params = AddServiceParams(
        serviceId = "payments-api",
        tier = "Tier1",
        owner = "Platform",
        reason = "New payments frontend",
      )
      val prompt = BankmodPrompts.addService(params)
      val body   = joined(prompt)
      assertTrue(
        prompt.name == "addService",
        prompt.messages.nonEmpty,
        body.contains("payments-api"),
        body.contains("Tier1"),
        body.contains("Platform"),
        body.contains("New payments frontend"),
      )
    },
    test("addService mentions a tool to drive progress") {
      val params = AddServiceParams("svc-x", "Tier2", "Product", "because")
      val body   = joined(BankmodPrompts.addService(params))
      assertTrue(mentionsAnyTool(body))
    },
    // ── migrateEndpoint ─────────────────────────────────────────────────────
    test("migrateEndpoint echoes params and has the expected name") {
      val params = MigrateEndpointParams(
        serviceId = "ledger",
        oldPort = "v1",
        newPort = "v2",
        protocol = "grpc",
      )
      val prompt = BankmodPrompts.migrateEndpoint(params)
      val body   = joined(prompt)
      assertTrue(
        prompt.name == "migrateEndpoint",
        prompt.messages.nonEmpty,
        body.contains("ledger"),
        body.contains("v1"),
        body.contains("v2"),
        body.contains("grpc"),
      )
    },
    test("migrateEndpoint mentions a tool to drive progress") {
      val params = MigrateEndpointParams("ledger", "v1", "v2", "rest")
      val body   = joined(BankmodPrompts.migrateEndpoint(params))
      assertTrue(mentionsAnyTool(body))
    },
    // ── introduceEvent ──────────────────────────────────────────────────────
    test("introduceEvent echoes params and has the expected name") {
      val params = IntroduceEventParams(
        fromService = "orders",
        toService = "notifications",
        topic = "order.placed",
        reason = "Decouple notifications",
      )
      val prompt = BankmodPrompts.introduceEvent(params)
      val body   = joined(prompt)
      assertTrue(
        prompt.name == "introduceEvent",
        prompt.messages.nonEmpty,
        body.contains("orders"),
        body.contains("notifications"),
        body.contains("order.placed"),
        body.contains("Decouple notifications"),
      )
    },
    test("introduceEvent mentions a tool to drive progress") {
      val params = IntroduceEventParams("a", "b", "topic-x", "r")
      val body   = joined(BankmodPrompts.introduceEvent(params))
      assertTrue(mentionsAnyTool(body))
    },
  )
