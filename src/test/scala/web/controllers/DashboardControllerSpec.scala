package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import db.*

object DashboardControllerSpec extends ZIOSpecDefault:

  private val sampleRun = MigrationRunRow(
    id = 2L,
    sourceDir = "/src",
    outputDir = "/out",
    status = RunStatus.Completed,
    startedAt = Instant.parse("2026-02-08T00:00:00Z"),
    completedAt = None,
    totalFiles = 3,
    processedFiles = 3,
    successfulConversions = 3,
    failedConversions = 0,
    currentPhase = Some("completed"),
    errorMessage = None,
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DashboardControllerSpec")(
    test("GET / renders dashboard") {
      for
        repo      <- TestRepository.make
        controller = DashboardControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get("/"))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Dashboard"),
        body.contains("/runs/2"),
      )
    },
    test("GET /api/runs/recent returns runs fragment") {
      for
        repo      <- TestRepository.make
        controller = DashboardControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get("/api/runs/recent"))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("#2"),
      )
    },
  )

  final private case class TestRepository() extends MigrationRepository:

    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]] =
      ZIO.succeed(List(sampleRun))

    override def createRun(run: MigrationRunRow): IO[PersistenceError, Long]                             =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit]                             =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]]                         =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                         =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]                        =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]]                    =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]                    =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]             =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]                 =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]]            =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]                           =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] =
      ZIO.dieMessage("unused in DashboardControllerSpec")
    override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]                         =
      ZIO.dieMessage("unused in DashboardControllerSpec")

  private object TestRepository:
    def make: UIO[TestRepository] = ZIO.succeed(TestRepository())
