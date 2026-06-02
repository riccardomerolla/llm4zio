package llm4zio.core

import zio.*
import zio.json.*

import llm4zio.tools.JsonSchema

/** Shared JSON-extraction + parsing helper used by connectors that derive `LlmService.executeStructured` from plain
  * text completion. Pulled out of GeminiCliProvider so every CLI connector can use the same logic instead of each one
  * re-implementing JSON parsing (or skipping it entirely).
  *
  * Strategy:
  *   1. Try the raw output as JSON
  *   2. Try every block fenced with ```...```
  *   3. Try every balanced `{...}` slice we can find
  *
  * The first candidate that decodes to `A` wins.
  */
object StructuredOutputs:

  /** Append an advisory schema to a prompt for backends without native schema enforcement (claude/gemini/copilot/
    * opencode). A no-op for an empty/trivial schema. The model is asked to return JSON matching the schema; the
    * universal fallback parser ([[parseFromText]]) still does the actual decoding.
    */
  def withSchemaHint(prompt: String, schema: JsonSchema): String =
    val s = schema.toString
    if s.isEmpty || s == "{}" then prompt
    else s"$prompt\n\nReturn JSON matching this schema:\n$s"

  /** Parse `A` out of arbitrary text. The schema is currently unused but kept in the signature so callers can pass it
    * through from `LlmService`; future implementations may use it to ask the underlying model for a constrained
    * response.
    *
    * Tries every candidate JSON slice in order (full text, fenced blocks, balanced `{...}` objects) and returns the
    * first that decodes. On total failure, reports the error from the JSON-looking candidate (the last balanced object
    * found) rather than the first attempt, so callers see the relevant codec mismatch instead of a generic "expected
    * '{' got 'W'" from the raw text that started with a CLI stderr warning.
    */
  def parseFromText[A: JsonCodec](raw: String, @scala.annotation.unused schema: JsonSchema): IO[LlmError, A] =
    val candidates = jsonCandidates(raw)
    val attempts   = candidates.map(candidate => candidate -> candidate.fromJson[A])
    attempts.collectFirst { case (_, Right(value)) => value } match
      case Some(value) => ZIO.succeed(value)
      case None        =>
        val report =
          if attempts.isEmpty then "no JSON candidate found in output"
          else
            // The last candidate is the most-deeply-extracted balanced JSON —
            // its error is the most relevant ("field X missing" beats
            // "expected { got W" from the raw text).
            val (lastCandidate, lastError) = attempts.last
            val why                        = lastError.left.getOrElse("unknown")
            s"$why (tried ${attempts.size} candidate(s); last: ${snippet(lastCandidate)})"
        ZIO.fail(
          LlmError.ParseError(
            s"Failed to parse response as structured output: $report",
            raw,
          )
        )

  private def snippet(text: String): String =
    val trimmed = text.trim
    if trimmed.length <= 120 then trimmed
    else trimmed.take(117) + "..."

  /** All viable JSON snippets to try: raw, every fenced block, every balanced `{...}` substring, deduped and trimmed.
    */
  def jsonCandidates(raw: String): List[String] =
    val fenced   = extractJsonFromMarkdownFences(raw)
    val balanced = extractBalancedJsonObjects(raw) ++ fenced.flatMap(extractBalancedJsonObjects)
    (raw :: fenced ::: balanced).map(_.trim).filter(_.nonEmpty).distinct

  private def extractJsonFromMarkdownFences(raw: String): List[String] =
    val fencePattern = "(?is)```(?:[\\w.+-]+)?\\s*(.*?)\\s*```".r
    fencePattern
      .findAllMatchIn(raw)
      .flatMap { matched =>
        val content = matched.group(1).trim
        Option.when(content.nonEmpty)(content)
      }
      .toList

  private def extractBalancedJsonObjects(raw: String): List[String] =
    final case class ScanState(
      startIdx: Option[Int] = None,
      depth: Int = 0,
      inString: Boolean = false,
      escaping: Boolean = false,
      results: List[String] = Nil,
    )

    raw.zipWithIndex.foldLeft(ScanState()) {
      case (state, (ch, idx)) =>
        if state.inString then
          if state.escaping then state.copy(escaping = false)
          else if ch == '\\' then state.copy(escaping = true)
          else if ch == '"' then state.copy(inString = false)
          else state
        else
          ch match
            case '"'                    =>
              state.copy(inString = true)
            case '{'                    =>
              state.copy(
                startIdx = state.startIdx.orElse(Some(idx)),
                depth = state.depth + 1,
              )
            case '}' if state.depth > 0 =>
              val nextDepth = state.depth - 1
              if nextDepth == 0 then
                state.startIdx match
                  case Some(start) =>
                    state.copy(
                      startIdx = None,
                      depth = 0,
                      results = raw.substring(start, idx + 1) :: state.results,
                    )
                  case None        =>
                    state.copy(depth = 0)
              else state.copy(depth = nextDepth)
            case _                      =>
              state
    }.results.reverse
