package llm4zio.providers

import java.time.format.DateTimeFormatter
import java.time.{ Instant, LocalDate, LocalTime, ZoneId }
import java.util.Locale

import zio.Duration

import llm4zio.core.LlmError

/** Pure classifier: maps a provider's raw error text into the right typed [[LlmError]] (or None if unrecognized).
  * `now`/`zone` are parameters (not read from a Clock) so parsing stays pure and unit-testable.
  */
object UsageLimits:

  private val codexAt         = """(?i)try again at\s+(\d{1,2}:\d{2}\s*[AP]M)""".r.unanchored
  private val claudeAt        = """(?i)usage limit.*?(?:resets?|try again) at\s+(\d{1,2}(?::\d{2})?\s*[ap]m)""".r.unanchored
  // Gemini reset durations are composite: "reset after 2s", "reset after 45m", "reset after 21h1m53s".
  private val geminiReset     = """(?i)reset after\s+((?:\d+(?:ms|[dhms]))+)""".r.unanchored
  private val resetComponent  = """(\d+)(ms|[dhms])""".r
  // Below this a reset is a rate blip worth riding out in-place (RateLimitError → TransientRetry's backoff);
  // above it it's a quota window that only a wait-until-reset layer (or a human) can handle (UsageLimitError).
  private val shortResetLimit = Duration.fromSeconds(120)

  def classify(provider: String, text: String, now: Instant, zone: ZoneId): Option[LlmError] =
    provider match
      case "codex"                        => wallClock(text, codexAt, now, zone).map(at => LlmError.UsageLimitError(Some(at), provider, text))
      case "claude"                       =>
        wallClock(text, claudeAt, now, zone).map(at => LlmError.UsageLimitError(Some(at), provider, text))
      case "gemini"                       =>
        text match
          // Most specific first: an explicit "reset after <duration>" yields a concrete wait.
          case geminiReset(spec)        =>
            val d = resetDuration(spec)
            if d.compareTo(shortResetLimit) <= 0 then Some(LlmError.RateLimitError(Some(d)))
            else Some(LlmError.UsageLimitError(resetAt = Some(now.plus(d)), provider = "gemini", message = text))
          case _ if isGeminiQuota(text) =>
            // Capacity/quota exhaustion without a duration: resetAt = None so wait-layers fall back to pollInterval.
            Some(LlmError.UsageLimitError(resetAt = None, provider = "gemini", message = text))
          case _                        => None
      // No documented reset-time format yet: keyword-only, resetAt = None routes wait layers to
      // pollInterval + heartbeat. Tighten to concrete reset parsing once real limit messages are captured.
      case "grok" | "cursor" | "opencode" =>
        if isGenericQuota(text) then Some(LlmError.UsageLimitError(resetAt = None, provider = provider, message = text))
        else None
      case _                              => None

  private def resetDuration(spec: String): Duration =
    resetComponent.findAllMatchIn(spec).foldLeft(Duration.Zero) { (acc, m) =>
      val n      = m.group(1).toLong
      val millis = m.group(2).toLowerCase(Locale.US) match
        case "d" => n * 86400_000
        case "h" => n * 3600_000
        case "m" => n * 60_000
        case "s" => n * 1000
        case _   => n // "ms"
      acc.plus(Duration.fromMillis(millis))
    }

  /** Case-insensitive substring check for gemini quota/capacity exhaustion signals. The ambiguous catch-all ("an
    * unknown error occurred") deliberately matches none of these so it stays an (unclassified) transient error.
    * `private[providers]` so the gemini executor can spot the quota diagnostics the CLI prints only to stderr.
    */
  private val geminiQuotaSignals =
    List("exhausted your capacity", "quota", "resource_exhausted", "rate_limit", "rate limit", "429")

  private[providers] def isGeminiQuota(text: String): Boolean =
    val lower = text.toLowerCase(Locale.US)
    geminiQuotaSignals.exists(lower.contains)

  /** Provider-agnostic quota/credit-cap signals for CLIs whose limit-message format is not yet pinned down. */
  private val genericQuotaSignals =
    geminiQuotaSignals ++ List("usage limit", "out of credits", "too many requests")

  private def isGenericQuota(text: String): Boolean =
    val lower = text.toLowerCase(Locale.US)
    genericQuotaSignals.exists(lower.contains)

  private def wallClock(text: String, re: scala.util.matching.Regex, now: Instant, zone: ZoneId): Option[Instant] =
    re.findFirstMatchIn(text).flatMap { m =>
      parseTime(m.group(1)).map { lt =>
        val today     = LocalDate.ofInstant(now, zone)
        val candidate = today.atTime(lt).atZone(zone).toInstant
        if candidate.isAfter(now) then candidate else today.plusDays(1).atTime(lt).atZone(zone).toInstant
      }
    }

  private def parseTime(s: String): Option[LocalTime] =
    val cleaned = s.trim.toUpperCase(Locale.US).replace(" ", "")
    val fmts    = List("h:mma", "ha")
    fmts.iterator.flatMap { p =>
      try Some(LocalTime.parse(cleaned, DateTimeFormatter.ofPattern(p, Locale.US)))
      catch case _: java.time.format.DateTimeParseException => None
    }.nextOption()
