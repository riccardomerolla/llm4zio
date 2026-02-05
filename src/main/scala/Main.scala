import java.nio.file.Paths

import zio.*
import zio.Console.*

import agents.*
import core.*
import models.*
import orchestration.*

/** Main entry point for the Legacy Modernization Agent system
  *
  * This application orchestrates the migration of COBOL programs to Spring Boot microservices using Scala 3 + ZIO 2.x
  * and Google Gemini CLI.
  */
object Main extends ZIOAppDefault:

  private val banner =
    """
      |╔═══════════════════════════════════════════════════════════════════╗
      |║   Legacy Modernization Agents: COBOL to Spring Boot Migration    ║
      |║   Scala 3 + ZIO 2.x Effect-Oriented Programming                  ║
      |║   Version 1.0.0                                                   ║
      |╚═══════════════════════════════════════════════════════════════════╝
      |""".stripMargin

  def run: ZIO[Any, Any, Unit] =
    program.provide(
      // Layer 3: Core Services
      FileService.live,
      GeminiConfig.default,

      // Layer 2: Service implementations (depend on Layer 3)
      GeminiService.live,
      StateService.live(Paths.get("reports")),

      // Layer 1: Agent implementations (depend on Layer 2 & 3)
      CobolDiscoveryAgent.live,
      CobolAnalyzerAgent.live,
      DependencyMapperAgent.live,
      JavaTransformerAgent.live,
      ValidationAgent.live,
      DocumentationAgent.live,

      // Layer 0: Orchestrator (depends on all agents)
      MigrationOrchestrator.live,
    )

  private def program: ZIO[MigrationOrchestrator, Throwable, Unit] =
    for
      _ <- printBanner
      _ <- Logger.info("Starting Legacy Modernization Agent System...")

      // Parse command line arguments
      args <- ZIO.succeed(List("--migrate")) // TODO: Get from actual args

      result <- args.headOption match
                  case Some("--migrate") =>
                    runFullMigration()
                  case Some("--step")    =>
                    args.lift(1) match
                      case Some(step) => runStep(step)
                      case None       =>
                        Logger.error("Missing step name. Usage: --step <step-name>") *>
                          ZIO.fail(new IllegalArgumentException("Missing step name"))
                  case Some("--help")    =>
                    showHelp()
                  case _                 =>
                    Logger.error("Invalid arguments. Use --help for usage information.") *>
                      showHelp()

      _ <- Logger.info("Migration agent system completed successfully!")
    yield ()

  private def printBanner: ZIO[Any, Nothing, Unit] =
    printLine(banner).orDie

  private def runFullMigration(): ZIO[MigrationOrchestrator, Throwable, Unit] =
    for
      _ <- Logger.info("Running full migration pipeline...")

      sourcePath = Paths.get("cobol-source")
      outputPath = Paths.get("java-output")

      _ <- Logger.info(s"Source path: $sourcePath")
      _ <- Logger.info(s"Output path: $outputPath")

      result <- MigrationOrchestrator.runFullMigration(sourcePath, outputPath)

      _ <- Logger.info(s"Migration completed: ${result.projects.length} projects generated")
      _ <- printMigrationSummary(result)
    yield ()

  private def runStep(stepName: String): ZIO[Any, Throwable, Unit] =
    for
      _    <- Logger.info(s"Running step: $stepName")
      step <- parseStep(stepName)
      // TODO: Implement step execution via MigrationOrchestrator
      _    <- Logger.info(s"Step $stepName completed")
    yield ()

  private def parseStep(stepName: String): ZIO[Any, Throwable, MigrationStep] =
    stepName.toLowerCase match
      case "discovery"      => ZIO.succeed(MigrationStep.Discovery)
      case "analysis"       => ZIO.succeed(MigrationStep.Analysis)
      case "mapping"        => ZIO.succeed(MigrationStep.Mapping)
      case "transformation" => ZIO.succeed(MigrationStep.Transformation)
      case "validation"     => ZIO.succeed(MigrationStep.Validation)
      case "documentation"  => ZIO.succeed(MigrationStep.Documentation)
      case _                => ZIO.fail(new IllegalArgumentException(s"Unknown step: $stepName"))

  private def showHelp(): ZIO[Any, Nothing, Unit] =
    val helpText =
      """
        |Usage: sbt "run [options]"
        |
        |Options:
        |  --migrate              Run the full migration pipeline
        |  --step <step-name>     Run a specific migration step
        |  --help                 Show this help message
        |
        |Available steps:
        |  discovery              Scan and catalog COBOL source files
        |  analysis               Deep analysis of COBOL programs
        |  mapping                Map dependencies between programs
        |  transformation         Transform COBOL to Spring Boot
        |  validation             Validate and test generated code
        |  documentation          Generate migration documentation
        |
        |Examples:
        |  sbt "run --migrate"
        |  sbt "run --step discovery"
        |  sbt "run --step analysis"
        |
        |Configuration:
        |  Place COBOL files in: cobol-source/
        |  Generated code goes to: java-output/
        |  Reports saved to: reports/
        |
        |For more information, see: README.md
        |""".stripMargin

    printLine(helpText).orDie

  private def printMigrationSummary(result: MigrationResult): ZIO[Any, Nothing, Unit] =
    val summary =
      s"""
         |╔═══════════════════════════════════════════════════════════════════╗
         |║                      Migration Summary                           ║
         |╠═══════════════════════════════════════════════════════════════════╣
         |║  Status: ${if result.success then "SUCCESS" else "FAILED"}
         |║  Projects Generated: ${result.projects.length}
         |║  Validation Reports: ${result.validationReports.length}
         |║
         |║  Output Directory: java-output/
         |║  Reports Directory: reports/
         |║  Documentation: Available in reports/
         |╚═══════════════════════════════════════════════════════════════════╝
         |""".stripMargin

    printLine(summary).orDie
