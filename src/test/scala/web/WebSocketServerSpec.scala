package web

import java.nio.file.Path

import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

import _root_.models.*
import core.*
import db.*
import gateway.*
import orchestration.*
import web.ws.{ ClientMessage, ServerMessage }

object WebSocketServerSpec extends ZIOSpecDefault:

  private val stubOrchestrator: MigrationOrchestrator = new MigrationOrchestrator:
    override def runFullMigration(
      sourcePath: Path,
      outputPath: Path,
    ): ZIO[Any, OrchestratorError, MigrationResult] =
      ZIO.fail(OrchestratorError.Interrupted("stub"))
    override def runFullMigrationWithProgress(
      sourcePath: Path,
      outputPath: Path,
      onProgress: PipelineProgressUpdate => UIO[Unit],
    ): ZIO[Any, OrchestratorError, MigrationResult] =
      ZIO.fail(OrchestratorError.Interrupted("stub"))
    override def runStep(step: MigrationStep): ZIO[Any, OrchestratorError, StepResult]                     =
      ZIO.fail(OrchestratorError.Interrupted("stub"))
    override def runStepStreaming(step: MigrationStep): ZStream[Any, OrchestratorError, StepProgressEvent] =
      ZStream.fail(OrchestratorError.Interrupted("stub"))
    override def startMigration(config: MigrationConfig): IO[OrchestratorError, Long]                      = ZIO.succeed(1L)
    override def cancelMigration(runId: Long): IO[OrchestratorError, Unit]                                 = ZIO.unit
    override def getRunStatus(runId: Long): IO[PersistenceError, Option[MigrationRunRow]]                  = ZIO.none
    override def listRuns(page: Int, pageSize: Int): IO[PersistenceError, List[MigrationRunRow]]           =
      ZIO.succeed(Nil)
    override def subscribeToProgress(runId: Long): UIO[Dequeue[ProgressUpdate]]                            =
      Queue.unbounded[ProgressUpdate].map(identity)

  private val stubWorkflowService: WorkflowService = new WorkflowService:
    override def createWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Long]          = ZIO.succeed(1L)
    override def getWorkflow(id: Long): IO[WorkflowServiceError, Option[WorkflowDefinition]]           = ZIO.none
    override def getWorkflowByName(name: String): IO[WorkflowServiceError, Option[WorkflowDefinition]] = ZIO.none
    override def listWorkflows: IO[WorkflowServiceError, List[WorkflowDefinition]]                     = ZIO.succeed(Nil)
    override def updateWorkflow(workflow: WorkflowDefinition): IO[WorkflowServiceError, Unit]          = ZIO.unit
    override def deleteWorkflow(id: Long): IO[WorkflowServiceError, Unit]                              = ZIO.unit

  private val stubRepository: MigrationRepository = new MigrationRepository:
    override def createRun(run: MigrationRunRow): IO[PersistenceError, Long]                             = ZIO.succeed(1L)
    override def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit]                             = ZIO.unit
    override def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]]                         = ZIO.none
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]]          = ZIO.succeed(Nil)
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                         = ZIO.unit
    override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]                        = ZIO.unit
    override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]]                    = ZIO.succeed(Nil)
    override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]                    = ZIO.succeed(1L)
    override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]             = ZIO.succeed(Nil)
    override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]                 = ZIO.unit
    override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]]            = ZIO.succeed(Nil)
    override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]                           = ZIO.succeed(1L)
    override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] = ZIO.none
    override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]                         = ZIO.unit
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                                  = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                       = ZIO.none
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]                   = ZIO.unit

  private val stubAbortRegistry: StreamAbortRegistry = new StreamAbortRegistry:
    override def register(conversationId: Long, cancel: UIO[Unit]): UIO[Unit] = ZIO.unit
    override def abort(conversationId: Long): UIO[Boolean]                    = ZIO.succeed(false)
    override def unregister(conversationId: Long): UIO[Unit]                  = ZIO.unit

  private val stubActivityHub: ActivityHub = new ActivityHub:
    override def publish(event: _root_.models.ActivityEvent): UIO[Unit] = ZIO.unit
    override def subscribe: UIO[Dequeue[_root_.models.ActivityEvent]]   =
      Queue.unbounded[_root_.models.ActivityEvent].map(identity)

  private val stubLogTailer: LogTailer = new LogTailer:
    override def tail(
      path: Path,
      levels: Set[core.LogLevel],
      search: Option[String],
      initialLines: Int,
    ): ZStream[Any, LogTailerError, LogEvent] =
      ZStream.empty

  private val stubHealthMonitor: HealthMonitor = new HealthMonitor:
    override def snapshot: UIO[HealthSnapshot] =
      ZIO.dieMessage("unused in WebSocketServerSpec")

    override def history(limit: Int): UIO[List[HealthSnapshot]] =
      ZIO.succeed(Nil)

    override def stream(interval: Duration): ZStream[Any, Nothing, HealthSnapshot] =
      ZStream.empty

  private val stubControlPlane: OrchestratorControlPlane = new OrchestratorControlPlane:
    override def startWorkflow(
      runId: String,
      workflowId: Long,
      definition: WorkflowDefinition,
    ): ZIO[Any, ControlPlaneError, String] = ZIO.succeed("corr")
    override def routeStep(
      runId: String,
      step: MigrationStep,
      capabilities: List[AgentCapability],
    ): ZIO[Any, ControlPlaneError, String] = ZIO.succeed("agent-1")
    override def allocateResource(runId: String): ZIO[Any, ControlPlaneError, Int]                            = ZIO.succeed(0)
    override def releaseResource(runId: String, slot: Int): ZIO[Any, ControlPlaneError, Unit]                 = ZIO.unit
    override def publishEvent(event: ControlPlaneEvent): ZIO[Any, ControlPlaneError, Unit]                    = ZIO.unit
    override def subscribeToEvents(runId: String): ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]            =
      Queue.unbounded[ControlPlaneEvent].map(identity)
    override def getActiveRuns: ZIO[Any, ControlPlaneError, List[ActiveRun]]                                  = ZIO.succeed(Nil)
    override def getRunState(runId: String): ZIO[Any, ControlPlaneError, Option[ActiveRun]]                   = ZIO.none
    override def updateRunState(runId: String, newState: WorkflowRunState): ZIO[Any, ControlPlaneError, Unit] = ZIO.unit
    override def executeCommand(command: ControlCommand): ZIO[Any, ControlPlaneError, Unit]                   = ZIO.unit
    override def getResourceState: ZIO[Any, ControlPlaneError, ResourceAllocationState]                       =
      ZIO.succeed(ResourceAllocationState(1, 0, Nil, None))
    override def getAgentMonitorSnapshot: ZIO[Any, ControlPlaneError, AgentMonitorSnapshot]                   =
      Clock.instant.map(ts => AgentMonitorSnapshot(ts, Nil))
    override def getAgentExecutionHistory(limit: Int): ZIO[Any, ControlPlaneError, List[AgentExecutionEvent]] =
      ZIO.succeed(Nil)
    override def pauseAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                    = ZIO.unit
    override def resumeAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                   = ZIO.unit
    override def abortAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]                    = ZIO.unit

  def spec: Spec[TestEnvironment, Any] = suite("WebSocketServerSpec")(
    test("WebSocketServer creates routes at ws/console") {
      for
        channelsRef <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
        registry     = ChannelRegistryLive(channelsRef)
        wsChannel   <- WebSocketChannel.make()
        _           <- registry.register(wsChannel)
        server       =
          WebSocketServerLive(
            stubOrchestrator,
            stubRepository,
            stubWorkflowService,
            registry,
            stubAbortRegistry,
            stubActivityHub,
            stubLogTailer,
            stubHealthMonitor,
            stubControlPlane,
          )
      yield assertTrue(server.routes.routes.nonEmpty)
    },
    test("ClientMessage Subscribe round-trips through JSON") {
      val msg  = ClientMessage.Subscribe("runs:1:progress")
      val json = msg.toJson
      assertTrue(json.fromJson[ClientMessage] == Right(msg))
    },
    test("ServerMessage Event round-trips through JSON") {
      val msg  = ServerMessage.Event("runs:1:progress", "phase-progress", "<div/>", 123L)
      val json = msg.toJson
      assertTrue(json.fromJson[ServerMessage] == Right(msg))
    },
  ) @@ TestAspect.sequential
