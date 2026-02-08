package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import db.*

object AnalysisControllerSpec extends ZIOSpecDefault:

  private val sampleRun = MigrationRunRow(
    id = 1L,
    sourceDir = "/src",
    outputDir = "/out",
    status = RunStatus.Completed,
    startedAt = Instant.parse("2026-02-08T00:00:00Z"),
    completedAt = None,
    totalFiles = 1,
    processedFiles = 1,
    successfulConversions = 1,
    failedConversions = 0,
    currentPhase = Some("analysis"),
    errorMessage = None,
  )

  private val file = CobolFileRow(
    id = 10L,
    runId = 1L,
    path = "/src/PROGRAM1.cbl",
    name = "PROGRAM1.cbl",
    fileType = FileType.Program,
    size = 10,
    lineCount = 2,
    encoding = "UTF-8",
    createdAt = Instant.parse("2026-02-08T00:00:00Z"),
  )

  private val analysis = CobolAnalysisRow(
    id = 20L,
    runId = 1L,
    fileId = 10L,
    analysisJson = "{\"ok\":true}",
    createdAt = Instant.parse("2026-02-08T00:00:00Z"),
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AnalysisControllerSpec")(
    test("GET /analysis lists files for run") {
      for
        repo      <- TestRepository.make
        controller = AnalysisControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get(URL.decode("/analysis?runId=1").toOption.get))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Analysis for Run #1"),
        body.contains("PROGRAM1.cbl"),
      )
    },
    test("GET /analysis/:fileId returns file analysis detail") {
      for
        repo      <- TestRepository.make
        controller = AnalysisControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get("/analysis/10"))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("PROGRAM1.cbl"),
        body.contains("&quot;ok&quot;:true"),
      )
    },
    test("GET /api/analysis/search filters by query") {
      for
        repo      <- TestRepository.make
        controller = AnalysisControllerLive(repo)
        response  <-
          controller.routes.runZIO(Request.get(URL.decode("/api/analysis/search?runId=1&q=program").toOption.get))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("PROGRAM1.cbl"),
      )
    },
  )

  final private case class TestRepository() extends MigrationRepository:

    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]] =
      ZIO.succeed(List(sampleRun))

    override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]] =
      ZIO.succeed(if runId == 1L then List(file) else Nil)

    override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]] =
      ZIO.succeed(if runId == 1L then List(analysis) else Nil)

    override def createRun(run: MigrationRunRow): IO[PersistenceError, Long]                             =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit]                             =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]]                         =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                         =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]                        =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]                    =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]                 =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]]            =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]                           =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] =
      ZIO.dieMessage("unused in AnalysisControllerSpec")
    override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]                         =
      ZIO.dieMessage("unused in AnalysisControllerSpec")

  private object TestRepository:
    def make: UIO[TestRepository] = ZIO.succeed(TestRepository())
