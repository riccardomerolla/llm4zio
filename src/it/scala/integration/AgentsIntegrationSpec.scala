package integration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.logging.backend.SLF4J
import zio.test.*
import zio.test.Assertion.*

import agents.*
import core.*
import models.*
import orchestration.{ MigrationResult, MigrationStatus }

object AgentsIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private val samplesDir = Path.of("cobol-source/samples")

  def spec: Spec[Any, Any] = suite("AgentsIntegrationSpec")(
    suite("Isolated agent tests with real Gemini calls")(
      test("CobolAnalyzerAgent ISOLATED - analyzes FORMAT-BALANCE.cbl") {
        ZIO.logLevel(LogLevel.Debug) {
          ZIO.scoped {
            for
              _         <- ensureSamplesDir
              outDir    <- ZIO.attemptBlocking(Files.createTempDirectory("it-isolated-analyzer"))
              config     = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
              cobolFile <- findCobolFile("FORMAT-BALANCE.cbl", config)
              analysis  <- CobolAnalyzerAgent
                             .analyze(cobolFile)
                             .provide(
                               FileService.live,
                               ResponseParser.live,
                               ZLayer.succeed(config),
                               rateLimiterLayer(config),
                               GeminiService.live >>> loggingGeminiLayer,
                               CobolAnalyzerAgent.live,
                             )
            yield assertTrue(
              analysis.file.name == "FORMAT-BALANCE.cbl",
              analysis.file.path == cobolFile.path,
              analysis.complexity.linesOfCode > 0,
            )
          }
        }
      },
      test("JavaTransformerAgent ISOLATED - transforms minimal analysis to Spring Boot") {
        ZIO.logLevel(LogLevel.Debug) {
          ZIO.scoped {
            for
              _        <- ensureSamplesDir
              outDir   <- ZIO.attemptBlocking(Files.createTempDirectory("it-isolated-transformer"))
              config    = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
              file     <- findCobolFile("TEST-PROGRAM.cbl", config)
              analysis <- CobolAnalyzerAgent
                            .analyze(file)
                            .provide(
                              FileService.live,
                              ResponseParser.live,
                              ZLayer.succeed(config),
                              rateLimiterLayer(config),
                              GeminiService.live >>> loggingGeminiLayer,
                              CobolAnalyzerAgent.live,
                            )
              project  <- JavaTransformerAgent
                            .transform(analysis, DependencyGraph.empty)
                            .provide(
                              FileService.live,
                              ResponseParser.live,
                              ZLayer.succeed(config),
                              rateLimiterLayer(config),
                              GeminiService.live >>> loggingGeminiLayer,
                              JavaTransformerAgent.live,
                            )
            yield assertTrue(
              project.projectName.nonEmpty,
              project.sourceProgram == file.name,
              project.entities.nonEmpty || project.services.nonEmpty,
            )
          }
        }
      },
      test("ValidationAgent ISOLATED - validates generated project with semantic check") {
        ZIO.logLevel(LogLevel.Debug) {
          ZIO.scoped {
            for
              _        <- ensureSamplesDir
              outDir   <- ZIO.attemptBlocking(Files.createTempDirectory("it-isolated-validation"))
              config    = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
              file     <- findCobolFile("TEST-PROGRAM.cbl", config)
              analysis <- CobolAnalyzerAgent
                            .analyze(file)
                            .provide(
                              FileService.live,
                              ResponseParser.live,
                              ZLayer.succeed(config),
                              rateLimiterLayer(config),
                              GeminiService.live >>> loggingGeminiLayer,
                              CobolAnalyzerAgent.live,
                            )
              project  <- JavaTransformerAgent
                            .transform(analysis, DependencyGraph.empty)
                            .provide(
                              FileService.live,
                              ResponseParser.live,
                              ZLayer.succeed(config),
                              rateLimiterLayer(config),
                              GeminiService.live >>> loggingGeminiLayer,
                              JavaTransformerAgent.live,
                            )
              report   <- ValidationAgent
                            .validate(project, analysis)
                            .provide(
                              FileService.live,
                              ResponseParser.live,
                              ZLayer.succeed(config),
                              rateLimiterLayer(config),
                              GeminiService.live >>> loggingGeminiLayer,
                              ValidationAgent.live,
                            )
            yield assertTrue(
              report.projectName == project.projectName,
              report.semanticValidation.issues.nonEmpty || report.semanticValidation.confidence > 0.0,
            )
          }
        }
      },
    ) @@ TestAspect.sequential,
    test("CobolAnalyzerAgent analyzes sample COBOL with Gemini") {
      ZIO.logLevel(LogLevel.Debug) {
        ZIO.scoped {
          for
            _         <- ensureSamplesDir
            outDir    <- ZIO.attemptBlocking(Files.createTempDirectory("it-analyzer-out"))
            config     = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
            cobolFile <- findCobolFile("CUSTOMER-INQUIRY.cbl", config)
            analysis  <- CobolAnalyzerAgent
                           .analyze(cobolFile)
                           .provide(
                             FileService.live,
                             ResponseParser.live,
                             ZLayer.succeed(config),
                             rateLimiterLayer(config),
                             GeminiService.live >>> loggingGeminiLayer,
                             CobolAnalyzerAgent.live,
                           )
            reportPath = Path.of("reports/analysis").resolve("CUSTOMER-INQUIRY.cbl.json")
            reportOk  <- ZIO.attemptBlocking(Files.isRegularFile(reportPath))
          yield assertTrue(
            analysis.file.name == "CUSTOMER-INQUIRY.cbl",
            analysis.divisions.procedure.nonEmpty,
            reportOk,
          )
        }
      }
    },
    test("GeminiService times out with tiny timeout") {
      ZIO.logLevel(LogLevel.Debug) {
        ZIO.scoped {
          for
            outDir <- ZIO.attemptBlocking(Files.createTempDirectory("it-gemini-timeout"))
            config  = MigrationConfig(
                        sourceDir = samplesDir,
                        outputDir = outDir,
                        geminiTimeout = 1.millis,
                        geminiMaxRetries = 0,
                      )
            result <- GeminiService
                        .execute("Respond with a short JSON object containing a field named ping.")
                        .provide(
                          ZLayer.succeed(config),
                          rateLimiterLayer(config),
                          GeminiService.live >>> loggingGeminiLayer,
                        )
                        .exit
          yield assert(result)(fails(isSubtype[GeminiError.Timeout](anything)))
        }
      }
    },
    test("CobolDiscoveryAgent discovers samples with Gemini-backed analysis") {
      ZIO.logLevel(LogLevel.Debug) {
        ZIO.scoped {
          for
            _         <- ensureSamplesDir
            outDir    <- ZIO.attemptBlocking(Files.createTempDirectory("it-discovery-out"))
            config     = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
            inventory <- CobolDiscoveryAgent
                           .discover(samplesDir)
                           .provide(
                             FileService.live,
                             ZLayer.succeed(config),
                             CobolDiscoveryAgent.live,
                           )
            cobolFile <- ZIO
                           .fromOption(inventory.files.find(_.name == "CUSTOMER-INQUIRY.cbl"))
                           .orElseFail(new RuntimeException("Missing CUSTOMER-INQUIRY.cbl"))
            analysis  <- CobolAnalyzerAgent
                           .analyze(cobolFile)
                           .provide(
                             FileService.live,
                             ResponseParser.live,
                             ZLayer.succeed(config),
                             rateLimiterLayer(config),
                             GeminiService.live >>> loggingGeminiLayer,
                             CobolAnalyzerAgent.live,
                           )
          yield assertTrue(
            inventory.files.nonEmpty,
            analysis.file.name == cobolFile.name,
          )
        }
      }
    },
    test("JavaTransformerAgent generates Spring Boot project with Gemini") {
      ZIO.logLevel(LogLevel.Debug) {
        ZIO.scoped {
          for
            _         <- ensureSamplesDir
            outDir    <- ZIO.attemptBlocking(Files.createTempDirectory("it-transformer-out"))
            config     = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
            cobolFile <- findCobolFile("CUSTOMER-DISPLAY.cbl", config)
            analysis  <- CobolAnalyzerAgent
                           .analyze(cobolFile)
                           .provide(
                             FileService.live,
                             ResponseParser.live,
                             ZLayer.succeed(config),
                             rateLimiterLayer(config),
                             GeminiService.live >>> loggingGeminiLayer,
                             CobolAnalyzerAgent.live,
                           )
            project   <- JavaTransformerAgent
                           .transform(analysis, DependencyGraph.empty)
                           .provide(
                             FileService.live,
                             ResponseParser.live,
                             ZLayer.succeed(config),
                             rateLimiterLayer(config),
                             GeminiService.live >>> loggingGeminiLayer,
                             JavaTransformerAgent.live,
                           )
            projectDir = outDir.resolve(project.projectName.toLowerCase)
            pomExists <- ZIO.attemptBlocking(Files.isRegularFile(projectDir.resolve("pom.xml")))
            appExists <- ZIO.attemptBlocking {
                           Files.isRegularFile(
                             projectDir
                               .resolve(s"src/main/java/com/example/${project.projectName.toLowerCase}")
                               .resolve("Application.java")
                           )
                         }
          yield assertTrue(
            project.projectName.nonEmpty,
            project.entities.nonEmpty,
            pomExists,
            appExists,
          )
        }
      }
    },
    test("DependencyMapperAgent builds graph from Gemini analyses") {
      ZIO.logLevel(LogLevel.Debug) {
        ZIO.scoped {
          for
            _         <- ensureSamplesDir
            outDir    <- ZIO.attemptBlocking(Files.createTempDirectory("it-mapper-out"))
            config     = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
            inventory <- CobolDiscoveryAgent
                           .discover(samplesDir)
                           .provide(
                             FileService.live,
                             ZLayer.succeed(config),
                             CobolDiscoveryAgent.live,
                           )
            targets    = Set("CUSTOMER-DISPLAY.cbl", "CUSTOMER-INQUIRY.cbl")
            programs   = inventory.files.filter(f => targets.contains(f.name))
            analyses  <- ZIO.foreach(programs) { file =>
                           CobolAnalyzerAgent
                             .analyze(file)
                             .provide(
                               FileService.live,
                               ResponseParser.live,
                               ZLayer.succeed(config),
                               rateLimiterLayer(config),
                               GeminiService.live >>> loggingGeminiLayer,
                               CobolAnalyzerAgent.live,
                             )
                         }
            graph     <- DependencyMapperAgent
                           .mapDependencies(analyses)
                           .provide(
                             FileService.live,
                             DependencyMapperAgent.live,
                           )
          yield assertTrue(
            graph.nodes.nonEmpty,
            graph.edges.nonEmpty,
          )
        }
      }
    },
    test("ValidationAgent runs semantic validation without Maven") {
      ZIO.logLevel(LogLevel.Debug) {
        ZIO.scoped {
          for
            _         <- ensureSamplesDir
            outDir    <- ZIO.attemptBlocking(Files.createTempDirectory("it-validation-out"))
            config     = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
            cobolFile <- findCobolFile("TEST-PROGRAM.cbl", config)
            analysis  <- CobolAnalyzerAgent
                           .analyze(cobolFile)
                           .provide(
                             FileService.live,
                             ResponseParser.live,
                             ZLayer.succeed(config),
                             rateLimiterLayer(config),
                             GeminiService.live >>> loggingGeminiLayer,
                             CobolAnalyzerAgent.live,
                           )
            project    = SpringBootProject(
                           projectName = "validation-sample",
                           sourceProgram = cobolFile.name,
                           generatedAt = Instant.EPOCH,
                           entities = List.empty,
                           services = List.empty,
                           controllers = List.empty,
                           repositories = List.empty,
                           configuration = ProjectConfiguration("com.example", "validation-sample", List.empty),
                           buildFile = BuildFile("maven", ""),
                         )
            report    <- ValidationAgent
                           .validate(project, analysis)
                           .provide(
                             FileService.live,
                             ResponseParser.live,
                             ZLayer.succeed(config),
                             rateLimiterLayer(config),
                             GeminiService.live >>> loggingGeminiLayer,
                             ValidationAgent.live,
                           )
            reportPath = Path.of("reports/validation").resolve("validation-sample-validation.json")
            reportOk  <- ZIO.attemptBlocking(Files.isRegularFile(reportPath))
          yield assertTrue(
            report.compileResult.success == false,
            report.overallStatus == ValidationStatus.Failed,
            reportOk,
          )
        }
      }
    },
    test("DocumentationAgent writes migration docs from Gemini output") {
      ZIO.logLevel(LogLevel.Debug) {
        ZIO.scoped {
          for
            _         <- ensureSamplesDir
            outDir    <- ZIO.attemptBlocking(Files.createTempDirectory("it-docs-out"))
            config     = MigrationConfig(sourceDir = samplesDir, outputDir = outDir)
            cobolFile <- findCobolFile("CUSTOMER-DISPLAY.cbl", config)
            analysis  <- CobolAnalyzerAgent
                           .analyze(cobolFile)
                           .provide(
                             FileService.live,
                             ResponseParser.live,
                             ZLayer.succeed(config),
                             rateLimiterLayer(config),
                             GeminiService.live >>> loggingGeminiLayer,
                             CobolAnalyzerAgent.live,
                           )
            project   <- JavaTransformerAgent
                           .transform(analysis, DependencyGraph.empty)
                           .provide(
                             FileService.live,
                             ResponseParser.live,
                             ZLayer.succeed(config),
                             rateLimiterLayer(config),
                             GeminiService.live >>> loggingGeminiLayer,
                             JavaTransformerAgent.live,
                           )
            result     = MigrationResult(
                           runId = "run-docs",
                           startedAt = Instant.EPOCH,
                           completedAt = Instant.EPOCH,
                           config = config,
                           inventory = FileInventory(
                             discoveredAt = Instant.EPOCH,
                             sourceDirectory = samplesDir,
                             files = List(cobolFile),
                             summary = InventorySummary(1, 1, 0, 0, cobolFile.lineCount, cobolFile.size),
                           ),
                           analyses = List(analysis),
                           dependencyGraph = DependencyGraph.empty,
                           projects = List(project),
                           validationReport = ValidationReport.empty,
                           validationReports = List.empty,
                           documentation = MigrationDocumentation.empty,
                           errors = List.empty,
                           status = MigrationStatus.CompletedWithWarnings,
                         )
            docs      <- DocumentationAgent.generateDocs(result).provide(FileService.live, DocumentationAgent.live)
            summaryOk <- ZIO.attemptBlocking(Files.isRegularFile(Path.of("reports/documentation/migration-summary.md")))
            diagramOk <- ZIO.attemptBlocking(
                           Files.isRegularFile(Path.of("reports/documentation/diagrams/architecture.mmd"))
                         )
          yield assertTrue(
            docs.summaryReport.nonEmpty,
            summaryOk,
            diagramOk,
          )
        }
      }
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def rateLimiterLayer(config: MigrationConfig): ZLayer[Any, Nothing, RateLimiter] =
    val rateConfig = RateLimiterConfig.fromMigrationConfig(config)
    ZLayer.succeed(rateConfig) >>> RateLimiter.live

  private val loggingGeminiLayer: ZLayer[GeminiService, Nothing, GeminiService] =
    ZLayer.fromZIO {
      ZIO.serviceWith[GeminiService] { underlying =>
        new GeminiService {
          override def executeLegacy(prompt: String): ZIO[Any, GeminiError, GeminiResponse] =
            logPrompt(prompt) *> underlying.executeLegacy(prompt).tapBoth(logError, logResponse("execute"))

          override def executeWithContextLegacy(prompt: String, context: String)
            : ZIO[Any, GeminiError, GeminiResponse] =
            logPrompt(prompt) *> underlying.executeWithContextLegacy(
              prompt,
              context,
            ).tapBoth(logError, logResponse("executeWithContext"))

          override def isAvailable: ZIO[Any, Nothing, Boolean] =
            underlying.isAvailable
        }
      }
    }

  private def logPrompt(prompt: String): UIO[Unit] =
    ZIO.logDebug(s"Gemini prompt length=${prompt.length}")

  private def logResponse(label: String)(response: GeminiResponse): UIO[Unit] =
    ZIO.logDebug(s"Gemini $label output (truncated): ${truncate(response.output)}")

  private def logError(error: GeminiError): UIO[Unit] =
    error match
      case GeminiError.Timeout(d)                =>
        ZIO.logError(s"Gemini TIMEOUT after ${d.toSeconds}s")
      case GeminiError.NonZeroExit(code, output) =>
        ZIO.logError(s"Gemini FAILED with exit code $code. Output: ${truncate(output, 1000)}")
      case other                                 =>
        ZIO.logError(s"Gemini ERROR: $error")

  private def truncate(value: String, max: Int = 500): String =
    if value.length <= max then value else value.take(max) + "..."

  private def findCobolFile(name: String, config: MigrationConfig): ZIO[Any, Throwable, CobolFile] =
    CobolDiscoveryAgent
      .discover(samplesDir)
      .mapError(err => new Exception(err.message))
      .flatMap { inventory =>
        ZIO
          .fromOption(inventory.files.find(_.name == name))
          .orElseFail(new Exception(s"Missing COBOL file: $name"))
      }
      .provide(
        FileService.live,
        ZLayer.succeed(config),
        CobolDiscoveryAgent.live,
      )

  private def ensureSamplesDir: Task[Unit] =
    ZIO
      .attemptBlocking(Files.isDirectory(samplesDir))
      .flatMap { isDir =>
        ZIO.fail(new RuntimeException(s"Missing COBOL samples at $samplesDir")).unless(isDir)
      }
      .unit
