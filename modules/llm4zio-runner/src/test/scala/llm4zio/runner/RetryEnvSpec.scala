package llm4zio.runner

import zio.Scope
import zio.test.*

object RetryEnvSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("RetryEnv.parse")(
    test("unset, blank, or invalid → default (3)") {
      assertTrue(
        RetryEnv.parse(None) == 3,
        RetryEnv.parse(Some("")) == 3,
        RetryEnv.parse(Some("   ")) == 3,
        RetryEnv.parse(Some("abc")) == 3,
        RetryEnv.parse(Some("-1")) == 3,
      )
    },
    test("0 → fail fast (no retries)") {
      assertTrue(RetryEnv.parse(Some("0")) == 0)
    },
    test("a positive number → that many retries") {
      assertTrue(
        RetryEnv.parse(Some("5")) == 5,
        RetryEnv.parse(Some(" 2 ")) == 2,
      )
    },
  )
