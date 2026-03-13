package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import agent.entity.{ Agent, AgentRepository }
import db.{ ConfigRepository, CustomAgentRow, PersistenceError as DbPersistenceError, SettingRow, WorkflowRow }
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId

object AgentPoolManagerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T12:00:00Z")

  final private case class StubConfigRepository(settingsRef: Ref[Map[String, String]]) extends ConfigRepository:
    override def getAllSettings: IO[DbPersistenceError, List[SettingRow]]                           =
      settingsRef.get.map(_.toList.map { case (key, value) => SettingRow(key, value, now) })
    override def getSetting(key: String): IO[DbPersistenceError, Option[SettingRow]]                =
      settingsRef.get.map(_.get(key).map(value => SettingRow(key, value, now)))
    override def upsertSetting(key: String, value: String): IO[DbPersistenceError, Unit]            =
      settingsRef.update(_.updated(key, value))
    override def deleteSetting(key: String): IO[DbPersistenceError, Unit]                           =
      settingsRef.update(_ - key)
    override def deleteSettingsByPrefix(prefix: String): IO[DbPersistenceError, Unit]               =
      settingsRef.update(_.filterNot(_._1.startsWith(prefix)))
    override def createWorkflow(workflow: WorkflowRow): IO[DbPersistenceError, Long]                = ZIO.dieMessage("unused")
    override def getWorkflow(id: Long): IO[DbPersistenceError, Option[WorkflowRow]]                 = ZIO.dieMessage("unused")
    override def getWorkflowByName(name: String): IO[DbPersistenceError, Option[WorkflowRow]]       =
      ZIO.dieMessage("unused")
    override def listWorkflows: IO[DbPersistenceError, List[WorkflowRow]]                           = ZIO.dieMessage("unused")
    override def updateWorkflow(workflow: WorkflowRow): IO[DbPersistenceError, Unit]                = ZIO.dieMessage("unused")
    override def deleteWorkflow(id: Long): IO[DbPersistenceError, Unit]                             = ZIO.dieMessage("unused")
    override def createCustomAgent(agent: CustomAgentRow): IO[DbPersistenceError, Long]             = ZIO.dieMessage("unused")
    override def getCustomAgent(id: Long): IO[DbPersistenceError, Option[CustomAgentRow]]           = ZIO.dieMessage("unused")
    override def getCustomAgentByName(name: String): IO[DbPersistenceError, Option[CustomAgentRow]] =
      ZIO.dieMessage("unused")
    override def listCustomAgents: IO[DbPersistenceError, List[CustomAgentRow]]                     = ZIO.dieMessage("unused")
    override def updateCustomAgent(agent: CustomAgentRow): IO[DbPersistenceError, Unit]             = ZIO.dieMessage("unused")
    override def deleteCustomAgent(id: Long): IO[DbPersistenceError, Unit]                          = ZIO.dieMessage("unused")

  final private case class StubAgentRepository(agents: List[Agent]) extends AgentRepository:
    override def append(event: _root_.agent.entity.AgentEvent): IO[PersistenceError, Unit] =
      ZIO.dieMessage("unused")
    override def get(id: AgentId): IO[PersistenceError, Agent]                             =
      ZIO.fromOption(agents.find(_.id == id)).orElseFail(PersistenceError.NotFound("agent", id.value))
    override def list(includeDeleted: Boolean): IO[PersistenceError, List[Agent]]          =
      ZIO.succeed(agents)
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]             =
      ZIO.succeed(agents.find(_.name.equalsIgnoreCase(name)))

  private def agent(name: String, maxConcurrentRuns: Int): Agent =
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
    )

  private def makeManager(
    settings: Map[String, String] = Map.empty,
    agents: List[Agent] = List(agent("coder", 1)),
  ) =
    for
      settingsRef <- Ref.make(settings)
      layer        =
        (
          ZLayer.succeed[ConfigRepository](StubConfigRepository(settingsRef)) ++
            ZLayer.succeed[AgentRepository](StubAgentRepository(agents))
        ) >>> AgentPoolManager.live
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
    )
