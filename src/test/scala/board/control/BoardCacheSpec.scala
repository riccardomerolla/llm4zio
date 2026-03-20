package board.control

import java.time.{ Duration, Instant }

import zio.*
import zio.test.*

import board.entity.*
import shared.ids.Ids.BoardIssueId
import workspace.control.GitService
import workspace.entity.*

object BoardCacheSpec extends ZIOSpecDefault:
  private val sampleIssue = BoardIssue(
    frontmatter = IssueFrontmatter(
      id = BoardIssueId("sample-1"),
      title = "Sample issue",
      priority = IssuePriority.Medium,
      assignedAgent = None,
      requiredCapabilities = List("scala"),
      blockedBy = Nil,
      tags = List("test"),
      acceptanceCriteria = List("works"),
      estimate = Some(IssueEstimate.S),
      proofOfWork = List("tests"),
      transientState = TransientState.None,
      branchName = None,
      failureReason = None,
      completedAt = None,
      createdAt = Instant.parse("2026-03-20T10:00:00Z"),
    ),
    body = "Body",
    column = BoardColumn.Todo,
    directoryPath = "/tmp/sample-1",
  )

  private val board = Board(
    workspacePath = "/tmp/workspace",
    columns = Map(
      BoardColumn.Backlog    -> Nil,
      BoardColumn.Todo       -> List(sampleIssue),
      BoardColumn.InProgress -> Nil,
      BoardColumn.Review     -> Nil,
      BoardColumn.Done       -> Nil,
      BoardColumn.Archive    -> Nil,
    ),
  )

  final private case class StubRepo(
    reads: Ref[Int],
    creates: Ref[Int],
    listCalls: Ref[Int],
  ) extends BoardRepository:
    override def initBoard(workspacePath: String): IO[BoardError, Unit]                                   = ZIO.unit
    override def readBoard(workspacePath: String): IO[BoardError, Board]                                  = reads.updateAndGet(_ + 1).as(board)
    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue]      =
      ZIO.fromOption(
        board.columns.values.flatten.find(_.frontmatter.id == issueId)
      ).orElseFail(BoardError.IssueNotFound(issueId.value))
    override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue)
      : IO[BoardError, BoardIssue] =
      creates.update(_ + 1).as(issue.copy(column = column))
    override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
      : IO[BoardError, BoardIssue] =
      ZIO.succeed(sampleIssue.copy(column = toColumn))
    override def updateIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      update: IssueFrontmatter => IssueFrontmatter,
    ): IO[BoardError, BoardIssue] =
      ZIO.succeed(sampleIssue.copy(frontmatter = update(sampleIssue.frontmatter)))
    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit]          = ZIO.unit
    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      listCalls.update(_ + 1) *> ZIO.succeed(board.columns.getOrElse(column, Nil))

  final private case class StubGit(ref: Ref[String]) extends GitService:
    override def status(repoPath: String): IO[GitError, GitStatus]                                         =
      ZIO.succeed(GitStatus("main", Nil, Nil, Nil))
    override def diff(repoPath: String, staged: Boolean): IO[GitError, GitDiff]                            = ZIO.succeed(GitDiff(Nil))
    override def diffStat(repoPath: String, staged: Boolean): IO[GitError, GitDiffStat]                    =
      ZIO.succeed(GitDiffStat(Nil))
    override def diffFile(repoPath: String, filePath: String, staged: Boolean): IO[GitError, String]       = ZIO.succeed("")
    override def log(repoPath: String, limit: Int): IO[GitError, List[GitLogEntry]]                        = ZIO.succeed(Nil)
    override def branchInfo(repoPath: String): IO[GitError, GitBranchInfo]                                 =
      ZIO.succeed(GitBranchInfo("main", List("main"), isDetached = false))
    override def showFile(repoPath: String, filePath: String, ref: String): IO[GitError, String]           = ZIO.succeed("")
    override def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind]              =
      ZIO.succeed(AheadBehind(0, 0))
    override def checkout(repoPath: String, branch: String): IO[GitError, Unit]                            = ZIO.unit
    override def add(repoPath: String, paths: List[String]): IO[GitError, Unit]                            = ZIO.unit
    override def mv(repoPath: String, from: String, to: String): IO[GitError, Unit]                        = ZIO.unit
    override def commit(repoPath: String, message: String): IO[GitError, String]                           = ZIO.succeed("sha")
    override def rm(repoPath: String, path: String, recursive: Boolean): IO[GitError, Unit]                = ZIO.unit
    override def mergeNoFastForward(repoPath: String, branch: String, message: String): IO[GitError, Unit] = ZIO.unit
    override def mergeAbort(repoPath: String): IO[GitError, Unit]                                          = ZIO.unit
    override def conflictedFiles(repoPath: String): IO[GitError, List[String]]                             = ZIO.succeed(Nil)
    override def headSha(repoPath: String): IO[GitError, String]                                           = ref.get
    override def showDiffStat(repoPath: String, ref: String): IO[GitError, GitDiffStat]                    =
      ZIO.succeed(GitDiffStat(Nil))

  private def makeHarness(ttl: Duration = Duration.ofSeconds(30))
    : UIO[(BoardCache, Ref[Int], Ref[Int], Ref[Int], Ref[String])] =
    for
      reads    <- Ref.make(0)
      creates  <- Ref.make(0)
      lists    <- Ref.make(0)
      gitHead  <- Ref.make("sha-1")
      cacheRef <- Ref.make(Map.empty[String, CachedBoard])
      repo      = StubRepo(reads, creates, lists)
      git       = StubGit(gitHead)
      cache     = BoardCache(repo, git, cacheRef, ttl)
    yield (cache, reads, creates, lists, gitHead)

  def spec: Spec[TestEnvironment, Any] = suite("BoardCacheSpec")(
    test("cache hit avoids re-reading board when HEAD and TTL are valid") {
      for
        (cache, reads, _, _, _) <- makeHarness()
        _                       <- cache.readBoard("/tmp/workspace")
        _                       <- cache.readBoard("/tmp/workspace")
        readCount               <- reads.get
      yield assertTrue(readCount == 1)
    },
    test("write-through invalidation on mutation forces next read to reload") {
      for
        (cache, reads, creates, _, _) <- makeHarness()
        _                             <- cache.readBoard("/tmp/workspace")
        _                             <- cache.createIssue("/tmp/workspace", BoardColumn.Todo, sampleIssue)
        _                             <- cache.readBoard("/tmp/workspace")
        readCount                     <- reads.get
        createCount                   <- creates.get
      yield assertTrue(readCount == 2, createCount == 1)
    },
    test("HEAD mismatch invalidates cached board") {
      for
        (cache, reads, _, _, gitHead) <- makeHarness()
        _                             <- cache.readBoard("/tmp/workspace")
        _                             <- gitHead.set("sha-2")
        _                             <- cache.readBoard("/tmp/workspace")
        readCount                     <- reads.get
      yield assertTrue(readCount == 2)
    },
    test("TTL expiry invalidates cached board") {
      for
        (cache, reads, _, _, _) <- makeHarness(ttl = Duration.ofMillis(100))
        _                       <- cache.readBoard("/tmp/workspace")
        _                       <- TestClock.adjust(200.millis)
        _                       <- cache.readBoard("/tmp/workspace")
        readCount               <- reads.get
      yield assertTrue(readCount == 2)
    },
    test("listIssues serves from board cache (lazy column load)") {
      for
        (cache, reads, _, lists, _) <- makeHarness()
        _                           <- cache.listIssues("/tmp/workspace", BoardColumn.Todo)
        _                           <- cache.listIssues("/tmp/workspace", BoardColumn.Todo)
        readCount                   <- reads.get
        listCalls                   <- lists.get
      yield assertTrue(
        readCount == 1,
        listCalls == 0,
      )
    },
  )
