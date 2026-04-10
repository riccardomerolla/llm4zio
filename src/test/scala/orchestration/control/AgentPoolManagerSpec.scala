package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.ConfigRepository
import agent.entity.{ Agent, AgentPermissions, AgentRepository, TrustLevel }
import orchestration.entity.{ AgentPoolManager, PoolError, SlotHandle }
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId
import shared.testfixtures.*

object AgentPoolManagerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T12:00:00Z")

  final private case class StubAgentRepository(agents: List[Agent]) extends AgentRepository:
    override def append(event: _root_.agent.entity.AgentEvent): IO[PersistenceError, Unit] =
      ZIO.dieMessage("unused")
    override def get(id: AgentId): IO[PersistenceError, Agent]                             =
      ZIO.fromOption(agents.find(_.id == id)).orElseFail(PersistenceError.NotFound("agent", id.value))
    override def list(includeDeleted: Boolean): IO[PersistenceError, List[Agent]]          =
      ZIO.succeed(agents)
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]             =
      ZIO.succeed(agents.find(_.name.equalsIgnoreCase(name)))

  private def agent(
    name: String,
    maxConcurrentRuns: Int,
    maxEstimatedTokens: Option[Long] = None,
  ): Agent =
    Agent(
      id = AgentId(name),
      name = name,
      description = s"Agent $name",
      cliTool = "codex",
      capabilities = Nil,
      defaultModel = None,
      systemPrompt = None,
      maxConcurrentRuns = maxConcurrentRuns,
      envVars = Map.empty,
      timeout = java.time.Duration.ofMinutes(5),
      enabled = true,
      createdAt = now,
      updatedAt = now,
      trustLevel = TrustLevel.Standard,
      permissions = AgentPermissions.defaults(
        trustLevel = TrustLevel.Standard,
        cliTool = "codex",
        timeout = java.time.Duration.ofMinutes(5),
        maxEstimatedTokens = maxEstimatedTokens,
      ),
    )

  private def makeManager(
    settings: Map[String, String] = Map.empty,
    agents: List[Agent] = List(agent("coder", 1)),
  ) =
    for
      settingsRef <- Ref.make(settings)
      layer        =
        (
          ZLayer.succeed[ConfigRepository](new MutableConfigRepository(settingsRef)) ++
            ZLayer.succeed[AgentRepository](StubAgentRepository(agents))
        ) >>> AgentPoolManagerLive.live
      manager     <- ZIO.service[AgentPoolManager].provideLayer(layer)
    yield (manager, settingsRef)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AgentPoolManagerSpec")(
      test("acquireSlot and releaseSlot update available capacity") {
        for
          (manager, _) <- makeManager()
          before       <- manager.availableSlots("coder")
          slot         <- manager.acquireSlot("coder")
          during       <- manager.availableSlots("coder")
          _            <- manager.releaseSlot(slot)
          after        <- manager.availableSlots("coder")
        yield assertTrue(before == 1, during == 0, after == 1)
      },
      test("acquireSlot waits until a slot is released") {
        for
          (manager, _) <- makeManager()
          first        <- manager.acquireSlot("coder")
          waiter       <- manager.acquireSlot("coder").fork
          _            <- TestClock.adjust(50.millis)
          pending      <- waiter.poll
          _            <- manager.releaseSlot(first)
          second       <- waiter.join
        yield assertTrue(pending.isEmpty, second.agentName == "coder")
      },
      test("config override sets the initial pool ceiling") {
        for
          (manager, _) <- makeManager(settings = Map(AgentPoolManager.configKey("coder") -> "2"))
          before       <- manager.availableSlots("coder")
          first        <- manager.acquireSlot("coder")
          after        <- manager.availableSlots("coder")
          _            <- manager.releaseSlot(first)
        yield assertTrue(before == 2, after == 1)
      },
      test("resize increases available capacity immediately") {
        for
          (manager, _) <- makeManager()
          first        <- manager.acquireSlot("coder")
          before       <- manager.availableSlots("coder")
          _            <- manager.resize("coder", 2)
          after        <- manager.availableSlots("coder")
          _            <- manager.releaseSlot(first)
        yield assertTrue(before == 0, after == 1)
      },
      test("recordTokenUsage pauses an agent after exceeding its token budget") {
        for
          (manager, _) <- makeManager(agents = List(agent("coder", 1, maxEstimatedTokens = Some(100))))
          _            <- manager.recordTokenUsage("coder", 60)
          blocked       = manager.recordTokenUsage("coder", 50).either
          result       <- blocked
          paused       <- manager.isPaused("coder")
          denied       <- manager.acquireSlot("coder").either
        yield assertTrue(
          result == Left(PoolError.CostLimitExceeded("coder", 100L)),
          paused,
          denied == Left(PoolError.AgentPaused("coder", "Token budget exceeded: 110 > 100")),
        )
      },
    )
