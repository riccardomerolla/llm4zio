package db

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.json.*
import zio.test.*

import models.*

object ResultPersisterSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-02-08T00:00:00Z")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ResultPersisterSpec")(
    test("saveDiscoveryResult maps inventory files to cobol_files rows") {
      for
        state     <- Ref.make(RepoState.empty)
        repo       = TestMigrationRepository(state)
        persister  = ResultPersisterLive(repo)
        inventory  = sampleInventory
        _         <- persister.saveDiscoveryResult(7L, inventory)
        persisted <- state.get.map(_.filesByRun.getOrElse(7L, List.empty))
      yield assertTrue(
        persisted.length == 2,
        persisted.exists(_.path == sampleFile.path.toString),
        persisted.exists(_.name == sampleFile.name),
        persisted.exists(_.fileType == db.FileType.Program),
        persisted.exists(_.fileType == db.FileType.Copybook),
      )
    },
    test("saveAnalysisResult resolves file id and persists serialized JSON") {
      for
        state    <- Ref.make(
                      RepoState.empty.copy(
                        filesByRun = Map(7L -> List(
                          CobolFileRow(
                            id = 11L,
                            runId = 7L,
                            path = sampleFile.path.toString,
                            name = sampleFile.name,
                            fileType = db.FileType.Program,
                            size = sampleFile.size,
                            lineCount = sampleFile.lineCount,
                            encoding = sampleFile.encoding,
                            createdAt = now,
                          )
                        ))
                      )
                    )
        repo      = TestMigrationRepository(state)
        persister = ResultPersisterLive(repo)
        _        <- persister.saveAnalysisResult(7L, sampleAnalysis)
        analyses <- state.get.map(_.analyses)
      yield assertTrue(
        analyses.length == 1,
        analyses.head.fileId == 11L,
        analyses.head.analysisJson == sampleAnalysis.toJson,
      )
    },
    test("saveDependencyResult maps graph edges to dependency rows") {
      for
        state    <- Ref.make(RepoState.empty)
        repo      = TestMigrationRepository(state)
        persister = ResultPersisterLive(repo)
        _        <- persister.saveDependencyResult(9L, sampleGraph)
        deps     <- state.get.map(_.dependencies)
      yield assertTrue(
        deps.length == 2,
        deps.forall(_.runId == 9L),
        deps.exists(dep => dep.sourceNode == "PROG1" && dep.targetNode == "COPY1"),
        deps.exists(_.edgeType == EdgeType.Includes.toString),
      )
    },
    test("saveTransformResult creates artifact file and stores JSON payload") {
      for
        state    <- Ref.make(RepoState.empty)
        repo      = TestMigrationRepository(state)
        persister = ResultPersisterLive(repo)
        _        <- persister.saveTransformResult(12L, sampleProject)
        files    <- state.get.map(_.filesByRun.getOrElse(12L, List.empty))
        analyses <- state.get.map(_.analyses)
      yield assertTrue(
        files.exists(_.path == "/virtual/transform/sample.json"),
        analyses.nonEmpty,
        analyses.last.analysisJson == sampleProject.toJson,
      )
    },
    test("saveValidationResult creates artifact file and stores report JSON payload") {
      for
        state    <- Ref.make(RepoState.empty)
        repo      = TestMigrationRepository(state)
        persister = ResultPersisterLive(repo)
        _        <- persister.saveValidationResult(15L, sampleValidationReport)
        files    <- state.get.map(_.filesByRun.getOrElse(15L, List.empty))
        analyses <- state.get.map(_.analyses)
      yield assertTrue(
        files.exists(_.path == "/virtual/validation/report.json"),
        analyses.nonEmpty,
        analyses.last.analysisJson == sampleValidationReport.toJson,
      )
    },
    test("saveAnalysisResult fails with typed error when source file is missing") {
      for
        state    <- Ref.make(RepoState.empty)
        repo      = TestMigrationRepository(state)
        persister = ResultPersisterLive(repo)
        exit     <- persister.saveAnalysisResult(88L, sampleAnalysis).exit
      yield assertTrue(
        exit match
          case Exit.Failure(cause) =>
            cause.failureOption.exists {
              case PersistenceError.QueryFailed("ResultPersister.saveAnalysisResult", _) => true
              case _                                                                     => false
            }
          case Exit.Success(_)     => false
      )
    },
  ) @@ TestAspect.sequential

  final private case class RepoState(
    filesByRun: Map[Long, List[CobolFileRow]],
    analyses: List[CobolAnalysisRow],
    dependencies: List[DependencyRow],
    nextFileId: Long,
    nextAnalysisId: Long,
  )

  private object RepoState:
    val empty: RepoState =
      RepoState(
        filesByRun = Map.empty,
        analyses = List.empty,
        dependencies = List.empty,
        nextFileId = 1L,
        nextAnalysisId = 1L,
      )

  final private case class TestMigrationRepository(
    state: Ref[RepoState]
  ) extends MigrationRepository:

    override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit] =
      state.update { st =>
        files.foldLeft(st) { (acc, file) =>
          val id      = acc.nextFileId
          val saved   = file.copy(id = id)
          val updated = acc.filesByRun.updated(file.runId, acc.filesByRun.getOrElse(file.runId, List.empty) :+ saved)
          acc.copy(filesByRun = updated, nextFileId = id + 1)
        }
      }

    override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]] =
      state.get.map(_.filesByRun.getOrElse(runId, List.empty))

    override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long] =
      state.modify { st =>
        val id = st.nextAnalysisId
        (
          id,
          st.copy(
            analyses = st.analyses :+ analysis.copy(id = id),
            nextAnalysisId = id + 1,
          ),
        )
      }

    override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit] =
      state.update(st => st.copy(dependencies = st.dependencies ++ deps))

    override def createRun(run: MigrationRunRow): IO[PersistenceError, Long]                             =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit]                             =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]]                         =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]]          =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                         =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]             =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]]            =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]                           =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] =
      ZIO.dieMessage("unused in ResultPersisterSpec")
    override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]                         =
      ZIO.dieMessage("unused in ResultPersisterSpec")

  private val sampleFile = CobolFile(
    path = Paths.get("/tmp/PROG1.cbl"),
    name = "PROG1.cbl",
    size = 120L,
    lineCount = 20L,
    lastModified = now,
    encoding = "UTF-8",
    fileType = models.FileType.Program,
  )

  private val sampleCopybook = CobolFile(
    path = Paths.get("/tmp/COPY1.cpy"),
    name = "COPY1.cpy",
    size = 30L,
    lineCount = 4L,
    lastModified = now,
    encoding = "UTF-8",
    fileType = models.FileType.Copybook,
  )

  private val sampleInventory = FileInventory(
    discoveredAt = now,
    sourceDirectory = Paths.get("/tmp"),
    files = List(sampleFile, sampleCopybook),
    summary = InventorySummary(
      totalFiles = 2,
      programFiles = 1,
      copybooks = 1,
      jclFiles = 0,
      totalLines = 24L,
      totalBytes = 150L,
    ),
  )

  private val sampleAnalysis = CobolAnalysis(
    file = sampleFile,
    divisions = CobolDivisions(Some("ID"), None, None, Some("PROC")),
    variables = List(Variable("CUST-ID", 1, "PIC X(10)", None, None)),
    procedures = List(Procedure("MAIN", List("MAIN"), List(Statement(1, "MOVE", "MOVE A TO B")))),
    copybooks = List("COPY1"),
    complexity = ComplexityMetrics(1, 20, 1),
  )

  private val sampleGraph = DependencyGraph(
    nodes = List(
      DependencyNode("PROG1", "PROG1", NodeType.Program, 1),
      DependencyNode("COPY1", "COPY1", NodeType.Copybook, 0),
    ),
    edges = List(
      DependencyEdge("PROG1", "COPY1", EdgeType.Includes),
      DependencyEdge("PROG1", "SVC1", EdgeType.Calls),
    ),
    serviceCandidates = List("SVC1"),
  )

  private val sampleProject = SpringBootProject(
    projectName = "sample",
    sourceProgram = "PROG1",
    generatedAt = now,
    entities = List.empty,
    services = List.empty,
    controllers = List.empty,
    repositories = List.empty,
    configuration = ProjectConfiguration("com.example", "sample", List("spring-boot-starter-web")),
    buildFile = BuildFile("maven", "<project/>"),
  )

  private val sampleValidationReport = ValidationReport(
    projectName = "sample",
    validatedAt = now,
    compileResult = CompileResult(success = true, exitCode = 0, output = "ok"),
    coverageMetrics = CoverageMetrics(100.0, 100.0, 100.0, List.empty),
    issues = List.empty,
    semanticValidation = SemanticValidation(
      businessLogicPreserved = true,
      confidence = 0.99,
      summary = "good",
      issues = List.empty,
    ),
    overallStatus = ValidationStatus.Passed,
  )
