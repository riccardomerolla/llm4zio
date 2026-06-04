package llm4zio.flow

import zio.test.*

import llm4zio.core.LlmError

object FlowErrorSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & TestEnvironment, Any] = suite("FlowError.Llm")(
    test("carries an optional typed LlmError cause; default None") {
      val plain  = FlowError.Llm("boom")
      val caused = FlowError.Llm("boom", Some(LlmError.UsageLimitError(None, "codex", "limit")))
      assertTrue(
        plain.cause.isEmpty,
        plain.message == "boom",
        caused.cause.exists(_.isInstanceOf[LlmError.UsageLimitError]),
      )
    }
  )
