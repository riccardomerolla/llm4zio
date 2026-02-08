package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import db.*

object GraphControllerSpec extends ZIOSpecDefault:

  private val dependency = DependencyRow(
    id = 1L,
    runId = 7L,
    sourceNode = "PROG1",
    targetNode = "COPY1",
    edgeType = "Includes",
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GraphControllerSpec")(
    test("GET /graph renders graph page") {
      for
        repo      <- TestRepository.make
        controller = GraphControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get(URL.decode("/graph?runId=7").toOption.get))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Dependency Graph"),
      )
    },
    test("GET /graph without runId falls back to latest run") {
      for
        repo      <- TestRepository.make
        controller = GraphControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get("/graph"))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Run #7"),
        body.contains("PROG1"),
      )
    },
    test("GET /api/graph/:runId returns JSON") {
      for
        repo      <- TestRepository.make
        controller = GraphControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get("/api/graph/7"))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("PROG1"),
        body.contains("COPY1"),
      )
    },
    test("GET /api/graph/:runId/export?format=mermaid returns mermaid") {
      for
        repo      <- TestRepository.make
        controller = GraphControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get(URL.decode("/api/graph/7/export?format=mermaid").toOption.get))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("graph TD"),
        body.contains("PROG1 -->|includes| COPY1"),
      )
    },
    test("GET /api/graph/:runId/export?format=d3 returns d3 json") {
      for
        repo      <- TestRepository.make
        controller = GraphControllerLive(repo)
        response  <- controller.routes.runZIO(Request.get(URL.decode("/api/graph/7/export?format=d3").toOption.get))
        body      <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("\"nodes\""),
        body.contains("\"links\""),
      )
    },
  )

  final private case class TestRepository() extends MigrationRepository:
    private val run = MigrationRunRow(
      id = 7L,
      sourceDir = "/tmp/source",
      outputDir = "/tmp/out",
      status = RunStatus.Completed,
      startedAt = Instant.parse("2026-02-08T00:00:00Z"),
      completedAt = Some(Instant.parse("2026-02-08T00:10:00Z")),
      totalFiles = 1,
      processedFiles = 1,
      successfulConversions = 1,
      failedConversions = 0,
      currentPhase = Some("Completed"),
      errorMessage = None,
    )

    override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]] =
      ZIO.succeed(if runId == 7L then List(dependency) else Nil)

    override def createRun(run: MigrationRunRow): IO[PersistenceError, Long]                             =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit]                             =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]]                         =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]]          =
      ZIO.succeed(List(run))
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                         =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]                        =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]]                    =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]                    =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]             =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]                 =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]                           =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] =
      ZIO.dieMessage("unused in GraphControllerSpec")
    override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]                         =
      ZIO.dieMessage("unused in GraphControllerSpec")

  private object TestRepository:
    def make: UIO[TestRepository] = ZIO.succeed(TestRepository())
