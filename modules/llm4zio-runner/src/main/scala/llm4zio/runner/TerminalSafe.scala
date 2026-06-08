package llm4zio.runner

/** Strip terminal control sequences from untrusted text (backend stderr, tool output, assistant/user text) before it's
  * styled and written to the terminal — so a crafted byte stream can't move the cursor, clear the screen, set the
  * title, or corrupt the renderer. Tabs and newlines are preserved; everything else in the control range, plus ANSI
  * CSI/OSC escapes, is dropped.
  */
object TerminalSafe:
  // ANSI CSI (ESC [ … final), OSC (ESC ] … BEL|ST), and other two-byte ESC sequences. \x1b = ESC, \x07 = BEL.
  private val escapes  = "\\x1b\\[[0-9;?]*[ -/]*[@-~]|\\x1b\\][^\\x07\\x1b]*(?:\\x07|\\x1b\\\\)|\\x1b[@-Z\\\\-_]".r
  // C0/C1 control bytes and DEL, EXCEPT tab (\x09) and newline (\x0a).
  private val controls = "[\\x00-\\x08\\x0b-\\x1f\\x7f]".r

  def sanitize(s: String): String =
    if s.isEmpty then s else controls.replaceAllIn(escapes.replaceAllIn(s, ""), "")
