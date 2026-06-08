package llm4zio.runner

import zio.Scope
import zio.test.*

object TerminalSafeSpec extends ZIOSpecDefault:
  private val ESC = 27.toChar.toString
  private val BEL = 7.toChar.toString
  private val TAB = 9.toChar.toString
  private val NL  = 10.toChar.toString

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("TerminalSafe.sanitize")(
    test("strips CSI colour and cursor/clear sequences") {
      val in = s"${ESC}[31mred${ESC}[0m${ESC}[2J${ESC}[1;1Hhi"
      assertTrue(TerminalSafe.sanitize(in) == "redhi")
    },
    test("strips OSC title-set sequences (BEL- and ST-terminated)") {
      val in = s"${ESC}]0;pwned${BEL}text${ESC}]2;again${ESC}\\end"
      assertTrue(TerminalSafe.sanitize(in) == "textend")
    },
    test("drops control bytes but preserves tab and newline") {
      val in = "a" + 0.toChar + "b" + 7.toChar + "c" + TAB + "d" + NL + "e" + 127.toChar
      assertTrue(TerminalSafe.sanitize(in) == "abc" + TAB + "d" + NL + "e")
    },
    test("leaves ordinary text (including parens/paths) untouched") {
      assertTrue(
        TerminalSafe.sanitize("● read_file (src/lib.rs)") == "● read_file (src/lib.rs)",
        TerminalSafe.sanitize("") == "",
      )
    },
  )
