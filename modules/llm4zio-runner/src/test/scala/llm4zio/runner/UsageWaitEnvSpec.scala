package llm4zio.runner

import zio.*
import zio.test.*

import llm4zio.flow.UsageLimitPolicy

object UsageWaitEnvSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & TestEnvironment, Any] = suite("UsageWaitEnv.parse")(
    test("unset or 'off' → off") {
      assertTrue(
        UsageWaitEnv.parse(None) == UsageLimitPolicy.off,
        UsageWaitEnv.parse(Some("off")) == UsageLimitPolicy.off,
      )
    },
    test("'4h' → enabled with a 4h cap") {
      val p = UsageWaitEnv.parse(Some("4h"))
      assertTrue(p.enabled, p.maxWait == 4.hours)
    },
    test("'90m' → enabled with a 90m cap") {
      assertTrue(UsageWaitEnv.parse(Some("90m")).maxWait == 90.minutes)
    },
    test("bare 'on'/'true' → patient default") {
      assertTrue(UsageWaitEnv.parse(Some("on")) == UsageLimitPolicy.patient)
    },
  )
