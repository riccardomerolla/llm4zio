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

**Exit code source:** [Gemini CLI Headless Mode Reference](https://geminicli.com/docs/cli/headless/) — exit 0 = success, 1 = general error, 42 = input error, 53 = turn limit exceeded.

**Sandbox source:** [Gemini CLI Sandboxing](https://geminicli.com/docs/cli/sandbox/) — `-s`/`--sandbox` is a boolean flag; the backend is selected via `GEMINI_SANDBOX` environment variable.

**Skills source:** [Gemini CLI Skills](https://geminicli.com/docs/cli/skills/) — Skills are auto-discovered from `.gemini/skills/` or `~/.gemini/skills/` relative to CWD. There is no `--skills-dir` CLI flag; skills work automatically when `cwd` is set correctly.

---

## Design Decisions

- **Tool calling (`executeWithTools`):** Remains unsupported — the CLI controls its own tool loop. Instead, tool events are surfaced as read-only observability via zero-delta `LlmChunk` metadata.
- **Sandbox placement:** `GeminiCliExecutionContext` (per-execution), not `LlmConfig` (provider-level). Backend type is passed via `GEMINI_SANDBOX` env var injected into the `ProcessBuilder`, not as a CLI flag argument.
- **Skills:** No new configuration needed. Skills auto-discover from `cwd`. Document as a convention: set `cwd` to a directory containing `.gemini/skills/` or `.agents/skills/`.
- **History format:** Structured prompt template with `**User:**`/`**Assistant:**` bold markers and optional `[SYSTEM CONTEXT]` block.
- **`turnLimit` threading:** Sourced from `GeminiCliExecutionContext` (already a parameter on both `runGeminiProcess`/`runGeminiProcessStream`) — read directly at `validateExitCode` call sites, no `GeminiCliExecutor` trait signature changes.

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

The `-s`/`--sandbox` flag is boolean (enables sandboxing). The backend is selected via the `GEMINI_SANDBOX` environment variable. `Default` enables sandboxing with no specific backend (gemini picks one based on OS).

```scala
enum GeminiSandbox:
  case Docker
  case Podman
  case SeatbeltMacOS  // sandbox-exec (macOS only)
  case Runsc          // gVisor (Linux)
  case Lxc            // LXC/LXD (Linux, experimental)
  case Default        // -s with no GEMINI_SANDBOX, let gemini pick

object GeminiSandbox:
  /** The value to set in the GEMINI_SANDBOX env var, if any. None = use -s flag only (Default). */
  def envValue(s: GeminiSandbox): Option[String] = s match
    case Docker        => Some("docker")
    case Podman        => Some("podman")
    case SeatbeltMacOS => Some("sandbox-exec")
    case Runsc         => Some("runsc")
    case Lxc           => Some("lxc")
    case Default       => None
```

### `GeminiCliExecutionContext` — extend with sandbox and turn limit

Note: no `skillsDir` field — skills auto-discover from `cwd` via `.gemini/skills/` / `.agents/skills/` convention.

```scala
final case class GeminiCliExecutionContext(
  cwd: Option[String]             = None,
  includeDirectories: List[String] = Nil,
  sandbox: Option[GeminiSandbox]  = None,   // NEW
  turnLimit: Option[Int]          = None,   // NEW
)
```

---

## Section 2: Exit Code Semantic Mapping

### New `TurnLimitError` in `Errors.scala`

**File:** `llm4zio/src/main/scala/llm4zio/core/Errors.scala`

Must be defined inside `object LlmError` to match the existing namespace convention:

```scala
object LlmError:
  case class ProviderError(message: String, cause: Option[Throwable] = None) extends LlmError
  case class RateLimitError(retryAfter: Option[Duration] = None)             extends LlmError
  case class AuthenticationError(message: String)                            extends LlmError
  case class InvalidRequestError(message: String)                            extends LlmError
  case class TimeoutError(duration: Duration)                                extends LlmError
  case class ParseError(message: String, raw: String)                        extends LlmError
  case class ToolError(toolName: String, message: String)                    extends LlmError
  case class ConfigError(message: String)                                    extends LlmError
  case class TurnLimitError(limit: Option[Int] = None)  extends LlmError    // NEW
```

### Updated `validateExitCode`

No signature change to the `GeminiCliExecutor` trait. `turnLimit` is read from the `executionContext` that is already in scope at each call site:

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

Call sites pass `executionContext.turnLimit`:
```scala
_ <- validateExitCode(exitCode, s"Gemini process exited with code $exitCode", Some(output), executionContext.turnLimit)
```

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
          case "error"       =>
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

### Session ID & model metadata propagation

Use a `Ref` for metadata state, updated on `Init` events and read on every `Message`/`Tool` event. This handles `Error` event termination cleanly in a single `.flatMap` pass — no `mapAccum`/`collectSome` split needed.



```scala
ZStream.fromZIO(Ref.make(baseMetadata)).flatMap { metaRef =>
  executor
    .runGeminiProcessStream(prompt, config, executionContext)
    .tap(logStreamEvent)
    .flatMap {
      case GeminiCliStreamEvent.Init(model, sessionId) =>
        val updates = model.map("model" -> _).toMap ++ sessionId.map("session_id" -> _).toMap
        ZStream.fromZIO(metaRef.update(_ ++ updates)).drain

      case GeminiCliStreamEvent.Message(role, content, _)
          if role.exists(_.equalsIgnoreCase("assistant")) =>
        ZStream.fromZIO(metaRef.get).flatMap { meta =>
          content.filter(_.nonEmpty) match
            case Some(text) => ZStream.succeed(LlmChunk(delta = text, metadata = meta))
            case None       => ZStream.empty
        }

      case GeminiCliStreamEvent.ToolUse(toolName, toolId, input) =>
        ZStream.fromZIO(metaRef.get).map { meta =>
          LlmChunk(delta = "", metadata = meta ++ Map(
            "event" -> "tool_use", "tool_name" -> toolName.getOrElse(""),
            "tool_id" -> toolId.getOrElse(""), "tool_input" -> input.getOrElse(""),
          ))
        }

      case GeminiCliStreamEvent.ToolResult(toolId, status, content) =>
        ZStream.fromZIO(metaRef.get).map { meta =>
          LlmChunk(delta = "", metadata = meta ++ Map(
            "event" -> "tool_result", "tool_id" -> toolId.getOrElse(""),
            "tool_status" -> status.getOrElse(""), "tool_content" -> content.getOrElse(""),
          ))
        }

      case GeminiCliStreamEvent.Error(message, _, _) =>
        ZStream.fail(LlmError.ProviderError(
          message.map(m => s"Gemini CLI stream error: $m").getOrElse("Gemini CLI stream error"),
          None,
        ))

      case GeminiCliStreamEvent.Result(status, errorMessage, stats) if status.contains("error") =>
        ZStream.fail(LlmError.ProviderError(
          errorMessage.map(msg => s"Gemini CLI returned an error: $msg").getOrElse("Gemini CLI returned an error"),
          None,
        ))

      case GeminiCliStreamEvent.Result(_, _, stats) =>
        ZStream.fromZIO(metaRef.get).map { meta =>
          LlmChunk(delta = "", finishReason = Some("stop"), usage = streamStatsToUsage(stats), metadata = meta)
        }

      case _ => ZStream.empty
    }
}
```

### Logging tap — cover `Error` variant

The existing `.tap` block needs a new case for `Error` events to avoid silent swallowing in the wildcard:

```scala
case GeminiCliStreamEvent.Error(message, code, errorType) =>
  ZIO.logWarning(
    s"Gemini stream error event: ${message.getOrElse("unknown")} code=${code.getOrElse(-1)} type=${errorType.getOrElse("unknown")}"
  )
```

---

## Section 4: History Format — Structured Prompt Template

**File:** `GeminiCliProvider.scala` (private method inside `make`)

Empty `messages` returns `Left(LlmError.InvalidRequestError(...))` — callers receive a typed error rather than an empty CLI invocation that would fail with exit 42.

```scala
private def formatHistory(messages: List[Message]): Either[LlmError, String] =
  if messages.isEmpty then
    Left(LlmError.InvalidRequestError("executeStreamWithHistory called with empty message list"))
  else
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

    Right(systemBlock + dialogueParts.mkString("\n\n"))

override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
  ZStream.fromEither(formatHistory(messages)).flatMap(executeStream)
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

## Section 5: CLI Argument Building — Sandbox & Turn Limit

### `buildGeminiArgs` — pure, testable argument construction

**File:** `GeminiCliProvider.scala` (companion object `GeminiCliExecutor`)

Sandbox backend is set via `GEMINI_SANDBOX` environment variable, not as a CLI argument. The `-s` boolean flag enables sandboxing:

```scala
private[providers] def buildGeminiArgs(
  prompt: String,
  config: LlmConfig,
  ctx: GeminiCliExecutionContext,
  outputFormat: String,
  isWindows: Boolean,
): List[String] =
  val effectivePrompt = if isWindows then normalizePromptForWindowsCmd(prompt) else prompt
  val baseArgs        = List("-p", effectivePrompt, "-m", config.model, "-y", "--output-format", outputFormat)
  val includeDirArgs  = ctx.includeDirectories.distinct.flatMap(p => List("--include-directories", p))
  val sandboxArgs     = ctx.sandbox.map(_ => "-s").toList   // -s is a boolean flag only
  val turnLimitArgs   = ctx.turnLimit.toList.flatMap(n => List("--turn-limit", n.toString))
  baseArgs ++ includeDirArgs ++ sandboxArgs ++ turnLimitArgs
```

`buildGeminiArgs` returns only the flag arguments (starting with `-p`). `startProcess` prepends `geminiCommand` (the OS-appropriate executable) before passing to `ProcessBuilder`.

### Sandbox environment variable injection & process launch

The `GEMINI_SANDBOX` env var is set on the `ProcessBuilder` for non-Default sandbox types. `buildGeminiArgs` output is separate from the command prefix so the log stays clean:

```scala
private def startProcess(...): IO[LlmError, Process] =
  val geminiArgs  = buildGeminiArgs(prompt, config, executionContext, outputFormat, isWindows)
  val commands    = geminiCommand ++ geminiArgs          // geminiCommand is the OS executable prefix
  val argsForLog  = geminiArgs.patch(1, List("<prompt>"), 1)  // index 1 = position after "-p"
  ZIO.logDebug(s"Starting Gemini process: gemini ${argsForLog.mkString(" ")}") *>
    ZIO
      .attemptBlocking {
        val builder = new ProcessBuilder(commands.asJava)
        executionContext.cwd.foreach(path => builder.directory(java.nio.file.Paths.get(path).toFile))
        executionContext.sandbox.foreach { s =>
          GeminiSandbox.envValue(s).foreach { v =>
            builder.environment().put("GEMINI_SANDBOX", v)
          }
        }
        builder.redirectErrorStream(mergeErrorStream).start()
      }
      .mapError(e => LlmError.ProviderError(s"Failed to start gemini process: ${e.getMessage}", Some(e)))
      .tapError(err => ZIO.logError(s"Failed to start Gemini process: $err"))
```

The log line `gemini ${argsForLog.mkString(" ")}` shows `gemini -p <prompt> -m model -y ...` — the `-p` flag is preserved, only the prompt value is replaced.

---

## Files Modified

| File | Change |
|------|--------|
| `llm4zio/src/main/scala/llm4zio/core/Errors.scala` | Add `TurnLimitError` inside `object LlmError` |
| `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala` | All other changes: `GeminiSandbox` ADT, extended `GeminiCliStreamEvent`, enriched `GeminiStreamJsonEvent`, updated `parseStreamEvent`, `buildGeminiArgs`, `startProcess` env var injection, `Ref`-based metadata propagation in `executeStream`, improved `executeStreamWithHistory` with `formatHistory` |
| `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala` | New tests for all changes |

---

## Files NOT Modified

- `LlmService.scala` — interface unchanged
- `Models.scala` — no new config fields needed
- `GeminiCliExecutor` trait — no signature changes
- `GeminiApiProvider.scala` — out of scope
- All other providers — out of scope

---

## Testing Strategy

- `parseStreamEvent` — unit tests for `"error"` event, enriched `tool_use`/`tool_result`, `Init` with model+session_id
- `validateExitCode` — unit tests for exit 42 → `InvalidRequestError`, exit 53 → `TurnLimitError(limit)`
- `buildGeminiArgs` — unit tests for sandbox flag, turn-limit flag, include-directories, combinations
- `formatHistory` — unit tests: system-only, dialogue-only, mixed, empty list → `Left(...)`
- `executeStream` — mock executor tests for `ToolUse`/`ToolResult` chunk metadata, `Error` event stream failure, `Init` metadata propagation to subsequent `Message` chunks
- `startProcess` env var injection — verify `GEMINI_SANDBOX` is set on `ProcessBuilder.environment()` for non-Default sandbox
- `GeminiSandbox` — exhaustive match coverage; `envValue(Default)` returns `None`
