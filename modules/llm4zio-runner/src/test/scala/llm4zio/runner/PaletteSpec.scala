package llm4zio.runner

import zio.Scope
import zio.test.*

object PaletteSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Palette")(
    test("plain palette returns text unchanged (no escapes)") {
      val p = Palette(enabled = false)
      assertTrue(p.stageStart("branch") == "▶ branch", !p.stageStart("branch").contains("\u001b"))
    },
    test("colored palette wraps text in ANSI escapes") {
      val p   = Palette(enabled = true)
      val out = p.stageStart("branch")
      assertTrue(out.contains("["), out.contains("branch"))
    },
    test("toolCall styles name and args, plain mode is readable") {
      val p = Palette(enabled = false)
      assertTrue(p.toolCall("Edit", "(src/lib.rs)") == "● Edit (src/lib.rs)")
    },
    test("toolCall with empty args omits the trailing space") {
      assertTrue(Palette(enabled = false).toolCall("Read", "") == "● Read")
    },
  )
