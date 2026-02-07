package integration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.logging.backend.SLF4J
import zio.stream.ZStream
import zio.test.*

import agents.*
import core.*
import models.*
import orchestration.{ MigrationOrchestrator, MigrationStatus }
import prompts.PromptHelpers

object MigrationOrchestratorIntegrationSpec extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >>> testEnvironment

  private val samplesDir = Path.of("cobol-source/samples")

  def spec: Spec[Any, Any] = suite("MigrationOrchestratorIntegrationSpec")(
    test("runs full pipeline on sample COBOL files") {
      ZIO.scoped {
        for
          _                <- ensureSamplesDir
          stateDir         <- ZIO.attemptBlocking(Files.createTempDirectory("it-state-full"))
          outDir           <- ZIO.attemptBlocking(Files.createTempDirectory("it-out-full"))
          config            = MigrationConfig(sourceDir = samplesDir, outputDir = outDir, stateDir = stateDir, parallelism = 2)
          result           <- MigrationOrchestrator
                                .runFullMigration(samplesDir, outDir)
                                .provide(
                                  FileService.live,
                                  StateService.live(stateDir),
                                  analyzerLayer,
                                  CobolDiscoveryAgent.live,
                                  DependencyMapperAgent.live,
                                  JavaTransformerAgent.live,
                                  validationLayer,
                                  DocumentationAgent.live,
                                  stubGeminiLayer,
                                  ResponseParser.live,
                                  ZLayer.succeed(config),
                                  MigrationOrchestrator.live,
                                )
          pomChecks        <- ZIO.foreach(result.projects) { project =>
                                val pomPath = outDir.resolve(project.projectName.toLowerCase).resolve("pom.xml")
                                ZIO.attemptBlocking(Files.isRegularFile(pomPath))
                              }
          mappingReport    <- ZIO.attemptBlocking(Files.isRegularFile(Path.of("reports/mapping/dependency-graph.json")))
          docsReport       <- ZIO.attemptBlocking(Files.isRegularFile(Path.of("reports/documentation/migration-summary.md")))
          callEdgeExists    =
            result.dependencyGraph.edges.exists(edge =>
              edge.edgeType == EdgeType.Calls && edge.from == "CUSTOMER-INQUIRY" && edge.to == "CUSTOMER-DISPLAY"
            )
          formatEdgeExists  =
            result.dependencyGraph.edges.exists(edge =>
              edge.edgeType == EdgeType.Calls && edge.from == "CUSTOMER-DISPLAY" && edge.to == "FORMAT-BALANCE"
            )
          includeEdgeExists =
            result.dependencyGraph.edges.exists(edge =>
              edge.edgeType == EdgeType.Includes && edge.from == "CUSTOMER-INQUIRY" && edge.to == "CUSTOMER-DATA"
            )
        yield assertTrue(
          result.status == MigrationStatus.Completed,
          result.projects.nonEmpty,
          pomChecks.forall(identity),
          mappingReport,
          docsReport,
          callEdgeExists,
          formatEdgeExists,
          includeEdgeExists,
        )
      }
    },
    test("resume run accumulates checkpoints across runs") {
      ZIO.scoped {
        for
          _              <- ensureSamplesDir
          stateDir       <- ZIO.attemptBlocking(Files.createTempDirectory("it-state-resume"))
          outDir         <- ZIO.attemptBlocking(Files.createTempDirectory("it-out-resume"))
          config          = MigrationConfig(sourceDir = samplesDir, outputDir = outDir, stateDir = stateDir, parallelism = 2)
          first          <- MigrationOrchestrator
                              .runFullMigration(samplesDir, outDir)
                              .provide(
                                FileService.live,
                                StateService.live(stateDir),
                                analyzerLayer,
                                CobolDiscoveryAgent.live,
                                DependencyMapperAgent.live,
                                JavaTransformerAgent.live,
                                failingValidationLayer,
                                DocumentationAgent.live,
                                stubGeminiLayer,
                                ResponseParser.live,
                                ZLayer.succeed(config),
                                MigrationOrchestrator.live,
                              )
          second         <- MigrationOrchestrator
                              .runFullMigration(samplesDir, outDir)
                              .provide(
                                FileService.live,
                                StateService.live(stateDir),
                                analyzerLayer,
                                CobolDiscoveryAgent.live,
                                DependencyMapperAgent.live,
                                JavaTransformerAgent.live,
                                validationLayer,
                                DocumentationAgent.live,
                                stubGeminiLayer,
                                ResponseParser.live,
                                ZLayer.succeed(config.copy(resumeFromCheckpoint = Some(first.runId))),
                                MigrationOrchestrator.live,
                              )
          checkpoints    <- StateService
                              .listCheckpoints(first.runId)
                              .provide(StateService.live(stateDir), FileService.live)
          checkpointSteps = checkpoints.map(_.step).toSet
        yield assertTrue(
          first.status == MigrationStatus.PartialFailure || first.status == MigrationStatus.Failed,
          second.status == MigrationStatus.Completed,
          first.runId == second.runId,
          checkpointSteps.contains(MigrationStep.Discovery),
          checkpointSteps.contains(MigrationStep.Analysis),
          checkpointSteps.contains(MigrationStep.Mapping),
          checkpointSteps.contains(MigrationStep.Transformation),
          checkpointSteps.contains(MigrationStep.Validation),
          checkpointSteps.contains(MigrationStep.Documentation),
        )
      }
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private val analyzerLayer: ZLayer[FileService, Nothing, CobolAnalyzerAgent] =
    ZLayer.fromFunction { (fileService: FileService) =>
      new CobolAnalyzerAgent {
        override def analyze(cobolFile: CobolFile): ZIO[Any, AnalysisError, CobolAnalysis] =
          for
            content <- fileService
                         .readFile(cobolFile.path)
                         .mapError(fe => AnalysisError.FileReadFailed(cobolFile.path, fe.message))
          yield buildAnalysis(cobolFile, content)

        override def analyzeAll(files: List[CobolFile]): ZStream[Any, AnalysisError, CobolAnalysis] =
          ZStream.fromIterable(files).mapZIO(analyze)
      }
    }

  private val stubGeminiLayer: ULayer[GeminiService] =
    ZLayer.succeed(new GeminiService {
      override def executeLegacy(prompt: String): ZIO[Any, GeminiError, GeminiResponse] =
        ZIO.succeed(GeminiResponse(selectResponse(prompt), 0))

      override def executeWithContextLegacy(prompt: String, context: String): ZIO[Any, GeminiError, GeminiResponse] =
        ZIO.succeed(GeminiResponse(selectResponse(prompt), 0))

      override def isAvailable: ZIO[Any, Nothing, Boolean] =
        ZIO.succeed(true)
    })

  private val validationLayer: ULayer[ValidationAgent] =
    ZLayer.succeed(new ValidationAgent {
      override def validate(project: SpringBootProject, analysis: CobolAnalysis)
        : ZIO[Any, ValidationError, ValidationReport] =
        ZIO.succeed(
          ValidationReport(
            projectName = project.projectName,
            validatedAt = Instant.EPOCH,
            compileResult = CompileResult(success = true, exitCode = 0, output = "ok"),
            coverageMetrics = CoverageMetrics(100.0, 100.0, 100.0, List.empty),
            issues = List.empty,
            semanticValidation = SemanticValidation(
              businessLogicPreserved = true,
              confidence = 0.98,
              summary = "stubbed",
              issues = List.empty,
            ),
            overallStatus = ValidationStatus.Passed,
          )
        )
    })

  private val failingValidationLayer: ULayer[ValidationAgent] =
    ZLayer.succeed(new ValidationAgent {
      override def validate(project: SpringBootProject, analysis: CobolAnalysis)
        : ZIO[Any, ValidationError, ValidationReport] =
        ZIO.fail(ValidationError.SemanticValidationFailed(project.projectName, "forced failure"))
    })

  private def selectResponse(prompt: String): String =
    if prompt.contains("JavaEntity") then
      """{
        |  "className": "CustomerRecord",
        |  "packageName": "com.example.customer.entity",
        |  "fields": [
        |    { "name": "id", "javaType": "Long", "cobolSource": "CUST-ID", "annotations": ["@Id"] }
        |  ],
        |  "annotations": ["@Entity", "@Table(name = \"customer\")"],
        |  "sourceCode": "public class CustomerRecord { }"
        |}""".stripMargin
    else if prompt.contains("JavaService") then
      """{
        |  "name": "CustomerService",
        |  "methods": [
        |    { "name": "process", "returnType": "void", "parameters": [], "body": "" }
        |  ]
        |}""".stripMargin
    else if prompt.contains("JavaController") then
      """{
        |  "name": "CustomerController",
        |  "basePath": "/api/customer",
        |  "endpoints": [
        |    { "path": "/process", "method": "POST", "methodName": "process" }
        |  ]
        |}""".stripMargin
    else
      "{}"

  private def buildAnalysis(cobolFile: CobolFile, content: String): CobolAnalysis =
    val divisions  = PromptHelpers.chunkByDivision(content)
    val lines      = content.linesIterator.toList
    val copybooks  = extractMatches(lines, "(?i)\\bCOPY\\s+([A-Z0-9-]+)\\.?")
    val variables  = extractMatches(lines, "(?i)\\b01\\s+([A-Z0-9-]+)")
      .map(name => Variable(name = name, level = 1, dataType = "group", picture = None, usage = None))
    val statements = lines.zipWithIndex.collect {
      case (line, idx) if line.toUpperCase.contains("CALL") =>
        Statement(lineNumber = idx + 1, statementType = "CALL", content = line.trim)
    }
    val procedures =
      if statements.isEmpty then List(Procedure(name = "MAIN", paragraphs = List("MAIN"), statements = List.empty))
      else List(Procedure(name = "MAIN", paragraphs = List("MAIN"), statements = statements))
    val ifCount    = lines.count(line => line.trim.toUpperCase.startsWith("IF "))
    val loc        = lines.count(line => line.trim.nonEmpty && !line.trim.startsWith("*"))
    CobolAnalysis(
      file = cobolFile,
      divisions = CobolDivisions(
        identification = divisions.get("IDENTIFICATION"),
        environment = divisions.get("ENVIRONMENT"),
        data = divisions.get("DATA"),
        procedure = divisions.get("PROCEDURE"),
      ),
      variables = variables,
      procedures = procedures,
      copybooks = copybooks,
      complexity = ComplexityMetrics(
        cyclomaticComplexity = Math.max(1, ifCount + 1),
        linesOfCode = loc,
        numberOfProcedures = procedures.size,
      ),
    )

  private def extractMatches(lines: List[String], pattern: String): List[String] =
    val regex = pattern.r
    lines
      .flatMap(line => regex.findAllMatchIn(line).map(_.group(1)).toList)
      .map(_.trim)
      .filter(_.nonEmpty)
      .distinct

  private def ensureSamplesDir: Task[Unit] =
    ZIO
      .attemptBlocking(Files.isDirectory(samplesDir))
      .flatMap { isDir =>
        ZIO.fail(new RuntimeException(s"Missing COBOL samples at $samplesDir")).unless(isDir)
      }
      .unit
