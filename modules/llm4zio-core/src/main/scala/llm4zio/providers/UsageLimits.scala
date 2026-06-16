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

  private val codexAt   = """(?i)try again at\s+(\d{1,2}:\d{2}\s*[AP]M)""".r.unanchored
  private val claudeAt  = """(?i)usage limit.*?(?:resets?|try again) at\s+(\d{1,2}(?::\d{2})?\s*[ap]m)""".r.unanchored
  private val geminiSec = """(?i)reset after\s+(\d+)\s*s""".r.unanchored

  def classify(provider: String, text: String, now: Instant, zone: ZoneId): Option[LlmError] =
    provider match
      case "codex"  => wallClock(text, codexAt, now, zone).map(at => LlmError.UsageLimitError(Some(at), provider, text))
      case "claude" =>
        wallClock(text, claudeAt, now, zone).map(at => LlmError.UsageLimitError(Some(at), provider, text))
      case "gemini" =>
        text match
          // Most specific first: an explicit "reset after Ns" yields a concrete wait duration.
          case geminiSec(secs)          => Some(LlmError.RateLimitError(Some(Duration.fromSeconds(secs.toLong))))
          case _ if isGeminiQuota(text) =>
            // Capacity/quota exhaustion without a duration: resetAt = None so wait-layers fall back to pollInterval.
            Some(LlmError.UsageLimitError(resetAt = None, provider = "gemini", message = text))
          case _                        => None
      case _        => None

  /** Case-insensitive substring check for gemini quota/capacity exhaustion signals. The ambiguous catch-all ("an
    * unknown error occurred") deliberately matches none of these so it stays an (unclassified) transient error.
    */
  private val geminiQuotaSignals =
    List("exhausted your capacity", "quota", "resource_exhausted", "rate_limit", "rate limit", "429")

  private def isGeminiQuota(text: String): Boolean =
    val lower = text.toLowerCase(Locale.US)
    geminiQuotaSignals.exists(lower.contains)

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
