package project.control

import java.nio.file.{ Files as JFiles, Path, Paths }

import zio.*
import zio.process.Command

import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import shared.store.StoreConfig
import workspace.control.GitService
import workspace.entity.GitError

trait ProjectStorageService:
  def initProjectStorage(projectId: ProjectId): IO[PersistenceError, Path]
  def projectRoot(projectId: ProjectId): UIO[Path]
  def boardPath(projectId: ProjectId): UIO[Path]
  def workspaceAnalysisPath(projectId: ProjectId, workspaceId: String): UIO[Path]

object ProjectStorageService:

  val live: URLayer[StoreConfig & GitService, ProjectStorageService] =
    ZLayer.fromFunction(ProjectStorageServiceLive.apply)

  def initProjectStorage(projectId: ProjectId): ZIO[ProjectStorageService, PersistenceError, Path] =
    ZIO.serviceWithZIO[ProjectStorageService](_.initProjectStorage(projectId))

  def projectRoot(projectId: ProjectId): URIO[ProjectStorageService, Path] =
    ZIO.serviceWithZIO[ProjectStorageService](_.projectRoot(projectId))

  def boardPath(projectId: ProjectId): URIO[ProjectStorageService, Path] =
    ZIO.serviceWithZIO[ProjectStorageService](_.boardPath(projectId))

  def workspaceAnalysisPath(projectId: ProjectId, workspaceId: String): URIO[ProjectStorageService, Path] =
    ZIO.serviceWithZIO[ProjectStorageService](_.workspaceAnalysisPath(projectId, workspaceId))

final case class ProjectStorageServiceLive(storeConfig: StoreConfig, gitService: GitService)
  extends ProjectStorageService:

  private val gatewayRoot: Path =
    Paths.get(storeConfig.dataStorePath).getParent.getParent

  private val boardColumns = List("backlog", "todo", "in-progress", "review", "done", "archive")

  override def projectRoot(projectId: ProjectId): UIO[Path] =
    ZIO.succeed(gatewayRoot.resolve("projects").resolve(projectId.value))

  override def boardPath(projectId: ProjectId): UIO[Path] =
    projectRoot(projectId).map(_.resolve(".board"))

  override def workspaceAnalysisPath(projectId: ProjectId, workspaceId: String): UIO[Path] =
    projectRoot(projectId).map(
      _.resolve("workspaces").resolve(workspaceId).resolve(".llm4zio").resolve("analysis")
    )

  override def initProjectStorage(projectId: ProjectId): IO[PersistenceError, Path] =
    for
      root   <- projectRoot(projectId)
      board   = root.resolve(".board")
      gitDir  = root.resolve(".git")
      _      <- ZIO
                  .attemptBlocking {
                    JFiles.createDirectories(root)
                    boardColumns.foreach(col => JFiles.createDirectories(board.resolve(col)))
                    if !JFiles.exists(board.resolve("BOARD.md")) then
                      val _ = JFiles.writeString(board.resolve("BOARD.md"), "# Board\n")
                  }
                  .mapError(err =>
                    PersistenceError.QueryFailed(
                      "project_storage_init",
                      s"Failed to create project directory: ${err.getMessage}",
                    )
                  )
      inited <- ZIO.attemptBlocking(JFiles.exists(gitDir.resolve("HEAD"))).orDie
      _      <- ZIO.unless(inited) {
                  ZIO
                    .attemptBlocking {
                      if JFiles.exists(gitDir) then
                        def deleteTree(p: Path): Unit =
                          if JFiles.isDirectory(p) then
                            JFiles.list(p).forEach(deleteTree)
                          JFiles.delete(p)
                        deleteTree(gitDir)
                    }
                    .ignore *>
                    runGit(root, "init", "--initial-branch=main").unit
                }
      hasCom <- gitService.log(root.toString, 1).map(_.nonEmpty).catchAll(_ => ZIO.succeed(false))
      _      <- ZIO.unless(hasCom) {
                  gitService
                    .add(root.toString, List("."))
                    .zipRight(gitService.commit(root.toString, "[project] Init: project storage"))
                    .mapError(mapGitError)
                }
    yield root

  private def runGit(repoDir: Path, args: String*): IO[PersistenceError, String] =
    Command("git", args*).workingDirectory(repoDir.toFile).string
      .mapError(err =>
        PersistenceError.QueryFailed("project_storage_git", s"git ${args.mkString(" ")}: ${err.getMessage}")
      )

  private def mapGitError(err: GitError): PersistenceError =
    PersistenceError.QueryFailed("project_storage_git", err.toString)
