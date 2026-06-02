package llm4zio.flow

import java.nio.file.Path

import scala.util.control.NonFatal

import zio.json.ast.Json

/** Compress a tool call's raw JSON args into a compact, human-friendly `(…)` summary for the terminal tree — e.g.
  * `{"file_path":"src/lib.rs"}` → `(src/lib.rs)`. Pure; ported from orca's ToolInputSummary heuristic.
  */
object ToolInputSummary:

  /** Headline fields in priority order: the first present non-empty one becomes the summary. */
  private val headlineFields = List("file_path", "path", "command", "pattern", "query", "url", "description")
  private val pathFields     = Set("file_path", "path")

  def summarise(rawInput: String, maxLen: Int, workDir: Path): String =
    extract(rawInput, workDir) match
      case Some(value) if value.nonEmpty => "(" + truncate(collapse(value), maxLen) + ")"
      case _                             => ""

  private def extract(rawInput: String, workDir: Path): Option[String] =
    try
      Json.decoder.decodeJson(rawInput).toOption.flatMap {
        case Json.Obj(fields) =>
          val byName = fields.toMap
          headlineFields.iterator
            .flatMap(name => byName.get(name).flatMap(asString).map(name -> _))
            .collectFirst { case (name, value) if value.trim.nonEmpty => render(name, value.trim, workDir) }
        case _                => None
      }
    catch case NonFatal(_) => None

  private def asString(j: Json): Option[String] = j match
    case Json.Str(s) => Some(s)
    case _           => None

  private def render(field: String, value: String, workDir: Path): String =
    if pathFields.contains(field) then relativize(value, workDir) else value

  private def relativize(value: String, workDir: Path): String =
    try
      val p = Path.of(value)
      if p.isAbsolute then workDir.relativize(p).toString else value
    catch case NonFatal(_) => value

  private def collapse(s: String): String = s.replaceAll("\\s+", " ").trim

  private def truncate(s: String, maxLen: Int): String =
    if s.length <= maxLen then s else s.take(math.max(0, maxLen - 1)) + "…"
