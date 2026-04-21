package agent.control

import java.time.{ Duration, Instant }

import zio.Scope
import zio.test.*

import agent.entity.Agent
import shared.ids.Ids.AgentId

object AgentMatchingSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-02T10:00:00Z")

  private def mkAgent(
    id: String,
    name: String,
    caps: List[String],
    maxConcurrentRuns: Int,
    enabled: Boolean = true,
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
    )
