package board.control

import java.nio.file.{ Files as JFiles, Path, Paths }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.process.Command

import board.entity.*
import shared.ids.Ids.BoardIssueId
import workspace.control.GitService
import workspace.entity.GitError

final case class BoardRepositoryFS(
  parser: IssueMarkdownParser,
  gitService: GitService,
  locksRef: Ref[Map[String, Semaphore]],
) extends BoardRepository:
  private val boardRootFolder   = ".board"
  private val boardSkillFile    = "BOARD.md"
  private val issueMarkdownFile = "ISSUE.md"

  private val columnsInOrder: List[BoardColumn] = List(
    BoardColumn.Backlog,
    BoardColumn.Todo,
    BoardColumn.InProgress,
    BoardColumn.Review,
    BoardColumn.Done,
    BoardColumn.Archive,
  )

  override def initBoard(workspacePath: String): IO[BoardError, Unit] =
    withWorkspaceLock(workspacePath) {
      val boardPath   = workspacePath + "/" + boardRootFolder
      val boardGitDir = Paths.get(boardPath).resolve(".git")
      withMutationRollback(boardPath) {
        for
          workspace <- ensureWorkspaceDirectory(workspacePath)
          boardRoot  = workspace.resolve(boardRootFolder)
          exists    <- pathExists(boardRoot)
          _         <- createBoardFilesystem(boardRoot, createOverview = !exists)
          _         <- initBoardGit(boardPath, boardGitDir)
        yield ()
      } *>
        updateGitignore(workspacePath).flatMap { changed =>
          ZIO.when(changed)(
            stageAndCommit(workspacePath, List(".gitignore"), "[board] Add .board/ to .gitignore").unit
          ).unit
        }
    }

  override def readBoard(workspacePath: String): IO[BoardError, Board] =
    withWorkspaceLock(workspacePath) {
      for
        workspace <- ensureWorkspaceDirectory(workspacePath)
        _         <- ensureBoardRoot(workspace)
        _         <- reconcileDuplicateIssuePlacements(workspacePath, workspace)
        columns   <- ZIO.foreach(columnsInOrder) { column =>
                       listIssues(workspacePath, column).map(column -> _)
                     }
      yield Board(workspacePath = workspacePath, columns = columns.toMap)
    }

  override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue] =
    for
      location <- locateIssue(workspacePath, issueId)
      issue    <- readIssueAt(location.column, location.issueDirectory)
    yield issue

  override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue): IO[BoardError, BoardIssue] =
    withWorkspaceLock(workspacePath) {
      val boardPath = workspacePath + "/" + boardRootFolder
      withMutationRollback(boardPath) {
        for
          workspace <- ensureWorkspaceDirectory(workspacePath)
          boardRoot  = workspace.resolve(boardRootFolder)
          _         <- ensureBoardRoot(workspace)
          exists    <- issueExists(workspacePath, issue.frontmatter.id)
          _         <- ZIO.fail(BoardError.IssueAlreadyExists(issue.frontmatter.id.value)).when(exists)
          issueDir   = issueDirectory(workspace, column, issue.frontmatter.id)
          issueFile  = issueDir.resolve(issueMarkdownFile)
          rendered  <- parser.render(issue.frontmatter, issue.body)
          _         <- ZIO
                         .attemptBlocking {
                           val _ = JFiles.createDirectories(issueDir)
                           val _ = JFiles.writeString(issueFile, rendered)
                         }
                         .mapError(err => BoardError.WriteError(issueFile.toString, err.getMessage))
          _         <- stageAndCommit(
                         workspacePath = boardPath,
                         addPaths = List(relativize(boardRoot, issueDir)),
                         commitMessage = s"[board] Create: ${issue.frontmatter.title}",
                       )
        yield issue.copy(column = column, directoryPath = issueDir.toString)
      }
    }

  override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
    : IO[BoardError, BoardIssue] =
    withWorkspaceLock(workspacePath) {
      val boardPath = workspacePath + "/" + boardRootFolder
      withMutationRollback(boardPath) {
        for
          workspace <- ensureWorkspaceDirectory(workspacePath)
          boardRoot  = workspace.resolve(boardRootFolder)
          _         <- ensureBoardRoot(workspace)
          location  <- locateIssue(workspacePath, issueId)
          _         <-
            ZIO.fail(BoardError.ConcurrencyConflict(s"Issue '${issueId.value}' is already in ${toColumn.folderName}"))
              .when(location.column == toColumn)
          fromPath   = location.issueDirectory
          toPath     = issueDirectory(workspace, toColumn, issueId)
          _         <- gitService
                         .mv(boardPath, relativize(boardRoot, fromPath), relativize(boardRoot, toPath))
                         .mapError(mapGitError("git mv"))
          _         <- gitService
                         .commit(
                           boardPath,
                           s"[board] Move: ${issueId.value} ${location.column.folderName} -> ${toColumn.folderName}",
                         )
                         .mapError(mapGitError("git commit"))
          issue     <- readIssueAt(toColumn, toPath)
        yield issue
      }
    }

  override def updateIssue(
    workspacePath: String,
    issueId: BoardIssueId,
    update: IssueFrontmatter => IssueFrontmatter,
  ): IO[BoardError, BoardIssue] =
    withWorkspaceLock(workspacePath) {
      val boardPath = workspacePath + "/" + boardRootFolder
      withMutationRollback(boardPath) {
        for
          workspace  <- ensureWorkspaceDirectory(workspacePath)
          boardRoot   = workspace.resolve(boardRootFolder)
          _          <- ensureBoardRoot(workspace)
          location   <- locateIssue(workspacePath, issueId)
          issueFile   = location.issueDirectory.resolve(issueMarkdownFile)
          raw        <- readFile(issueFile)
          updatedRaw <- parser.updateFrontmatter(raw, update)
          _          <- writeFile(issueFile, updatedRaw)
          _          <- stageAndCommit(
                          workspacePath = boardPath,
                          addPaths = List(relativize(boardRoot, issueFile)),
                          commitMessage = s"[board] Update: ${issueId.value}",
                        )
          issue      <- readIssueAt(location.column, location.issueDirectory)
        yield issue
      }
    }

  override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
    withWorkspaceLock(workspacePath) {
      val boardPath = workspacePath + "/" + boardRootFolder
      withMutationRollback(boardPath) {
        for
          workspace <- ensureWorkspaceDirectory(workspacePath)
          boardRoot  = workspace.resolve(boardRootFolder)
          _         <- ensureBoardRoot(workspace)
          location  <- locateIssue(workspacePath, issueId)
          _         <- gitService
                         .rm(boardPath, relativize(boardRoot, location.issueDirectory), recursive = true)
                         .mapError(mapGitError("git rm"))
          _         <- gitService
                         .commit(boardPath, s"[board] Delete: ${issueId.value}")
                         .mapError(mapGitError("git commit"))
        yield ()
      }
    }

  override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
    for
      workspace <- ensureWorkspaceDirectory(workspacePath)
      _         <- ensureBoardRoot(workspace)
      issueDirs <- listIssueDirectories(workspace, column)
      issues    <- ZIO.foreach(issueDirs)(dir => readIssueAt(column, dir))
      higherCols = columnsInOrder.drop(columnPriority(column) + 1)
      visible   <- ZIO.filter(issues) { issue =>
                     ZIO
                       .foreach(higherCols)(higher => pathExists(issueDirectory(workspace, higher, issue.frontmatter.id)))
                       .map(occurrences => !occurrences.contains(true))
                   }
    yield visible

  override def invalidateWorkspace(workspacePath: String): UIO[Unit] =
    ZIO.unit

  final private case class IssueLocation(column: BoardColumn, issueDirectory: Path)

  private def withWorkspaceLock[A](workspacePath: String)(effect: IO[BoardError, A]): IO[BoardError, A] =
    for
      semaphore <- semaphoreForWorkspace(workspacePath)
      result    <- semaphore.withPermit(effect)
    yield result

  private def semaphoreForWorkspace(workspacePath: String): UIO[Semaphore] =
    for
      created <- Semaphore.make(1)
      sem     <- locksRef.modify { current =>
                   current.get(workspacePath) match
                     case Some(existing) => (existing, current)
                     case None           => (created, current.updated(workspacePath, created))
                 }
    yield sem

  private def withMutationRollback[A](boardPath: String)(effect: IO[BoardError, A]): IO[BoardError, A] =
    for
      failed <- Ref.make(true)
      result <- effect.tap(_ => failed.set(false)).ensuring {
                  failed.get.flatMap {
                    case true  => rollbackBoardChanges(boardPath).ignore
                    case false => ZIO.unit
                  }
                }
    yield result

  private def rollbackBoardChanges(boardPath: String): IO[BoardError, Unit] =
    runGit(boardPath, "checkout", "--", ".").unit

  private def createBoardFilesystem(boardRoot: Path, createOverview: Boolean): IO[BoardError, Unit] =
    ZIO
      .attemptBlocking {
        val _ = JFiles.createDirectories(boardRoot)
        columnsInOrder.foreach(column => JFiles.createDirectories(boardRoot.resolve(column.folderName)))
        if createOverview then
          val skillPath = boardRoot.resolve(boardSkillFile)
          if !JFiles.exists(skillPath) then
            val content = scala.io.Source.fromResource("board/SKILL.md").mkString
            val _       = JFiles.writeString(skillPath, content)
      }
      .mapError(err => BoardError.WriteError(boardRoot.toString, err.getMessage))

  private def initBoardGit(boardPath: String, boardGitDir: Path): IO[BoardError, Unit] =
    for
      gitInitialized <- pathExists(boardGitDir.resolve("HEAD"))
      _              <- ZIO.unless(gitInitialized) {
                          // The .git directory exists but is incomplete (no HEAD).
                          // This can happen if git init was interrupted or objects were corrupted.
                          // Remove the entire broken .git dir so git init starts completely fresh,
                          // avoiding "unable to read tree" errors from a stale index.
                          ZIO
                            .attemptBlocking {
                              if JFiles.exists(boardGitDir) then
                                def deleteTree(p: Path): Unit =
                                  if JFiles.isDirectory(p) then
                                    JFiles.list(p).forEach(deleteTree)
                                  JFiles.delete(p)
                                deleteTree(boardGitDir)
                            }
                            .ignore *>
                            runGit(boardPath, "init").unit
                        }
      hasCommits     <- gitService
                          .log(boardPath, 1)
                          .map(_.nonEmpty)
                          .catchAll(_ => ZIO.succeed(false))
      _              <- ZIO.unless(hasCommits)(
                          stageAndCommit(boardPath, List("."), "[board] Init: board structure")
                        )
    yield ()

  private def updateGitignore(workspacePath: String): IO[BoardError, Boolean] =
    val gitignorePath = Paths.get(workspacePath).resolve(".gitignore")
    ZIO
      .attemptBlocking {
        val existing = if JFiles.exists(gitignorePath) then JFiles.readString(gitignorePath) else ""
        if !existing.linesIterator.exists(_.trim == "/.board/") then
          val sep        = if existing.nonEmpty && !existing.endsWith("\n") then "\n" else ""
          val newContent = s"$existing${sep}/.board/\n"
          val tmp        = JFiles.createTempFile(Paths.get(workspacePath), ".gitignore-", ".tmp")
          JFiles.writeString(tmp, newContent)
          val _          = JFiles.move(tmp, gitignorePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          true
        else false
      }
      .mapError(err => BoardError.WriteError(gitignorePath.toString, err.getMessage))

  private def locateIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, IssueLocation] =
    for
      workspace <- ensureWorkspaceDirectory(workspacePath)
      _         <- ensureBoardRoot(workspace)
      found     <- ZIO.foreach(columnsInOrder) { column =>
                     val issueDir = issueDirectory(workspace, column, issueId)
                     pathExists(issueDir).map(exists => if exists then Some(IssueLocation(column, issueDir)) else None)
                   }.map(_.flatten)
      _         <- ZIO
                     .logWarning(
                       s"[board] duplicate issue '${issueId.value}' detected across columns: ${
                           found.map(_.column.folderName).mkString(", ")
                         }; preferring highest priority column"
                     )
                     .when(found.size > 1)
      location  <- ZIO
                     .fromOption(found.sortBy(loc => columnPriority(loc.column)).lastOption)
                     .orElseFail(BoardError.IssueNotFound(issueId.value))
    yield location

  private def listIssueDirectories(workspace: Path, column: BoardColumn): IO[BoardError, List[Path]] =
    val issuesDir = workspace.resolve(boardRootFolder).resolve(column.folderName)
    ZIO
      .attemptBlocking {
        if JFiles.exists(issuesDir) then
          JFiles
            .list(issuesDir)
            .iterator()
            .asScala
            .filter(path => JFiles.isDirectory(path))
            .toList
            .sortBy(_.getFileName.toString)
        else Nil
      }
      .mapError(err =>
        BoardError.ParseError(s"Unable to list column '${column.folderName}': ${err.getMessage}")
      )
      .flatMap { dirs =>
        ZIO
          .attemptBlocking(dirs.partition(d => JFiles.exists(d.resolve(issueMarkdownFile))))
          .mapError(err => BoardError.ParseError(s"Unable to check column '${column.folderName}': ${err.getMessage}"))
          .flatMap { case (valid, orphaned) =>
            ZIO
              .foreachDiscard(orphaned) { d =>
                ZIO.logWarning(
                  s"[board] Orphaned issue directory (no ISSUE.md) — skipping: ${d}. " +
                    s"This can happen when ISSUE.md is deleted without removing the parent directory (e.g. via 'git rm' without -r)."
                )
              }
              .as(valid)
          }
      }

  private def reconcileDuplicateIssuePlacements(workspacePath: String, workspace: Path): IO[BoardError, Unit] =
    val boardPath = workspacePath + "/" + boardRootFolder
    val boardRoot = workspace.resolve(boardRootFolder)
    for
      perColumn <- ZIO.foreach(columnsInOrder) { column =>
                     listIssueDirectories(workspace, column).map(dirs => column -> dirs)
                   }
      placements = perColumn.flatMap {
                     case (column, dirs) =>
                       dirs.map(dir => (dir.getFileName.toString, column, dir))
                   }
      duplicates = placements.groupBy(_._1).view.mapValues(_.toList).toMap.filter(_._2.size > 1)
      toRemove   = duplicates.values.toList.flatMap { entries =>
                     val keep = entries.maxBy { case (_, column, _) => columnPriority(column) }
                     entries.filterNot(_ == keep).map(_._3)
                   }
      _         <- ZIO
                     .logWarning(
                       s"[board] duplicate issue placements detected in $workspacePath; removing ${
                           toRemove.size
                         } stale directories"
                     )
                     .when(toRemove.nonEmpty)
      _         <- ZIO.foreachDiscard(toRemove) { dir =>
                     gitService
                       .rm(boardPath, relativize(boardRoot, dir), recursive = true)
                       .mapError(mapGitError("git rm"))
                   }
      _         <- gitService
                     .commit(boardPath, s"[board] Repair: deduplicate issue placements (${toRemove.size})")
                     .mapError(mapGitError("git commit"))
                     .when(toRemove.nonEmpty)
    yield ()

  private def issueExists(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Boolean] =
    locateIssue(workspacePath, issueId).as(true).catchSome { case _: BoardError.IssueNotFound => ZIO.succeed(false) }

  private def readIssueAt(column: BoardColumn, issueDirectory: Path): IO[BoardError, BoardIssue] =
    for
      raw                 <- readFile(issueDirectory.resolve(issueMarkdownFile))
      (frontmatter, body) <- parser.parse(raw)
    yield BoardIssue(
      frontmatter = frontmatter,
      body = body,
      column = column,
      directoryPath = issueDirectory.toString,
    )

  private def ensureWorkspaceDirectory(workspacePath: String): IO[BoardError, Path] =
    ZIO
      .attempt(Paths.get(workspacePath))
      .mapError(err => BoardError.BoardNotFound(s"$workspacePath (${err.getMessage})"))
      .flatMap { path =>
        ZIO
          .attemptBlocking(JFiles.exists(path) && JFiles.isDirectory(path))
          .mapError(err => BoardError.BoardNotFound(s"$workspacePath (${err.getMessage})"))
          .flatMap {
            case true  => ZIO.succeed(path)
            case false => ZIO.fail(BoardError.BoardNotFound(workspacePath))
          }
      }

  private def ensureBoardRoot(workspacePath: Path): IO[BoardError, Path] =
    val root = workspacePath.resolve(boardRootFolder)
    pathExists(root).flatMap {
      case true  => ZIO.succeed(root)
      case false => ZIO.fail(BoardError.BoardNotFound(workspacePath.toString))
    }

  private def issueDirectory(workspace: Path, column: BoardColumn, issueId: BoardIssueId): Path =
    workspace.resolve(boardRootFolder).resolve(column.folderName).resolve(issueId.value)

  private def columnPriority(column: BoardColumn): Int =
    columnsInOrder.indexOf(column)

  private def stageAndCommit(workspacePath: String, addPaths: List[String], commitMessage: String)
    : IO[BoardError, String] =
    for
      _   <- gitService.add(workspacePath, addPaths).mapError(mapGitError("git add"))
      sha <- gitService.commit(workspacePath, commitMessage).mapError(mapGitError("git commit"))
    yield sha

  private def pathExists(path: Path): IO[BoardError, Boolean] =
    ZIO.attemptBlocking(JFiles.exists(path)).mapError(err => BoardError.ParseError(err.getMessage))

  private def readFile(path: Path): IO[BoardError, String] =
    ZIO.attemptBlocking(JFiles.readString(path)).mapError(err =>
      BoardError.ParseError(s"${path.toString}: ${err.getMessage}")
    )

  private def writeFile(path: Path, content: String): IO[BoardError, Unit] =
    ZIO
      .attemptBlocking {
        val _ = JFiles.writeString(path, content)
      }
      .mapError(err => BoardError.WriteError(path.toString, err.getMessage))

  private def relativize(root: Path, path: Path): String =
    root.relativize(path).toString

  private def runGit(workspacePath: String, args: String*): IO[BoardError, String] =
    ZIO
      .attemptBlocking(Paths.get(workspacePath).toFile)
      .mapError(err => BoardError.GitOperationFailed("git", err.getMessage))
      .flatMap { directory =>
        Command("git", args*).workingDirectory(directory).string.mapError(err =>
          BoardError.GitOperationFailed(s"git ${args.mkString(" ")}", err.getMessage)
        )
      }

  private def mapGitError(operation: String)(error: GitError): BoardError =
    BoardError.GitOperationFailed(operation, error.toString)

object BoardRepositoryFS:
  val live: URLayer[IssueMarkdownParser & GitService, BoardRepository] =
    ZLayer.fromZIO {
      for
        parser <- ZIO.service[IssueMarkdownParser]
        git    <- ZIO.service[GitService]
        locks  <- Ref.make(Map.empty[String, Semaphore])
      yield BoardRepositoryFS(parser, git, locks)
    }
