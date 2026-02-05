package core

import java.nio.file.Path

import zio.*

import models.{ FileError, MigrationState }

/** StateService - State persistence and checkpointing
  *
  * Features:
  *   - Save migration state to JSON files
  *   - Load previous state for recovery
  *   - Checkpoint management
  *   - Progress tracking
  */
trait StateService:
  def saveState(state: MigrationState): ZIO[Any, Throwable, Unit]
  def loadState(): ZIO[Any, Throwable, Option[MigrationState]]
  def createCheckpoint(step: String): ZIO[Any, Throwable, Unit]

object StateService:
  def saveState(state: MigrationState): ZIO[StateService, Throwable, Unit] =
    ZIO.serviceWithZIO[StateService](_.saveState(state))

  def loadState(): ZIO[StateService, Throwable, Option[MigrationState]] =
    ZIO.serviceWithZIO[StateService](_.loadState())

  def createCheckpoint(step: String): ZIO[StateService, Throwable, Unit] =
    ZIO.serviceWithZIO[StateService](_.createCheckpoint(step))

  def live(stateDir: Path): ZLayer[FileService, Nothing, StateService] = ZLayer.fromFunction {
    (fileService: FileService) =>
      new StateService {
        private val statePath = stateDir.resolve("migration-state.json")

        override def saveState(state: MigrationState): ZIO[Any, Throwable, Unit] =
          (for
            // TODO: Implement JSON serialization with zio-json
            _   <- ZIO.logInfo(s"Saving state to $statePath")
            json = "{}" // Placeholder
            _   <- fileService.writeFile(statePath, json)
          yield ()).mapError(fileErrorToThrowable)

        override def loadState(): ZIO[Any, Throwable, Option[MigrationState]] =
          (for
            _      <- ZIO.logInfo(s"Loading state from $statePath")
            exists <- fileService.exists(statePath)
            state  <- if exists then fileService.readFile(statePath).map(_ => Some(MigrationState.empty))
                      else ZIO.succeed(None)
          yield state).mapError(fileErrorToThrowable)

        override def createCheckpoint(step: String): ZIO[Any, Throwable, Unit] =
          for _ <- ZIO.logInfo(s"Creating checkpoint for step: $step")
          // TODO: Implement checkpoint logic
          yield ()

        private def fileErrorToThrowable(error: FileError): Throwable =
          new RuntimeException(error.message)
      }
  }
