package llm4zio.providers

import zio.json.ast.Json

import llm4zio.core.{ LlmChunk, TokenUsage }

/** Shared helpers for CLI providers that read newline-delimited JSON (`stream-json` / `--json`) and normalize events
  * onto the [[LlmChunk]] metadata contract used across all coders.
  */
object CliStreamJson:

  /** Parse one line into a JSON value, or None for blank/non-JSON lines (preambles, warnings). */
  def parseLine(line: String): Option[Json] =
    val t = line.trim
    if t.isEmpty || !t.startsWith("{") then None
    else Json.decoder.decodeJson(t).toOption

  def str(json: Json, field: String): Option[String] = json match
    case Json.Obj(fields) => fields.toMap.get(field).collect { case Json.Str(s) => s }
    case _                => None

  def int(json: Json, field: String): Option[Int] = json match
    case Json.Obj(fields) => fields.toMap.get(field).collect { case Json.Num(n) => n.intValue }
    case _                => None

  def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.toMap.get(name)
    case _                => None

  /** A tool-use chunk in the contract shape: empty delta, `event=tool_use`, name + raw-JSON args. */
  def toolChunk(name: String, rawInput: String): LlmChunk =
    LlmChunk(
      delta = "",
      metadata = Map("event" -> "tool_use", "tool_name" -> name, "tool_input" -> rawInput),
    )

  /** A terminal usage chunk carrying token counts and the model name. */
  def usageChunk(model: Option[String], usage: TokenUsage): LlmChunk =
    LlmChunk(delta = "", finishReason = Some("stop"), usage = Some(usage), metadata = model.map("model" -> _).toMap)
