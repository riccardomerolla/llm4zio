package orchestration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.stream.ZStream
import zio.test.*

import agents.*
import core.*
import db.*
import models.*

object MigrationOrchestratorSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] = suite("MigrationOrchestratorSpec")(
    test("runs full 6-phase pipeline and returns completed result") {
      ZIO.scoped {
        for
          stateDir  <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-state"))
          sourceDir <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-src"))
          outputDir <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-out"))
          result    <- MigrationOrchestrator
                         .runFullMigration(sourceDir, outputDir)
                         .provide(
                           FileService.live,
                           StateService.live(stateDir),
                           mockDiscoveryAgent,
                           mockAnalyzerAgent,
                           mockBusinessLogicAgent,
                           mockMapperAgent,
                           mockTransformerAgent,
                           mockValidationAgent,
                           mockDocumentationAgent,
                           stubHttpAIClient,
                           ZLayer.succeed(MigrationConfig(
                             sourceDir = sourceDir,
                             outputDir = outputDir,
                             stateDir = stateDir,
                           )),
                           noOpRepositoryLayer,
                           noOpTrackerLayer,
                           noOpPersisterLayer,
                           MigrationOrchestrator.live,
                         )
        yield assertTrue(
          result.status == MigrationStatus.Completed,
          result.projects.nonEmpty,
          result.validationReport.semanticValidation.businessLogicPreserved,
          result.errors.isEmpty,
        )
      }
    },
    test("dry-run mode returns completed with warnings and skips generation phases") {
      ZIO.scoped {
        for
          stateDir  <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-state-dry"))
          sourceDir <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-src-dry"))
          outputDir <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-out-dry"))
          result    <- MigrationOrchestrator
                         .runFullMigration(sourceDir, outputDir)
                         .provide(
                           FileService.live,
                           StateService.live(stateDir),
                           mockDiscoveryAgent,
                           mockAnalyzerAgent,
                           mockBusinessLogicAgent,
                           mockMapperAgent,
                           mockTransformerAgent,
                           mockValidationAgent,
                           mockDocumentationAgent,
                           stubHttpAIClient,
                           ZLayer.succeed(MigrationConfig(
                             sourceDir = sourceDir,
                             outputDir = outputDir,
                             stateDir = stateDir,
                             dryRun = true,
                           )),
                           noOpRepositoryLayer,
                           noOpTrackerLayer,
                           noOpPersisterLayer,
                           MigrationOrchestrator.live,
                         )
        yield assertTrue(
          result.status == MigrationStatus.CompletedWithWarnings,
          result.projects.isEmpty,
          result.validationReport == ValidationReport.empty,
        )
      }
    },
    test("dry-run runs business logic extractor when enabled") {
      ZIO.scoped {
        for
          stateDir      <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-state-dry-ble"))
          sourceDir     <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-src-dry-ble"))
          outputDir     <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-out-dry-ble"))
          extractionRef <- Ref.make(0)
          countingAgent  = ZLayer.succeed(new BusinessLogicExtractorAgent {
                             override def extract(analysis: CobolAnalysis)
                               : ZIO[Any, BusinessLogicExtractionError, BusinessLogicExtraction] =
                               extractionRef.update(_ + 1).as(sampleBusinessLogicExtraction.copy(fileName =
                                 analysis.file.name
                               ))
                             override def extractAll(
                               analyses: List[CobolAnalysis]
                             ): ZIO[Any, BusinessLogicExtractionError, List[BusinessLogicExtraction]] =
                               extractionRef.update(_ + 1).as(analyses.map(a =>
                                 sampleBusinessLogicExtraction.copy(fileName = a.file.name)
                               ))
                           })
          _             <- MigrationOrchestrator
                             .runFullMigration(sourceDir, outputDir)
                             .provide(
                               FileService.live,
                               StateService.live(stateDir),
                               mockDiscoveryAgent,
                               mockAnalyzerAgent,
                               countingAgent,
                               mockMapperAgent,
                               mockTransformerAgent,
                               mockValidationAgent,
                               mockDocumentationAgent,
                               stubHttpAIClient,
                               ZLayer.succeed(MigrationConfig(
                                 sourceDir = sourceDir,
                                 outputDir = outputDir,
                                 stateDir = stateDir,
                                 dryRun = true,
                                 enableBusinessLogicExtractor = true,
                               )),
                               noOpRepositoryLayer,
                               noOpTrackerLayer,
                               noOpPersisterLayer,
                               MigrationOrchestrator.live,
                             )
          calls         <- extractionRef.get
        yield assertTrue(calls == 1)
      }
    },
    test("dry-run does not run business logic extractor when disabled") {
      ZIO.scoped {
        for
          stateDir      <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-state-dry-ble-off"))
          sourceDir     <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-src-dry-ble-off"))
          outputDir     <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-out-dry-ble-off"))
          extractionRef <- Ref.make(0)
          countingAgent  = ZLayer.succeed(new BusinessLogicExtractorAgent {
                             override def extract(analysis: CobolAnalysis)
                               : ZIO[Any, BusinessLogicExtractionError, BusinessLogicExtraction] =
                               extractionRef.update(_ + 1).as(sampleBusinessLogicExtraction.copy(fileName =
                                 analysis.file.name
                               ))
                             override def extractAll(
                               analyses: List[CobolAnalysis]
                             ): ZIO[Any, BusinessLogicExtractionError, List[BusinessLogicExtraction]] =
                               extractionRef.update(_ + 1).as(analyses.map(a =>
                                 sampleBusinessLogicExtraction.copy(fileName = a.file.name)
                               ))
                           })
          _             <- MigrationOrchestrator
                             .runFullMigration(sourceDir, outputDir)
                             .provide(
                               FileService.live,
                               StateService.live(stateDir),
                               mockDiscoveryAgent,
                               mockAnalyzerAgent,
                               countingAgent,
                               mockMapperAgent,
                               mockTransformerAgent,
                               mockValidationAgent,
                               mockDocumentationAgent,
                               stubHttpAIClient,
                               ZLayer.succeed(MigrationConfig(
                                 sourceDir = sourceDir,
                                 outputDir = outputDir,
                                 stateDir = stateDir,
                                 dryRun = true,
                                 enableBusinessLogicExtractor = false,
                               )),
                               noOpRepositoryLayer,
                               noOpTrackerLayer,
                               noOpPersisterLayer,
                               MigrationOrchestrator.live,
                             )
          calls         <- extractionRef.get
        yield assertTrue(calls == 0)
      }
    },
    test("phase failure is captured as failed result") {
      ZIO.scoped {
        for
          stateDir       <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-state-fail"))
          sourceDir      <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-src-fail"))
          outputDir      <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-out-fail"))
          failingAnalyzer = ZLayer.succeed(new CobolAnalyzerAgent {
                              override def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
                                ZIO.fail(AnalysisError.AIFailed(cobolFile.name, "analysis boom"))
                              override def analyzeAll(files: List[CobolFile])
                                : ZStream[Any, AnalysisError, CobolAnalysis] =
                                ZStream.fromZIO(
                                  ZIO.fail(AnalysisError.AIFailed(
                                    files.headOption.map(_.name).getOrElse("unknown"),
                                    "analysis boom",
                                  ))
                                )
                              override def analyzeAllWithProgress(
                                files: List[CobolFile],
                                stepName: String,
                              ): ZStream[Any, AnalysisError, StepProgressEvent] =
                                ZStream.empty
                            })
          result         <- MigrationOrchestrator
                              .runFullMigration(sourceDir, outputDir)
                              .provide(
                                FileService.live,
                                StateService.live(stateDir),
                                mockDiscoveryAgent,
                                failingAnalyzer,
                                mockBusinessLogicAgent,
                                mockMapperAgent,
                                mockTransformerAgent,
                                mockValidationAgent,
                                mockDocumentationAgent,
                                stubHttpAIClient,
                                ZLayer.succeed(MigrationConfig(
                                  sourceDir = sourceDir,
                                  outputDir = outputDir,
                                  stateDir = stateDir,
                                )),
                                noOpRepositoryLayer,
                                noOpTrackerLayer,
                                noOpPersisterLayer,
                                MigrationOrchestrator.live,
                              )
        yield assertTrue(
          result.status == MigrationStatus.Failed,
          result.errors.nonEmpty,
          result.errors.exists(_.step == MigrationStep.Analysis),
          result.errors.exists(error =>
            error.step == MigrationStep.Analysis && error.message.contains("Analysis failed for")
          ),
        )
      }
    },
    test("discovery phase failure keeps phase-specific orchestrator error information") {
      ZIO.scoped {
        for
          stateDir        <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-state-discovery-fail"))
          sourceDir       <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-src-discovery-fail"))
          outputDir       <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-out-discovery-fail"))
          failingDiscovery = ZLayer.succeed(new CobolDiscoveryAgent {
                               override def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory] =
                                 ZIO.fail(DiscoveryError.ScanFailed(sourcePath, "discovery boom"))
                             })
          result          <- MigrationOrchestrator
                               .runFullMigration(sourceDir, outputDir)
                               .provide(
                                 FileService.live,
                                 StateService.live(stateDir),
                                 failingDiscovery,
                                 mockAnalyzerAgent,
                                 mockBusinessLogicAgent,
                                 mockMapperAgent,
                                 mockTransformerAgent,
                                 mockValidationAgent,
                                 mockDocumentationAgent,
                                 stubHttpAIClient,
                                 ZLayer.succeed(MigrationConfig(
                                   sourceDir = sourceDir,
                                   outputDir = outputDir,
                                   stateDir = stateDir,
                                 )),
                                 noOpRepositoryLayer,
                                 noOpTrackerLayer,
                                 noOpPersisterLayer,
                                 MigrationOrchestrator.live,
                               )
        yield assertTrue(
          result.status == MigrationStatus.Failed,
          result.errors.exists(error =>
            error.step == MigrationStep.Discovery && error.message.contains("Discovery failed")
          ),
        )
      }
    },
    test("state errors propagate as typed orchestrator failures") {
      ZIO.scoped {
        for
          sourceDir        <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-src-state-fail"))
          outputDir        <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-out-state-fail"))
          failingStateLayer = ZLayer.succeed(new StateService {
                                override def saveState(state: MigrationState): ZIO[Any, StateError, Unit]           =
                                  ZIO.fail(StateError.WriteError(state.runId, "cannot persist"))
                                override def loadState(runId: String): ZIO[Any, StateError, Option[MigrationState]] =
                                  ZIO.succeed(None)
                                override def createCheckpoint(runId: String, step: MigrationStep)
                                  : ZIO[Any, StateError, Unit] =
                                  ZIO.unit
                                override def getLastCheckpoint(runId: String)
                                  : ZIO[Any, StateError, Option[MigrationStep]] =
                                  ZIO.none
                                override def listCheckpoints(runId: String): ZIO[Any, StateError, List[Checkpoint]] =
                                  ZIO.succeed(List.empty)
                                override def validateCheckpointIntegrity(runId: String): ZIO[Any, StateError, Unit] =
                                  ZIO.unit
                                override def listRuns(): ZIO[Any, StateError, List[MigrationRunSummary]]            =
                                  ZIO.succeed(List.empty)
                                override def getStateDirectory(runId: String): ZIO[Any, StateError, Path]           =
                                  ZIO.succeed(outputDir.resolve(".state"))
                              })
          exit             <- MigrationOrchestrator
                                .runFullMigration(sourceDir, outputDir)
                                .provide(
                                  FileService.live,
                                  failingStateLayer,
                                  mockDiscoveryAgent,
                                  mockAnalyzerAgent,
                                  mockBusinessLogicAgent,
                                  mockMapperAgent,
                                  mockTransformerAgent,
                                  mockValidationAgent,
                                  mockDocumentationAgent,
                                  stubHttpAIClient,
                                  ZLayer.succeed(MigrationConfig(
                                    sourceDir = sourceDir,
                                    outputDir = outputDir,
                                  )),
                                  noOpRepositoryLayer,
                                  noOpTrackerLayer,
                                  noOpPersisterLayer,
                                  MigrationOrchestrator.live,
                                )
                                .exit
        yield assertTrue(
          exit match
            case Exit.Failure(cause) =>
              cause.failureOption.exists {
                case OrchestratorError.StateFailed(StateError.WriteError(_, "cannot persist")) => true
                case _                                                                         => false
              }
            case Exit.Success(_)     => false
        )
      }
    },
    test("resume skips completed phases after checkpoint") {
      ZIO.scoped {
        for
          stateDir         <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-state-resume"))
          sourceDir        <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-src-resume"))
          outputDir        <- ZIO.attemptBlocking(Files.createTempDirectory("orchestrator-out-resume"))
          discoveryCalls   <- Ref.make(0)
          countingDiscovery = ZLayer.succeed(new CobolDiscoveryAgent {
                                override def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory] =
                                  for
                                    _ <- discoveryCalls.update(_ + 1)
                                  yield FileInventory(
                                    discoveredAt = Instant.parse("2026-02-06T00:00:00Z"),
                                    sourceDirectory = sourcePath,
                                    files = List(sampleFile),
                                    summary = InventorySummary(1, 1, 0, 0, 10, 100),
                                  )
                              })
          failingAnalyzer   = ZLayer.succeed(new CobolAnalyzerAgent {
                                override def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
                                  ZIO.fail(AnalysisError.AIFailed(cobolFile.name, "analysis boom"))
                                override def analyzeAll(files: List[CobolFile])
                                  : ZStream[Any, AnalysisError, CobolAnalysis] =
                                  ZStream.empty
                                override def analyzeAllWithProgress(
                                  files: List[CobolFile],
                                  stepName: String,
                                ): ZStream[Any, AnalysisError, StepProgressEvent] =
                                  ZStream.empty
                              })
          firstResult      <- MigrationOrchestrator
                                .runFullMigration(sourceDir, outputDir)
                                .provide(
                                  FileService.live,
                                  StateService.live(stateDir),
                                  countingDiscovery,
                                  failingAnalyzer,
                                  mockBusinessLogicAgent,
                                  mockMapperAgent,
                                  mockTransformerAgent,
                                  mockValidationAgent,
                                  mockDocumentationAgent,
                                  stubHttpAIClient,
                                  ZLayer.succeed(MigrationConfig(
                                    sourceDir = sourceDir,
                                    outputDir = outputDir,
                                    stateDir = stateDir,
                                  )),
                                  noOpRepositoryLayer,
                                  noOpTrackerLayer,
                                  noOpPersisterLayer,
                                  MigrationOrchestrator.live,
                                )
          resumedResult    <- MigrationOrchestrator
                                .runFullMigration(sourceDir, outputDir)
                                .provide(
                                  FileService.live,
                                  StateService.live(stateDir),
                                  countingDiscovery,
                                  mockAnalyzerAgent,
                                  mockBusinessLogicAgent,
                                  mockMapperAgent,
                                  mockTransformerAgent,
                                  mockValidationAgent,
                                  mockDocumentationAgent,
                                  stubHttpAIClient,
                                  ZLayer.succeed(MigrationConfig(
                                    sourceDir = sourceDir,
                                    outputDir = outputDir,
                                    stateDir = stateDir,
                                    resumeFromCheckpoint = Some(firstResult.runId),
                                  )),
                                  noOpRepositoryLayer,
                                  noOpTrackerLayer,
                                  noOpPersisterLayer,
                                  MigrationOrchestrator.live,
                                )
          calls            <- discoveryCalls.get
        yield assertTrue(
          firstResult.status == MigrationStatus.Failed,
          resumedResult.status == MigrationStatus.Completed,
          calls == 1,
        )
      }
    },
  ) @@ TestAspect.sequential

  private val sampleFile = CobolFile(
    path = Path.of("/tmp/SAMPLE.cbl"),
    name = "SAMPLE.cbl",
    size = 100,
    lineCount = 10,
    lastModified = Instant.parse("2026-02-06T00:00:00Z"),
    encoding = "UTF-8",
    fileType = models.FileType.Program,
  )

  private val sampleAnalysis = CobolAnalysis(
    file = sampleFile,
    divisions = CobolDivisions(Some("PROGRAM-ID."), None, None, Some("MAIN.")),
    variables = List.empty,
    procedures = List.empty,
    copybooks = List.empty,
    complexity = ComplexityMetrics(1, 10, 1),
  )

  private val sampleProject = SpringBootProject(
    projectName = "sample",
    sourceProgram = "SAMPLE.cbl",
    generatedAt = Instant.parse("2026-02-06T00:00:00Z"),
    entities = List.empty,
    services = List.empty,
    controllers = List.empty,
    repositories = List.empty,
    configuration = ProjectConfiguration("com.example", "sample", List.empty),
    buildFile = BuildFile("maven", "<project/>"),
  )

  private val sampleValidation = ValidationReport(
    projectName = "sample",
    validatedAt = Instant.parse("2026-02-06T00:00:00Z"),
    compileResult = CompileResult(success = true, exitCode = 0, output = ""),
    coverageMetrics = CoverageMetrics(90.0, 80.0, 85.0, List.empty),
    issues = List.empty,
    semanticValidation = SemanticValidation(
      businessLogicPreserved = true,
      confidence = 0.95,
      summary = "OK",
      issues = List.empty,
    ),
    overallStatus = ValidationStatus.Passed,
  )

  private val sampleBusinessLogicExtraction = BusinessLogicExtraction(
    fileName = "SAMPLE.cbl",
    businessPurpose = "Maintains customer records and applies validation checks.",
    useCases = List(
      BusinessUseCase(
        name = "Lookup customer",
        trigger = "Customer inquiry request",
        description = "Retrieves a customer profile by identifier.",
        keySteps = List("Validate input", "Read customer data", "Return response"),
      )
    ),
    rules = List(
      BusinessRule(
        category = "DataValidation",
        description = "Customer identifier must be provided.",
        condition = Some("Before processing"),
        errorCode = Some("CUST-001"),
        suggestion = Some("Provide a non-empty customer identifier."),
      )
    ),
    summary = "Single-record lookup flow with required input validation.",
  )

  private val sampleDocumentation = MigrationDocumentation(
    generatedAt = Instant.parse("2026-02-06T00:00:00Z"),
    summaryReport = "summary",
    designDocument = "design",
    apiDocumentation = "api",
    dataMappingReference = "mapping",
    deploymentGuide = "deploy",
    diagrams = List.empty,
  )

  private val mockDiscoveryAgent: ULayer[CobolDiscoveryAgent] =
    ZLayer.succeed(new CobolDiscoveryAgent {
      override def discover(sourcePath: Path): ZIO[Any, DiscoveryError, FileInventory] =
        ZIO.succeed(
          FileInventory(
            discoveredAt = Instant.parse("2026-02-06T00:00:00Z"),
            sourceDirectory = sourcePath,
            files = List(sampleFile),
            summary = InventorySummary(1, 1, 0, 0, 10, 100),
          )
        )
    })

  private val mockAnalyzerAgent: ULayer[CobolAnalyzerAgent] =
    ZLayer.succeed(new CobolAnalyzerAgent {
      override def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis]          =
        ZIO.succeed(sampleAnalysis.copy(file = cobolFile))
      override def analyzeAll(files: List[CobolFile]): ZStream[Any, AnalysisError, CobolAnalysis] =
        ZStream.fromIterable(files.map(file => sampleAnalysis.copy(file = file)))
      override def analyzeAllWithProgress(
        files: List[CobolFile],
        stepName: String,
      ): ZStream[Any, AnalysisError, StepProgressEvent] =
        ZStream.empty
    })

  private val mockMapperAgent: ULayer[DependencyMapperAgent] =
    ZLayer.succeed(new DependencyMapperAgent {
      override def mapDependencies(analyses: List[CobolAnalysis]): ZIO[Any, MappingError, DependencyGraph] =
        ZIO.succeed(DependencyGraph.empty)
    })

  private val mockBusinessLogicAgent: ULayer[BusinessLogicExtractorAgent] =
    ZLayer.succeed(new BusinessLogicExtractorAgent {
      override def extract(analysis: CobolAnalysis): ZIO[Any, BusinessLogicExtractionError, BusinessLogicExtraction] =
        ZIO.succeed(sampleBusinessLogicExtraction.copy(fileName = analysis.file.name))
      override def extractAll(
        analyses: List[CobolAnalysis]
      ): ZIO[Any, BusinessLogicExtractionError, List[BusinessLogicExtraction]] =
        ZIO.succeed(analyses.map(a => sampleBusinessLogicExtraction.copy(fileName = a.file.name)))
    })

  private val mockTransformerAgent: ULayer[JavaTransformerAgent] =
    ZLayer.succeed(new JavaTransformerAgent {
      override def transform(
        analyses: List[CobolAnalysis],
        dependencyGraph: DependencyGraph,
      ): ZIO[Any, TransformError, SpringBootProject] =
        if analyses.isEmpty then ZIO.fail(TransformError.AIFailed("empty", "no analyses to transform"))
        else ZIO.succeed(sampleProject)
    })

  private val mockValidationAgent: ULayer[ValidationAgent] =
    ZLayer.succeed(new ValidationAgent {
      override def validate(project: SpringBootProject, analysis: CobolAnalysis)
        : ZIO[Any, ValidationError, ValidationReport] =
        ZIO.succeed(sampleValidation)
    })

  private val mockDocumentationAgent: ULayer[DocumentationAgent] =
    ZLayer.succeed(new DocumentationAgent {
      override def generateDocs(result: MigrationResult): ZIO[Any, DocError, MigrationDocumentation] =
        ZIO.succeed(sampleDocumentation)
    })

  private val stubHttpAIClient: ULayer[HttpAIClient] =
    ZLayer.succeed(new HttpAIClient {
      override def postJson(
        url: String,
        body: String,
        headers: Map[String, String],
        timeout: Duration,
      ): ZIO[Any, AIError, String] =
        ZIO.fail(AIError.ProviderUnavailable(url, "unused in MigrationOrchestratorSpec"))
    })

  private val noOpRepositoryLayer: ULayer[MigrationRepository] =
    ZLayer.succeed(new MigrationRepository {
      override def createRun(run: MigrationRunRow): IO[PersistenceError, Long]                             = ZIO.succeed(1L)
      override def updateRun(run: MigrationRunRow): IO[PersistenceError, Unit]                             = ZIO.unit
      override def getRun(id: Long): IO[PersistenceError, Option[MigrationRunRow]]                         = ZIO.none
      override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[MigrationRunRow]]          =
        ZIO.succeed(List.empty)
      override def deleteRun(id: Long): IO[PersistenceError, Unit]                                         = ZIO.unit
      override def saveFiles(files: List[CobolFileRow]): IO[PersistenceError, Unit]                        = ZIO.unit
      override def getFilesByRun(runId: Long): IO[PersistenceError, List[CobolFileRow]]                    = ZIO.succeed(List.empty)
      override def saveAnalysis(analysis: CobolAnalysisRow): IO[PersistenceError, Long]                    = ZIO.succeed(1L)
      override def getAnalysesByRun(runId: Long): IO[PersistenceError, List[CobolAnalysisRow]]             =
        ZIO.succeed(List.empty)
      override def saveDependencies(deps: List[DependencyRow]): IO[PersistenceError, Unit]                 = ZIO.unit
      override def getDependenciesByRun(runId: Long): IO[PersistenceError, List[DependencyRow]]            =
        ZIO.succeed(List.empty)
      override def saveProgress(p: PhaseProgressRow): IO[PersistenceError, Long]                           = ZIO.succeed(1L)
      override def getProgress(runId: Long, phase: String): IO[PersistenceError, Option[PhaseProgressRow]] = ZIO.none
      override def updateProgress(p: PhaseProgressRow): IO[PersistenceError, Unit]                         = ZIO.unit
      override def getAllSettings: IO[PersistenceError, List[SettingRow]]                                  = ZIO.succeed(Nil)
      override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                       = ZIO.none
      override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]                   = ZIO.unit
    })

  private val noOpTrackerLayer: ULayer[ProgressTracker] =
    ZLayer.succeed(new ProgressTracker {
      override def startPhase(runId: Long, phase: String, total: Int): IO[PersistenceError, Unit]   = ZIO.unit
      override def updateProgress(update: ProgressUpdate): IO[PersistenceError, Unit]               = ZIO.unit
      override def completePhase(runId: Long, phase: String): IO[PersistenceError, Unit]            = ZIO.unit
      override def failPhase(runId: Long, phase: String, error: String): IO[PersistenceError, Unit] = ZIO.unit
      override def subscribe(runId: Long): UIO[Dequeue[ProgressUpdate]]                             = Queue.unbounded[ProgressUpdate]
    })

  private val noOpPersisterLayer: ULayer[ResultPersister] =
    ZLayer.succeed(new ResultPersister {
      override def saveDiscoveryResult(runId: Long, inventory: FileInventory): IO[PersistenceError, Unit]   = ZIO.unit
      override def saveAnalysisResult(runId: Long, analysis: CobolAnalysis): IO[PersistenceError, Unit]     = ZIO.unit
      override def saveDependencyResult(runId: Long, graph: DependencyGraph): IO[PersistenceError, Unit]    = ZIO.unit
      override def saveTransformResult(runId: Long, project: SpringBootProject): IO[PersistenceError, Unit] = ZIO.unit
      override def saveValidationResult(runId: Long, report: ValidationReport): IO[PersistenceError, Unit]  = ZIO.unit
    })
