package llm4zio.runner

import zio.test.*

import llm4zio.core.TokenUsage
import llm4zio.flow.FlowEvent
import zio.Scope

object VerbositySpec extends ZIOSpecDefault:
  private val tokens                                           = FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(1, 2, 3))
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Verbosity")(
    test("renders matrix") {
      assertTrue(
        // stages + abort + fail render at every level, including quiet
        Verbosity.Quiet.renders(FlowEvent.StageStarted("s")),
        Verbosity.Quiet.renders(FlowEvent.StageFailed("s", "x")),
        Verbosity.Quiet.renders(FlowEvent.Aborted("a")),
        // quiet hides prose/tool/info/tokens
        !Verbosity.Quiet.renders(FlowEvent.AssistantMessage("hi")),
        !Verbosity.Quiet.renders(FlowEvent.Info("i")),
        !Verbosity.Quiet.renders(FlowEvent.ToolUse("t", "a")),
        !Verbosity.Quiet.renders(tokens),
        // normal shows prose/tool/info, still hides tokens
        Verbosity.Normal.renders(FlowEvent.AssistantMessage("hi")),
        Verbosity.Normal.renders(FlowEvent.Info("i")),
        !Verbosity.Normal.renders(tokens),
        // verbose + debug show tokens
        Verbosity.Verbose.renders(tokens),
        Verbosity.Debug.renders(tokens),
      )
    },
    test("VerbosityEnv.parse") {
      assertTrue(
        VerbosityEnv.parse(None) == Verbosity.Normal,
        VerbosityEnv.parse(Some("  ")) == Verbosity.Normal,
        VerbosityEnv.parse(Some("nope")) == Verbosity.Normal,
        VerbosityEnv.parse(Some("QUIET")) == Verbosity.Quiet,
        VerbosityEnv.parse(Some(" verbose ")) == Verbosity.Verbose,
        VerbosityEnv.parse(Some("debug")) == Verbosity.Debug,
      )
    },
  )
