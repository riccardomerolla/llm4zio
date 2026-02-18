package integration

import java.net.ServerSocket
import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import db.*
import models.*
import orchestration.MigrationOrchestrator
import orchestration.{ MigrationResult, PipelineProgressUpdate, StepResult }
import web.WebServer
import web.controllers.*

object WebServerIntegrationSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WebServerIntegrationSpec")(
    test("boots serve and responds to GET /") {
      ZIO.scoped {
        for
          port     <- allocatePort
          appLayer  = webLayer
          _        <- WebServer.start("127.0.0.1", port).provideLayer(appLayer).forkScoped
          response <- fetch(port, "/").retry(Schedule.spaced(100.millis) && Schedule.recurs(20))
        yield assertTrue(
          response._1 == 200,
          response._2.contains("Dashboard — COBOL Modernization"),
          response._2.contains("Recent Runs"),
        )
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private val now = Instant.parse("2026-02-08T00:00:00Z")

  private val webLayer: ULayer[WebServer] =
    ZLayer.make[WebServer](
      ZLayer.succeed(TestMigrationRepository),
      ZLayer.succeed(TestOrchestrator),
      RunsController.live,
      AnalysisController.live,
      GraphController.live,
      DashboardController.live,
      WebServer.live,
    )

  private def allocatePort: Task[Int] =
    ZIO.attemptBlocking {
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }

  private def fetch(port: Int, path: String): Task[(Int, String)] =
    for
      url      <- ZIO.fromEither(URL.decode(s"http://127.0.0.1:$port$path")).mapError(new RuntimeException(_))
      response <- Client.batched(Request.get(url)).provide(Client.default)
      body     <- response.body.asString
    yield (response.status.code, body)

  private object TestMigrationRepository extends TaskRepository:

    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]] =
      ZIO.succeed(
        List(
          TaskRunRow(
            id = 1L,
            sourceDir = "/tmp/source",
            outputDir = "/tmp/output",
            status = RunStatus.Completed,
            startedAt = now,
            completedAt = Some(now),
            totalFiles = 1,
            processedFiles = 1,
            successfulConversions = 1,
            failedConversions = 0,
            currentPhase = Some("Completed"),
            errorMessage = None,
          )
        )
      )

    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                                  =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                                  =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                              =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                         =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]                        =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]]                    =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]                    =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]             =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]                 =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]]            =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]                           =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")
    override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]                         =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")

  private object TestOrchestrator extends MigrationOrchestrator:
    override def runFullMigration(sourcePath: java.nio.file.Path, outputPath: java.nio.file.Path)
      : ZIO[Any, OrchestratorError, MigrationResult] =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")

    override def runFullMigrationWithProgress(
      sourcePath: java.nio.file.Path,
      outputPath: java.nio.file.Path,
      onProgress: PipelineProgressUpdate => UIO[Unit],
    ): ZIO[Any, OrchestratorError, MigrationResult] =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")

    override def runStep(step: TaskStep): ZIO[Any, OrchestratorError, StepResult] =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")

    override def startMigration(config: MigrationConfig): IO[OrchestratorError, Long] =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")

    override def cancelMigration(runId: Long): IO[OrchestratorError, Unit] =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")

    override def getRunStatus(runId: Long): IO[PersistenceError, Option[TaskRunRow]] =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")

    override def listRuns(page: Int, pageSize: Int): IO[PersistenceError, List[TaskRunRow]] =
      ZIO.dieMessage("unused in WebServerIntegrationSpec")

    override def subscribeToProgress(runId: Long): UIO[Dequeue[ProgressUpdate]] =
      Queue.unbounded[ProgressUpdate]
