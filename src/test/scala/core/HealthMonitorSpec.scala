package core

import java.time.Instant

import zio.*
import zio.stream.ZStream
import zio.test.*

import _root_.models.*
import agents.AgentRegistry
import db.*
import gateway.*
import gateway.models.{ NormalizedMessage, SessionKey, SessionScopeStrategy }

object HealthMonitorSpec extends ZIOSpecDefault:

  private val stubRepo: TaskRepository = new TaskRepository:
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]] =
      ZIO.succeed(
        List(
          TaskRunRow(
            id = 9L,
            sourceDir = "/src",
            outputDir = "/out",
            status = RunStatus.Failed,
            startedAt = Instant.parse("2026-02-08T00:00:00Z"),
            completedAt = None,
            totalFiles = 1,
            processedFiles = 0,
            successfulConversions = 0,
            failedConversions = 1,
            currentPhase = Some("analysis"),
            errorMessage = Some("boom"),
          )
        )
      )

    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                           = ZIO.dieMessage("unused")
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                           = ZIO.dieMessage("unused")
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                       = ZIO.dieMessage("unused")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                  = ZIO.dieMessage("unused")
    override def saveReport(report: TaskReportRow): IO[PersistenceError, Long]                    = ZIO.dieMessage("unused")
    override def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]]           = ZIO.dieMessage("unused")
    override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]]     = ZIO.dieMessage("unused")
    override def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long]              = ZIO.dieMessage("unused")
    override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]] =
      ZIO.dieMessage("unused")
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                = ZIO.none
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.unit

  private val stubGateway: GatewayService = new GatewayService:
    override def enqueueInbound(message: NormalizedMessage): UIO[Unit]                                         = ZIO.unit
    override def enqueueOutbound(message: NormalizedMessage): UIO[Unit]                                        = ZIO.unit
    override def processInbound(message: NormalizedMessage): IO[GatewayServiceError, Unit]                     = ZIO.unit
    override def processOutbound(message: NormalizedMessage): IO[GatewayServiceError, List[NormalizedMessage]] =
      ZIO.succeed(Nil)
    override def metrics: UIO[GatewayMetricsSnapshot]                                                          =
      ZIO.succeed(GatewayMetricsSnapshot(enqueued = 10, processed = 8, failed = 2, emittedChunks = 42))

  private val channelWithSession: MessageChannel =
    new MessageChannel:
      private val session                                                                                 = SessionKey("websocket", "1")
      override def name: String                                                                           = "websocket"
      override def scopeStrategy: SessionScopeStrategy                                                    = SessionScopeStrategy.PerConversation
      override def open(sessionKey: SessionKey): IO[MessageChannelError, Unit]                            = ZIO.unit
      override def close(sessionKey: SessionKey): UIO[Unit]                                               = ZIO.unit
      override def closeAll: UIO[Unit]                                                                    = ZIO.unit
      override def receive(message: NormalizedMessage): IO[MessageChannelError, Unit]                     = ZIO.unit
      override def send(message: NormalizedMessage): IO[MessageChannelError, Unit]                        = ZIO.unit
      override def inbound: ZStream[Any, MessageChannelError, NormalizedMessage]                          = ZStream.empty
      override def outbound(sessionKey: SessionKey): ZStream[Any, MessageChannelError, NormalizedMessage] =
        ZStream.empty
      override def activeSessions: UIO[Set[SessionKey]]                                                   = ZIO.succeed(Set(session))

  private val sampleAgent = AgentInfo(
    name = "a1",
    displayName = "Agent A1",
    description = "",
    agentType = AgentType.BuiltIn,
    usesAI = false,
    tags = Nil,
    metrics = AgentMetrics(
      invocations = 1,
      successCount = 1,
      failureCount = 0,
      totalLatencyMs = 10,
      lastInvocation = Some(Instant.parse("2026-02-08T00:00:00Z")),
    ),
    health = AgentHealth(status = AgentHealthStatus.Healthy, isEnabled = true),
  )

  private val stubAgentRegistry: AgentRegistry = new AgentRegistry:
    override def registerAgent(request: RegisterAgentRequest): UIO[AgentInfo]                             = ZIO.succeed(sampleAgent)
    override def findByName(name: String): UIO[Option[AgentInfo]]                                         = ZIO.none
    override def findAgents(query: AgentQuery): UIO[List[AgentInfo]]                                      = ZIO.succeed(List(sampleAgent))
    override def getAllAgents: UIO[List[AgentInfo]]                                                       = ZIO.succeed(List(sampleAgent))
    override def findAgentsWithSkill(skill: String): UIO[List[AgentInfo]]                                 = ZIO.succeed(Nil)
    override def findAgentsForStep(step: TaskStep): UIO[List[AgentInfo]]                                  = ZIO.succeed(Nil)
    override def findAgentsForTransformation(inputType: String, outputType: String): UIO[List[AgentInfo]] =
      ZIO.succeed(Nil)
    override def recordInvocation(agentName: String, success: Boolean, latencyMs: Long): UIO[Unit]        = ZIO.unit
    override def updateHealth(agentName: String, success: Boolean, message: Option[String]): UIO[Unit]    = ZIO.unit
    override def setAgentEnabled(agentName: String, enabled: Boolean): UIO[Unit]                          = ZIO.unit
    override def getMetrics(agentName: String): UIO[Option[AgentMetrics]]                                 = ZIO.none
    override def getHealth(agentName: String): UIO[Option[AgentHealth]]                                   = ZIO.none
    override def loadCustomAgents(customAgents: List[CustomAgentRow]): UIO[Int]                           = ZIO.succeed(0)
    override def getRankedAgents(query: AgentQuery): UIO[List[AgentInfo]]                                 = ZIO.succeed(Nil)

  private def makeRegistry: UIO[ChannelRegistry] =
    for
      ref <- Ref.Synchronized.make(Map("websocket" -> channelWithSession))
    yield ChannelRegistryLive(ref)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("HealthMonitorSpec")(
    test("snapshot aggregates gateway, agents, channels, and errors") {
      for
        started  <- Clock.instant
        history  <- Ref.make(List.empty[HealthSnapshot])
        registry <- makeRegistry
        monitor   = HealthMonitorLive(started, history, stubGateway, registry, stubAgentRegistry, stubRepo)
        snap     <- monitor.snapshot
      yield assertTrue(
        snap.gateway.connectionCount == 2,
        snap.resources.tokenUsageEstimate == 42,
        snap.errors.recentFailures.nonEmpty,
        snap.agents.total == 1,
      )
    }
  )
