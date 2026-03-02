package workspace.control

import java.time.Instant

import zio.test.*

import workspace.entity.{ AheadBehind, ChangeStatus }

object GitServiceSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("GitServiceSpec")(
    test("parseStatusPorcelain extracts branch and file buckets") {
      val raw = List(
        "# branch.oid 12dfe9",
        "# branch.head feature/run-ui",
        "1 M. N... 100644 100644 100644 abcdef abcdef src/main/scala/Foo.scala",
        "1 .M N... 100644 100644 100644 abcdef abcdef src/main/scala/Bar.scala",
        "2 R. N... 100644 100644 100644 abcdef abcdef R100 src/main/scala/New.scala\tsrc/main/scala/Old.scala",
        "? README.md",
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
        parsed.exists(_.untracked == List("README.md")),
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
  )
