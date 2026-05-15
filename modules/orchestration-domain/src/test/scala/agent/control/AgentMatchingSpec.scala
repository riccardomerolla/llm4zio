package agent.control

import java.time.{ Duration, Instant }

import zio.Scope
import zio.test.*

import agent.entity.{ Agent, EmployeeRole }
import issues.entity.TicketLane
import shared.ids.Ids.AgentId

object AgentMatchingSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-02T10:00:00Z")

  private def mkAgent(
    id: String,
    name: String,
    caps: List[String],
    maxConcurrentRuns: Int,
    enabled: Boolean = true,
    role: EmployeeRole = EmployeeRole.Custom,
  ): Agent =
    Agent(
      id = AgentId(id),
      name = name,
      description = s"agent $name",
      cliTool = "gemini",
      capabilities = caps,
      defaultModel = None,
      systemPrompt = None,
      maxConcurrentRuns = maxConcurrentRuns,
      envVars = Map.empty,
      timeout = Duration.ofMinutes(30),
      enabled = enabled,
      createdAt = now,
      updatedAt = now,
      role = role,
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("AgentMatchingSpec")(
      test("ranks by score then active runs") {
        val agents = List(
          mkAgent("a1", "alpha", List("scala", "testing"), maxConcurrentRuns = 2),
          mkAgent("a2", "beta", List("scala"), maxConcurrentRuns = 2),
          mkAgent("a3", "gamma", List("scala", "testing"), maxConcurrentRuns = 2),
        )
        val ranked = AgentMatching.rankAgents(
          agents,
          requiredCapabilities = List("scala", "testing"),
          activeRunsByAgent = Map("alpha" -> 1, "beta" -> 0, "gamma" -> 0),
        )

        assertTrue(
          ranked.map(_.agent.name) == List("gamma", "alpha", "beta"),
          ranked.head.score == 1.0,
          ranked.last.score == 0.5,
        )
      },
      test("excludes agents at max concurrent runs") {
        val agents = List(
          mkAgent("a1", "alpha", List("scala", "testing"), maxConcurrentRuns = 1),
          mkAgent("a2", "beta", List("scala", "testing"), maxConcurrentRuns = 2),
        )
        val ranked = AgentMatching.rankAgents(
          agents,
          requiredCapabilities = List("scala"),
          activeRunsByAgent = Map("alpha" -> 1, "beta" -> 1),
        )

        assertTrue(
          ranked.map(_.agent.name) == List("beta")
        )
      },
      test("pickEmployeeForLane picks the role-matched employee with occupancy zero") {
        val agents = List(
          mkAgent("a1", "pat", Nil, maxConcurrentRuns = 1, role = EmployeeRole.PM),
          mkAgent("a2", "alex", Nil, maxConcurrentRuns = 1, role = EmployeeRole.FrontendEng),
          mkAgent("a3", "ben", Nil, maxConcurrentRuns = 1, role = EmployeeRole.BackendEng),
          mkAgent("a4", "dana", Nil, maxConcurrentRuns = 1, role = EmployeeRole.QA),
          mkAgent("a5", "rex", Nil, maxConcurrentRuns = 1, role = EmployeeRole.Reviewer),
        )

        assertTrue(
          AgentMatching.pickEmployeeForLane(TicketLane.Frontend, agents, Map.empty).map(_.name).contains("alex"),
          AgentMatching.pickEmployeeForLane(TicketLane.Backend, agents, Map.empty).map(_.name).contains("ben"),
          AgentMatching.pickEmployeeForLane(TicketLane.Testing, agents, Map.empty).map(_.name).contains("dana"),
          AgentMatching.pickEmployeeForLane(TicketLane.Triage, agents, Map.empty).map(_.name).contains("pat"),
          AgentMatching.pickEmployeeForLane(TicketLane.Review, agents, Map.empty).map(_.name).contains("rex"),
        )
      },
      test("pickEmployeeForLane returns None when the role-matched employee is busy") {
        val alex = mkAgent("a2", "alex", Nil, maxConcurrentRuns = 1, role = EmployeeRole.FrontendEng)

        assertTrue(
          AgentMatching
            .pickEmployeeForLane(TicketLane.Frontend, List(alex), activeRunsByAgent = Map("alex" -> 1))
            .isEmpty
        )
      },
      test("pickEmployeeForLane falls back to a Custom employee if no role match available") {
        val cody = mkAgent("a1", "cody", Nil, maxConcurrentRuns = 1, role = EmployeeRole.Custom)

        assertTrue(
          AgentMatching
            .pickEmployeeForLane(TicketLane.Frontend, List(cody), activeRunsByAgent = Map.empty)
            .map(_.name)
            .contains("cody")
        )
      },
      test("pickEmployeeForLane skips disabled employees") {
        val alex = mkAgent("a2", "alex", Nil, maxConcurrentRuns = 1, enabled = false, role = EmployeeRole.FrontendEng)

        assertTrue(
          AgentMatching
            .pickEmployeeForLane(TicketLane.Frontend, List(alex), activeRunsByAgent = Map.empty)
            .isEmpty
        )
      },
    )
