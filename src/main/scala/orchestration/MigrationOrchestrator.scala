package orchestration

import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

import zio.*
import zio.json.*

import agents.*
import core.*
import db.*
import models.*

/** MigrationOrchestrator - Main workflow orchestrator using ZIO effects.
  */
trait MigrationOrchestrator:
  def runFullMigration(sourcePath: Path, outputPath: Path): ZIO[Any, OrchestratorError, MigrationResult]
  def runFullMigrationWithProgress(
    sourcePath: Path,
    outputPath: Path,
    onProgress: PipelineProgressUpdate => UIO[Unit],
  ): ZIO[Any, OrchestratorError, MigrationResult]
  def runStep(step: MigrationStep): ZIO[Any, OrchestratorError, StepResult]
  def startMigration(config: MigrationConfig): IO[OrchestratorError, Long]
  def cancelMigration(runId: Long): IO[OrchestratorError, Unit]
  def getRunStatus(runId: Long): IO[PersistenceError, Option[MigrationRunRow]]
  def listRuns(page: Int, pageSize: Int): IO[PersistenceError, List[MigrationRunRow]]
  def subscribeToProgress(runId: Long): UIO[Dequeue[ProgressUpdate]]

object MigrationOrchestrator:

  def runFullMigration(
    sourcePath: Path,
    outputPath: Path,
  ): ZIO[MigrationOrchestrator, OrchestratorError, MigrationResult] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.runFullMigration(sourcePath, outputPath))

  def runFullMigrationWithProgress(
    sourcePath: Path,
    outputPath: Path,
    onProgress: PipelineProgressUpdate => UIO[Unit],
  ): ZIO[MigrationOrchestrator, OrchestratorError, MigrationResult] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.runFullMigrationWithProgress(sourcePath, outputPath, onProgress))

  def runStep(step: MigrationStep): ZIO[MigrationOrchestrator, OrchestratorError, StepResult] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.runStep(step))

  def startMigration(config: MigrationConfig): ZIO[MigrationOrchestrator, OrchestratorError, Long] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.startMigration(config))

  def cancelMigration(runId: Long): ZIO[MigrationOrchestrator, OrchestratorError, Unit] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.cancelMigration(runId))

  def getRunStatus(runId: Long): ZIO[MigrationOrchestrator, PersistenceError, Option[MigrationRunRow]] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.getRunStatus(runId))

  def listRuns(page: Int, pageSize: Int): ZIO[MigrationOrchestrator, PersistenceError, List[MigrationRunRow]] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.listRuns(page, pageSize))

  def subscribeToProgress(runId: Long): ZIO[MigrationOrchestrator, Nothing, Dequeue[ProgressUpdate]] =
    ZIO.serviceWithZIO[MigrationOrchestrator](_.subscribeToProgress(runId))

  val live: ZLayer[
    CobolDiscoveryAgent &
      CobolAnalyzerAgent &
      DependencyMapperAgent &
      JavaTransformerAgent &
      ValidationAgent &
      DocumentationAgent &
      StateService &
      AIService &
      MigrationConfig &
      MigrationRepository &
      ProgressTracker &
      ResultPersister,
    Nothing,
    MigrationOrchestrator,
  ] = ZLayer.scoped {
    for
      discoveryAgent     <- ZIO.service[CobolDiscoveryAgent]
      analyzerAgent      <- ZIO.service[CobolAnalyzerAgent]
      mapperAgent        <- ZIO.service[DependencyMapperAgent]
      transformerAgent   <- ZIO.service[JavaTransformerAgent]
      validationAgent    <- ZIO.service[ValidationAgent]
      documentationAgent <- ZIO.service[DocumentationAgent]
      stateService       <- ZIO.service[StateService]
      _                  <- ZIO.service[AIService]
      config             <- ZIO.service[MigrationConfig]
      repository         <- ZIO.service[MigrationRepository]
      tracker            <- ZIO.service[ProgressTracker]
      persister          <- ZIO.service[ResultPersister]
      fibers             <- Ref.make(Map.empty[Long, Fiber.Runtime[OrchestratorError, MigrationResult]])
      backgroundScope    <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
    yield new MigrationOrchestrator {

      override def runFullMigration(
        sourcePath: Path,
        outputPath: Path,
      ): ZIO[Any, OrchestratorError, MigrationResult] =
        runPipeline(sourcePath, outputPath, None, _ => ZIO.unit)

      override def runFullMigrationWithProgress(
        sourcePath: Path,
        outputPath: Path,
        onProgress: PipelineProgressUpdate => UIO[Unit],
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

      override def startMigration(requestConfig: MigrationConfig): IO[OrchestratorError, Long] =
        for
          now   <- Clock.instant
          runId <- repository
                     .createRun(
                       MigrationRunRow(
                         id = 0L,
                         sourceDir = requestConfig.sourceDir.toString,
                         outputDir = requestConfig.outputDir.toString,
                         status = RunStatus.Pending,
                         startedAt = now,
                         completedAt = None,
                         totalFiles = 0,
                         processedFiles = 0,
                         successfulConversions = 0,
                         failedConversions = 0,
                         currentPhase = Some("Pending"),
                         errorMessage = None,
                       )
                     )
                     .mapError(persistenceAsOrchestrator("createRun", "new"))
          fiber <- runTrackedMigration(runId, requestConfig)
                     .onExit(exit => handleRunExit(runId, exit))
                     .forkIn(backgroundScope)
          _     <- fibers.update(_ + (runId -> fiber))
        yield runId

      override def cancelMigration(runId: Long): IO[OrchestratorError, Unit] =
        for
          maybeFiber <- fibers.modify(current => (current.get(runId), current - runId))
          fiber      <- ZIO
                          .fromOption(maybeFiber)
                          .orElseFail(OrchestratorError.StateFailed(StateError.StateNotFound(runId.toString)))
          _          <- fiber.interrupt.unit
          now        <- Clock.instant
          _          <- updateRunStatus(
                          runId = runId,
                          status = RunStatus.Cancelled,
                          currentPhase = Some("Cancelled"),
                          errorMessage = None,
                          completedAt = Some(now),
                        )
        yield ()

      override def getRunStatus(runId: Long): IO[PersistenceError, Option[MigrationRunRow]] =
        repository.getRun(runId)

      override def listRuns(page: Int, pageSize: Int): IO[PersistenceError, List[MigrationRunRow]] =
        val safePage     = Math.max(page, 1)
        val safePageSize = Math.max(pageSize, 1)
        val offset       = (safePage - 1) * safePageSize
        repository.listRuns(offset = offset, limit = safePageSize)

      override def subscribeToProgress(runId: Long): UIO[Dequeue[ProgressUpdate]] =
        tracker.subscribe(runId)

      private def runTrackedMigration(
        runId: Long,
        runConfig: MigrationConfig,
      ): ZIO[Any, OrchestratorError, MigrationResult] =
        for
          _ <- updateRunStatus(runId, RunStatus.Running, Some(MigrationStep.Discovery.toString), None, None)

          inventory <-
            AgentTracker
              .trackPhase(runId, "discovery", 1, tracker)(
                discoveryAgent.discover(runConfig.sourceDir).mapError(OrchestratorError.DiscoveryFailed.apply)
              )
          _         <- persistIgnoringFailure(
                         s"saveDiscoveryResult(runId=$runId)",
                         persister.saveDiscoveryResult(runId, inventory),
                       )

          analysisFiles = inventory.files.filter(_.fileType == models.FileType.Program)
          _            <- updateRunStatus(
                            runId = runId,
                            status = RunStatus.Running,
                            currentPhase = Some(MigrationStep.Analysis.toString),
                            errorMessage = None,
                            completedAt = None,
                            totalFiles = Some(analysisFiles.length),
                          )
          analyses     <- AgentTracker.trackBatch(runId, "analysis", analysisFiles, tracker) { (file, _) =>
                            analyzerAgent.analyze(file).mapError(OrchestratorError.AnalysisFailed(file.name, _))
                          }
          _            <- ZIO.foreachDiscard(analyses)(analysis =>
                            persistIgnoringFailure(
                              s"saveAnalysisResult(runId=$runId,file=${analysis.file.name})",
                              persister.saveAnalysisResult(runId, analysis),
                            )
                          )
          _            <- updateRunStatus(
                            runId = runId,
                            status = RunStatus.Running,
                            currentPhase = Some(MigrationStep.Mapping.toString),
                            errorMessage = None,
                            completedAt = None,
                            processedFiles = Some(analyses.length),
                          )

          dependencyGraph <- AgentTracker
                               .trackPhase(runId, "mapping", 1, tracker)(
                                 mapperAgent.mapDependencies(analyses).mapError(OrchestratorError.MappingFailed.apply)
                               )
          _               <- persistIgnoringFailure(
                               s"saveDependencyResult(runId=$runId)",
                               persister.saveDependencyResult(runId, dependencyGraph),
                             )

          projects <- if runConfig.dryRun then ZIO.succeed(List.empty[SpringBootProject])
                      else
                        AgentTracker.trackBatch(runId, "transformation", analyses, tracker) { (analysis, _) =>
                          transformerAgent
                            .transform(analysis, dependencyGraph)
                            .mapError(OrchestratorError.TransformationFailed(analysis.file.name, _))
                        }
          _        <- ZIO.foreachDiscard(projects)(project =>
                        persistIgnoringFailure(
                          s"saveTransformResult(runId=$runId,project=${project.projectName})",
                          persister.saveTransformResult(runId, project),
                        )
                      )

          validationReports <-
            if runConfig.dryRun then ZIO.succeed(List.empty[ValidationReport])
            else
              AgentTracker.trackBatch(runId, "validation", projects.zip(analyses), tracker) {
                case ((project, analysis), _) =>
                  validationAgent
                    .validate(project, analysis)
                    .mapError(OrchestratorError.ValidationFailed(analysis.file.name, _))
              }
          validationReport   = aggregateValidation(validationReports)
          _                 <- if runConfig.dryRun then ZIO.unit
                               else
                                 persistIgnoringFailure(
                                   s"saveValidationResult(runId=$runId)",
                                   persister.saveValidationResult(runId, validationReport),
                                 )

          completedAt   <- Clock.instant
          status         = determineStatus(
                             errors = List.empty,
                             projects = projects,
                             dryRun = runConfig.dryRun,
                             validationReport = validationReport,
                           )
          baseResult     = MigrationResult(
                             runId = runId.toString,
                             startedAt = completedAt,
                             completedAt = completedAt,
                             config = runConfig,
                             inventory = inventory,
                             analyses = analyses,
                             dependencyGraph = dependencyGraph,
                             projects = projects,
                             validationReport = validationReport,
                             validationReports = validationReports,
                             documentation = MigrationDocumentation.empty,
                             errors = List.empty,
                             status = status,
                           )
          documentation <- if runConfig.dryRun then ZIO.succeed(MigrationDocumentation.empty)
                           else
                             AgentTracker
                               .trackPhase(runId, "documentation", 1, tracker)(
                                 documentationAgent
                                   .generateDocs(baseResult)
                                   .mapError(OrchestratorError.DocumentationFailed.apply)
                               )
          finalResult    = baseResult.copy(documentation = documentation)
          successCount   = projects.length
          failCount      = analysisFiles.length - successCount
          _             <- updateRunStatus(
                             runId = runId,
                             status = RunStatus.Completed,
                             currentPhase = Some(MigrationStep.Documentation.toString),
                             errorMessage = None,
                             completedAt = Some(completedAt),
                             processedFiles = Some(analysisFiles.length),
                             successfulConversions = Some(successCount),
                             failedConversions = Some(failCount),
                           )
        yield finalResult

      private def handleRunExit(
        runId: Long,
        exit: Exit[OrchestratorError, MigrationResult],
      ): UIO[Unit] =
        fibers.update(_ - runId) *>
          (exit match
            case Exit.Success(_)     =>
              ZIO.unit
            case Exit.Failure(cause) =>
              val isInterrupted = cause.isInterruptedOnly
              val status        = if isInterrupted then RunStatus.Cancelled else RunStatus.Failed
              val message       = cause.failureOption.map(_.message).orElse(Some(cause.prettyPrint))
              Clock.instant
                .flatMap(now =>
                  updateRunStatus(
                    runId = runId,
                    status = status,
                    currentPhase = Some(if isInterrupted then "Cancelled" else "Failed"),
                    errorMessage = message,
                    completedAt = Some(now),
                  )
                )
                .catchAll(err => Logger.warn(s"Failed to update run status for $runId: ${err.message}")))

      private def updateRunStatus(
        runId: Long,
        status: RunStatus,
        currentPhase: Option[String],
        errorMessage: Option[String],
        completedAt: Option[Instant],
        totalFiles: Option[Int] = None,
        processedFiles: Option[Int] = None,
        successfulConversions: Option[Int] = None,
        failedConversions: Option[Int] = None,
      ): IO[OrchestratorError, Unit] =
        for
          row <- repository.getRun(runId).mapError(persistenceAsOrchestrator("getRun", runId.toString))
          run <- ZIO
                   .fromOption(row)
                   .orElseFail(OrchestratorError.StateFailed(StateError.StateNotFound(runId.toString)))
          _   <- repository
                   .updateRun(
                     run.copy(
                       status = status,
                       currentPhase = currentPhase,
                       errorMessage = errorMessage.orElse(run.errorMessage),
                       completedAt = completedAt.orElse(run.completedAt),
                       totalFiles = totalFiles.getOrElse(run.totalFiles),
                       processedFiles = processedFiles.getOrElse(run.processedFiles),
                       successfulConversions = successfulConversions.getOrElse(run.successfulConversions),
                       failedConversions = failedConversions.getOrElse(run.failedConversions),
                     )
                   )
                   .mapError(persistenceAsOrchestrator("updateRun", runId.toString))
        yield ()

      private def persistIgnoringFailure(action: String, effect: IO[PersistenceError, Unit]): UIO[Unit] =
        effect.catchAll(err => Logger.warn(s"Result persistence failed in $action: $err"))

      private def persistenceAsOrchestrator(action: String, runId: String)(error: PersistenceError): OrchestratorError =
        OrchestratorError.StateFailed(StateError.WriteError(runId, s"$action failed: $error"))

      private def runPipeline(
        sourcePath: Path,
        outputPath: Path,
        forcedStart: Option[MigrationStep],
        onProgress: PipelineProgressUpdate => UIO[Unit],
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
                        ZIO.foreach(inventory.files.filter(_.fileType == models.FileType.Program)) { file =>
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
          _            <- onProgress(PipelineProgressUpdate(MigrationStep.Documentation, s"Migration completed: $status", 100))
          _            <- Logger.info(s"Migration pipeline completed: $status")
        yield result

      private def runPhase[A](
        name: String,
        step: MigrationStep,
        shouldRun: Boolean,
        progress: Int,
        onProgress: PipelineProgressUpdate => UIO[Unit],
        stateRef: Ref[MigrationState],
        errorsRef: Ref[List[MigrationError]],
      )(
        effect: ZIO[Any, OrchestratorError, A]
      ): ZIO[Any, OrchestratorError, Option[A]] =
        if !shouldRun then ZIO.succeed(None)
        else
          for
            _      <- onProgress(PipelineProgressUpdate(step, s"Starting phase: $name", progress))
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
                            _            <- onProgress(PipelineProgressUpdate(step, s"Completed phase: $name", progress + 10))
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
                            _  <- onProgress(PipelineProgressUpdate(
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

case class PipelineProgressUpdate(
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
