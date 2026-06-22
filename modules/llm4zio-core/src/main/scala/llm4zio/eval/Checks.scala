package llm4zio.eval

import scala.util.matching.Regex

import zio.ZIO
import zio.json.*
import zio.json.ast.Json

/** Layer 1 — deterministic, pure checks. Each is an `Evaluator[String]` scoring `0` or `maxScore`; they never fail.
  * Adapt to a richer input with `.contramap`, e.g. `Checks.noPii().contramap[Sample](_.response)`.
  */
object Checks:

  /** Common PII shapes. Regex-only (NER is out of scope); extend by composing your own `matches`. */
  private val piiPatterns: List[(String, Regex)] = List(
    "email"       -> """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""".r,
    "ssn"         -> """\b\d{3}-\d{2}-\d{4}\b""".r,
    "credit-card" -> """\b(?:\d[ -]?){13,16}\b""".r,
    "phone"       -> """\b\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b""".r,
    "ipv4"        -> """\b(?:\d{1,3}\.){3}\d{1,3}\b""".r,
  )

  private def one(name: String, ok: Boolean, maxScore: Int, why: String): EvalResult =
    EvalResult(List(DimensionScore(name, if ok then maxScore else 0, why)))

  /** No common PII (email, SSN, credit-card, phone, IPv4). Detection is best-effort regex-only and can produce false
    * positives — e.g. a version string like `1.2.3.4` matches the IPv4 pattern and a bare 13–16 digit run matches the
    * credit-card pattern. Callers wanting stricter detection can compose their own `matches` check.
    */
  def noPii(maxScore: Int = 2): Evaluator[String] =
    (s: String) =>
      val hits = piiPatterns.collect { case (label, re) if re.findFirstIn(s).isDefined => label }
      ZIO.succeed(
        one(
          "no-pii",
          hits.isEmpty,
          maxScore,
          if hits.isEmpty then "no PII detected" else s"matched: ${hits.mkString(", ")}",
        )
      )

  /** Output parses as JSON. */
  def validJson(maxScore: Int = 2): Evaluator[String] =
    (s: String) =>
      val ok = s.fromJson[Json].isRight
      ZIO.succeed(one("valid-json", ok, maxScore, if ok then "parses as JSON" else "not valid JSON"))

  /** Output fully matches `regex` (`String.matches` — whole-string match, the format-validation case). */
  def matches(regex: String, name: String = "format", maxScore: Int = 2): Evaluator[String] =
    (s: String) =>
      val ok = s.matches(regex)
      ZIO.succeed(one(name, ok, maxScore, if ok then s"matches /$regex/" else s"does not match /$regex/"))

  /** Length within `[min, max]` inclusive. */
  def lengthBetween(min: Int, max: Int, maxScore: Int = 2): Evaluator[String] =
    (s: String) =>
      val n = s.length
      ZIO.succeed(one("length", n >= min && n <= max, maxScore, s"length $n; bounds [$min, $max]"))
