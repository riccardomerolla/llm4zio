package llm4zio.runner

import zio.Scope
import zio.test.*

object TraceKeepEnvSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("TraceKeepEnv")(
    test("unset / blank / invalid / negative → default (20)") {
      assertTrue(
        TraceKeepEnv.parse(None) == 20,
        TraceKeepEnv.parse(Some("  ")) == 20,
        TraceKeepEnv.parse(Some("nope")) == 20,
        TraceKeepEnv.parse(Some("-1")) == 20,
      )
    },
    test("a valid non-negative int is used") {
      assertTrue(TraceKeepEnv.parse(Some("0")) == 0, TraceKeepEnv.parse(Some("50")) == 50)
    },
  )
