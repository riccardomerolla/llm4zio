# Design: GeminiCliProvider Improvements

**Date:** 2026-03-21
**Scope:** Full audit and improvement of `GeminiCliProvider` based on Gemini CLI documentation gaps analysis
**Approach:** Layered extension with new types (Approach B) — surgical additions to existing files, new ADTs where needed

---

## Background

A comparison of the Gemini CLI documentation against the current `GeminiCliProvider` implementation revealed the following gaps:

| Gap | Severity |
|-----|----------|
| `"error"` stream-json event silently parsed as `LogLine` | High |
| Exit codes 42 and 53 map to generic `ProviderError` | Medium |
| No sandbox support (`-s`/`--sandbox`) | Medium |
| `executeStreamWithHistory` naively concatenates role+content | Medium |
| `ToolUse`/`ToolResult` events discarded, no tool observability | Medium |
| `Init` event model/session_id not propagated to chunk metadata | Low |
| No turn-limit configuration (`--turn-limit N`) | Low |
| No skills directory support (`--skills-dir`) | Low |

---

## Design Decisions

- **Tool calling (`executeWithTools`):** Remains unsupported — the CLI controls its own tool loop. Instead, tool events are surfaced as read-only observability via zero-delta `LlmChunk` metadata.
- **Sandbox placement:** `GeminiCliExecutionContext` (per-execution), not `LlmConfig` (provider-level).
- **History format:** Structured prompt template with `**User:**`/`**Assistant:**` bold markers and optional `[SYSTEM CONTEXT]` block.

---

## Section 1: New Types & ADT Changes

### `GeminiCliStreamEvent` — add `Error` variant, enrich `ToolUse`/`ToolResult`

**File:** `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`

```scala
enum GeminiCliStreamEvent:
  case LogLine(line: String)
  case Init(model: Option[String], sessionId: Option[String])
  case Message(role: Option[String], content: Option[String], delta: Boolean)
  case ToolUse(toolName: Option[String], toolId: Option[String], input: Option[String])     // add input
  case ToolResult(toolId: Option[String], status: Option[String], content: Option[String])  // add content
  case Error(message: Option[String], code: Option[Int], errorType: Option[String])         // NEW
  case Result(status: Option[String], errorMessage: Option[String], stats: Option[GeminiCliProvider.GeminiStreamStats])
```

### `GeminiSandbox` — new ADT for sandbox backends

**File:** `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala` (top-level in same file)

```scala
enum GeminiSandbox:
  case Docker
  case Podman
  case SeatbeltMacOS  // sandbox-exec (macOS only)
  case Runsc          // gVisor (Linux)
  case Lxc            // LXC/LXD (Linux, experimental)
  case Default        // -s with no value, let gemini pick

object GeminiSandbox:
  def cliValue(s: GeminiSandbox): Option[String] = s match
    case Docker        => Some("docker")
    case Podman        => Some("podman")
    case SeatbeltMacOS => Some("sandbox-exec")
    case Runsc         => Some("runsc")
    case Lxc           => Some("lxc")
    case Default       => None
```

### `GeminiCliExecutionContext` — extend with sandbox, skills dir, turn limit

```scala
final case class GeminiCliExecutionContext(
  cwd: Option[String]             = None,
  includeDirectories: List[String] = Nil,
  sandbox: Option[GeminiSandbox]  = None,   // NEW
  skillsDir: Option[String]       = None,   // NEW
  turnLimit: Option[Int]          = None,   // NEW
)
```

---

## Section 2: Exit Code Semantic Mapping

### New `TurnLimitError` in `Errors.scala`

**File:** `llm4zio/src/main/scala/llm4zio/core/Errors.scala`

```scala
case class TurnLimitError(limit: Option[Int] = None) extends LlmError  // NEW
```

### Updated `validateExitCode`

```scala
private def validateExitCode(
  exitCode: Int,
  description: String,
  output: Option[String],
  turnLimit: Option[Int] = None,
): IO[LlmError, Unit] =
  exitCode match
    case 0  => ZIO.unit
    case 42 => ZIO.fail(LlmError.InvalidRequestError(
                 s"Gemini CLI rejected the input (exit 42): $description"))
    case 53 => ZIO.fail(LlmError.TurnLimitError(turnLimit))
    case _  =>
      val renderedOutput = output
        .map(out => s". Output: ${out.take(500)}${if out.length > 500 then "..." else ""}")
        .getOrElse("")
      ZIO.logError(s"$description$renderedOutput") *>
        ZIO.fail(LlmError.ProviderError(description, None))
```

