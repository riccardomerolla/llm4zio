package board.control

import java.nio.file.{ Files as JFiles, Path }
import java.time.Instant

import zio.*
import zio.process.Command
import zio.test.*

import board.entity.*
import shared.ids.Ids.BoardIssueId
import workspace.control.GitServiceLive

object BoardRepositoryFSSpec extends ZIOSpecDefault:
  private val parser = IssueMarkdownParserLive()
  private val git    = GitServiceLive()

  private def deleteRecursively(path: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val stream = JFiles.walk(path)
      try
        stream.sorted(java.util.Comparator.reverseOrder()).forEach(p =>
          JFiles.deleteIfExists(p)
          ()
        )
      finally stream.close()
    }.unit

  private def runCmd(cwd: Path, args: String*): Task[String] =
    Command(args.head, args.drop(1)*).workingDirectory(cwd.toFile).string

  private def initRepo: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      for
        dir <- ZIO.attempt(JFiles.createTempDirectory("board-repo-fs-spec"))
        _   <- runCmd(dir, "git", "init")
        _   <- runCmd(dir, "git", "config", "user.name", "spec-user")
        _   <- runCmd(dir, "git", "config", "user.email", "spec@example.com")
      yield dir
    )(dir => deleteRecursively(dir).orDie)

  private def repository: UIO[BoardRepositoryFS] =
    Ref.make(Map.empty[String, Semaphore]).map(ref => BoardRepositoryFS(parser, git, ref))

  private def issue(id: String, title: String): BoardIssue =
    BoardIssue(
      frontmatter = IssueFrontmatter(
        id = BoardIssueId(id),
        title = title,
        priority = IssuePriority.Medium,
        assignedAgent = None,
        requiredCapabilities = List("scala", "zio"),
        blockedBy = Nil,
        tags = List("backend"),
        acceptanceCriteria = List("tests pass"),
        estimate = Some(IssueEstimate.M),
        proofOfWork = List("include unit tests"),
        transientState = TransientState.None,
        branchName = None,
        failureReason = None,
        completedAt = None,
        createdAt = Instant.parse("2026-03-20T10:00:00Z"),
      ),
      body = s"# $title\n\nImplement $title",
      column = BoardColumn.Backlog,
      directoryPath = "",
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("BoardRepositoryFSSpec")(
    test("initBoard creates .board structure and initial commit") {
      ZIO.scoped {
        for
          repoPath  <- initRepo
          repo      <- repository
          _         <- repo.initBoard(repoPath.toString)
          boardDir   = repoPath.resolve(".board")
          checks    <- ZIO.attempt(
                         (
                           JFiles.exists(boardDir.resolve("BOARD.md")),
                           JFiles.isDirectory(boardDir.resolve("backlog")),
                           JFiles.isDirectory(boardDir.resolve("todo")),
                           JFiles.isDirectory(boardDir.resolve("in-progress")),
                           JFiles.isDirectory(boardDir.resolve("review")),
                           JFiles.isDirectory(boardDir.resolve("done")),
                           JFiles.isDirectory(boardDir.resolve("archive")),
                         )
                       )
          boardLog  <- runCmd(repoPath.resolve(".board"), "git", "log", "--oneline", "-n", "1")
          gitignore <- ZIO.attemptBlocking(JFiles.readString(repoPath.resolve(".gitignore")))
        yield assertTrue(
          checks._1,
          boardLog.contains("[board] Init: board structure"),
          checks._2,
          checks._3,
          checks._4,
          checks._5,
          checks._6,
          checks._7,
          gitignore.linesIterator.exists(_.trim == "/.board/"),
        )
      }
    },
    test("full CRUD operations persist and produce expected commit messages") {
      ZIO.scoped {
        for
          repoPath <- initRepo
          repo     <- repository
          _        <- repo.initBoard(repoPath.toString)
          created  <-
            repo.createIssue(repoPath.toString, BoardColumn.Todo, issue("fix-auth-timeout", "Fix auth timeout"))
          moved    <- repo.moveIssue(repoPath.toString, BoardIssueId("fix-auth-timeout"), BoardColumn.InProgress)
          updated  <- repo.updateIssue(
                        repoPath.toString,
                        BoardIssueId("fix-auth-timeout"),
                        _.copy(
                          priority = IssuePriority.High,
                          transientState = TransientState.Merging(Instant.parse("2026-03-20T11:00:00Z")),
                        ),
                      )
          _        <- repo.deleteIssue(repoPath.toString, BoardIssueId("fix-auth-timeout"))
          backlog  <- repo.listIssues(repoPath.toString, BoardColumn.Backlog)
          todo     <- repo.listIssues(repoPath.toString, BoardColumn.Todo)
          logs     <- runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s", "-n", "5")
        yield assertTrue(
          created.column == BoardColumn.Todo,
          moved.column == BoardColumn.InProgress,
          updated.frontmatter.priority == IssuePriority.High,
          backlog.isEmpty,
          todo.isEmpty,
          logs.contains("[board] Create: Fix auth timeout"),
          logs.contains("[board] Move: fix-auth-timeout todo -> in-progress"),
          logs.contains("[board] Update: fix-auth-timeout"),
          logs.contains("[board] Delete: fix-auth-timeout"),
        )
      }
    },
    test("concurrent createIssue operations are serialized by workspace lock") {
      ZIO.scoped {
        for
          repoPath <- initRepo
          repo     <- repository
          _        <- repo.initBoard(repoPath.toString)
          _        <- ZIO.collectAllParDiscard(
                        List(
                          repo.createIssue(repoPath.toString, BoardColumn.Todo, issue("task-a", "Task A")),
                          repo.createIssue(repoPath.toString, BoardColumn.Todo, issue("task-b", "Task B")),
                        )
                      )
          todo     <- repo.listIssues(repoPath.toString, BoardColumn.Todo)
          logCount <-
            runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s").map(_.linesIterator.count(_.contains("[board] Create:")))
        yield assertTrue(
          todo.map(_.frontmatter.id.value).toSet == Set("task-a", "task-b"),
          logCount == 2,
        )
      }
    },
    test("readBoard repairs duplicate issue placement across columns and commits repair") {
      ZIO.scoped {
        for
          repoPath <- initRepo
          repo     <- repository
          _        <- repo.initBoard(repoPath.toString)
          _        <- repo.createIssue(repoPath.toString, BoardColumn.Todo, issue("dup-issue", "Duplicate Issue"))
          _        <- ZIO.attemptBlocking {
                        val todoDir   = repoPath.resolve(".board").resolve("todo").resolve("dup-issue")
                        val reviewDir = repoPath.resolve(".board").resolve("review").resolve("dup-issue")
                        val _         = JFiles.createDirectories(reviewDir)
                        val _         = JFiles.copy(
                          todoDir.resolve("ISSUE.md"),
                          reviewDir.resolve("ISSUE.md"),
                          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                      }
          _        <- runCmd(repoPath.resolve(".board"), "git", "add", "review/dup-issue")
          _        <- runCmd(repoPath.resolve(".board"), "git", "commit", "-m", "simulate duplicate placement from merge")
          board    <- repo.readBoard(repoPath.toString)
          todo      = board.columns.getOrElse(BoardColumn.Todo, Nil).map(_.frontmatter.id.value)
          review    = board.columns.getOrElse(BoardColumn.Review, Nil).map(_.frontmatter.id.value)
          logs     <- runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s", "-n", "1")
        yield assertTrue(
          !todo.contains("dup-issue"),
          review.contains("dup-issue"),
          logs.contains("[board] Repair: deduplicate issue placements"),
        )
      }
    },
    test("initBoard is idempotent — second call does not create extra commits") {
      ZIO.scoped {
        for
          repoPath  <- initRepo
          repo      <- repository
          _         <- repo.initBoard(repoPath.toString)
          _         <- repo.initBoard(repoPath.toString)
          logCount  <- runCmd(repoPath.resolve(".board"), "git", "log", "--pretty=%s")
                         .map(_.linesIterator.count(_.contains("[board] Init:")))
          gitignore <- ZIO.attemptBlocking(JFiles.readString(repoPath.resolve(".gitignore")))
        yield assertTrue(
          logCount == 1,
          gitignore.linesIterator.count(_.trim == "/.board/") == 1,
        )
      }
    },
    test("updateGitignore does not add duplicate /.board/ entry when already present") {
      ZIO.scoped {
        for
          repoPath  <- initRepo
          repo      <- repository
          _         <- ZIO.attemptBlocking(
                         JFiles.writeString(repoPath.resolve(".gitignore"), "/.board/\n")
                       )
          _         <- repo.initBoard(repoPath.toString)
          gitignore <- ZIO.attemptBlocking(JFiles.readString(repoPath.resolve(".gitignore")))
        yield assertTrue(
          gitignore.linesIterator.count(_.trim == "/.board/") == 1,
        )
      }
    },
  )
