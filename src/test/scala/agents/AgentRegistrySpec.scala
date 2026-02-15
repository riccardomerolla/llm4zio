package agents

import java.time.Instant

import zio.*
import zio.test.*

import db.CustomAgentRow
import models.*

object AgentRegistrySpec extends ZIOSpecDefault:

  private val now: Instant = Instant.parse("2026-02-13T10:00:00Z")

  private val testLayer: ZLayer[Any, Nothing, AgentRegistry] = AgentRegistry.live

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AgentRegistry")(
    test("findByName should be case-insensitive and trim input") {
      for
        found <- AgentRegistry.findByName("  COBOLanalyzer ")
      yield assertTrue(found.exists(_.name == "cobolAnalyzer"))
    },
    test("loadCustomAgents should merge built-in and unique custom agents") {
      val customAgents = List(
        CustomAgentRow(
          name = "customOne",
          displayName = "Zeta Custom",
          description = Some("My custom"),
          systemPrompt = "Prompt",
          tags = Some("alpha, beta"),
          createdAt = now,
          updatedAt = now,
        ),
        CustomAgentRow(
          name = " customone ",
          displayName = "Should Be Dropped",
          description = Some("Duplicate"),
          systemPrompt = "Prompt",
          tags = Some("ignored"),
          createdAt = now,
          updatedAt = now,
        ),
        CustomAgentRow(
          name = "cobolAnalyzer",
          displayName = "Conflicting BuiltIn",
          description = None,
          systemPrompt = "Prompt",
          tags = Some("x"),
          createdAt = now,
          updatedAt = now,
        ),
        CustomAgentRow(
          name = "customTwo",
          displayName = "Alpha Custom",
          description = None,
          systemPrompt = "Prompt",
          tags = Some(" gamma , ,delta "),
          createdAt = now,
          updatedAt = now,
        ),
      )

      for
        loaded    <- AgentRegistry.loadCustomAgents(customAgents)
        all       <- AgentRegistry.getAllAgents
        customOnly = all.filter(_.agentType == AgentType.Custom)
      yield assertTrue(
        loaded == 2,
        all.count(_.name == "cobolAnalyzer") == 1,
        customOnly.map(_.displayName).sorted == List("Alpha Custom", "Zeta Custom"),
        customOnly.exists(a =>
          a.name == "customOne" &&
          a.description == "My custom" &&
          a.tags == List("alpha", "beta")
        ),
        customOnly.exists(a =>
          a.name == "customTwo" &&
          a.description == "Custom agent" &&
          a.tags == List("gamma", "delta")
        ),
      )
    },
    test("findAgentsWithSkill should filter by skill") {
      for
        agents <- AgentRegistry.findAgentsWithSkill("cobol-parsing")
      yield assertTrue(
        agents.nonEmpty,
        agents.exists(_.name == "cobolAnalyzer"),
      )
    },
    test("findAgentsForStep should filter by supported step") {
      for
        discoveryAgents <- AgentRegistry.findAgentsForStep(MigrationStep.Discovery)
        analysisAgents  <- AgentRegistry.findAgentsForStep(MigrationStep.Analysis)
      yield assertTrue(
        discoveryAgents.exists(_.name == "cobolDiscovery"),
        analysisAgents.exists(_.name == "cobolAnalyzer"),
        analysisAgents.exists(_.name == "businessLogicExtractor"),
      )
    },
    test("findAgentsForTransformation should filter by input/output types") {
      for
        agents <- AgentRegistry.findAgentsForTransformation("CobolFile", "CobolAnalysis")
      yield assertTrue(
        agents.nonEmpty,
        agents.exists(_.name == "cobolAnalyzer"),
      )
    },
    test("registerAgent should add new agent") {
      val request = RegisterAgentRequest(
        name = "testAgent",
        displayName = "Test Agent",
        description = "Agent for testing",
        agentType = AgentType.Custom,
        usesAI = true,
        tags = List("test"),
        skills = List(
          AgentSkill(
            skill = "test-skill",
            description = "Testing skill",
            inputTypes = List("String"),
            outputTypes = List("String"),
          )
        ),
        supportedSteps = List(MigrationStep.Analysis),
      )

      for
        registered <- AgentRegistry.registerAgent(request)
        found      <- AgentRegistry.findByName("testAgent")
      yield assertTrue(
        registered.name == "testAgent",
        found.isDefined,
        found.get.displayName == "Test Agent",
        found.get.skills.exists(_.skill == "test-skill"),
      )
    },
    test("recordInvocation should update metrics") {
      for
        before <- AgentRegistry.getMetrics("cobolAnalyzer")
        _      <- AgentRegistry.recordInvocation("cobolAnalyzer", success = true, latencyMs = 100)
        after  <- AgentRegistry.getMetrics("cobolAnalyzer")
      yield assertTrue(
        before.isDefined,
        after.isDefined,
        after.get.invocations == before.get.invocations + 1,
        after.get.successCount == before.get.successCount + 1,
      )
    },
    test("updateHealth should track agent health") {
      for
        before <- AgentRegistry.getHealth("cobolAnalyzer")
        _      <- AgentRegistry.updateHealth("cobolAnalyzer", success = false, Some("Test error"))
        after  <- AgentRegistry.getHealth("cobolAnalyzer")
      yield assertTrue(
        before.isDefined,
        after.isDefined,
        after.get.consecutiveFailures > before.get.consecutiveFailures,
      )
    },
    test("setAgentEnabled should enable/disable agents") {
      for
        _        <- AgentRegistry.setAgentEnabled("cobolAnalyzer", enabled = false)
        disabled <- AgentRegistry.getHealth("cobolAnalyzer")
        _        <- AgentRegistry.setAgentEnabled("cobolAnalyzer", enabled = true)
        enabled  <- AgentRegistry.getHealth("cobolAnalyzer")
      yield assertTrue(
        disabled.exists(!_.isEnabled),
        enabled.exists(_.isEnabled),
      )
    },
    test("getRankedAgents should sort by health and performance") {
      val query = AgentQuery(supportedStep = Some(MigrationStep.Analysis))
      for
        ranked <- AgentRegistry.getRankedAgents(query)
      yield assertTrue(
        ranked.nonEmpty,
        ranked.forall(a => a.supportedSteps.contains(MigrationStep.Analysis)),
      )
    },
    test("findAgents with query should filter correctly") {
      val query = AgentQuery(
        skill = Some("cobol-parsing"),
        supportedStep = Some(MigrationStep.Analysis),
        onlyEnabled = true,
      )
      for
        agents <- AgentRegistry.findAgents(query)
      yield assertTrue(
        agents.nonEmpty,
        agents.forall(a => a.skills.exists(_.skill == "cobol-parsing")),
        agents.forall(a => a.supportedSteps.contains(MigrationStep.Analysis)),
      )
    },
  ).provide(testLayer)
