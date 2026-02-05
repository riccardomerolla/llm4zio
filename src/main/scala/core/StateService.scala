package core

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import zio.*
import zio.json.*

import models.{ FileError, MigrationRunSummary, MigrationState, MigrationStep, StateError }

/** StateService - State persistence and checkpointing
  *
  * Features:
  *   - Save migration state to JSON files
  *   - Load previous state for recovery
  *   - Checkpoint management with atomic writes
  *   - Progress tracking across runs
  *   - Run history and summaries
  */
trait StateService:
  def saveState(state: MigrationState): ZIO[Any, StateError, Unit]
  def loadState(runId: String): ZIO[Any, StateError, Option[MigrationState]]
  def createCheckpoint(runId: String, step: MigrationStep): ZIO[Any, StateError, Unit]
  def getLastCheckpoint(runId: String): ZIO[Any, StateError, Option[MigrationStep]]
  def listRuns(): ZIO[Any, StateError, List[MigrationRunSummary]]

object StateService:
  def saveState(state: MigrationState): ZIO[StateService, StateError, Unit] =
    ZIO.serviceWithZIO[StateService](_.saveState(state))

  def loadState(runId: String): ZIO[StateService, StateError, Option[MigrationState]] =
    ZIO.serviceWithZIO[StateService](_.loadState(runId))

  def createCheckpoint(runId: String, step: MigrationStep): ZIO[StateService, StateError, Unit] =
    ZIO.serviceWithZIO[StateService](_.createCheckpoint(runId, step))

  def getLastCheckpoint(runId: String): ZIO[StateService, StateError, Option[MigrationStep]] =
    ZIO.serviceWithZIO[StateService](_.getLastCheckpoint(runId))

  def listRuns(): ZIO[StateService, StateError, List[MigrationRunSummary]] =
    ZIO.serviceWithZIO[StateService](_.listRuns())

  /** Live implementation with FileService dependency */
  def live(stateDir: Path): ZLayer[FileService, Nothing, StateService] = ZLayer.fromFunction {
    (fileService: FileService) =>
      new StateService {
        private val runsDir   = stateDir.resolve("runs")
        private val indexPath = stateDir.resolve("index.json")

        private def runDir(runId: String): Path = runsDir.resolve(runId)

        private def statePath(runId: String): Path = runDir(runId).resolve("state.json")

        private def checkpointsDir(runId: String): Path = runDir(runId).resolve("checkpoints")

        private def checkpointPath(runId: String, step: MigrationStep): Path =
          checkpointsDir(runId).resolve(s"${step.toString.toLowerCase}.json")

        override def saveState(state: MigrationState): ZIO[Any, StateError, Unit] =
          for
            _       <- ZIO.logInfo(s"Saving state for run: ${state.runId}")
            _       <- fileService.ensureDirectory(runDir(state.runId)).mapError(fe => mapFileToStateError(state.runId)(fe))
            json     = state.toJsonPretty
            ts      <- Clock.currentTime(TimeUnit.MILLISECONDS)
            tempPath = statePath(state.runId).resolveSibling(s"state.tmp.$ts")
            _       <- fileService.writeFile(tempPath, json).mapError(fe => mapFileToStateError(state.runId)(fe))
            _       <- ZIO.attemptBlocking {
                         val target = statePath(state.runId)
                         java.nio.file.Files.move(
                           tempPath,
                           target,
                           java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                           java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                         )
                       }.mapError(e => StateError.WriteError(state.runId, e.getMessage))
            _       <- updateIndex(state.runId, state).mapError(fe => mapFileToStateError(state.runId)(fe))
            _       <- ZIO.logInfo(s"State saved successfully for run: ${state.runId}")
          yield ()

        override def loadState(runId: String): ZIO[Any, StateError, Option[MigrationState]] =
          for
            _      <- ZIO.logInfo(s"Loading state for run: $runId")
            exists <- fileService.exists(statePath(runId)).mapError(fe => mapFileToStateError(runId)(fe))
            state  <-
              if exists then
                for
                  json  <- fileService.readFile(statePath(runId)).mapError(fe => mapFileToStateError(runId)(fe))
                  state <- ZIO
                             .fromEither(json.fromJson[MigrationState])
                             .mapError(err => StateError.InvalidState(runId, err))
                  _     <- ZIO.logInfo(s"State loaded successfully for run: $runId")
                yield Some(state)
              else
                ZIO.logInfo(s"No state found for run: $runId").as(None)
          yield state

        override def createCheckpoint(runId: String, step: MigrationStep): ZIO[Any, StateError, Unit] =
          for
            _        <- ZIO.logInfo(s"Creating checkpoint for run $runId, step: $step")
            stateOpt <- loadState(runId)
            state    <- ZIO
                          .fromOption(stateOpt)
                          .mapError(_ => StateError.StateNotFound(runId))
            _        <- fileService.ensureDirectory(checkpointsDir(runId)).mapError(fe => mapFileToStateError(runId)(fe))
            json      = state.toJsonPretty
            _        <- fileService.writeFile(checkpointPath(runId, step), json).mapError(fe => mapFileToStateError(runId)(fe))
            _        <- ZIO.logInfo(s"Checkpoint created for run $runId, step: $step")
          yield ()

        override def getLastCheckpoint(runId: String): ZIO[Any, StateError, Option[MigrationStep]] =
          for
            _          <- ZIO.logInfo(s"Getting last checkpoint for run: $runId")
            dirExists  <- fileService.exists(checkpointsDir(runId)).mapError(fe => mapFileToStateError(runId)(fe))
            checkpoint <-
              if dirExists then
                fileService
                  .listFiles(checkpointsDir(runId), Set(".json"))
                  .runCollect
                  .mapError(fe => mapFileToStateError(runId)(fe))
                  .map { files =>
                    files.headOption.flatMap { path =>
                      val filename = path.getFileName.toString.replace(".json", "")
                      MigrationStep.values.find(_.toString.toLowerCase == filename)
                    }
                  }
              else ZIO.succeed(None)
            _          <- ZIO.logInfo(s"Last checkpoint for run $runId: $checkpoint")
          yield checkpoint

        override def listRuns(): ZIO[Any, StateError, List[MigrationRunSummary]] =
          for
            _      <- ZIO.logInfo("Listing all migration runs")
            exists <-
              fileService.exists(runsDir).mapError(_ => StateError.ReadError("all", "Failed to check runs directory"))
            runs   <-
              if exists then
                ZIO.attemptBlocking {
                  import java.nio.file.Files
                  import scala.jdk.CollectionConverters.*
                  Files
                    .list(runsDir)
                    .iterator()
                    .asScala
                    .filter(Files.isDirectory(_))
                    .toList
                }.mapError(e => StateError.ReadError("all", e.getMessage))
                  .flatMap { paths =>
                    ZIO.foreach(paths) { runPath =>
                      val runId = runPath.getFileName.toString
                      loadState(runId).map {
                        case Some(state) =>
                          Some(
                            MigrationRunSummary(
                              runId = state.runId,
                              startedAt = state.startedAt,
                              currentStep = state.currentStep,
                              completedSteps = state.completedSteps,
                              errorCount = state.errors.length,
                            )
                          )
                        case None        => None
                      }
                    }.map(_.collect { case Some(s) => s }.sortBy(_.startedAt.toEpochMilli).reverse)
                  }
              else ZIO.succeed(List.empty)
            _      <- ZIO.logInfo(s"Found ${runs.length} migration runs")
          yield runs

        private def updateIndex(runId: String, state: MigrationState): ZIO[Any, FileError, Unit] =
          for
            _        <- fileService.ensureDirectory(stateDir)
            existing <- fileService
                          .exists(indexPath)
                          .flatMap { exists =>
                            if exists then
                              fileService
                                .readFile(indexPath)
                                .map(_.fromJson[List[MigrationRunSummary]].getOrElse(List.empty))
                            else ZIO.succeed(List.empty)
                          }
            summary   = MigrationRunSummary(
                          runId = state.runId,
                          startedAt = state.startedAt,
                          currentStep = state.currentStep,
                          completedSteps = state.completedSteps,
                          errorCount = state.errors.length,
                        )
            updated   = (summary :: existing.filterNot(_.runId == runId)).sortBy(_.startedAt.toEpochMilli).reverse
            json      = updated.toJsonPretty
            _        <- fileService.writeFile(indexPath, json)
          yield ()

        private def mapFileToStateError(runId: String)(fe: FileError): StateError = fe match
          case FileError.NotFound(_)         => StateError.StateNotFound(runId)
          case FileError.PermissionDenied(_) => StateError.WriteError(runId, "Permission denied")
          case FileError.IOError(_, cause)   => StateError.WriteError(runId, cause)
          case _                             => StateError.WriteError(runId, fe.message)
      }
  }
