package llm4zio.modernize

import zio.Scope
import zio.test.*

import llm4zio.flow.*

object ProgramJudgeSpec extends ZIOSpecDefault:

  private def packWith(template: Option[String]): Pack =
    Pack(
      name = "test",
      source = "cobol",
      scaffold = None,
      sources = None,
      programs = None,
      programFiles = template,
      specsDir = "docs/specs",
      featuresDir = "features",
      gates = Map.empty,
      replay = None,
      equivalence = ComparisonPolicy.default,
      judgeDimensions = Nil,
      coverage = Nil,
      survey = Nil,
      prompts = Map.empty,
      lenses = Nil,
      lessons = None,
      dir = java.nio.file.Path.of("."),
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ProgramJudge")(
    test("groupFiles assigns changed files to programs and collects the remainder") {
      val pack                    = packWith(None) // default: case-insensitive name match
      val changed                 = List(
        "src/main/java/Acctxfr.java",
        "src/main/java/AcctxfrService.java",
        "src/main/java/Balinq.java",
        "src/main/java/CommonUtil.java",
        "pom.xml",
      )
      val (byProgram, unassigned) = ProgramJudge.groupFiles(pack, List("ACCTXFR", "BALINQ"), changed)
      assertTrue(
        byProgram("ACCTXFR").toSet == Set("src/main/java/Acctxfr.java", "src/main/java/AcctxfrService.java"),
        byProgram("BALINQ") == List("src/main/java/Balinq.java"),
        unassigned.toSet == Set("src/main/java/CommonUtil.java", "pom.xml"),
      )
    },
    test("groupFiles honours a pack's programFiles template") {
      val pack                    = packWith(Some("""src/main/java/.*<NAME>.*\.java"""))
      val changed                 = List("src/main/java/ACCTXFR.java", "docs/ACCTXFR.md")
      val (byProgram, unassigned) = ProgramJudge.groupFiles(pack, List("ACCTXFR"), changed)
      assertTrue(
        byProgram("ACCTXFR") == List("src/main/java/ACCTXFR.java"),
        unassigned == List("docs/ACCTXFR.md"),
      )
    },
    test("groupFiles assigns a file matching two programs to both") {
      val pack                    = packWith(None)
      val changed                 = List("src/main/java/AcctxfrBalinqBridge.java")
      val (byProgram, unassigned) = ProgramJudge.groupFiles(pack, List("ACCTXFR", "BALINQ"), changed)
      assertTrue(
        byProgram("ACCTXFR") == changed,
        byProgram("BALINQ") == changed,
        unassigned.isEmpty,
      )
    },
  )
