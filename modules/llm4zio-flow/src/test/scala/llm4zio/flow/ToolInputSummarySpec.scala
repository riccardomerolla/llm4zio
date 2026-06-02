package llm4zio.flow

import java.nio.file.Paths

import zio.test.*
import zio.Scope

object ToolInputSummarySpec extends ZIOSpecDefault:
  private val wd                                               = Paths.get("/repo")
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ToolInputSummary.summarise")(
    test("extracts file_path and wraps in parens") {
      assertTrue(ToolInputSummary.summarise("""{"file_path":"src/lib.rs"}""", 120, wd) == "(src/lib.rs)")
    },
    test("relativizes an absolute path against workDir") {
      assertTrue(ToolInputSummary.summarise("""{"file_path":"/repo/src/lib.rs"}""", 120, wd) == "(src/lib.rs)")
    },
    test("prefers command over description") {
      assertTrue(ToolInputSummary.summarise(
        """{"description":"run it","command":"cargo test"}""",
        120,
        wd,
      ) == "(cargo test)")
    },
    test("falls back to pattern then query then url") {
      assertTrue(
        ToolInputSummary.summarise("""{"pattern":"**/*.rs"}""", 120, wd) == "(**/*.rs)",
        ToolInputSummary.summarise("""{"query":"multiply"}""", 120, wd) == "(multiply)",
        ToolInputSummary.summarise("""{"url":"https://x.dev"}""", 120, wd) == "(https://x.dev)",
      )
    },
    test("collapses whitespace and truncates with ellipsis") {
      assertTrue(ToolInputSummary.summarise(
        s"""{"command":"a\n   ${"x" * 200}"}""",
        20,
        wd,
      ).length <= 22) // 20 + parens
    },
    test("returns empty string when no headline field present") {
      assertTrue(ToolInputSummary.summarise("""{"foo":"bar"}""", 120, wd) == "")
    },
    test("returns empty string on unparseable input") {
      assertTrue(ToolInputSummary.summarise("not json", 120, wd) == "")
    },
  )
