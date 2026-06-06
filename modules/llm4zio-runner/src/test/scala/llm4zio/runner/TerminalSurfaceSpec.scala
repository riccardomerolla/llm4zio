package llm4zio.runner

import zio.*
import zio.test.*

object TerminalSurfaceSpec extends ZIOSpecDefault:
  // A genuinely ANSI-styled line (real ESC codes), built via fansi so we never type ESC in source.
  private val styled = fansi.Color.Red("boom").render

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("TerminalSurface")(
    test("stripAnsi removes SGR colour codes, leaving plain text") {
      assertTrue(
        styled != "boom", // sanity: the input really is styled
        TerminalSurface.stripAnsi(styled) == "boom",
      )
    },
    test("teeingToLog writes the styled line to the surface AND a stripped line to the log") {
      (for
        recorded  <- Ref.make(List.empty[String])
        underlying = new TerminalSurface:
                       def log(line: String): UIO[Unit]                       = recorded.update(_ :+ line)
                       def setStatus(label: Option[String]): UIO[Unit]        = ZIO.unit
                       def suspend[R, E, A](read: ZIO[R, E, A]): ZIO[R, E, A] = read
        _         <- TerminalSurface.teeingToLog(underlying).log(styled)
        lines     <- recorded.get
        logged    <- ZTestLogger.logOutput
      yield assertTrue(
        lines == List(styled),                // terminal keeps the colour
        logged.exists(_.message() == "boom"), // log gets the line
        logged.forall(e => e.message() == TerminalSurface.stripAnsi(e.message())), // …with ANSI stripped
      )).provideLayer(ZTestLogger.default)
    },
  )
