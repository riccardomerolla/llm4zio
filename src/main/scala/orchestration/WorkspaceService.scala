package orchestration

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.json.*

import core.FileService
import models.*
import models.Codecs.given

/** Workspace - Isolated directory structure for a migration run
  *
  * @param runId
  *   Unique identifier for the migration run
  * @param stateDir
  *   Directory for checkpoints and progress tracking
  * @param reportsDir
  *   Directory for generated analysis and transformation reports
  * @param outputDir
  *   Directory for final migrated code output
  * @param tempDir
  *   Directory for temporary files during processing
  * @param configSnapshot
  *   Immutable configuration snapshot for this run
  * @param createdAt
  *   Timestamp when workspace was created
  */
case class Workspace(
  runId: String,
  stateDir: Path,
  reportsDir: Path,
  outputDir: Path,
  tempDir: Path,
  configSnapshot: MigrationConfig,
  createdAt: Instant,
) derives JsonCodec:
  def rootDir: Path = stateDir.getParent

/** WorkspaceService - Manages isolated workspaces for parallel migration runs
  *
  * Features:
  *   - Per-run directory isolation (state, reports, outputs, temp)
  *   - Lifecycle management with ZIO Scope for guaranteed cleanup
  *   - Configuration snapshots to prevent mid-run changes
  *   - Workspace metadata persistence
  *   - Automatic cleanup on completion or cancellation
  *   - Workspace retention policies
  */
trait WorkspaceService:
  /** Create a new workspace for a migration run (scoped lifecycle)
    *
    * The workspace will be automatically cleaned up when the Scope closes, unless explicitly retained.
    *
    * @param runId
    *   Unique identifier for the migration run
    * @param config
    *   Migration configuration (will be snapshot)
    * @return
    *   Workspace instance with all directories created
    */
  def create(runId: String, config: MigrationConfig): ZIO[Scope, WorkspaceError, Workspace]

  /** Get workspace for an existing run
    *
    * @param runId
    *   Unique identifier for the migration run
    * @return
    *   Workspace instance if it exists
    */
  def get(runId: String): ZIO[Any, WorkspaceError, Option[Workspace]]

  /** Cleanup workspace after run completion or cancellation
    *
    * Removes all workspace directories and files.
    *
    * @param runId
    *   Unique identifier for the migration run
    * @return
    *   Unit effect
    */
  def cleanup(runId: String): ZIO[Any, WorkspaceError, Unit]

  /** List all active workspaces
    *
    * @return
    *   List of active workspace instances
    */
  def listActive: ZIO[Any, WorkspaceError, List[Workspace]]

  /** Check if workspace exists for a run
    *
    * @param runId
    *   Unique identifier for the migration run
    * @return
    *   True if workspace exists
    */
  def exists(runId: String): ZIO[Any, WorkspaceError, Boolean]

