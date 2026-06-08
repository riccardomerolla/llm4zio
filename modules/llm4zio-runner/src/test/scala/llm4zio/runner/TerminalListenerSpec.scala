package llm4zio.runner

import zio.*
import zio.test.*

import llm4zio.core.TokenUsage
import llm4zio.flow.{ FlowEvent, FlowEvents }

object TerminalListenerSpec extends ZIOSpecDefault:
  private val p                                                = Palette(enabled = false)
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("TerminalListener.line")(
    test("renders each event type in plain mode") {
      assertTrue(
        TerminalListener.line(FlowEvent.StageStarted("branch"), p) == "▶ branch",
        TerminalListener.line(FlowEvent.StageCompleted("branch"), p) == "✔ branch",
        TerminalListener.line(FlowEvent.StageFailed("push", "boom"), p) == "✖ push — boom",
        TerminalListener.line(FlowEvent.Aborted("stop"), p) == "✖ aborted: stop",
        TerminalListener.line(FlowEvent.Info("hi"), p) == "· hi",
        TerminalListener.line(FlowEvent.ToolUse("Edit", "(src/lib.rs)"), p) == "● Edit (src/lib.rs)",
        TerminalListener.line(FlowEvent.AssistantMessage("done"), p) == "● done",
      )
    },
    test("AssistantMessage preserves the model's line breaks (trimmed at the ends)") {
      assertTrue(TerminalListener.line(FlowEvent.AssistantMessage("\na\nb\n  c\n"), p) == "● a\nb\n  c")
    },
    test("indentBlock prefixes a single line and hang-indents continuation lines") {
      assertTrue(
        TerminalListener.indentBlock(1, "● done") == "  ● done",
        TerminalListener.indentBlock(2, "● a\nb\nc") == "    ● a\n      b\n      c",
        TerminalListener.indentBlock(0, "✔ x") == "✔ x",
      )
    },
    test("TokensUsed renders nothing inline (consumed by CostTracker)") {
      assertTrue(TerminalListener.line(FlowEvent.TokensUsed("coder", None, TokenUsage(1, 1, 2)), p).isEmpty)
    },
    test("indentDepths increments on start, decrements on completion/fail/abort") {
      val events = Chunk(
        FlowEvent.StageStarted("a"),
        FlowEvent.Info("x"),
        FlowEvent.StageStarted("b"),
        FlowEvent.ToolUse("Read", ""),
        FlowEvent.StageCompleted("b"),
        FlowEvent.StageCompleted("a"),
      )
      assertTrue(TerminalListener.indentDepths(events) == Chunk(0, 1, 1, 2, 1, 0))
    },
    test("awaitDrained renders a failure published just before teardown — no silent drop") {
      // Reproduces the silent-error bug: a StageFailed published right before the scope closes must still render.
      def recording(ref: Ref[List[String]]): TerminalSurface = new TerminalSurface:
        def log(line: String): UIO[Unit]                       = ref.update(_ :+ line)
        def setStatus(label: Option[String]): UIO[Unit]        = ZIO.unit
        def suspend[R, E, A](read: ZIO[R, E, A]): ZIO[R, E, A] = read
      ZIO.scoped {
        for
          hub      <- FlowEvents.hub()
          recorded <- Ref.make(List.empty[String])
          consumed <- TerminalListener.consumeTo(hub, p, recording(recorded))
          _        <- (hub: FlowEvents).publish(FlowEvent.StageStarted("task"))
          _        <- (hub: FlowEvents).publish(FlowEvent.StageFailed("task", "boom"))
          _        <- TerminalListener.awaitDrained(hub, consumed, 3.seconds)
          lines    <- recorded.get
        yield assertTrue(lines.exists(_.contains("✖ task — boom")))
      }
    } @@ TestAspect.withLiveClock, // awaitDrained polls with real ZIO.sleep; don't run it on the virtual TestClock
  )
