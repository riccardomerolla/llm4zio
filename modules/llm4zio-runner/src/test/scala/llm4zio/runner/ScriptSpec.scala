package llm4zio.runner

import zio.*
import zio.test.*

object ScriptSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Llm4zio.script")(
    test("resolvePrompt prefers the first arg, falls back to the default, then to a usage error") {
      assertTrue(
        Llm4zio.resolvePrompt(List("do it", "extra"), Some("dflt")) == Right("do it"),
        Llm4zio.resolvePrompt(Nil, Some("dflt")) == Right("dflt"),
        Llm4zio.resolvePrompt(List("  "), Some("dflt")) == Right("dflt"),
        Llm4zio.resolvePrompt(Nil, None).isLeft,
      )
    },
    test("script fails with ScriptUsage before touching any connector when no prompt is available") {
      for exit <- Llm4zio.script(Nil, claude)(ZIO.unit).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Llm4zio.ScriptUsage]))
    },
  )
