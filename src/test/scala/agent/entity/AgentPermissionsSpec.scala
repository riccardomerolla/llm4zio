package agent.entity

import java.time.{ Duration, Instant }

import zio.json.*
import zio.test.*

import shared.ids.Ids.AgentId

object AgentPermissionsSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T12:00:00Z")

  def spec: Spec[Any, Nothing] =
    suite("AgentPermissionsSpec")(
      test("trust level defaults map to progressively broader capabilities") {
        val untrusted = AgentPermissions.defaults(
          trustLevel = TrustLevel.Untrusted,
          cliTool = "codex",
          timeout = Duration.ofMinutes(5),
          maxEstimatedTokens = Some(1000L),
        )
        val elevated  = AgentPermissions.defaults(
          trustLevel = TrustLevel.Elevated,
          cliTool = "codex",
          timeout = Duration.ofMinutes(5),
          maxEstimatedTokens = Some(2000L),
        )

        assertTrue(
          untrusted.network == NetworkAccessScope.Disabled,
          !untrusted.git.commit,
          !untrusted.git.push,
          untrusted.fileSystem.writeScopes == List(AgentPathScope.Worktree),
          untrusted.resources.maxEstimatedTokens.contains(1000L),
          elevated.network == NetworkAccessScope.Unrestricted,
          elevated.git.commit,
          elevated.git.push,
          elevated.fileSystem.writeScopes.contains(AgentPathScope.CrossWorkspace),
          elevated.resources.maxEstimatedTokens.contains(2000L),
        )
      },
      test("agent json and event reconstruction preserve trust and permissions") {
        val agent = Agent(
          id = AgentId("agent-1"),
          name = "sandboxed",
          description = "Permissioned agent",
          cliTool = "codex",
          capabilities = List("scala"),
          defaultModel = Some("gpt-5.4"),
          systemPrompt = Some("Keep changes scoped."),
          maxConcurrentRuns = 1,
          envVars = Map("MODE" -> "test"),
          timeout = Duration.ofMinutes(15),
          enabled = true,
          createdAt = now,
          updatedAt = now,
          trustLevel = TrustLevel.Limited,
          permissions = AgentPermissions.defaults(
            trustLevel = TrustLevel.Limited,
            cliTool = "codex",
            timeout = Duration.ofMinutes(15),
            maxEstimatedTokens = Some(5000L),
          ),
        )

        val jsonRoundTrip  = agent.toJson.fromJson[Agent]
        val rebuiltFromLog = Agent.fromEvents(List(AgentEvent.Created(agent, now)))

        assertTrue(
          jsonRoundTrip == Right(agent),
          rebuiltFromLog == Right(agent),
        )
      },
    )
