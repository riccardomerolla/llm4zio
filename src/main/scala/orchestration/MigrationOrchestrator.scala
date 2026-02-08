package orchestration

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import zio.*
import zio.json.*

import agents.*
import core.*
import models.*

/** MigrationOrchestrator - Main workflow orchestrator using ZIO effects.
  */
trait MigrationOrchestrator:
  def runFullMigration(sourcePath: Path, outputPath: Path): ZIO[Any, OrchestratorError, MigrationResult]
  def runFullMigrationWithProgress(
    sourcePath: Path,
    outputPath: Path,
    onProgress: ProgressUpdate => UIO[Unit],
  ): ZIO[Any, OrchestratorError, MigrationResult]
  def runStep(step: MigrationStep): ZIO[Any, OrchestratorError, StepResult]

object MigrationOrchestrator:

  def runFullMigration(
    sourcePath: Path,
    outputPath: Path,
  ): ZIO[MigrationOrchestrator, OrchestratorError, MigrationResult] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.runFullMigration(sourcePath, outputPath))

  def runFullMigrationWithProgress(
    sourcePath: Path,
    outputPath: Path,
    onProgress: ProgressUpdate => UIO[Unit],
  ): ZIO[MigrationOrchestrator, OrchestratorError, MigrationResult] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.runFullMigrationWithProgress(sourcePath, outputPath, onProgress))

  def runStep(step: MigrationStep): ZIO[MigrationOrchestrator, OrchestratorError, StepResult] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.runStep(step))

  val live: ZLayer[
    CobolDiscoveryAgent &
      CobolAnalyzerAgent &
      DependencyMapperAgent &
      JavaTransformerAgent &
      ValidationAgent &
      DocumentationAgent &
      StateService &
      AIService &
      MigrationConfig,
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
      _aiService: AIService,
      config: MigrationConfig,
    ) =>
      new MigrationOrchestrator {

        override def runFullMigration(
          sourcePath: Path,
          outputPath: Path,
        ): ZIO[Any, OrchestratorError, MigrationResult] =
          runPipeline(sourcePath, outputPath, None, _ => ZIO.unit)

        override def runFullMigrationWithProgress(
          sourcePath: Path,
          outputPath: Path,
          onProgress: ProgressUpdate => UIO[Unit],
        ): ZIO[Any, OrchestratorError, MigrationResult] =
          runPipeline(sourcePath, outputPath, None, onProgress)

        override def runStep(step: MigrationStep): ZIO[Any, OrchestratorError, StepResult] =
          runPipeline(config.sourceDir, config.outputDir, Some(step), _ => ZIO.unit)
            .map { result =>
              val success =
                result.status == MigrationStatus.Completed || result.status == MigrationStatus.CompletedWithWarnings
              StepResult(
                step,
                success = success,
                if success then None else Some(result.errors.map(_.message).mkString("; ")),
              )
            }

        private def runPipeline(
          sourcePath: Path,
          outputPath: Path,
          forcedStart: Option[MigrationStep],
          onProgress: ProgressUpdate => UIO[Unit],
        ): ZIO[Any, OrchestratorError, MigrationResult] =
          for
            now         <- Clock.instant
            generatedId <- Clock.currentTime(TimeUnit.MILLISECONDS).map(ts => s"run-$ts")
            resumeState <- loadResumeState(config.resumeFromCheckpoint)
            runId        = resumeState.map(_.runId).getOrElse(generatedId)
            startedAt    = resumeState.map(_.startedAt).getOrElse(now)
            startStep    = forcedStart.orElse(derivedStartStep(resumeState)).getOrElse(MigrationStep.Discovery)
            stateRef    <- Ref.make(
                             resumeState.getOrElse(
                               MigrationState(
                                 runId = runId,
                                 startedAt = startedAt,
                                 currentStep = startStep,
                                 completedSteps = Set.empty,
                                 artifacts = Map.empty,
                                 errors = List.empty,
                                 config = config.copy(sourceDir = sourcePath, outputDir = outputPath),
                                 fileInventory = None,
                                 analyses = List.empty,
                                 dependencyGraph = None,
                                 projects = List.empty,
                                 validationReports = List.empty,
                                 lastCheckpoint = now,
                               )
                             )
                           )
            errorsRef   <- Ref.make(List.empty[MigrationError])
            _           <- Logger.info(s"Starting migration run: $runId (from $startStep)")
            _           <- stateRef.get.flatMap(stateService.saveState).mapError(OrchestratorError.StateFailed.apply)

            // 1) Discovery
            discovered <- runPhase(
                            name = "Discovery",
                            step = MigrationStep.Discovery,
                            shouldRun = shouldRunStep(MigrationStep.Discovery, startStep),
                            progress = 10,
                            onProgress = onProgress,
                            stateRef = stateRef,
                            errorsRef = errorsRef,
                          ) {
                            discoveryAgent
                              .discover(sourcePath)
                              .mapError(OrchestratorError.DiscoveryFailed.apply)
                          }

            inventory = discovered
                          .orElse(resumeState.flatMap(_.fileInventory))
                          .getOrElse(
                            FileInventory(
                              discoveredAt = now,
                              sourceDirectory = sourcePath,
                              files = List.empty,
                              summary = InventorySummary(
                                totalFiles = 0,
                                programFiles = 0,
                                copybooks = 0,
                                jclFiles = 0,
                                totalLines = 0L,
                                totalBytes = 0L,
                              ),
                            )
                          )
            _        <- updateStateArtifacts(
                          stateRef,
                          fileInventory = Some(inventory),
                        )

            // 2) Analysis
            analyzed <- runPhase(
                          name = "Analysis",
                          step = MigrationStep.Analysis,
                          shouldRun = shouldRunStep(MigrationStep.Analysis, startStep),
                          progress = 25,
                          onProgress = onProgress,
                          stateRef = stateRef,
                          errorsRef = errorsRef,
                        ) {
                          ZIO.foreach(inventory.files.filter(_.fileType == FileType.Program)) { file =>
                            analyzerAgent
                              .analyze(file)
                              .mapError(OrchestratorError.AnalysisFailed(file.name, _))
                          }
                        }

            analyses = analyzed.orElse(resumeState.map(_.analyses)).getOrElse(List.empty)
            _       <- updateStateArtifacts(
                         stateRef,
                         analyses = Some(analyses),
                       )

            // 3) Mapping
            mapped <- runPhase(
                        name = "Dependency Mapping",
                        step = MigrationStep.Mapping,
                        shouldRun = shouldRunStep(MigrationStep.Mapping, startStep),
                        progress = 40,
                        onProgress = onProgress,
                        stateRef = stateRef,
                        errorsRef = errorsRef,
                      ) {
                        mapperAgent.mapDependencies(analyses).mapError(OrchestratorError.MappingFailed.apply)
                      }

            dependencyGraph = mapped.orElse(resumeState.flatMap(_.dependencyGraph)).getOrElse(DependencyGraph.empty)
            _              <- updateStateArtifacts(
                                stateRef,
                                dependencyGraph = Some(dependencyGraph),
                              )

            // 4) Transformation (skip in dry-run)
            transformed <- runPhase(
                             name = "Transformation",
                             step = MigrationStep.Transformation,
                             shouldRun = shouldRunStep(MigrationStep.Transformation, startStep) && !config.dryRun,
                             progress = 60,
                             onProgress = onProgress,
                             stateRef = stateRef,
                             errorsRef = errorsRef,
                           ) {
                             ZIO.foreach(analyses) { analysis =>
                               transformerAgent.transform(
                                 analysis,
                                 dependencyGraph,
                               ).mapError(OrchestratorError.TransformationFailed(analysis.file.name, _))
                             }
                           }

            projects = transformed.orElse(resumeState.map(_.projects)).getOrElse(List.empty)
            _       <- updateStateArtifacts(
                         stateRef,
                         projects = Some(projects),
                       )

            // 5) Validation (skip in dry-run)
            validated <- runPhase(
                           name = "Validation",
                           step = MigrationStep.Validation,
                           shouldRun = shouldRunStep(MigrationStep.Validation, startStep) && !config.dryRun,
                           progress = 80,
                           onProgress = onProgress,
                           stateRef = stateRef,
                           errorsRef = errorsRef,
                         ) {
                           for
                             _       <-
                               ZIO
                                 .when(projects.isEmpty || analyses.isEmpty) {
                                   Logger.warn(
                                     s"Validation phase has no inputs (projects=${projects.size}, analyses=${analyses.size}); nothing to validate."
                                   )
                                 }
                                 .unit
                             reports <- ZIO.foreach(projects.zip(analyses)) { (project, analysis) =>
                                          validationAgent
                                            .validate(project, analysis)
                                            .mapError(OrchestratorError.ValidationFailed(analysis.file.name, _))
                                        }
                           yield reports
                         }

            validationReports = validated.orElse(resumeState.map(_.validationReports)).getOrElse(List.empty)
            validationReport  = aggregateValidation(validationReports)
            _                <- updateStateArtifacts(
                                  stateRef,
                                  validationReports = Some(validationReports),
                                )

            // 6) Documentation (skip in dry-run)
            documented <- runPhase(
                            name = "Documentation",
                            step = MigrationStep.Documentation,
                            shouldRun = shouldRunStep(MigrationStep.Documentation, startStep) && !config.dryRun,
                            progress = 95,
                            onProgress = onProgress,
                            stateRef = stateRef,
                            errorsRef = errorsRef,
                          ) {
                            documentationAgent.generateDocs(
                              MigrationResult(
                                runId = runId,
                                startedAt = startedAt,
                                completedAt = startedAt,
                                config = config.copy(sourceDir = sourcePath, outputDir = outputPath),
                                inventory = inventory,
                                analyses = analyses,
                                dependencyGraph = dependencyGraph,
                                projects = projects,
                                validationReport = validationReport,
                                validationReports = validationReports,
                                documentation = MigrationDocumentation.empty,
                                errors = List.empty,
                                status = MigrationStatus.CompletedWithWarnings,
                              )
                            ).mapError(OrchestratorError.DocumentationFailed.apply)
                          }

            documentation = documented.getOrElse(MigrationDocumentation.empty)
            completedAt  <- Clock.instant
            errors       <- errorsRef.get
            status        = determineStatus(errors, projects, config.dryRun, validationReport)
            result        = MigrationResult(
                              runId = runId,
                              startedAt = startedAt,
                              completedAt = completedAt,
                              config = config.copy(sourceDir = sourcePath, outputDir = outputPath),
                              inventory = inventory,
                              analyses = analyses,
                              dependencyGraph = dependencyGraph,
                              projects = projects,
                              validationReport = validationReport,
                              validationReports = validationReports,
                              documentation = documentation,
                              errors = errors,
                              status = status,
                            )
            _            <- finalizeState(stateRef, errors, completedAt)
            _            <- onProgress(ProgressUpdate(MigrationStep.Documentation, s"Migration completed: $status", 100))
            _            <- Logger.info(s"Migration pipeline completed: $status")
          yield result

        private def runPhase[A](
          name: String,
          step: MigrationStep,
          shouldRun: Boolean,
          progress: Int,
          onProgress: ProgressUpdate => UIO[Unit],
          stateRef: Ref[MigrationState],
          errorsRef: Ref[List[MigrationError]],
        )(
          effect: ZIO[Any, OrchestratorError, A]
        ): ZIO[Any, OrchestratorError, Option[A]] =
          if !shouldRun then ZIO.succeed(None)
          else
            for
              _      <- onProgress(ProgressUpdate(step, s"Starting phase: $name", progress))
              _      <- Logger.info(s"Starting phase: $name")
              before <- stateRef.get
              now    <- Clock.instant
              _      <- stateRef.set(before.copy(currentStep = step, lastCheckpoint = now))
              _      <- stateRef.get.flatMap(stateService.saveState).mapError(OrchestratorError.StateFailed.apply)
              out    <- effect.either
              result <- out match
                          case Right(value) =>
                            for
                              checkpointAt <- Clock.instant
                              current      <- stateRef.get
                              _            <- stateRef.set(
                                                current.copy(
                                                  completedSteps = current.completedSteps + step,
                                                  lastCheckpoint = checkpointAt,
                                                )
                                              )
                              _            <- stateRef.get
                                                .flatMap(stateService.saveState)
                                                .mapError(OrchestratorError.StateFailed.apply)
                              _            <- stateService
                                                .createCheckpoint(current.runId, step)
                                                .mapError(OrchestratorError.StateFailed.apply)
                              _            <- onProgress(ProgressUpdate(step, s"Completed phase: $name", progress + 10))
                              _            <- Logger.info(s"Completed phase: $name")
                            yield Some(value)
                          case Left(err)    =>
                            for
                              ts <- Clock.instant
                              _  <- errorsRef.update(_ :+ MigrationError(step, err.message, ts))
                              es <- errorsRef.get
                              st <- stateRef.get
                              _  <- stateRef.set(st.copy(errors = es, lastCheckpoint = ts))
                              _  <- stateRef.get
                                      .flatMap(stateService.saveState)
                                      .mapError(OrchestratorError.StateFailed.apply)
                              _  <- onProgress(ProgressUpdate(
                                      step,
                                      s"Phase failed: $name (${err.message})",
                                      progress + 5,
                                    ))
                              _  <- Logger.error(s"Phase failed: $name (${err.message})")
                            yield None
            yield result

        private def shouldRunStep(step: MigrationStep, start: MigrationStep): Boolean =
          stepOrder(step) >= stepOrder(start)

        private def stepOrder(step: MigrationStep): Int = step match
          case MigrationStep.Discovery      => 1
          case MigrationStep.Analysis       => 2
          case MigrationStep.Mapping        => 3
          case MigrationStep.Transformation => 4
          case MigrationStep.Validation     => 5
          case MigrationStep.Documentation  => 6

        private def aggregateValidation(reports: List[ValidationReport]): ValidationReport =
          if reports.isEmpty then ValidationReport.empty
          else
            val projectNames = reports.map(_.projectName).filter(_.nonEmpty)
            val avgVar       = reports.map(_.coverageMetrics.variablesCovered).sum / reports.size.toDouble
            val avgProc      = reports.map(_.coverageMetrics.proceduresCovered).sum / reports.size.toDouble
            val avgFile      = reports.map(_.coverageMetrics.fileSectionCovered).sum / reports.size.toDouble
            val mergedIssues = reports.flatMap(_.issues).distinct
            val semanticOk   = reports.forall(_.semanticValidation.businessLogicPreserved)
            val compileOk    = reports.forall(_.compileResult.success)
            val status       =
              if reports.exists(_.overallStatus == ValidationStatus.Failed) then ValidationStatus.Failed
              else if reports.exists(_.overallStatus == ValidationStatus.PassedWithWarnings) then
                ValidationStatus.PassedWithWarnings
              else ValidationStatus.Passed
            ValidationReport(
              projectName = projectNames.mkString(","),
              validatedAt = reports.map(_.validatedAt).maxBy(_.toEpochMilli),
              compileResult = CompileResult(
                success = compileOk,
                exitCode = if compileOk then 0 else 1,
                output = if compileOk then "Aggregated compile success" else "One or more projects failed compile",
              ),
              coverageMetrics = CoverageMetrics(
                variablesCovered = avgVar,
                proceduresCovered = avgProc,
                fileSectionCovered = avgFile,
                unmappedItems = reports.flatMap(_.coverageMetrics.unmappedItems).distinct,
              ),
              issues = mergedIssues,
              semanticValidation = SemanticValidation(
                businessLogicPreserved = semanticOk,
                confidence = reports.map(_.semanticValidation.confidence).sum / reports.size.toDouble,
                summary = "Aggregated validation report",
                issues = reports.flatMap(_.semanticValidation.issues).distinct,
              ),
              overallStatus = status,
            )

        private def determineStatus(
          errors: List[MigrationError],
          projects: List[SpringBootProject],
          dryRun: Boolean,
          validationReport: ValidationReport,
        ): MigrationStatus =
          if errors.isEmpty then
            if dryRun || !validationReport.semanticValidation.businessLogicPreserved || validationReport.issues.nonEmpty
            then
              MigrationStatus.CompletedWithWarnings
            else MigrationStatus.Completed
          else if projects.nonEmpty then MigrationStatus.PartialFailure
          else MigrationStatus.Failed

        private def loadResumeState(
          resumeRunId: Option[String]
        ): ZIO[Any, OrchestratorError, Option[MigrationState]] =
          resumeRunId match
            case Some(runId) =>
              for
                _     <- stateService
                           .validateCheckpointIntegrity(runId)
                           .mapError(OrchestratorError.StateFailed.apply)
                state <- stateService.loadState(runId).mapError(OrchestratorError.StateFailed.apply)
              yield state
            case None        => ZIO.none

        private def derivedStartStep(state: Option[MigrationState]): Option[MigrationStep] =
          state.flatMap { st =>
            val done = st.completedSteps
            if !done.contains(MigrationStep.Discovery) then Some(MigrationStep.Discovery)
            else if !done.contains(MigrationStep.Analysis) then Some(MigrationStep.Analysis)
            else if !done.contains(MigrationStep.Mapping) then Some(MigrationStep.Mapping)
            else if !done.contains(MigrationStep.Transformation) then Some(MigrationStep.Transformation)
            else if !done.contains(MigrationStep.Validation) then Some(MigrationStep.Validation)
            else if !done.contains(MigrationStep.Documentation) then Some(MigrationStep.Documentation)
            else None
          }

        private def updateStateArtifacts(
          stateRef: Ref[MigrationState],
          fileInventory: Option[FileInventory] = None,
          analyses: Option[List[CobolAnalysis]] = None,
          dependencyGraph: Option[DependencyGraph] = None,
          projects: Option[List[SpringBootProject]] = None,
          validationReports: Option[List[ValidationReport]] = None,
        ): ZIO[Any, OrchestratorError, Unit] =
          for
            current <- stateRef.get
            updated  = current.copy(
                         fileInventory = fileInventory.orElse(current.fileInventory),
                         analyses = analyses.getOrElse(current.analyses),
                         dependencyGraph = dependencyGraph.orElse(current.dependencyGraph),
                         projects = projects.getOrElse(current.projects),
                         validationReports = validationReports.getOrElse(current.validationReports),
                       )
            _       <- stateRef.set(updated)
            _       <- stateService.saveState(updated).mapError(OrchestratorError.StateFailed.apply)
          yield ()

        private def finalizeState(
          stateRef: Ref[MigrationState],
          errors: List[MigrationError],
          completedAt: java.time.Instant,
        ): ZIO[Any, OrchestratorError, Unit] =
          for
            st <- stateRef.get
            _  <- stateRef.set(st.copy(errors = errors, lastCheckpoint = completedAt))
            _  <- stateRef.get.flatMap(stateService.saveState).mapError(OrchestratorError.StateFailed.apply)
          yield ()
      }
  }

enum MigrationStatus derives JsonCodec:
  case Completed, CompletedWithWarnings, PartialFailure, Failed

case class ProgressUpdate(
  step: MigrationStep,
  message: String,
  percent: Int,
) derives JsonCodec

case class MigrationResult(
  runId: String,
  startedAt: java.time.Instant,
  completedAt: java.time.Instant,
  config: MigrationConfig,
  inventory: FileInventory,
  analyses: List[CobolAnalysis],
  dependencyGraph: DependencyGraph,
  projects: List[SpringBootProject],
  validationReport: ValidationReport,
  validationReports: List[ValidationReport],
  documentation: MigrationDocumentation,
  errors: List[MigrationError],
  status: MigrationStatus,
) derives JsonCodec:
  def success: Boolean = status == MigrationStatus.Completed || status == MigrationStatus.CompletedWithWarnings

case class StepResult(
  step: MigrationStep,
  success: Boolean,
  error: Option[String],
)
