import java.nio.file.{ Path, Paths }

import zio.*
import zio.Console.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.http.Client

import _root_.config.ConfigLoader
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

  // ============================================================================
  // CLI Definition
  // ============================================================================

  /** CLI Options */
  private val sourceDirOpt  = Options.directory("source").alias("s") ?? "Source directory containing COBOL files"
  private val outputDirOpt  = Options.directory("output").alias("o") ?? "Output directory for generated Java code"
  private val stateDirOpt   =
    Options.directory("state-dir").optional ?? "Directory for migration state (default: .migration-state)"
  private val configFileOpt = Options.file("config").alias("c").optional ?? "Configuration file (HOCON or JSON)"

  private val geminiModelOpt   = Options.text("gemini-model").optional ?? "Gemini model name (default: gemini-2.5-flash)"
  private val geminiTimeoutOpt =
    Options.integer("gemini-timeout").optional ?? "Gemini API timeout in seconds (default: 60)"
  private val geminiRetriesOpt = Options.integer("gemini-retries").optional ?? "Max Gemini API retries (default: 3)"

  private val aiProviderOpt    =
    Options.text("ai-provider").optional ?? "AI provider: gemini-cli|gemini-api|openai|anthropic"
  private val aiModelOpt       = Options.text("ai-model").optional ?? "AI model name (e.g., gpt-4o, gemini-2.5-flash)"
  private val aiBaseUrlOpt     = Options.text("ai-base-url").optional ?? "AI base URL (e.g., http://localhost:1234/v1)"
  private val aiApiKeyOpt      = Options.text("ai-api-key").optional ?? "AI API key (sensitive)"
  private val aiTemperatureOpt = Options.text("ai-temperature").optional ?? "AI temperature (0.0-2.0)"
  private val aiMaxTokensOpt   = Options.integer("ai-max-tokens").optional ?? "AI max output tokens"

  private val discoveryMaxDepthOpt =
    Options.integer("discovery-max-depth").optional ?? "Max discovery scan depth (default: 25)"
  private val discoveryExcludeOpt  =
    Options.text("discovery-exclude").optional ?? "Comma-separated exclude globs for discovery"

  private val parallelismOpt =
    Options.integer("parallelism").alias("p").optional ?? "Number of parallel workers (default: 4)"
  private val batchSizeOpt   = Options.integer("batch-size").optional ?? "Batch size for file processing (default: 10)"

  private val resumeOpt  = Options.text("resume").optional ?? "Resume from checkpoint (provide run ID)"
  private val dryRunOpt  = Options.boolean("dry-run").optional ?? "Perform dry run without writing files"
  private val verboseOpt = Options.boolean("verbose").alias("v").optional ?? "Enable verbose logging"

  private val stepNameArg = Args.text("step-name") ?? "Migration step to run"

  /** CLI Commands */
  type MigrateOpts = (
    Path,
    Path,
    Option[Path],
    Option[Path],
    Option[String],
    Option[BigInt],
    Option[BigInt],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[String],
    Option[BigInt],
    Option[BigInt],
    Option[String],
    Option[BigInt],
    Option[BigInt],
    Option[String],
    Option[Boolean],
    Option[Boolean],
  )

  type StepOpts     = ((Path, Path, Option[Path], Option[Boolean], Option[BigInt], Option[String]), String)
  type ListRunsOpts = (Option[Path], Option[Path])

  private val migrateCmd: Command[MigrateOpts] =
    Command(
      "migrate",
      sourceDirOpt ++ outputDirOpt ++ stateDirOpt ++ configFileOpt ++
        geminiModelOpt ++ geminiTimeoutOpt ++ geminiRetriesOpt ++ aiProviderOpt ++ aiModelOpt ++ aiBaseUrlOpt ++
        aiApiKeyOpt ++ aiTemperatureOpt ++ aiMaxTokensOpt ++ discoveryMaxDepthOpt ++
        discoveryExcludeOpt ++ parallelismOpt ++ batchSizeOpt ++ resumeOpt ++ dryRunOpt ++ verboseOpt,
    ).withHelp("Run the full migration pipeline")

  private val stepCmd: Command[StepOpts] = Command(
    "step",
    sourceDirOpt ++ outputDirOpt ++ configFileOpt ++ verboseOpt ++ discoveryMaxDepthOpt ++ discoveryExcludeOpt,
    stepNameArg,
  ).withHelp("Run a specific migration step")

  private val listRunsCmd: Command[ListRunsOpts] = Command(
    "list-runs",
    stateDirOpt ++ configFileOpt,
  ).withHelp("List available migration runs and checkpoints")

  private val cliApp = CliApp.make(
    name = "zio-legacy-modernization",
    version = "1.0.0",
    summary = text("COBOL to Spring Boot Migration Tool"),
    command = migrateCmd | stepCmd | listRunsCmd,
  ) {
    case opts: MigrateOpts @unchecked =>
      executeMigrate(
        opts._1,
        opts._2,
        opts._3,
        opts._4,
        opts._5,
        opts._6,
        opts._7,
        opts._8,
        opts._9,
        opts._10,
        opts._11,
        opts._12,
        opts._13,
        opts._14,
        opts._15,
        opts._16,
        opts._17,
        opts._18,
        opts._19,
        opts._20,
      )

    case opts: StepOpts @unchecked =>
      executeStep(opts._1._1, opts._1._2, opts._1._3, opts._1._4, opts._1._5, opts._1._6, opts._2)

    case opts: ListRunsOpts @unchecked =>
      executeListRuns(opts._1, opts._2)

    case _ =>
      ZIO.fail(new IllegalArgumentException("Unknown command"))
  }

  // ============================================================================
  // Execution Functions
  // ============================================================================

  /** Execute the full migration pipeline */
  private def executeMigrate(
    sourceDir: Path,
    outputDir: Path,
    stateDir: Option[Path],
    configFile: Option[Path],
    geminiModel: Option[String],
    geminiTimeout: Option[BigInt],
    geminiRetries: Option[BigInt],
    aiProvider: Option[String],
    aiModel: Option[String],
    aiBaseUrl: Option[String],
    aiApiKey: Option[String],
    aiTemperature: Option[String],
    aiMaxTokens: Option[BigInt],
    discoveryMaxDepth: Option[BigInt],
    discoveryExclude: Option[String],
    parallelism: Option[BigInt],
    batchSize: Option[BigInt],
    resume: Option[String],
    dryRun: Option[Boolean],
    verbose: Option[Boolean],
  ): ZIO[Any, Throwable, Unit] =
    for
      // Load base configuration from file or defaults
      baseConfig <- configFile match
                      case Some(path) =>
                        ConfigLoader.loadFromFile(path).orElse(ZIO.succeed(MigrationConfig(sourceDir, outputDir)))
                      case None       =>
                        ConfigLoader.loadWithEnvOverrides.orElse(ZIO.succeed(MigrationConfig(sourceDir, outputDir)))

      configSectionProvider <- (configFile match
                                 case Some(path) => ConfigLoader.loadAIProviderFromFile(path)
                                 case None       => ConfigLoader.loadAIProviderFromDefaultConfig
                               )
                                 .mapError(msg => new IllegalArgumentException(msg))

      withConfigProvider = configSectionProvider match
                             case Some(p) => baseConfig.copy(aiProvider = Some(p))
                             case None    => baseConfig

      envResolvedConfig <- ConfigLoader
                             .applyAIEnvironmentOverrides(withConfigProvider)
                             .mapError(msg => new IllegalArgumentException(msg))

      parsedAiProvider <- parseAIProviderOption(aiProvider).mapError(msg => new IllegalArgumentException(msg))
      parsedTemp       <- parseTemperatureOption(aiTemperature).mapError(msg => new IllegalArgumentException(msg))

      _ <- aiApiKey match
             case Some(_) =>
               printLineError(
                 "Warning: passing --ai-api-key on CLI may leak secrets in shell history. Prefer MIGRATION_AI_API_KEY."
               )
             case None    => ZIO.unit

      resolvedProvider = envResolvedConfig.resolvedProviderConfig
      providerConfig   = AIProviderConfig.withDefaults(
                           resolvedProvider.copy(
                             provider = parsedAiProvider.getOrElse(resolvedProvider.provider),
                             model = aiModel.orElse(geminiModel).getOrElse(resolvedProvider.model),
                             baseUrl = aiBaseUrl.orElse(resolvedProvider.baseUrl),
                             apiKey = aiApiKey.orElse(resolvedProvider.apiKey),
                             timeout = geminiTimeout
                               .map(t => zio.Duration.fromSeconds(t.toLong))
                               .getOrElse(resolvedProvider.timeout),
                             maxRetries = geminiRetries.map(_.toInt).getOrElse(resolvedProvider.maxRetries),
                             temperature = parsedTemp.orElse(resolvedProvider.temperature),
                             maxTokens = aiMaxTokens.map(_.toInt).orElse(resolvedProvider.maxTokens),
                           )
                         )

      // Override with CLI arguments
      migrationConfig = envResolvedConfig.copy(
                          sourceDir = sourceDir,
                          outputDir = outputDir,
                          stateDir = stateDir.getOrElse(envResolvedConfig.stateDir),
                          aiProvider = Some(providerConfig),
                          discoveryMaxDepth =
                            discoveryMaxDepth.map(_.toInt).getOrElse(envResolvedConfig.discoveryMaxDepth),
                          discoveryExcludePatterns =
                            parseExcludePatterns(
                              discoveryExclude
                            ).getOrElse(envResolvedConfig.discoveryExcludePatterns),
                          parallelism = parallelism.map(_.toInt).getOrElse(envResolvedConfig.parallelism),
                          batchSize = batchSize.map(_.toInt).getOrElse(envResolvedConfig.batchSize),
                          resumeFromCheckpoint = resume,
                          dryRun = dryRun.getOrElse(envResolvedConfig.dryRun),
                          verbose = verbose.getOrElse(envResolvedConfig.verbose),
                        )

      // Validate configuration
      validatedConfig <- ConfigLoader.validate(migrationConfig).mapError(msg => new IllegalArgumentException(msg))

      // Run migration with validated config
      _ <- runMigrationWithConfig(validatedConfig)
    yield ()

  /** Execute a specific migration step */
  private def executeStep(
    sourceDir: Path,
    outputDir: Path,
    configFile: Option[Path],
    verbose: Option[Boolean],
    discoveryMaxDepth: Option[BigInt],
    discoveryExclude: Option[String],
    stepName: String,
  ): ZIO[Any, Throwable, Unit] =
    for
      step <- parseStepName(stepName)

      baseConfig <- configFile match
                      case Some(path) =>
                        ConfigLoader.loadFromFile(path).orElse(ZIO.succeed(MigrationConfig(sourceDir, outputDir)))
                      case None       =>
                        ConfigLoader.loadWithEnvOverrides.orElse(ZIO.succeed(MigrationConfig(sourceDir, outputDir)))

      configSectionProvider <- (configFile match
                                 case Some(path) => ConfigLoader.loadAIProviderFromFile(path)
                                 case None       => ConfigLoader.loadAIProviderFromDefaultConfig
                               )
                                 .mapError(msg => new IllegalArgumentException(msg))

      withConfigProvider = configSectionProvider match
                             case Some(p) => baseConfig.copy(aiProvider = Some(p))
                             case None    => baseConfig

      envResolvedConfig <- ConfigLoader
                             .applyAIEnvironmentOverrides(withConfigProvider)
                             .mapError(msg => new IllegalArgumentException(msg))

      migrationConfig = envResolvedConfig.copy(
                          sourceDir = sourceDir,
                          outputDir = outputDir,
                          verbose = verbose.getOrElse(envResolvedConfig.verbose),
                          discoveryMaxDepth =
                            discoveryMaxDepth.map(_.toInt).getOrElse(envResolvedConfig.discoveryMaxDepth),
                          discoveryExcludePatterns =
                            parseExcludePatterns(discoveryExclude).getOrElse(envResolvedConfig.discoveryExcludePatterns),
                        )

      validatedConfig <- ConfigLoader.validate(migrationConfig).mapError(msg => new IllegalArgumentException(msg))

      _ <- runStepWithConfig(step, validatedConfig)
    yield ()

  private def parseExcludePatterns(value: Option[String]): Option[List[String]] =
    value.map(_.split(",").map(_.trim).filter(_.nonEmpty).toList)

  private def parseAIProviderOption(value: Option[String]): IO[String, Option[AIProvider]] =
    value match
      case None      => ZIO.succeed(None)
      case Some(raw) =>
        raw.trim.toLowerCase match
          case "gemini-cli" => ZIO.succeed(Some(AIProvider.GeminiCli))
          case "gemini-api" => ZIO.succeed(Some(AIProvider.GeminiApi))
          case "openai"     => ZIO.succeed(Some(AIProvider.OpenAi))
          case "anthropic"  => ZIO.succeed(Some(AIProvider.Anthropic))
          case other        =>
            ZIO.fail(s"Invalid --ai-provider '$other'. Expected: gemini-cli|gemini-api|openai|anthropic")

  private def parseTemperatureOption(value: Option[String]): IO[String, Option[Double]] =
    value match
      case None      => ZIO.succeed(None)
      case Some(raw) =>
        ZIO
          .attempt(raw.toDouble)
          .mapError(_ => s"Invalid --ai-temperature '$raw'. Expected decimal between 0.0 and 2.0")
          .map(Some(_))

  private def executeListRuns(stateDir: Option[Path], configFile: Option[Path]): ZIO[Any, Throwable, Unit] =
    for
      baseConfig      <- configFile match
                           case Some(path) =>
                             ConfigLoader.loadFromFile(path).orElse(
                               ZIO.succeed(MigrationConfig(Paths.get("cobol-source"), Paths.get("java-output")))
                             )
                           case None       =>
                             ConfigLoader.loadWithEnvOverrides.orElse(
                               ZIO.succeed(MigrationConfig(Paths.get("cobol-source"), Paths.get("java-output")))
                             )
      resolvedStateDir = stateDir.getOrElse(baseConfig.stateDir)
      runs            <- StateService
                           .listRuns()
                           .provide(
                             StateService.live(resolvedStateDir),
                             FileService.live,
                           )
                           .mapError(e => new IllegalStateException(e.message))
      _               <-
        if runs.isEmpty then
          printLine(s"No migration runs found in: $resolvedStateDir")
        else
          printLine(s"Migration runs in: $resolvedStateDir") *>
            ZIO.foreachDiscard(runs) { run =>
              for
                checkpoints    <- StateService
                                    .listCheckpoints(run.runId)
                                    .provide(
                                      StateService.live(resolvedStateDir),
                                      FileService.live,
                                    )
                                    .mapError(e => new IllegalStateException(e.message))
                checkpointNames = checkpoints.map(_.step.toString).mkString(", ")
                _              <-
                  printLine(
                    s"- ${run.runId} | step=${run.currentStep} | completed=${run.completedSteps.size} | errors=${run.errorCount} | checkpoints=${
                        if checkpointNames.nonEmpty then checkpointNames else "none"
                      }"
                  )
              yield ()
            }
    yield ()

  private val banner =
    """
      |╔═══════════════════════════════════════════════════════════════════╗
      |║   Legacy Modernization Agents: COBOL to Spring Boot Migration    ║
      |║   Scala 3 + ZIO 2.x Effect-Oriented Programming                  ║
      |║   Version 1.0.0                                                   ║
      |╚═══════════════════════════════════════════════════════════════════╝
      |""".stripMargin

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      args <- ZIO.serviceWith[ZIOAppArgs](_.getArgs)
      _    <- cliApp.run(args.toList).catchAll(err => printLine(s"Error: $err")).ignore
    yield ()

  /** Run migration with validated configuration */
  private def runMigrationWithConfig(config: MigrationConfig): ZIO[Any, Throwable, Unit] =
    migrationProgram(config).provide(
      // Layer 3: Core Services & Config
      FileService.live,
      ZLayer.succeed(config),
      ZLayer.succeed(config.resolvedProviderConfig),
      ZLayer.succeed(RateLimiterConfig.fromMigrationConfig(config)),

      // Layer 2: Service implementations (depend on Layer 3 & Config)
      RateLimiter.live,
      ResponseParser.live,
      Client.default,
      HttpAIClient.live,
      AIService.fromConfig.mapError(e => new RuntimeException(e.message)),
      StateService.live(config.stateDir),

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

  /** Run specific step with validated configuration */
  private def runStepWithConfig(step: MigrationStep, config: MigrationConfig): ZIO[Any, Throwable, Unit] =
    stepProgram(step, config)

  private def migrationProgram(config: MigrationConfig): ZIO[MigrationOrchestrator, Throwable, Unit] =
    for
      _ <- printBanner
      _ <- Logger.info("Starting Legacy Modernization Agent System...")
      _ <- Logger.info(s"Configuration: ${config}")
      _ <- Logger.info("Running full migration pipeline...")

      _ <- Logger.info(s"Source directory: ${config.sourceDir}")
      _ <- Logger.info(s"Output directory: ${config.outputDir}")
      _ <- Logger.info(s"Parallelism: ${config.parallelism}")

      result <-
        if config.dryRun then
          Logger.info("DRY RUN MODE - No files will be written") *>
            Clock.instant.map { now =>
              MigrationResult(
                runId = s"dry-run-${now.toEpochMilli}",
                startedAt = now,
                completedAt = now,
                config = config,
                inventory = FileInventory(
                  discoveredAt = now,
                  sourceDirectory = config.sourceDir,
                  files = List.empty,
                  summary = InventorySummary(
                    totalFiles = 0,
                    programFiles = 0,
                    copybooks = 0,
                    jclFiles = 0,
                    totalLines = 0L,
                    totalBytes = 0L,
                  ),
                ),
                analyses = List.empty,
                dependencyGraph = DependencyGraph.empty,
                projects = List.empty,
                validationReport = ValidationReport.empty,
                validationReports = List.empty,
                documentation = MigrationDocumentation.empty,
                errors = List.empty,
                status = MigrationStatus.CompletedWithWarnings,
              )
            }
        else MigrationOrchestrator.runFullMigration(config.sourceDir, config.outputDir)

      _ <- Logger.info(s"Migration completed: ${result.projects.length} projects generated")
      _ <- printMigrationSummary(result)
    yield ()

  private def stepProgram(step: MigrationStep, config: MigrationConfig): ZIO[Any, Throwable, Unit] =
    for
      _ <- printBanner
      _ <- Logger.info(s"Running step: ${step}")
      _ <- Logger.info(s"Configuration: ${config}")
      // TODO: Implement step execution via MigrationOrchestrator
      _ <- Logger.info(s"Step ${step} completed")
    yield ()

  private def printBanner: ZIO[Any, Nothing, Unit] =
    printLine(banner).orDie

  private def parseStepName(stepName: String): ZIO[Any, Throwable, MigrationStep] =
    stepName.toLowerCase match
      case "discovery"      => ZIO.succeed(MigrationStep.Discovery)
      case "analysis"       => ZIO.succeed(MigrationStep.Analysis)
      case "mapping"        => ZIO.succeed(MigrationStep.Mapping)
      case "transformation" => ZIO.succeed(MigrationStep.Transformation)
      case "validation"     => ZIO.succeed(MigrationStep.Validation)
      case "documentation"  => ZIO.succeed(MigrationStep.Documentation)
      case _                => ZIO.fail(new IllegalArgumentException(s"Unknown step: $stepName"))

  private def printMigrationSummary(result: MigrationResult): ZIO[Any, Nothing, Unit] =
    val summary =
      s"""
         |╔═══════════════════════════════════════════════════════════════════╗
         |║                      Migration Summary                           ║
         |╠═══════════════════════════════════════════════════════════════════╣
         |║  Status: ${result.status}
         |║  Projects Generated: ${result.projects.length}
         |║  Validation Errors: ${result.errors.length}
         |║
         |║  Output Directory: java-output/
         |║  Reports Directory: reports/
         |║  Documentation: Available in reports/
         |╚═══════════════════════════════════════════════════════════════════╝
         |""".stripMargin

    printLine(summary).orDie
