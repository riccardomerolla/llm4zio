package integration

import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

import zio.*
import zio.json.*
import zio.logging.backend.SLF4J
import zio.test.*

import db.*
import models.*

object ResultPersisterIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private def liveLayer(dbName: String): ZLayer[Any, PersistenceError, ResultPersister & MigrationRepository] =
    ZLayer.make[ResultPersister & MigrationRepository](
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")),
      Database.live,
      MigrationRepository.live,
      ResultPersister.live,
    )

  private val now = Instant.parse("2026-02-08T00:00:00Z")

  private val sampleFile = CobolFile(
    path = Paths.get("/tmp/it/PROG1.cbl"),
    name = "PROG1.cbl",
    size = 100L,
    lineCount = 10L,
    lastModified = now,
    encoding = "UTF-8",
    fileType = models.FileType.Program,
  )

  private val sampleInventory = FileInventory(
    discoveredAt = now,
    sourceDirectory = Paths.get("/tmp/it"),
    files = List(sampleFile),
    summary = InventorySummary(
      totalFiles = 1,
      programFiles = 1,
      copybooks = 0,
      jclFiles = 0,
      totalLines = 10L,
      totalBytes = 100L,
    ),
  )

  private val sampleAnalysis = CobolAnalysis(
    file = sampleFile,
    divisions = CobolDivisions(Some("ID"), None, None, Some("PROC")),
    variables = List(Variable("CUSTOMER-ID", 1, "PIC X(10)", None, None)),
    procedures = List(Procedure("MAIN", List("MAIN"), List(Statement(1, "MOVE", "MOVE A TO B")))),
    copybooks = List.empty,
    complexity = ComplexityMetrics(1, 10, 1),
  )

  private val sampleGraph = DependencyGraph(
    nodes = List(DependencyNode("PROG1", "PROG1", NodeType.Program, 1)),
    edges = List(DependencyEdge("PROG1", "SVC1", EdgeType.Calls)),
    serviceCandidates = List("SVC1"),
  )

  private val sampleProject = SpringBootProject(
    projectName = "it-sample",
    sourceProgram = "PROG1",
    generatedAt = now,
    entities = List.empty,
    services = List.empty,
    controllers = List.empty,
    repositories = List.empty,
    configuration = ProjectConfiguration("com.example", "it-sample", List("spring-boot-starter-web")),
    buildFile = BuildFile("maven", "<project/>"),
  )

  private val sampleValidation = ValidationReport(
    projectName = "it-sample",
    validatedAt = now,
    compileResult = CompileResult(success = true, exitCode = 0, output = "ok"),
    coverageMetrics = CoverageMetrics(100.0, 100.0, 100.0, List.empty),
    issues = List.empty,
    semanticValidation = SemanticValidation(
      businessLogicPreserved = true,
      confidence = 0.99,
      summary = "ok",
      issues = List.empty,
    ),
    overallStatus = ValidationStatus.Passed,
  )

  private val baseRun = MigrationRunRow(
    id = 0L,
    sourceDir = "/tmp/it-source",
    outputDir = "/tmp/it-output",
    status = RunStatus.Running,
    startedAt = now,
    completedAt = None,
    totalFiles = 1,
    processedFiles = 0,
    successfulConversions = 0,
    failedConversions = 0,
    currentPhase = Some("Discovery"),
    errorMessage = None,
  )

  def spec: Spec[Any, Any] = suite("ResultPersisterIntegrationSpec")(
    test("persists discovery/analysis/dependency/transform/validation artifacts in live db") {
      val layer = liveLayer(s"it-result-persister-${UUID.randomUUID()}")

      ZIO.scoped {
        (for
          runId <- MigrationRepository.createRun(baseRun)
          _     <- ResultPersister.saveDiscoveryResult(runId, sampleInventory)
          _     <- ResultPersister.saveAnalysisResult(runId, sampleAnalysis)
          _     <- ResultPersister.saveDependencyResult(runId, sampleGraph)
          _     <- ResultPersister.saveTransformResult(runId, sampleProject)
          _     <- ResultPersister.saveValidationResult(runId, sampleValidation)

          files     <- MigrationRepository.getFilesByRun(runId)
          analyses  <- MigrationRepository.getAnalysesByRun(runId)
          deps      <- MigrationRepository.getDependenciesByRun(runId)
          sourceOpt  = files.find(_.path == sampleFile.path.toString)
          sourceId   = sourceOpt.map(_.id)
          transform  = analyses.find(_.analysisJson == sampleProject.toJson)
          validation = analyses.find(_.analysisJson == sampleValidation.toJson)
          sourceAna  = sourceId.flatMap(id =>
                         analyses.find(row => row.fileId == id && row.analysisJson == sampleAnalysis.toJson)
                       )
        yield assertTrue(
          files.exists(_.path == sampleFile.path.toString),
          files.exists(_.path == "/virtual/transform/it-sample.json"),
          files.exists(_.path == "/virtual/validation/report.json"),
          sourceAna.isDefined,
          transform.isDefined,
          validation.isDefined,
          deps.exists(dep => dep.sourceNode == "PROG1" && dep.targetNode == "SVC1" && dep.edgeType == "Calls"),
        )).provideLayer(layer)
      }
    },
    test("returns typed error when saving analysis without corresponding discovered file") {
      val layer = liveLayer(s"it-result-persister-missing-file-${UUID.randomUUID()}")

      ZIO.scoped {
        (for
          runId <- MigrationRepository.createRun(baseRun)
          exit  <- ResultPersister.saveAnalysisResult(runId, sampleAnalysis).exit
        yield assertTrue(
          exit match
            case Exit.Failure(cause) =>
              cause.failureOption.exists {
                case PersistenceError.QueryFailed("ResultPersister.saveAnalysisResult", _) => true
                case _                                                                     => false
              }
            case Exit.Success(_)     => false
        )).provideLayer(layer)
      }
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
