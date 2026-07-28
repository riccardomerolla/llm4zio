package llm4zio.flow

import zio.*
import zio.test.*

import java.nio.file.{ Files, Path }

object GitToolSpec extends ZIOSpecDefault:

  // Privileged mint: integration specs exercise the tools directly with every capability granted.
  private given Caps.All = Caps.grantAll

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-git-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private def write(dir: Path, name: String, content: String): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking(Files.write(dir.resolve(name), content.getBytes))
      .mapError(e => FlowError.Persistence(s"write $name", Some(e)))
      .unit

  /** A temp repo with identity configured and one initial commit. */
  private def newRepo(dir: Path): IO[FlowError, GitTool] =
    val git = GitTool(dir)
    for
      _ <- git.init
      _ <- git.config("user.email", "test@example.com")
      _ <- git.config("user.name", "Test")
      _ <- git.config("commit.gpgsign", "false")
      _ <- write(dir, "README.md", "hello")
      _ <- git.commitAll("init")
    yield git

  def spec = suite("GitTool")(
    test("createBranch creates a branch, then reports AlreadyExists") {
      ZIO.scoped {
        for
          dir    <- tempDir
          git    <- newRepo(dir)
          a      <- git.createBranch("feature/x")
          cur    <- git.currentBranch
          b      <- git.createBranch("feature/x")
          _      <- git.checkout("main")
          onMain <- git.currentBranch
          _      <- git.checkoutOrCreate("feature/x") // existing → switches back
          back   <- git.currentBranch
        yield assertTrue(
          a == GitTool.CreateBranch.Created,
          cur == "feature/x",
          b == GitTool.CreateBranch.AlreadyExists,
          onMain == "main",
          back == "feature/x",
        )
      }
    },
    test("commitAll commits changes, then reports NothingToCommit on a clean tree") {
      ZIO.scoped {
        for
          dir <- tempDir
          git <- newRepo(dir)
          _   <- write(dir, "a.txt", "content")
          c1  <- git.commitAll("add a")
          c2  <- git.commitAll("noop")
        yield assertTrue(c1 == GitTool.Commit.Committed, c2 == GitTool.Commit.NothingToCommit)
      }
    },
    test("diff shows uncommitted changes to a tracked file") {
      ZIO.scoped {
        for
          dir <- tempDir
          git <- newRepo(dir)
          _   <- write(dir, "README.md", "changed body")
          d   <- git.diff
        yield assertTrue(d.contains("README.md"), d.contains("changed body"))
      }
    },
    test("push publishes the branch to a local bare remote") {
      ZIO.scoped {
        for
          dir    <- tempDir
          git    <- newRepo(dir)
          remote <- tempDir
          _      <- GitTool(remote).initBare
          _      <- git.addRemote("origin", remote.toString)
          _      <- git.push("origin", "main")
          refs   <- git.lsRemote("origin")
        yield assertTrue(refs.contains("refs/heads/main"))
      }
    },
    test("diffVsBase shows the branch delta vs base; changedFilesVsBase lists the file") {
      ZIO.scoped {
        for
          dir   <- tempDir
          git   <- newRepo(dir)
          _     <- git.createBranch("feat")
          _     <- write(dir, "README.md", "changed-content-xyz")
          _     <- git.commitAll("change")
          diff  <- git.diffVsBase("main")
          files <- git.changedFilesVsBase("main")
        yield assertTrue(
          diff.contains("changed-content-xyz"),
          diff.contains("README.md"),
          files == List("README.md"),
        )
      }
    },
    test("defaultBase falls back to main when there is no remote") {
      ZIO.scoped {
        for
          dir  <- tempDir
          git  <- newRepo(dir)
          base <- git.defaultBase
        yield assertTrue(base == "main")
      }
    },
    test("diffAll includes untracked files; diff does not; commitAll still commits cleanly after") {
      ZIO.scoped {
        for
          dir         <- tempDir
          git         <- newRepo(dir)
          _           <- write(dir, "NewFile.scala", "class NewFile { val x = 42 }")
          trackedDiff <- git.diff
          allDiff     <- git.diffAll
          committed   <- git.commitAll("add new file")
        yield assertTrue(
          !trackedDiff.contains("NewFile.scala"),
          !trackedDiff.contains("val x = 42"),
          allDiff.contains("NewFile.scala"),
          allDiff.contains("val x = 42"),
          committed == GitTool.Commit.Committed,
        )
      }
    },
  ) @@ TestAspect.sequential

