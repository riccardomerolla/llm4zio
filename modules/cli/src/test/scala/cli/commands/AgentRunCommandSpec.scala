package cli.commands

import java.nio.file.Files
import java.time.{ Duration, Instant }

import zio.*
import zio.test.*

import agent.entity.{ Agent, AgentEvent, AgentRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId

object AgentRunCommandSpec extends ZIOSpecDefault:

  private def mkAgent(name: String, cliTool: String): Agent =
    Agent(
      id = AgentId.generate,
      name = name,
      description = "",
      cliTool = cliTool,
      capabilities = Nil,
      defaultModel = None,
      systemPrompt = None,
      maxConcurrentRuns = 1,
      envVars = Map.empty,
      timeout = Duration.ofMinutes(30),
      enabled = true,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH,
    )

  final class FixedAgentRepo(agents: Map[String, Agent]) extends AgentRepository:
    override def append(event: AgentEvent): IO[PersistenceError, Unit] = ZIO.unit
    override def get(id: AgentId): IO[PersistenceError, Agent]         =
      ZIO.fail(PersistenceError.NotFound("agent", id.value))
    override def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]] =
      ZIO.succeed(agents.values.toList)
    override def findByName(name: String): IO[PersistenceError, Option[Agent]] =
      ZIO.succeed(agents.get(name))

  def spec = suite("AgentRunCommand")(
    test("runs the configured CLI tool and returns exit code 0 on success") {
      val dir   = Files.createTempDirectory("agent-run-spec-").toString
      val agent = mkAgent("echo-bot", "echo")
      val repo  = FixedAgentRepo(Map("echo-bot" -> agent))
      for
        exit <- AgentRunCommand
                  .run(dir, "echo-bot", "hello")
                  .provide(ZLayer.succeed[AgentRepository](repo))
      yield assertTrue(exit == 0)
    },
    test("fails with a helpful error when the agent is not registered") {
      val repo = FixedAgentRepo(Map.empty)
      for
        result <- AgentRunCommand
                    .run("/tmp", "missing", "anything")
                    .provide(ZLayer.succeed[AgentRepository](repo))
                    .either
      yield assertTrue(result.isLeft) &&
        assertTrue(result.left.exists(_.contains("No agent 'missing'")))
    },
    test("slugifies the agent name before lookup") {
      val agent = mkAgent("researcher-bot", "echo")
      val repo  = FixedAgentRepo(Map("researcher-bot" -> agent))
      val dir   = Files.createTempDirectory("agent-run-spec-").toString
      for
        exit <- AgentRunCommand
                  .run(dir, "Researcher Bot", "hi")
                  .provide(ZLayer.succeed[AgentRepository](repo))
      yield assertTrue(exit == 0)
    },
  )
