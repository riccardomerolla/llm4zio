package orchestration.control

import java.time.{ Duration, Instant }

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*

import _root_.agent.entity.{
  Agent,
  AgentPermissions,
  TrustLevel,
}
import _root_.config.entity.ConfigRepository
import agent.entity.AgentRepository
import orchestration.entity.{ AgentPoolManager, PoolError }
import shared.ids.Ids.AgentId
import shared.testfixtures.{ StubAgentRepository, StubConfigRepository }

object AgentPoolManagerLiveSpec extends ZIOSpecDefault:

  private val now: Instant = Instant.parse("2026-04-25T12:00:00Z")

  /** Build a minimal Agent for pool tests.
    *
    * Token limit is wired through `AgentPermissions.defaults` then the
    * `resources` lens is overridden when `maxTokens` is provided.
    */
  private def mkAgent(name: String, maxRuns: Int, maxTokens: Option[Long] = None): Agent =
    val basePerms = AgentPermissions.defaults(
      trustLevel = TrustLevel.Standard,
      cliTool = "gemini",
      timeout = Duration.ofMinutes(30),
      maxEstimatedTokens = maxTokens,
    )
    Agent(
      id = AgentId(s"agent-$name"),
      name = name,
      description = s"stub agent $name",
      cliTool = "gemini",
      capabilities = Nil,
      defaultModel = None,
      systemPrompt = None,
      maxConcurrentRuns = maxRuns,
      envVars = Map.empty,
      timeout = Duration.ofMinutes(30),
      enabled = true,
      createdAt = now,
      updatedAt = now,
      permissions = basePerms,
    )

  /** Build the AgentPoolManager layer from stub repos. */
  private def poolLayer(agents: List[Agent]): ZLayer[Any, Nothing, AgentPoolManager] =
    val cfgLayer: ZLayer[Any, Nothing, ConfigRepository] =
      ZLayer.fromZIO(StubConfigRepository.make())
    val agentLayer: ZLayer[Any, Nothing, AgentRepository] =
      ZLayer.fromZIO(StubAgentRepository.make(agents))
    (cfgLayer ++ agentLayer) >>> AgentPoolManagerLive.live

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("AgentPoolManagerLiveSpec")(
      test("acquireSlot up to maxConcurrentRuns succeeds; the next acquire blocks until release") {
        for
          pool  <- ZIO.service[AgentPoolManager]
          h1    <- pool.acquireSlot("dispatcher")
          h2    <- pool.acquireSlot("dispatcher")
          avail <- pool.availableSlots("dispatcher")
          // Third acquire must block — fork it so the test can proceed
          fiber <- pool.acquireSlot("dispatcher").fork
          // Give the fiber a moment to block (it enqueues as a waiter)
          _     <- ZIO.sleep(50.millis)
          // Release one of the held slots — the waiter should be granted
          _     <- pool.releaseSlot(h1)
          // The forked fiber must complete within the timeout
          h3    <- fiber.join.timeoutFail("third acquire was not granted after release")(5.seconds)
          _     <- pool.releaseSlot(h2)
          _     <- pool.releaseSlot(h3)
        yield assertTrue(avail == 0, h3.agentName == "dispatcher")
      }.provideLayer(poolLayer(List(mkAgent("dispatcher", maxRuns = 2)))) @@ withLiveClock @@ timeout(10.seconds),

      test("releaseSlot grants the next waiter") {
        for
          pool  <- ZIO.service[AgentPoolManager]
          h1    <- pool.acquireSlot("worker")
          // Second acquire blocks immediately — capacity is 1
          fiber <- pool.acquireSlot("worker").fork
          _     <- ZIO.sleep(50.millis)
          _     <- pool.releaseSlot(h1)
          h2    <- fiber.join.timeoutFail("waiter not granted after releaseSlot")(5.seconds)
          _     <- pool.releaseSlot(h2)
        yield assertTrue(h2.agentName == "worker")
      }.provideLayer(poolLayer(List(mkAgent("worker", maxRuns = 1)))) @@ withLiveClock @@ timeout(10.seconds),

      test("isPaused becomes true after token budget breach; subsequent acquireSlot fails AgentPaused") {
        val agentName = "token-agent"
        for
          pool        <- ZIO.service[AgentPoolManager]
          h1          <- pool.acquireSlot(agentName)
          // Record usage that exceeds the 100-token budget
          usageExit   <- pool.recordTokenUsage(agentName, 150L).exit
          paused      <- pool.isPaused(agentName)
          acquireExit <- pool.acquireSlot(agentName).exit
          _           <- pool.releaseSlot(h1)
        yield
          assert(usageExit)(fails(equalTo(PoolError.CostLimitExceeded(agentName, 100L)))) &&
          assertTrue(paused) &&
          assert(acquireExit)(fails(isSubtype[PoolError.AgentPaused](anything)))
      }.provideLayer(poolLayer(List(mkAgent("token-agent", maxRuns = 2, maxTokens = Some(100L))))),

      test("recordTokenUsage accumulates across calls; over-limit call fails CostLimitExceeded") {
        val agentName = "accumulate-agent"
        for
          pool    <- ZIO.service[AgentPoolManager]
          _       <- pool.recordTokenUsage(agentName, 30L)  // total 30 — ok
          _       <- pool.recordTokenUsage(agentName, 30L)  // total 60 — ok
          // Third call pushes total to 110, exceeding the 100-token limit
          exit    <- pool.recordTokenUsage(agentName, 50L).exit
        yield assert(exit)(fails(equalTo(PoolError.CostLimitExceeded(agentName, 100L))))
      }.provideLayer(poolLayer(List(mkAgent("accumulate-agent", maxRuns = 2, maxTokens = Some(100L))))),
    )
