package core

import java.nio.file.Path
import java.security.MessageDigest

import zio.*
import zio.json.*

import models.*

/** StateService - State persistence and checkpointing for task execution.
  */
trait StateService:
  def saveState(state: TaskState): ZIO[Any, StateError, Unit]
  def loadState(runId: String): ZIO[Any, StateError, Option[TaskState]]
  def createCheckpoint(runId: String, stepName: String): ZIO[Any, StateError, Unit]
  def getLastCheckpoint(runId: String): ZIO[Any, StateError, Option[String]]
  def listCheckpoints(runId: String): ZIO[Any, StateError, List[Checkpoint]]
  def validateCheckpointIntegrity(runId: String): ZIO[Any, StateError, Unit]
  def listRuns(): ZIO[Any, StateError, List[TaskRunSummary]]
  def getStateDirectory(runId: String): ZIO[Any, StateError, Path]

object StateService:
  def saveState(state: TaskState): ZIO[StateService, StateError, Unit] =
    ZIO.serviceWithZIO[StateService](_.saveState(state))

  def loadState(runId: String): ZIO[StateService, StateError, Option[TaskState]] =
    ZIO.serviceWithZIO[StateService](_.loadState(runId))

  def createCheckpoint(runId: String, stepName: String): ZIO[StateService, StateError, Unit] =
    ZIO.serviceWithZIO[StateService](_.createCheckpoint(runId, stepName))

  def getLastCheckpoint(runId: String): ZIO[StateService, StateError, Option[String]] =
    ZIO.serviceWithZIO[StateService](_.getLastCheckpoint(runId))

  def listCheckpoints(runId: String): ZIO[StateService, StateError, List[Checkpoint]] =
    ZIO.serviceWithZIO[StateService](_.listCheckpoints(runId))

  def validateCheckpointIntegrity(runId: String): ZIO[StateService, StateError, Unit] =
    ZIO.serviceWithZIO[StateService](_.validateCheckpointIntegrity(runId))

  def listRuns(): ZIO[StateService, StateError, List[TaskRunSummary]] =
    ZIO.serviceWithZIO[StateService](_.listRuns())

  def getStateDirectory(runId: String): ZIO[StateService, StateError, Path] =
    ZIO.serviceWithZIO[StateService](_.getStateDirectory(runId))

  def live(stateDir: Path): ZLayer[FileService, Nothing, StateService] = ZLayer.fromFunction {
    (fileService: FileService) =>
      new StateService {
        private val runsDir   = stateDir.resolve("runs")
        private val indexPath = stateDir.resolve("index.json")

        private def runDir(runId: String): Path = runsDir.resolve(runId)

        private def statePath(runId: String): Path = runDir(runId).resolve("state.json")

        private def checkpointsDir(runId: String): Path = runDir(runId).resolve("checkpoints")

        private def checkpointPath(runId: String, stepName: String): Path =
          checkpointsDir(runId).resolve(s"${normalizeStep(stepName)}.json")

        override def saveState(state: TaskState): ZIO[Any, StateError, Unit] =
          for
            runId <- resolveRunId(state)
            _     <- ZIO.logInfo(s"Saving state for run: $runId")
            _     <- fileService.ensureDirectory(runDir(runId)).mapError(fe => mapFileToStateError(runId)(fe))
            _     <- fileService
                       .writeFileAtomic(statePath(runId), state.toJsonPretty)
                       .mapError(fe => mapFileToStateError(runId)(fe))
            _     <- updateIndex(runId, state).mapError(fe => mapFileToStateError(runId)(fe))
          yield ()

        override def loadState(runId: String): ZIO[Any, StateError, Option[TaskState]] =
          for
            exists <- fileService.exists(statePath(runId)).mapError(fe => mapFileToStateError(runId)(fe))
            state  <-
              if exists then
                fileService
                  .readFile(statePath(runId))
                  .mapError(fe => mapFileToStateError(runId)(fe))
                  .flatMap(json =>
                    ZIO.fromEither(json.fromJson[TaskState]).mapError(err => StateError.InvalidState(runId, err))
                  )
                  .map(Some(_))
              else ZIO.succeed(None)
          yield state

        override def createCheckpoint(runId: String, stepName: String): ZIO[Any, StateError, Unit] =
          for
            stateOpt  <- loadState(runId)
            state     <- ZIO.fromOption(stateOpt).mapError(_ => StateError.StateNotFound(runId))
            _         <- fileService.ensureDirectory(checkpointsDir(runId)).mapError(fe => mapFileToStateError(runId)(fe))
            createdAt <- Clock.instant
            checksum   = calculateChecksum(state.toJson)
            checkpoint = Checkpoint(
                           runId = runId,
                           step = stepName,
                           createdAt = createdAt,
                           artifactPaths = Map.empty,
                           checksum = checksum,
                         )
            snapshot   = CheckpointSnapshot(checkpoint = checkpoint, state = state)
            _         <- fileService
                           .writeFileAtomic(checkpointPath(runId, stepName), snapshot.toJsonPretty)
                           .mapError(fe => mapFileToStateError(runId)(fe))
          yield ()

        override def getLastCheckpoint(runId: String): ZIO[Any, StateError, Option[String]] =
          listCheckpoints(runId).map(_.sortBy(_.createdAt.toEpochMilli).lastOption.map(_.step))

        override def listCheckpoints(runId: String): ZIO[Any, StateError, List[Checkpoint]] =
          for
            dirExists <- fileService.exists(checkpointsDir(runId)).mapError(fe => mapFileToStateError(runId)(fe))
            snapshots <-
              if dirExists then
                for
                  files  <- fileService
                              .listFiles(checkpointsDir(runId), Set(".json"))
                              .runCollect
                              .mapError(fe => mapFileToStateError(runId)(fe))
                  parsed <- ZIO.foreach(files.toList)(path => readCheckpointSnapshot(path, runId))
                yield parsed
              else ZIO.succeed(List.empty)
          yield snapshots.map(_.checkpoint).sortBy(_.createdAt.toEpochMilli)

        override def validateCheckpointIntegrity(runId: String): ZIO[Any, StateError, Unit] =
          listCheckpoints(runId).flatMap { checkpoints =>
            ZIO.foreachDiscard(checkpoints) { checkpoint =>
              for
                snapshot <- readCheckpointSnapshot(checkpointPath(runId, checkpoint.step), runId)
                _        <-
                  if snapshot.checkpoint.runId == runId then ZIO.unit
                  else ZIO.fail(StateError.InvalidState(runId, s"Checkpoint ${checkpoint.step} has mismatched runId"))
                checksum  = calculateChecksum(snapshot.state.toJson)
                _        <-
                  if checksum == snapshot.checkpoint.checksum then ZIO.unit
                  else ZIO.fail(StateError.InvalidState(runId, s"Checkpoint ${checkpoint.step} checksum mismatch"))
              yield ()
            }
          }

        override def listRuns(): ZIO[Any, StateError, List[TaskRunSummary]] =
          for
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
                }.mapError(e => StateError.ReadError("all", e.getMessage)).flatMap { paths =>
                  ZIO.foreach(paths) { runPath =>
                    val runId = runPath.getFileName.toString
                    for
                      stateOpt <- loadState(runId)
                      summary  <- stateOpt match
                                    case Some(state) =>
                                      for
                                        ts <- readRunUpdatedAt(runPath, runId)
                                      yield Some(
                                        TaskRunSummary(
                                          runId = runId,
                                          currentStep = state.currentStep,
                                          completedSteps = state.completedSteps,
                                          errorCount = state.errors.length,
                                          taskRunId = state.taskRunId,
                                          currentStepName = state.currentStepName.orElse(Some(state.currentStep)),
                                          status = state.status,
                                          startedAt = state.startedAt,
                                          updatedAt = ts,
                                        )
                                      )
                                    case None        => ZIO.succeed(None)
                    yield summary
                  }.map(_.collect { case Some(s) => s }.sortBy(_.updatedAt.toEpochMilli).reverse)
                }
              else ZIO.succeed(List.empty)
          yield runs

        override def getStateDirectory(runId: String): ZIO[Any, StateError, Path] =
          ZIO.succeed(runDir(runId))

        private def updateIndex(runId: String, state: TaskState): ZIO[Any, FileError, Unit] =
          for
            _        <- fileService.ensureDirectory(stateDir)
            existing <- fileService
                          .exists(indexPath)
                          .flatMap { exists =>
                            if exists then
                              fileService
                                .readFile(indexPath)
                                .map(_.fromJson[List[TaskRunSummary]].getOrElse(List.empty))
                            else ZIO.succeed(List.empty)
                          }
            now      <- Clock.instant
            summary   = TaskRunSummary(
                          runId = runId,
                          currentStep = state.currentStep,
                          completedSteps = state.completedSteps,
                          errorCount = state.errors.length,
                          taskRunId = state.taskRunId,
                          currentStepName = state.currentStepName.orElse(Some(state.currentStep)),
                          status = state.status,
                          startedAt = state.startedAt,
                          updatedAt = now,
                        )
            updated   = (summary :: existing.filterNot(_.runId == runId)).sortBy(_.updatedAt.toEpochMilli).reverse
            _        <- fileService.writeFileAtomic(indexPath, updated.toJsonPretty)
          yield ()

        private def resolveRunId(state: TaskState): IO[StateError, String] =
          state.taskRunId match
            case Some(id) => ZIO.succeed(id.toString)
            case None     =>
              if state.runId.trim.nonEmpty then ZIO.succeed(state.runId.trim)
              else
                ZIO.fail(StateError.InvalidState("unknown", "TaskState.taskRunId or runId is required for persistence"))

        private def readRunUpdatedAt(runPath: Path, runId: String): IO[StateError, java.time.Instant] =
          ZIO
            .attemptBlocking {
              import java.nio.file.Files
              Files.getLastModifiedTime(runPath).toInstant
            }
            .mapError(err => StateError.ReadError(runId, err.getMessage))

        private def mapFileToStateError(runId: String)(fe: FileError): StateError = fe match
          case FileError.NotFound(_)         => StateError.StateNotFound(runId)
          case FileError.PermissionDenied(_) => StateError.WriteError(runId, "Permission denied")
          case FileError.IOError(_, cause)   => StateError.WriteError(runId, cause)
          case _                             => StateError.WriteError(runId, fe.message)

        private def readCheckpointSnapshot(path: Path, runId: String): ZIO[Any, StateError, CheckpointSnapshot] =
          for
            json     <- fileService.readFile(path).mapError(fe => mapFileToStateError(runId)(fe))
            snapshot <-
              ZIO
                .fromEither(json.fromJson[CheckpointSnapshot])
                .mapError(err => StateError.InvalidState(runId, s"Invalid checkpoint ${path.getFileName}: $err"))
          yield snapshot

        private def calculateChecksum(content: String): String =
          val digest = MessageDigest.getInstance("SHA-256")
          val bytes  = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          bytes.map("%02x".format(_)).mkString

        private def normalizeStep(stepName: String): String =
          stepName.trim.toLowerCase.replaceAll("[^a-z0-9_-]", "-")
      }
  }
