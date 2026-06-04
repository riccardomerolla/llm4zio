package llm4zio.core

import zio.durationInt
import zio.test.*

object ErrorsSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & TestEnvironment, Any] = suite("LlmError")(
    test("is a pure ADT — not a Throwable") {
      val e: LlmError = LlmError.ProviderError("boom")
      assertTrue(!e.isInstanceOf[Throwable])
    },
    test("every case renders a non-empty message") {
      val errs = List[LlmError](
        LlmError.ProviderError("provider down"),
        LlmError.AuthenticationError("bad key"),
        LlmError.InvalidRequestError("nope"),
        LlmError.ParseError("not json", "<<<"),
        LlmError.ToolError("Bash", "exit 1"),
        LlmError.ConfigError("missing model"),
        LlmError.RateLimitError(Some(5.seconds)),
        LlmError.TimeoutError(30.seconds),
        LlmError.TurnLimitError(Some(10)),
      )
      assertTrue(errs.forall(_.message.nonEmpty))
    },
    test("field-backed cases expose their message; computed cases describe themselves") {
      assertTrue(
        LlmError.ProviderError("provider down").message == "provider down",
        LlmError.ToolError("Bash", "exit 1").message.contains("Bash"),
        LlmError.ToolError("Bash", "exit 1").message.contains("exit 1"),
        LlmError.RateLimitError(Some(5.seconds)).message.contains("retry after"),
        LlmError.TurnLimitError(Some(10)).message.contains("10"),
      )
    },
    test("UsageLimitError is a pure ADT carrying resetAt + provider, and renders its message") {
      val at  = java.time.Instant.parse("2026-06-04T14:38:00Z")
      val err = LlmError.UsageLimitError(Some(at), "codex", "You've hit your usage limit")
      assertTrue(
        !err.isInstanceOf[Throwable],
        err.resetAt.contains(at),
        err.provider == "codex",
        err.message == "You've hit your usage limit",
      )
    },
  )
