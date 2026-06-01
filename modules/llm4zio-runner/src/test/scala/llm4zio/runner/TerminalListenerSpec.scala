package llm4zio.runner

import zio.test.*

import llm4zio.flow.FlowEvent

object TerminalListenerSpec extends ZIOSpecDefault:
  def spec = suite("TerminalListener.render")(
    test("renders each event kind, surfacing its salient text") {
      assertTrue(
        TerminalListener.render(FlowEvent.StageStarted("build")).contains("build"),
        TerminalListener.render(FlowEvent.StageCompleted("build")).contains("build"),
        TerminalListener.render(FlowEvent.StageFailed("build", "boom")).contains("boom"),
        TerminalListener.render(FlowEvent.Aborted("stop")).toLowerCase.contains("abort"),
        TerminalListener.render(FlowEvent.Info("note")).contains("note"),
      )
    }
  )
