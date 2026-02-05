package orchestration

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import zio.*

import agents.*
import core.*
import models.*

/** MigrationOrchestrator - Main workflow orchestrator using ZIO effects
  *
  * Orchestrates the six-step migration pipeline:
  *   1. Discovery and Inventory
  *   2. Deep Analysis
  *   3. Dependency Mapping
  *   4. Code Transformation
  *   5. Validation and Testing
  *   6. Documentation Generation
  */
trait MigrationOrchestrator:
  def runFullMigration(sourcePath: Path, outputPath: Path): ZIO[Any, Throwable, MigrationResult]
  def runStep(step: MigrationStep): ZIO[Any, Throwable, StepResult]

object MigrationOrchestrator:

  def runFullMigration(sourcePath: Path, outputPath: Path): ZIO[MigrationOrchestrator, Throwable, MigrationResult] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.runFullMigration(sourcePath, outputPath))

  val live: ZLayer[
    CobolDiscoveryAgent &
      CobolAnalyzerAgent &
      DependencyMapperAgent &
      JavaTransformerAgent &
      ValidationAgent &
      DocumentationAgent &
      StateService &
      GeminiService,
    Nothing,
    MigrationOrchestrator,
  ] = ZLayer.fromFunction {
    (
      discoveryAgent: CobolDiscoveryAgent,
      analyzerAgent: CobolAnalyzerAgent,
      mapperAgent: DependencyMapperAgent,
      transformerAgent: JavaTransformerAgent,
      validationAgent: ValidationAgent,
      documentationAgent: DocumentationAgent,
      stateService: StateService,
      geminiService: GeminiService,
    ) =>
      new MigrationOrchestrator {

        override def runFullMigration(sourcePath: Path, outputPath: Path): ZIO[Any, Throwable, MigrationResult] =
          for
            _ <- Logger.info("Starting full migration pipeline...")

            // Generate run ID for this migration
            runId <- Clock.currentTime(TimeUnit.MILLISECONDS).map(ts => s"run-$ts")

            // Step 1: Discovery and Inventory
            _         <- Logger.info("Step 1: Discovery and Inventory")
            inventory <- discoveryAgent.discover(sourcePath, List("*.cbl", "*.cpy", "*.jcl"))
            _         <- stateService.createCheckpoint(runId, MigrationStep.Discovery).mapError(e => new Exception(e.message))

            // Step 2: Deep Analysis
            _        <- Logger.info("Step 2: Deep Analysis")
            analyses <- ZIO.foreach(inventory.files.filter(_.fileType == FileType.Program)) { file =>
                          analyzerAgent.analyze(file).provide(ZLayer.succeed(geminiService))
                        }
            _        <- stateService.createCheckpoint(runId, MigrationStep.Analysis).mapError(e => new Exception(e.message))

            // Step 3: Dependency Mapping
            _               <- Logger.info("Step 3: Dependency Mapping")
            dependencyGraph <- mapperAgent.mapDependencies(analyses)
            _               <- stateService.createCheckpoint(runId, MigrationStep.Mapping).mapError(e => new Exception(e.message))

            // Step 4: Code Transformation
            _        <- Logger.info("Step 4: Code Transformation")
            projects <- ZIO.foreach(analyses) { analysis =>
                          transformerAgent.transform(analysis, dependencyGraph).provide(ZLayer.succeed(geminiService))
                        }
            _        <-
              stateService.createCheckpoint(runId, MigrationStep.Transformation).mapError(e => new Exception(e.message))

            // Step 5: Validation and Testing
            _                 <- Logger.info("Step 5: Validation and Testing")
            validationReports <- ZIO.foreach(projects) { project =>
                                   validationAgent.validate(project)
                                 }
            _                 <- stateService.createCheckpoint(runId, MigrationStep.Validation).mapError(e => new Exception(e.message))

            // Step 6: Documentation Generation
            _             <- Logger.info("Step 6: Documentation Generation")
            documentation <- documentationAgent.generateDocumentation(
                               projects.head,
                               validationReports.head,
                               dependencyGraph,
                             )
            _             <-
              stateService.createCheckpoint(runId, MigrationStep.Documentation).mapError(e => new Exception(e.message))

            _ <- Logger.info("Migration pipeline completed successfully!")
          yield MigrationResult(
            success = true,
            projects = projects,
            documentation = documentation,
            validationReports = validationReports,
          )

        override def runStep(step: MigrationStep): ZIO[Any, Throwable, StepResult] =
          Logger.info(s"Running step: $step") *>
            ZIO.succeed(StepResult(step, success = true, None))
      }
  }

case class MigrationResult(
  success: Boolean,
  projects: List[SpringBootProject],
  documentation: MigrationDocumentation,
  validationReports: List[ValidationReport],
)

case class StepResult(
  step: MigrationStep,
  success: Boolean,
  error: Option[String],
)
