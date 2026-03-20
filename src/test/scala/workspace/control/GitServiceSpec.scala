package workspace.control

import java.nio.file.{ Files as JFiles, Path }
import java.time.Instant

import zio.*
import zio.process.Command
import zio.test.*

import workspace.entity.{ AheadBehind, ChangeStatus }

object GitServiceSpec extends ZIOSpecDefault:

  private def deleteRecursively(path: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val stream = JFiles.walk(path)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p =>
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
        dir <- ZIO.attempt(JFiles.createTempDirectory("git-service-spec"))
        _   <- runCmd(dir, "git", "init")
        _   <- runCmd(dir, "git", "config", "user.name", "spec-user")
        _   <- runCmd(dir, "git", "config", "user.email", "spec@example.com")
      yield dir
    )(dir => deleteRecursively(dir).orDie)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GitServiceSpec")(
    test("parseStatusPorcelain extracts branch and file buckets") {
      val raw = List(
        "# branch.oid 12dfe9",
        "# branch.head feature/run-ui",
        "1 M. N... 100644 100644 100644 abcdef abcdef src/main/scala/Foo.scala",
        "1 .M N... 100644 100644 100644 abcdef abcdef src/main/scala/Bar.scala",
        "2 R. N... 100644 100644 100644 abcdef abcdef R100 src/main/scala/New.scala\tsrc/main/scala/Old.scala",
        "? README.md",
        "? src/main.rs",
      ).mkString("\n")

      val parsed = GitParsers.parseStatusPorcelain(raw)
      assertTrue(
        parsed.isRight,
        parsed.exists(_.branch == "feature/run-ui"),
        parsed.exists(_.staged.exists(fc =>
          fc.path == "src/main/scala/Foo.scala" && fc.status == ChangeStatus.Modified
        )),
        parsed.exists(_.unstaged.exists(fc =>
          fc.path == "src/main/scala/Bar.scala" && fc.status == ChangeStatus.Modified
        )),
        parsed.exists(_.staged.exists(fc =>
          fc.path == "src/main/scala/New.scala" && fc.status == ChangeStatus.Renamed
        )),
        parsed.exists(_.untracked == List("README.md", "src/main.rs")),
      )
    },
    test("parseDiffNumStat handles numeric and binary lines") {
      val raw = List(
        "12\t3\tsrc/main/scala/Foo.scala",
        "-\t-\tassets/logo.png",
      ).mkString("\n")

      val parsed = GitParsers.parseDiffNumStat(raw)
      assertTrue(
        parsed.isRight,
        parsed.exists(_.files.length == 2),
        parsed.exists(_.files.head.path == "src/main/scala/Foo.scala"),
        parsed.exists(_.files.head.additions == 12),
        parsed.exists(stat => stat.files(1).additions == 0 && stat.files(1).deletions == 0),
      )
    },
    test("parseLog decodes custom git format") {
      val ts  = "2026-03-02T08:00:00Z"
      val raw = s"""abc123def${"\u001f"}abc123d${"\u001f"}riccardo${"\u001f"}feat: add service${"\u001f"}$ts"""

      val parsed = GitParsers.parseLog(raw)
      assertTrue(
        parsed.isRight,
        parsed.exists(_.head.hash == "abc123def"),
        parsed.exists(_.head.shortHash == "abc123d"),
        parsed.exists(_.head.author == "riccardo"),
        parsed.exists(_.head.message == "feat: add service"),
        parsed.exists(_.head.date == Instant.parse(ts)),
      )
    },
    test("parseAheadBehind maps rev-list counts") {
      val parsed = GitParsers.parseAheadBehind("2 5")
      assertTrue(
        parsed == Right(AheadBehind(ahead = 5, behind = 2))
      )
    },
    test("add and commit stage file and return HEAD sha") {
      ZIO.scoped {
        for
          repo   <- initRepo
          service = GitServiceLive()
          _      <- ZIO.attempt(JFiles.writeString(repo.resolve("README.md"), "hello\n"))
          _      <- service.add(repo.toString, List("README.md"))
          sha    <- service.commit(repo.toString, "feat: add readme")
          listed <- runCmd(repo, "git", "show", "--name-only", "--format=", sha)
        yield assertTrue(
          sha.matches("^[0-9a-f]{40}$"),
          listed.linesIterator.exists(_.trim == "README.md"),
        )
      }
    },
    test("mv stages rename") {
      ZIO.scoped {
        for
          repo   <- initRepo
          service = GitServiceLive()
          _      <- ZIO.attempt(JFiles.writeString(repo.resolve("before.txt"), "before\n"))
          _      <- service.add(repo.toString, List("before.txt"))
          _      <- service.commit(repo.toString, "chore: add before")
          _      <- service.mv(repo.toString, "before.txt", "after.txt")
          status <- service.status(repo.toString)
        yield assertTrue(
          status.staged.exists(change => change.path == "after.txt" && change.status == ChangeStatus.Renamed)
        )
      }
    },
    test("rm recursive removes tracked directory") {
      ZIO.scoped {
        for
          repo   <- initRepo
          service = GitServiceLive()
          _      <- ZIO.attempt(JFiles.createDirectories(repo.resolve("nested")))
          _      <- ZIO.attempt(JFiles.writeString(repo.resolve("nested/file.txt"), "nested\n"))
          _      <- service.add(repo.toString, List("nested/file.txt"))
          _      <- service.commit(repo.toString, "chore: add nested file")
          _      <- service.rm(repo.toString, "nested", recursive = true)
          status <- service.status(repo.toString)
        yield assertTrue(
          status.staged.exists(change => change.path == "nested/file.txt" && change.status == ChangeStatus.Deleted)
        )
      }
    },
  )
