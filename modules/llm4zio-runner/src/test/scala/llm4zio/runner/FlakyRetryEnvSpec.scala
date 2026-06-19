package llm4zio.runner

import zio.Scope
import zio.test.*

object FlakyRetryEnvSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlakyRetryEnv")(
    test("unset / blank / invalid → default (6)") {
      assertTrue(
        FlakyRetryEnv.parse(None) == 6,
        FlakyRetryEnv.parse(Some("  ")) == 6,
        FlakyRetryEnv.parse(Some("nope")) == 6,
        FlakyRetryEnv.parse(Some("-1")) == 6,
      )
    },
    test("a valid non-negative int is used") {
      assertTrue(FlakyRetryEnv.parse(Some("0")) == 0, FlakyRetryEnv.parse(Some("10")) == 10)
    },
  )