`turnLimit` is sourced from `GeminiCliExecutionContext.turnLimit` and threaded through `runGeminiProcess` and `runGeminiProcessStream`.

---

## Section 3: Stream Event Parsing & Tool Observability

### `GeminiStreamJsonEvent` — add `tool_input` field

```scala
final private case class GeminiStreamJsonEvent(
  `type`: String,
  role: Option[String]              = None,
  content: Option[String]           = None,
  delta: Option[Boolean]            = None,
  tool_name: Option[String]         = None,
  tool_id: Option[String]           = None,
  tool_input: Option[String]        = None,  // NEW — tool call arguments JSON
  status: Option[String]            = None,
  model: Option[String]             = None,
  session_id: Option[String]        = None,
  error: Option[GeminiStreamError]  = None,
  stats: Option[GeminiStreamStats]  = None,
) derives JsonDecoder
```

### Updated `parseStreamEvent`

```scala
def parseStreamEvent(line: String): GeminiCliStreamEvent =
  val trimmed = line.trim
  if trimmed.isEmpty then GeminiCliStreamEvent.LogLine(line)
  else
    trimmed.fromJson[GeminiStreamJsonEvent] match
      case Right(event) =>
        event.`type` match
          case "init"        => GeminiCliStreamEvent.Init(event.model, event.session_id)
          case "message"     => GeminiCliStreamEvent.Message(event.role, event.content, event.delta.getOrElse(false))
          case "tool_use"    => GeminiCliStreamEvent.ToolUse(event.tool_name, event.tool_id, event.tool_input)
          case "tool_result" => GeminiCliStreamEvent.ToolResult(event.tool_id, event.status, event.content)
          case "error"       =>                                                              // NEW
            GeminiCliStreamEvent.Error(
              message   = event.error.flatMap(_.message),
              code      = event.error.flatMap(_.code),
              errorType = event.error.flatMap(_.`type`),
            )
          case "result"      =>
            GeminiCliStreamEvent.Result(event.status, event.error.flatMap(_.message), event.stats)
          case _             => GeminiCliStreamEvent.LogLine(line)
      case Left(_) => GeminiCliStreamEvent.LogLine(line)
```

### Tool observability in `executeStream`

Tool events become zero-delta `LlmChunk`s with structured metadata. `Error` events terminate the stream:

```scala
case GeminiCliStreamEvent.ToolUse(toolName, toolId, input) =>
  ZStream.succeed(LlmChunk(
    delta    = "",
    metadata = currentMetadata ++ Map(
      "event"      -> "tool_use",
      "tool_name"  -> toolName.getOrElse(""),
      "tool_id"    -> toolId.getOrElse(""),
      "tool_input" -> input.getOrElse(""),
    ),
  ))

case GeminiCliStreamEvent.ToolResult(toolId, status, content) =>
  ZStream.succeed(LlmChunk(
    delta    = "",
    metadata = currentMetadata ++ Map(
      "event"        -> "tool_result",
      "tool_id"      -> toolId.getOrElse(""),
      "tool_status"  -> status.getOrElse(""),
      "tool_content" -> content.getOrElse(""),
    ),
  ))

case GeminiCliStreamEvent.Error(message, code, errorType) =>
  ZStream.fail(LlmError.ProviderError(
    message.map(m => s"Gemini CLI stream error: $m").getOrElse("Gemini CLI stream error"),
    None,
  ))
```

### Session ID & model metadata propagation

`Init` events update a running metadata map via `mapAccumZIO` so all subsequent chunks carry `session_id` and the live `model`:

