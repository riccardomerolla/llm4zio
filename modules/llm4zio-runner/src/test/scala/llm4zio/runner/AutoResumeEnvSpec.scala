package llm4zio.runner

import zio.Scope
import zio.test.*

object AutoResumeEnvSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("AutoResumeEnv")(
    test("unset / blank / invalid / negative → default (2)") {
      assertTrue(
        AutoResumeEnv.parse(None) == 2,
        AutoResumeEnv.parse(Some("  ")) == 2,
        AutoResumeEnv.parse(Some("nope")) == 2,
        AutoResumeEnv.parse(Some("-1")) == 2,
      )
    },
    test("a valid non-negative int is used (0 disables)") {
      assertTrue(AutoResumeEnv.parse(Some("0")) == 0, AutoResumeEnv.parse(Some("5")) == 5)
    },
  )