object WorkspaceService:
  /** Create a new workspace (scoped) */
  def create(runId: String, config: MigrationConfig): ZIO[WorkspaceService & Scope, WorkspaceError, Workspace] =
    ZIO.serviceWithZIO[WorkspaceService](_.create(runId, config))

  /** Get workspace for existing run */
  def get(runId: String): ZIO[WorkspaceService, WorkspaceError, Option[Workspace]] =
    ZIO.serviceWithZIO[WorkspaceService](_.get(runId))

  /** Cleanup workspace */
  def cleanup(runId: String): ZIO[WorkspaceService, WorkspaceError, Unit] =
    ZIO.serviceWithZIO[WorkspaceService](_.cleanup(runId))

  /** List all active workspaces */
  def listActive: ZIO[WorkspaceService, WorkspaceError, List[Workspace]] =
    ZIO.serviceWithZIO[WorkspaceService](_.listActive)

  /** Check if workspace exists */
  def exists(runId: String): ZIO[WorkspaceService, WorkspaceError, Boolean] =
    ZIO.serviceWithZIO[WorkspaceService](_.exists(runId))

  /** Live implementation with FileService dependency */
  val live: ZLayer[FileService & MigrationConfig, Nothing, WorkspaceService] = ZLayer.fromFunction {
    (fileService: FileService, globalConfig: MigrationConfig) =>
      new WorkspaceService {
        private val workspaceRoot = globalConfig.stateDir.resolve("workspaces")

        private def workspaceDir(runId: String): Path = workspaceRoot.resolve(s"run-$runId")

        private def metadataPath(runId: String): Path = workspaceDir(runId).resolve("workspace.json")

        override def create(runId: String, config: MigrationConfig): ZIO[Scope, WorkspaceError, Workspace] =
          for
            _            <- ZIO.logInfo(s"Creating workspace for run: $runId")
            _            <- checkDoesNotExist(runId)
            now          <- Clock.instant
            workspaceBase = workspaceDir(runId)
            stateDir      = workspaceBase.resolve("state")
            reportsDir    = workspaceBase.resolve("reports")
            outputDir     = config.outputDir // Use configured output dir, or could be workspaceBase.resolve("outputs")
            tempDir       = workspaceBase.resolve("temp")
            workspace     = Workspace(
                              runId = runId,
                              stateDir = stateDir,
                              reportsDir = reportsDir,
                              outputDir = outputDir,
                              tempDir = tempDir,
                              configSnapshot = config,
                              createdAt = now,
                            )
            _            <- createDirectories(workspace).mapError(fe => WorkspaceError.IOError(runId, fe.message))
            _            <- saveMetadata(workspace).mapError(fe => WorkspaceError.IOError(runId, fe.message))
            _            <- ZIO.addFinalizer(
                              ZIO.logInfo(s"Workspace finalizer triggered for run: $runId") *>
                                cleanupInternal(runId, failOnError = false).catchAll(_ => ZIO.unit)
                            )
            _            <- ZIO.logInfo(s"Workspace created successfully for run: $runId at ${workspaceBase}")
          yield workspace

        override def get(runId: String): ZIO[Any, WorkspaceError, Option[Workspace]] =
          for
            _              <- ZIO.logInfo(s"Getting workspace for run: $runId")
            metadataExists <-
              fileService.exists(metadataPath(runId)).mapError(fe => WorkspaceError.IOError(runId, fe.message))
            workspace      <-
              if metadataExists then
                for
                  json      <-
                    fileService.readFile(metadataPath(runId)).mapError(fe => WorkspaceError.IOError(runId, fe.message))
                  workspace <- ZIO
                                 .fromEither(json.fromJson[Workspace])
                                 .mapError(err => WorkspaceError.InvalidConfiguration(runId, err))
                yield Some(workspace)
              else ZIO.succeed(None)
          yield workspace

        override def cleanup(runId: String): ZIO[Any, WorkspaceError, Unit] =
          cleanupInternal(runId, failOnError = true)

        override def listActive: ZIO[Any, WorkspaceError, List[Workspace]] =
          for
            _          <- ZIO.logInfo("Listing active workspaces")
            rootExists <- fileService.exists(workspaceRoot).mapError(fe => WorkspaceError.IOError("all", fe.message))
            workspaces <-
              if rootExists then
                for
                  dirs       <- ZIO.attemptBlockingIO {
                                  import scala.jdk.CollectionConverters.*
                                  Files
                                    .list(workspaceRoot)
                                    .iterator()
                                    .asScala
                                    .filter(Files.isDirectory(_))
                                    .toList
                                }.mapError(ex => WorkspaceError.IOError("all", ex.getMessage))
                  workspaces <- ZIO.foreach(dirs) { dir =>
                                  val runId = dir.getFileName.toString.stripPrefix("run-")
                                  get(runId).map(_.toList).catchAll(_ => ZIO.succeed(List.empty))
                                }
                yield workspaces.flatten
              else ZIO.succeed(List.empty)
            _          <- ZIO.logInfo(s"Found ${workspaces.length} active workspaces")
          yield workspaces

        override def exists(runId: String): ZIO[Any, WorkspaceError, Boolean] =
          fileService
            .exists(workspaceDir(runId))
            .mapError(fe => WorkspaceError.IOError(runId, fe.message))

        private def checkDoesNotExist(runId: String): ZIO[Any, WorkspaceError, Unit] =
          for
            alreadyExists <-
              fileService.exists(workspaceDir(runId)).mapError(fe => WorkspaceError.IOError(runId, fe.message))
            _             <- ZIO.when(alreadyExists)(ZIO.fail(WorkspaceError.AlreadyExists(runId)))
          yield ()

        private def createDirectories(workspace: Workspace): ZIO[Any, FileError, Unit] =
          for
            _ <- fileService.ensureDirectory(workspace.stateDir)
            _ <- fileService.ensureDirectory(workspace.reportsDir)
            _ <- fileService.ensureDirectory(workspace.outputDir)
            _ <- fileService.ensureDirectory(workspace.tempDir)
          yield ()

        private def saveMetadata(workspace: Workspace): ZIO[Any, FileError, Unit] =
          val json = workspace.toJsonPretty
          fileService.writeFileAtomic(metadataPath(workspace.runId), json)

        private def cleanupInternal(runId: String, failOnError: Boolean): ZIO[Any, WorkspaceError, Unit] =
          val effect =
            for
              _      <- ZIO.logInfo(s"Cleaning up workspace for run: $runId")
              wsDir   = workspaceDir(runId)
              exists <- fileService.exists(wsDir).mapError(fe => WorkspaceError.IOError(runId, fe.message))
              _      <- ZIO.when(exists) {
                          ZIO.attemptBlockingIO {
                            import scala.jdk.CollectionConverters.*
                            // Delete recursively
                            Files
                              .walk(wsDir)
                              .iterator()
                              .asScala
                              .toList
                              .reverse // Delete children before parents
                              .foreach(Files.deleteIfExists)
                          }.mapError(ex => WorkspaceError.IOError(runId, ex.getMessage))
                        }
              _      <- ZIO.logInfo(s"Workspace cleanup completed for run: $runId")
            yield ()

          if failOnError then effect
          else
            effect
              .catchAll(err =>
                ZIO.logWarning(s"Workspace cleanup failed for run $runId: $err").as(())
              )
      }
  }