```scala
// Seed state: base metadata without session_id
// On Init: add model + session_id to accumulator
// On Message/Tool: emit chunk with current accumulator as metadata
.mapAccumZIO(Map("provider" -> "gemini-cli", "model" -> config.model)) {
  (meta, event) =>
    event match
      case GeminiCliStreamEvent.Init(model, sessionId) =>
        val updated = meta
          ++ model.map("model" -> _).toMap
          ++ sessionId.map("session_id" -> _).toMap
        ZIO.succeed((updated, ZStream.empty))
      case ... =>
        ZIO.succeed((meta, ZStream.succeed(chunkWithMeta(meta, event))))
}
```

---

## Section 4: History Format — Structured Prompt Template

**File:** `GeminiCliProvider.scala` (private method inside `make`)

```scala
private def formatHistory(messages: List[Message]): String =
  val systemParts = messages
    .collect { case Message(MessageRole.System, content) => content.trim }
    .filter(_.nonEmpty)

  val dialogueParts = messages
    .filterNot(_.role == MessageRole.System)
    .map {
      case Message(MessageRole.User, content)      => s"**User:**\n${content.trim}"
      case Message(MessageRole.Assistant, content) => s"**Assistant:**\n${content.trim}"
      case Message(MessageRole.Tool, content)      => s"**Tool Result:**\n${content.trim}"
      case Message(role, content)                  => s"**${role}:**\n${content.trim}"
    }

  val systemBlock =
    if systemParts.isEmpty then ""
    else s"[SYSTEM CONTEXT]\n${systemParts.mkString("\n\n")}\n\n---\n\n"

  systemBlock + dialogueParts.mkString("\n\n")
```

**Example output:**

```
[SYSTEM CONTEXT]
You are a concise Scala code reviewer.

---

**User:**
Can you review this function?

**Assistant:**
Sure, I see a few issues with the error handling...

**User:**
What about the return type?
```

---

## Section 5: CLI Argument Building

### `buildGeminiArgs` — pure, testable argument construction

**File:** `GeminiCliProvider.scala` (moved to `GeminiCliExecutor` companion)

```scala
private[providers] def buildGeminiArgs(
  prompt: String,
  config: LlmConfig,
  ctx: GeminiCliExecutionContext,
  outputFormat: String,
  isWindows: Boolean,
): List[String] =
  val effectivePrompt  = if isWindows then normalizePromptForWindowsCmd(prompt) else prompt
  val baseArgs         = List("-p", effectivePrompt, "-m", config.model, "-y", "--output-format", outputFormat)
  val includeDirArgs   = ctx.includeDirectories.distinct.flatMap(p => List("--include-directories", p))
  val sandboxArgs      = ctx.sandbox.toList.flatMap {
                           case GeminiSandbox.Default => List("-s")
                           case s                     => List("-s", GeminiSandbox.cliValue(s).get)
                         }
  val skillsDirArgs    = ctx.skillsDir.toList.flatMap(dir => List("--skills-dir", dir))
  val turnLimitArgs    = ctx.turnLimit.toList.flatMap(n => List("--turn-limit", n.toString))
  baseArgs ++ includeDirArgs ++ sandboxArgs ++ skillsDirArgs ++ turnLimitArgs
```

The debug log omits the prompt value to avoid logging large inputs:

```scala
ZIO.logDebug(s"Starting Gemini process: gemini ${argsWithoutPrompt.mkString(" ")}")
```

---

## Files Modified

| File | Change |
|------|--------|
| `llm4zio/src/main/scala/llm4zio/core/Errors.scala` | Add `TurnLimitError` |
| `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala` | All other changes: new ADTs, event parsing, stream handling, arg building, history format |
| `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala` | New tests for all changes |

---

## Files NOT Modified

- `LlmService.scala` — interface unchanged
- `Models.scala` — no new config fields needed
- `GeminiApiProvider.scala` — out of scope
- All other providers — out of scope

---

## Testing Strategy

- `parseStreamEvent` — unit tests for `"error"` event, enriched `tool_use`/`tool_result`
- `validateExitCode` — unit tests for exit 42 → `InvalidRequestError`, exit 53 → `TurnLimitError`
- `buildGeminiArgs` — unit tests for each new flag combination
- `formatHistory` — unit tests for system-only, dialogue-only, mixed, empty input
- `executeStream` — mock executor tests for `ToolUse`/`ToolResult` chunk metadata, `Error` stream failure
- `GeminiCliExecutionContext` — compile-time coverage via exhaustive `GeminiSandbox` match
