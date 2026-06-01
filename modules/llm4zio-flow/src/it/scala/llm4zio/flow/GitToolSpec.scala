package llm4zio.flow

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

object GitToolSpec extends ZIOSpecDefault:

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
          dir <- tempDir
          git <- newRepo(dir)
          a   <- git.createBranch("feature/x")
          cur <- git.currentBranch
          b   <- git.createBranch("feature/x")
          _   <- git.checkout("main")
          onMain <- git.currentBranch
          _   <- git.checkoutOrCreate("feature/x") // existing → switches back
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
  ) @@ TestAspect.sequential
