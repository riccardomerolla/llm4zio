package web

import java.nio.file.Path

import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

import _root_.models.*
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

  def spec: Spec[TestEnvironment, Any] = suite("WebSocketServerSpec")(
    test("WebSocketServer creates routes at ws/console") {
      for
        channelsRef <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
        registry     = ChannelRegistryLive(channelsRef)
        wsChannel   <- WebSocketChannel.make()
        _           <- registry.register(wsChannel)
        server       = WebSocketServerLive(stubOrchestrator, stubRepository, stubWorkflowService, registry)
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
