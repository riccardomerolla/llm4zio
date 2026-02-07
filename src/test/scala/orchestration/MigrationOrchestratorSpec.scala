package orchestration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.stream.ZStream
import zio.test.*

import agents.*
import core.*
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
                           mockMapperAgent,
                           mockTransformerAgent,
                           mockValidationAgent,
                           mockDocumentationAgent,
                           mockAI,
                           ZLayer.succeed(MigrationConfig(
                             sourceDir = sourceDir,
                             outputDir = outputDir,
                             stateDir = stateDir,
                           )),
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
                           mockMapperAgent,
                           mockTransformerAgent,
                           mockValidationAgent,
                           mockDocumentationAgent,
                           mockAI,
                           ZLayer.succeed(MigrationConfig(
                             sourceDir = sourceDir,
                             outputDir = outputDir,
                             stateDir = stateDir,
                             dryRun = true,
                           )),
                           MigrationOrchestrator.live,
                         )
        yield assertTrue(
          result.status == MigrationStatus.CompletedWithWarnings,
          result.projects.isEmpty,
          result.validationReport == ValidationReport.empty,
        )
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
                            })
          result         <- MigrationOrchestrator
                              .runFullMigration(sourceDir, outputDir)
                              .provide(
                                FileService.live,
                                StateService.live(stateDir),
                                mockDiscoveryAgent,
                                failingAnalyzer,
                                mockMapperAgent,
                                mockTransformerAgent,
                                mockValidationAgent,
                                mockDocumentationAgent,
                                mockAI,
                                ZLayer.succeed(MigrationConfig(
                                  sourceDir = sourceDir,
                                  outputDir = outputDir,
                                  stateDir = stateDir,
                                )),
                                MigrationOrchestrator.live,
                              )
        yield assertTrue(
          result.status == MigrationStatus.Failed,
          result.errors.nonEmpty,
          result.errors.exists(_.step == MigrationStep.Analysis),
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
                              })
          firstResult      <- MigrationOrchestrator
                                .runFullMigration(sourceDir, outputDir)
                                .provide(
                                  FileService.live,
                                  StateService.live(stateDir),
                                  countingDiscovery,
                                  failingAnalyzer,
                                  mockMapperAgent,
                                  mockTransformerAgent,
                                  mockValidationAgent,
                                  mockDocumentationAgent,
                                  mockAI,
                                  ZLayer.succeed(MigrationConfig(
                                    sourceDir = sourceDir,
                                    outputDir = outputDir,
                                    stateDir = stateDir,
                                  )),
                                  MigrationOrchestrator.live,
                                )
          resumedResult    <- MigrationOrchestrator
                                .runFullMigration(sourceDir, outputDir)
                                .provide(
                                  FileService.live,
                                  StateService.live(stateDir),
                                  countingDiscovery,
                                  mockAnalyzerAgent,
                                  mockMapperAgent,
                                  mockTransformerAgent,
                                  mockValidationAgent,
                                  mockDocumentationAgent,
                                  mockAI,
                                  ZLayer.succeed(MigrationConfig(
                                    sourceDir = sourceDir,
                                    outputDir = outputDir,
                                    stateDir = stateDir,
                                    resumeFromCheckpoint = Some(firstResult.runId),
                                  )),
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
    fileType = FileType.Program,
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
    })

  private val mockMapperAgent: ULayer[DependencyMapperAgent] =
    ZLayer.succeed(new DependencyMapperAgent {
      override def mapDependencies(analyses: List[CobolAnalysis]): ZIO[Any, MappingError, DependencyGraph] =
        ZIO.succeed(DependencyGraph.empty)
    })

  private val mockTransformerAgent: ULayer[JavaTransformerAgent] =
    ZLayer.succeed(new JavaTransformerAgent {
      override def transform(
        analysis: CobolAnalysis,
        dependencyGraph: DependencyGraph,
      ): ZIO[Any, TransformError, SpringBootProject] =
        ZIO.succeed(sampleProject)
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

  private val mockAI: ULayer[AIService] =
    ZLayer.succeed(new AIService {
      override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
        ZIO.succeed(AIResponse("{}"))

      override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
        execute(prompt)

      override def isAvailable: ZIO[Any, Nothing, Boolean] =
        ZIO.succeed(true)
    })
