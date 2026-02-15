package orchestration

import java.nio.file.{ Path, Paths }
import java.time.Instant
import java.util.UUID

import zio.*
import zio.json.*
import zio.test.*

import agents.*
import core.*
import db.*
import models.*

object MigrationOrchestratorWebSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-02-08T00:00:00Z")

  private val sampleFile = CobolFile(
    path = Paths.get("/tmp/WEB-SAMPLE.cbl"),
    name = "WEB-SAMPLE.cbl",
    size = 100,
    lineCount = 10,
    lastModified = now,
    encoding = "UTF-8",
    fileType = models.FileType.Program,
  )

  private val sampleAnalysis = CobolAnalysis(
    file = sampleFile,
    divisions = CobolDivisions(Some("PROGRAM-ID"), None, None, Some("MAIN")),
    variables = List.empty,
    procedures = List.empty,
    copybooks = List.empty,
    complexity = ComplexityMetrics(1, 10, 1),
  )

  private val sampleProject = SpringBootProject(
    projectName = "web-sample",
    sourceProgram = sampleFile.name,
    generatedAt = now,
    entities = List.empty,
    services = List.empty,
    controllers = List.empty,
    repositories = List.empty,
    configuration = ProjectConfiguration("com.example", "web-sample", List.empty),
    buildFile = BuildFile("maven", "<project/>"),
  )

  private val sampleValidation = ValidationReport(
    projectName = "web-sample",
    validatedAt = now,
    compileResult = CompileResult(success = true, exitCode = 0, output = "ok"),
    coverageMetrics = CoverageMetrics(95.0, 95.0, 95.0, List.empty),
    issues = List.empty,
    semanticValidation = SemanticValidation(true, 0.99, "ok", List.empty),
    overallStatus = ValidationStatus.Passed,
  )

  private def config(sourceDir: Path, outputDir: Path, stateDir: Path): MigrationConfig =
    MigrationConfig(
      sourceDir = sourceDir,
      outputDir = outputDir,
      stateDir = stateDir,
      dryRun = true,
    )

  private def layer(
    dbName: String,
    cfg: MigrationConfig,
    discovery: ULayer[CobolDiscoveryAgent],
    transformer: ULayer[JavaTransformerAgent] = transformerLayer,
    validation: ULayer[ValidationAgent] = validationLayer,
    documentation: ULayer[DocumentationAgent] = documentationLayer,
  ): ZLayer[Any, Nothing, MigrationOrchestrator & MigrationRepository] =
    ZLayer.make[MigrationOrchestrator & MigrationRepository](
      FileService.live,
      StateService.live(cfg.stateDir),
      discovery,
      analyzerLayer,
      businessLogicLayer,
      mapperLayer,
      transformer,
      validation,
      documentation,
      stubHttpAIClient,
      ZLayer.succeed(cfg),
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:file:$dbName?mode=memory&cache=shared")),
      Database.live.mapError(err => new RuntimeException(err.toString)).orDie,
      MigrationRepository.live,
      ProgressTracker.live,
      ResultPersister.live,
      MigrationOrchestrator.live,
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MigrationOrchestratorWebSpec")(
    test("startMigration creates run record and subscription receives progress events") {
      for
        sourceDir <- ZIO.attemptBlocking(Path.of("/tmp"))
        outputDir <- ZIO.attemptBlocking(Path.of("/tmp"))
        stateDir  <- ZIO.attemptBlocking(java.nio.file.Files.createTempDirectory("web-orch-state-start"))
        cfg        = config(sourceDir, outputDir, stateDir)
        dbName     = s"web-orch-start-${UUID.randomUUID()}"
        appLayer   = layer(dbName, cfg, discoveryFastLayer)
        tuple     <- (for
                       runId  <- MigrationOrchestrator.startMigration(cfg)
                       queue  <- MigrationOrchestrator.subscribeToProgress(runId)
                       first  <- queue.take.timeoutFail("missing initial progress event")(5.seconds)
                       status <- MigrationOrchestrator.getRunStatus(runId)
                     yield (runId, first, status)).provideLayer(appLayer)
        runId      = tuple._1
        first      = tuple._2
        status     = tuple._3
      yield assertTrue(
        runId > 0,
        first.runId == runId,
        status.isDefined,
      )
    },
    test("cancelMigration interrupts running migration and marks run cancelled") {
      for
        sourceDir <- ZIO.attemptBlocking(Path.of("/tmp"))
        outputDir <- ZIO.attemptBlocking(Path.of("/tmp"))
        stateDir  <- ZIO.attemptBlocking(java.nio.file.Files.createTempDirectory("web-orch-state-cancel"))
        cfg        = config(sourceDir, outputDir, stateDir)
        dbName     = s"web-orch-cancel-${UUID.randomUUID()}"
        appLayer   = layer(dbName, cfg, discoverySlowLayer)
        status    <- (for
                       runId <- MigrationOrchestrator.startMigration(cfg)
                       _     <- ZIO.sleep(250.millis)
                       _     <- MigrationOrchestrator.cancelMigration(runId)
                       row   <- MigrationOrchestrator
                                  .getRunStatus(runId)
                                  .repeatUntil(_.exists(_.status == RunStatus.Cancelled))
                                  .timeoutFail("run status was not marked as cancelled")(5.seconds)
                     yield row).provideLayer(appLayer)
      yield assertTrue(status.exists(_.status == RunStatus.Cancelled))
    },
    test("listRuns paginates results") {
      for
        sourceDir <- ZIO.attemptBlocking(Path.of("/tmp"))
        outputDir <- ZIO.attemptBlocking(Path.of("/tmp"))
        stateDir  <- ZIO.attemptBlocking(java.nio.file.Files.createTempDirectory("web-orch-state-list"))
        cfg        = config(sourceDir, outputDir, stateDir)
        dbName     = s"web-orch-list-${UUID.randomUUID()}"
        appLayer   = layer(dbName, cfg, discoveryFastLayer)
        tuple     <- (for
                       _     <- ZIO.foreachDiscard(1 to 3) { _ =>
                                  MigrationOrchestrator
                                    .startMigration(cfg)
                                    .retry(Schedule.spaced(50.millis) && Schedule.recurs(5)) *>
                                    ZIO.sleep(50.millis)
                                }
                       page1 <- MigrationOrchestrator.listRuns(1, 2)
                       page2 <- MigrationOrchestrator.listRuns(2, 2)
                     yield (page1, page2)).provideLayer(appLayer)
        page1      = tuple._1
        page2      = tuple._2
      yield assertTrue(
        page1.length == 2,
        page2.length == 1,
      )
    },
    test("workflow-defined steps skip transformation, validation, and documentation") {
      for
        sourceDir          <- ZIO.attemptBlocking(Path.of("/tmp"))
        outputDir          <- ZIO.attemptBlocking(Path.of("/tmp"))
        stateDir           <- ZIO.attemptBlocking(java.nio.file.Files.createTempDirectory("web-orch-workflow"))
        transformCalls     <- Ref.make(0)
        validationCalls    <- Ref.make(0)
        documentationCalls <- Ref.make(0)
        transformer         = ZLayer.succeed(new JavaTransformerAgent {
                                override def transform(
                                  analyses: List[CobolAnalysis],
                                  dependencyGraph: DependencyGraph,
                                ): ZIO[Any, TransformError, SpringBootProject] =
                                  transformCalls.update(_ + 1).as(sampleProject)
                              })
        validation          = ZLayer.succeed(new ValidationAgent {
                                override def validate(project: SpringBootProject, analysis: CobolAnalysis)
                                  : ZIO[Any, ValidationError, ValidationReport] =
                                  validationCalls.update(_ + 1).as(sampleValidation)
                              })
        documentation       = ZLayer.succeed(new DocumentationAgent {
                                override def generateDocs(result: MigrationResult)
                                  : ZIO[Any, DocError, MigrationDocumentation] =
                                  documentationCalls.update(_ + 1).as(MigrationDocumentation.empty)
                              })
        dbName              = s"web-orch-workflow-${UUID.randomUUID()}"
        workflowSteps       = List(MigrationStep.Discovery, MigrationStep.Analysis, MigrationStep.Mapping).toJson
        cfg                 = config(sourceDir, outputDir, stateDir).copy(dryRun = false)
        appLayer            = layer(dbName, cfg, discoveryFastLayer, transformer, validation, documentation)
        runOutcome         <- (for
                                workflowId <- MigrationRepository.createWorkflow(
                                                WorkflowRow(
                                                  name = "Partial Workflow",
                                                  description = Some("Discovery/Analysis/Mapping only"),
                                                  steps = workflowSteps,
                                                  isBuiltin = false,
                                                  createdAt = now,
                                                  updatedAt = now,
                                                )
                                              )
                                runId      <- MigrationOrchestrator.startMigration(cfg.copy(workflowId = Some(workflowId)))
                                status     <- awaitTerminal(runId).timeoutFail("workflow run did not complete")(20.seconds)
                              yield status).provideLayer(appLayer)
        transformCount     <- transformCalls.get
        validationCount    <- validationCalls.get
        documentationCount <- documentationCalls.get
      yield assertTrue(
        runOutcome.exists(_.status == RunStatus.Completed),
        transformCount == 0,
        validationCount == 0,
        documentationCount == 0,
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def terminal(run: MigrationRunRow): Boolean =
    run.status == RunStatus.Completed ||
    run.status == RunStatus.Failed ||
    run.status == RunStatus.Cancelled

  private def awaitTerminal(runId: Long): ZIO[MigrationOrchestrator, PersistenceError, Option[MigrationRunRow]] =
    MigrationOrchestrator.getRunStatus(runId).flatMap {
      case Some(run) if terminal(run) => ZIO.succeed(Some(run))
      case _                          => ZIO.sleep(50.millis) *> awaitTerminal(runId)
    }

  private val discoveryFastLayer: ULayer[CobolDiscoveryAgent] =
    ZLayer.succeed(new CobolDiscoveryAgent {
      override def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory] =
        ZIO.succeed(
          FileInventory(
            discoveredAt = now,
            sourceDirectory = sourcePath,
            files = List(sampleFile),
            summary = InventorySummary(1, 1, 0, 0, 10, 100),
          )
        )
    })

  private val discoverySlowLayer: ULayer[CobolDiscoveryAgent] =
    ZLayer.succeed(new CobolDiscoveryAgent {
      override def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory] =
        ZIO.sleep(10.seconds) *>
          ZIO.succeed(
            FileInventory(
              discoveredAt = now,
              sourceDirectory = sourcePath,
              files = List(sampleFile),
              summary = InventorySummary(1, 1, 0, 0, 10, 100),
            )
          )
    })

  private val analyzerLayer: ULayer[CobolAnalyzerAgent] =
    ZLayer.succeed(new CobolAnalyzerAgent {
      override def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]                     =
        ZIO.succeed(sampleAnalysis.copy(file = cobolFile))
      override def analyzeAll(files: List[CobolFile]): zio.stream.ZStream[Any, AnalysisError, CobolAnalysis] =
        zio.stream.ZStream.fromIterable(files).map(file => sampleAnalysis.copy(file = file))
      override def analyzeAllWithProgress(
        files: List[CobolFile],
        stepName: String,
      ): zio.stream.ZStream[Any, AnalysisError, StepProgressEvent] =
        zio.stream.ZStream.empty
    })

  private val mapperLayer: ULayer[DependencyMapperAgent] =
    ZLayer.succeed(new DependencyMapperAgent {
      override def mapDependencies(analyses: List[CobolAnalysis]): ZIO[Any, MappingError, DependencyGraph] =
        ZIO.succeed(DependencyGraph.empty)
    })

  private val businessLogicLayer: ULayer[BusinessLogicExtractorAgent] =
    ZLayer.succeed(new BusinessLogicExtractorAgent {
      override def extract(analysis: CobolAnalysis): ZIO[Any, BusinessLogicExtractionError, BusinessLogicExtraction] =
        ZIO.succeed(
          BusinessLogicExtraction(
            fileName = analysis.file.name,
            businessPurpose = "Stub",
            useCases = List.empty,
            rules = List.empty,
            summary = "Stub",
          )
        )
      override def extractAll(
        analyses: List[CobolAnalysis]
      ): ZIO[Any, BusinessLogicExtractionError, List[BusinessLogicExtraction]] =
        ZIO.foreach(analyses)(extract)
    })

  private val transformerLayer: ULayer[JavaTransformerAgent] =
    ZLayer.succeed(new JavaTransformerAgent {
      override def transform(
        analyses: List[CobolAnalysis],
        dependencyGraph: DependencyGraph,
      ): ZIO[Any, TransformError, SpringBootProject] =
        ZIO.succeed(sampleProject)
    })

  private val validationLayer: ULayer[ValidationAgent] =
    ZLayer.succeed(new ValidationAgent {
      override def validate(project: SpringBootProject, analysis: CobolAnalysis)
        : ZIO[Any, ValidationError, ValidationReport] =
        ZIO.succeed(sampleValidation)
    })

  private val documentationLayer: ULayer[DocumentationAgent] =
    ZLayer.succeed(new DocumentationAgent {
      override def generateDocs(result: MigrationResult): ZIO[Any, DocError, MigrationDocumentation] =
        ZIO.succeed(MigrationDocumentation.empty)
    })

  private val stubHttpAIClient: ULayer[HttpAIClient] =
    ZLayer.succeed(new HttpAIClient {
      override def postJson(
        url: String,
        body: String,
        headers: Map[String, String],
        timeout: Duration,
      ): ZIO[Any, AIError, String] =
        ZIO.fail(AIError.ProviderUnavailable(url, "unused in MigrationOrchestratorWebSpec"))
    })
