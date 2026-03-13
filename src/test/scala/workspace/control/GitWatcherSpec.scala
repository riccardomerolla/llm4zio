package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import workspace.entity.*

object GitWatcherSpec extends ZIOSpecDefault:

  private val statusA = GitStatus(
    branch = "feature/a",
    staged = List(FileChange("A.scala", ChangeStatus.Modified)),
    unstaged = Nil,
    untracked = Nil,
  )

  private val statusB = GitStatus(
    branch = "feature/a",
    staged = List(FileChange("A.scala", ChangeStatus.Modified), FileChange("B.scala", ChangeStatus.Added)),
    unstaged = Nil,
    untracked = Nil,
  )

  private val commitA = GitLogEntry(
    hash = "hash-a",
    shortHash = "hash-a",
    author = "ric",
    message = "first",
    date = Instant.parse("2026-03-02T10:00:00Z"),
  )

  private val commitB = GitLogEntry(
    hash = "hash-b",
    shortHash = "hash-b",
    author = "ric",
    message = "second",
    date = Instant.parse("2026-03-02T10:01:00Z"),
  )

  final private case class StubGitService(
    statusRef: Ref[GitStatus],
    logRef: Ref[List[GitLogEntry]],
  ) extends GitService:
    override def status(repoPath: String): IO[GitError, GitStatus]                                         = statusRef.get
    override def diff(repoPath: String, staged: Boolean): IO[GitError, GitDiff]                            = ZIO.succeed(GitDiff(Nil))
    override def diffStat(repoPath: String, staged: Boolean): IO[GitError, GitDiffStat]                    = ZIO.succeed(GitDiffStat(Nil))
    override def diffFile(repoPath: String, filePath: String, staged: Boolean): IO[GitError, String]       = ZIO.succeed("")
    override def log(repoPath: String, limit: Int): IO[GitError, List[GitLogEntry]]                        = logRef.get.map(_.take(limit))
    override def branchInfo(repoPath: String): IO[GitError, GitBranchInfo]                                 = ZIO.succeed(
      GitBranchInfo("feature/a", List("main", "feature/a"), isDetached = false)
    )
    override def showFile(repoPath: String, filePath: String, ref: String): IO[GitError, String]           = ZIO.succeed("")
    override def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind]              = ZIO.succeed(
      AheadBehind(ahead = 0, behind = 0)
    )
    override def checkout(repoPath: String, branch: String): IO[GitError, Unit]                            = ZIO.unit
    override def mergeNoFastForward(repoPath: String, branch: String, message: String): IO[GitError, Unit] =
      ZIO.unit

  def spec: Spec[TestEnvironment, Any] = suite("GitWatcherSpec")(
    test("publishes status and commit updates when values change") {
      for
        statusRef <- Ref.make(statusA)
        logRef    <- Ref.make(List(commitA))
        service    = StubGitService(statusRef, logRef)
        watcher   <- GitWatcherLive.make(service, pollInterval = 100.millis)
        _         <- watcher.registerRun("run-1", "/tmp/repo")
        result    <- ZIO.scoped {
                       for
                         queue <- watcher.subscribe("run-1")
                         _     <- TestClock.adjust(150.millis)
                         e1    <- queue.take.timeoutFail("missing initial status")(1.second)
                         e2    <- queue.take.timeoutFail("missing initial commit")(1.second)
                         _     <- statusRef.set(statusB)
                         _     <- logRef.set(List(commitB))
                         _     <- TestClock.adjust(150.millis)
                         e3    <- queue.take.timeoutFail("missing changed status")(1.second)
                         e4    <- queue.take.timeoutFail("missing changed commit")(1.second)
                       yield List(e1, e2, e3, e4)
                     }
        _         <- watcher.unregisterRun("run-1")
      yield assertTrue(
        result.exists {
          case GitWatcherEvent.StatusUpdated(s) => s == statusA
          case _                                => false
        },
        result.exists {
          case GitWatcherEvent.NewCommit(c) => c.hash == "hash-a"
          case _                            => false
        },
        result.exists {
          case GitWatcherEvent.StatusUpdated(s) => s == statusB
          case _                                => false
        },
        result.exists {
          case GitWatcherEvent.NewCommit(c) => c.hash == "hash-b"
          case _                            => false
        },
      )
    }
  )
