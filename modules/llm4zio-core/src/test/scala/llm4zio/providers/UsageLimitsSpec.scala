package llm4zio.providers

import java.time.{ Instant, ZoneId }

import zio.test.*

import llm4zio.core.LlmError

object UsageLimitsSpec extends ZIOSpecDefault:
  private val zone = ZoneId.of("UTC")
  private val now  = Instant.parse("2026-06-04T12:27:00Z")

  def spec: Spec[Environment & TestEnvironment, Any] = suite("UsageLimits.classify")(
    test("codex wall-clock 'try again at 2:38 PM' → UsageLimitError with today's resetAt") {
      val text = "You've hit your usage limit. Upgrade to Pro ... try again at 2:38 PM."
      UsageLimits.classify("codex", text, now, zone) match
        case Some(LlmError.UsageLimitError(Some(at), "codex", _)) =>
          assertTrue(at == Instant.parse("2026-06-04T14:38:00Z"))
        case _                                                    => assertTrue(false)
    },
    test("codex time already passed today → rolls to tomorrow") {
      val text = "usage limit ... try again at 11:00 AM."
      UsageLimits.classify("codex", text, now, zone) match
        case Some(LlmError.UsageLimitError(Some(at), _, _)) =>
          assertTrue(at == Instant.parse("2026-06-05T11:00:00Z"))
        case _                                              => assertTrue(false)
    },
    test("gemini short 'reset after 2s' → RateLimitError, not UsageLimitError") {
      val text = "You have exhausted your capacity on this model. Your quota will reset after 2s.."
      UsageLimits.classify("gemini", text, now, zone) match
        case Some(LlmError.RateLimitError(Some(d))) => assertTrue(d == zio.Duration.fromSeconds(2))
        case _                                      => assertTrue(false)
    },
    test("gemini composite 'reset after 21h1m53s' → UsageLimitError with a concrete resetAt") {
      // The real TerminalQuotaError line gemini prints to stderr when a model's daily quota is exhausted.
      val text =
        "Error when talking to Gemini API Full report available at: /tmp/gemini-client-error.json " +
          "TerminalQuotaError: You have exhausted your capacity on this model. Your quota will reset after 21h1m53s."
      UsageLimits.classify("gemini", text, now, zone) match
        case Some(LlmError.UsageLimitError(Some(at), "gemini", msg)) =>
          assertTrue(at == now.plusSeconds(21L * 3600 + 60 + 53), msg == text)
        case _                                                       => assertTrue(false)
    },
    test("gemini 'reset after 45m' → UsageLimitError (beyond the short-reset window)") {
      val text = "You have exhausted your capacity on this model. Your quota will reset after 45m."
      UsageLimits.classify("gemini", text, now, zone) match
        case Some(LlmError.UsageLimitError(Some(at), "gemini", _)) =>
          assertTrue(at == now.plusSeconds(45L * 60))
        case _                                                     => assertTrue(false)
    },
    test("gemini 'reset after 90s' stays a short RateLimitError") {
      val text = "Your quota will reset after 90s."
      UsageLimits.classify("gemini", text, now, zone) match
        case Some(LlmError.RateLimitError(Some(d))) => assertTrue(d == zio.Duration.fromSeconds(90))
        case _                                      => assertTrue(false)
    },
    test("gemini 'reset after 500ms' is not misread as 500 minutes") {
      val text = "Your quota will reset after 500ms."
      UsageLimits.classify("gemini", text, now, zone) match
        case Some(LlmError.RateLimitError(Some(d))) => assertTrue(d == zio.Duration.fromMillis(500))
        case _                                      => assertTrue(false)
    },
    test("gemini 'exhausted your capacity' (no reset) → UsageLimitError, resetAt None") {
      val text = "You have exhausted your capacity on this model."
      UsageLimits.classify("gemini", text, now, zone) match
        case Some(LlmError.UsageLimitError(resetAt, "gemini", msg)) =>
          assertTrue(resetAt.isEmpty, msg == text)
        case _                                                      => assertTrue(false)
    },
    test("gemini 'quota exceeded' → UsageLimitError") {
      UsageLimits.classify("gemini", "Quota exceeded for this project.", now, zone) match
        case Some(LlmError.UsageLimitError(None, "gemini", _)) => assertTrue(true)
        case _                                                 => assertTrue(false)
    },
    test("gemini RESOURCE_EXHAUSTED with code=429 → UsageLimitError") {
      val text = "Gemini CLI stream error (type=RESOURCE_EXHAUSTED, code=429): rate_limit reached"
      UsageLimits.classify("gemini", text, now, zone) match
        case Some(LlmError.UsageLimitError(None, "gemini", _)) => assertTrue(true)
        case _                                                 => assertTrue(false)
    },
    test("gemini ambiguous catch-all 'an unknown error occurred' → None") {
      assertTrue(
        UsageLimits.classify("gemini", "API Error: An unknown error occurred.", now, zone).isEmpty
      )
    },
    test("unrecognized text → None") {
      assertTrue(UsageLimits.classify("codex", "some unrelated failure", now, zone).isEmpty)
    },
  )
