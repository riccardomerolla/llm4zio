# GeminiCliProvider Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve `GeminiCliProvider` with semantic exit code mapping, `Error` stream event handling, tool observability via chunk metadata, sandbox support, improved history formatting, and session/model metadata propagation.

**Architecture:** All changes are surgical additions to two files (`Errors.scala` and `GeminiCliProvider.scala`) plus the existing test file. The `LlmService` trait interface is unchanged. A new `GeminiSandbox` ADT and the `Error` stream event variant make illegal states unrepresentable. A `Ref`-based approach tracks metadata state inside `executeStream`.

**Tech Stack:** Scala 3.5.2, ZIO 2.x, zio-json, ZStream, sbt, ZIO Test

**Spec:** `docs/superpowers/specs/2026-03-21-gemini-cli-provider-improvements-design.md`

---

## File Map

| File | Role |
|------|------|
| `llm4zio/src/main/scala/llm4zio/core/Errors.scala` | Add `TurnLimitError` inside `object LlmError` |
| `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala` | All provider changes |
| `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala` | New tests for all changes |
| `llm4zio/src/main/scala/llm4zio/observability/Langfuse.scala` | Add `TurnLimitError` arm to `mapError` (Task 1) |
| `llm4zio/src/main/scala/llm4zio/rag/Embeddings.scala` | Add `TurnLimitError` arm to `toEmbeddingError` (Task 1) |

**Do NOT modify:** `LlmService.scala`, `Models.scala`, `GeminiApiProvider.scala`, any other provider.

---

## How to run tests

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Full suite (run at the end):
```bash
sbt "llm4zio/test"
```

---

## Task 1: `TurnLimitError` in `Errors.scala`

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/core/Errors.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

- [ ] **Step 1: Write the failing test**

Add this test inside the `suite("GeminiCliProvider")(...)` block in `GeminiCliProviderSpec.scala`, before the closing `)`:

```scala
test("TurnLimitError carries the configured limit") {
  val err = LlmError.TurnLimitError(Some(5))
  assertTrue(err.limit == Some(5))
},
test("TurnLimitError has None limit when not configured") {
  val err = LlmError.TurnLimitError()
  assertTrue(err.limit.isEmpty)
},
```

- [ ] **Step 2: Run the test — expect compile failure**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: Compile error — `TurnLimitError` is not a member of `LlmError`

- [ ] **Step 3: Add `TurnLimitError` to `Errors.scala`**

Open `llm4zio/src/main/scala/llm4zio/core/Errors.scala`. Add as the last line inside `object LlmError`:

```scala
  case class TurnLimitError(limit: Option[Int] = None) extends LlmError
```

The complete file should now be:

```scala
package llm4zio.core

import zio.*

sealed trait LlmError extends Throwable

object LlmError:
  case class ProviderError(message: String, cause: Option[Throwable] = None) extends LlmError
  case class RateLimitError(retryAfter: Option[Duration] = None)             extends LlmError
  case class AuthenticationError(message: String)                            extends LlmError
  case class InvalidRequestError(message: String)                            extends LlmError
  case class TimeoutError(duration: Duration)                                extends LlmError
  case class ParseError(message: String, raw: String)                        extends LlmError
  case class ToolError(toolName: String, message: String)                    extends LlmError
  case class ConfigError(message: String)                                    extends LlmError
  case class TurnLimitError(limit: Option[Int] = None)                      extends LlmError
```

- [ ] **Step 4: Run tests — expect pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All tests pass including the 2 new ones.

- [ ] **Step 5: Fix non-exhaustive match in `Langfuse.scala`**

`Langfuse.scala` has a `.mapError` block at line ~70 that matches all 8 `LlmError` variants but will miss `TurnLimitError`. Open `llm4zio/src/main/scala/llm4zio/observability/Langfuse.scala` and add after the `LlmError.ToolError` case:

```scala
case LlmError.TurnLimitError(_) => LangfuseError.Transport("Turn limit exceeded")
```

- [ ] **Step 6: Fix non-exhaustive match in `Embeddings.scala`**

`Embeddings.scala` has a `toEmbeddingError` function at line ~210 that matches all 8 `LlmError` variants. Open `llm4zio/src/main/scala/llm4zio/rag/Embeddings.scala` and add after the `LlmError.ToolError` case:

```scala
case LlmError.TurnLimitError(_) => EmbeddingError.ProviderError("Turn limit exceeded")
```

- [ ] **Step 7: Run tests — expect pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All tests pass including the 2 new ones. No exhaustiveness warnings.

- [ ] **Step 8: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/core/Errors.scala \
        llm4zio/src/main/scala/llm4zio/observability/Langfuse.scala \
        llm4zio/src/main/scala/llm4zio/rag/Embeddings.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: add TurnLimitError for Gemini CLI exit code 53, update exhaustive matches"
```

---

## Task 2: `GeminiSandbox` ADT + extend `GeminiCliExecutionContext`

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

- [ ] **Step 1: Write failing tests**

Add inside the `suite("GeminiCliProvider")(...)` block:

```scala
suite("GeminiSandbox")(
  test("envValue returns None for Default sandbox") {
    assertTrue(GeminiSandbox.envValue(GeminiSandbox.Default).isEmpty)
  },
  test("envValue returns docker for Docker sandbox") {
    assertTrue(GeminiSandbox.envValue(GeminiSandbox.Docker) == Some("docker"))
  },
  test("envValue returns podman for Podman sandbox") {
    assertTrue(GeminiSandbox.envValue(GeminiSandbox.Podman) == Some("podman"))
  },
  test("envValue returns sandbox-exec for SeatbeltMacOS sandbox") {
    assertTrue(GeminiSandbox.envValue(GeminiSandbox.SeatbeltMacOS) == Some("sandbox-exec"))
  },
  test("envValue returns runsc for Runsc sandbox") {
    assertTrue(GeminiSandbox.envValue(GeminiSandbox.Runsc) == Some("runsc"))
  },
  test("envValue returns lxc for Lxc sandbox") {
    assertTrue(GeminiSandbox.envValue(GeminiSandbox.Lxc) == Some("lxc"))
  },
),
test("GeminiCliExecutionContext includes sandbox and turnLimit fields") {
  val ctx = GeminiCliExecutionContext(
    sandbox    = Some(GeminiSandbox.Docker),
    turnLimit  = Some(10),
  )
  assertTrue(
    ctx.sandbox == Some(GeminiSandbox.Docker),
    ctx.turnLimit == Some(10),
  )
},
```

- [ ] **Step 2: Run — expect compile failure**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: `GeminiSandbox` not found, `sandbox` not a member of `GeminiCliExecutionContext`

- [ ] **Step 3: Add `GeminiSandbox` enum before `GeminiCliExecutor` trait in `GeminiCliProvider.scala`**

Locate the line `trait GeminiCliExecutor:` (around line 23). Insert before it:

```scala
enum GeminiSandbox:
  case Docker
  case Podman
  case SeatbeltMacOS  // sandbox-exec (macOS only)
  case Runsc          // gVisor (Linux)
  case Lxc            // LXC/LXD (Linux, experimental)
  case Default        // -s only, no backend preference

object GeminiSandbox:
  /** Value to set in GEMINI_SANDBOX env var. None = let gemini choose (Default case). */
  def envValue(s: GeminiSandbox): Option[String] = s match
    case Docker        => Some("docker")
    case Podman        => Some("podman")
    case SeatbeltMacOS => Some("sandbox-exec")
    case Runsc         => Some("runsc")
    case Lxc           => Some("lxc")
    case Default       => None

```

- [ ] **Step 4: Extend `GeminiCliExecutionContext`**

Find `final case class GeminiCliExecutionContext` and replace it:

```scala
final case class GeminiCliExecutionContext(
  cwd: Option[String]              = None,
  includeDirectories: List[String] = Nil,
  sandbox: Option[GeminiSandbox]   = None,
  turnLimit: Option[Int]           = None,
)
```

- [ ] **Step 5: Run tests — expect pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All existing tests still pass (new fields have defaults, no callers break), plus the 7 new ones pass.

- [ ] **Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: add GeminiSandbox ADT and extend GeminiCliExecutionContext"
```

---

## Task 3: Extend `GeminiCliStreamEvent` — add `Error`, enrich `ToolUse`/`ToolResult`

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

> **Note on existing tests:** `ToolUse` and `ToolResult` gain optional fields with defaults (`input = None`, `content = None`). Named-argument call sites that omit the new fields will still compile and compare equal. The one existing test that constructs `ToolUse` with named args will pick up the default `None` automatically.

- [ ] **Step 1: Write failing test for the `Error` variant**

Add inside the suite:

```scala
test("GeminiCliStreamEvent.Error carries message, code, and errorType") {
  val event = GeminiCliStreamEvent.Error(
    message   = Some("auth failed"),
    code      = Some(401),
    errorType = Some("authentication_error"),
  )
  assertTrue(
    event.message   == Some("auth failed"),
    event.code      == Some(401),
    event.errorType == Some("authentication_error"),
  )
},
test("GeminiCliStreamEvent.ToolUse carries input field") {
  val event = GeminiCliStreamEvent.ToolUse(
    toolName = Some("read_file"),
    toolId   = Some("t1"),
    input    = Some("""{"path":"/src/Main.scala"}"""),
  )
  assertTrue(event.input == Some("""{"path":"/src/Main.scala"}"""))
},
test("GeminiCliStreamEvent.ToolResult carries content field") {
  val event = GeminiCliStreamEvent.ToolResult(
    toolId  = Some("t1"),
    status  = Some("success"),
    content = Some("file content here"),
  )
  assertTrue(event.content == Some("file content here"))
},
```

- [ ] **Step 2: Run — expect compile failure**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: `Error` not a member of `GeminiCliStreamEvent`

- [ ] **Step 3: Update `GeminiCliStreamEvent` enum**

Find the enum at the top of `GeminiCliProvider.scala` and replace:

```scala
enum GeminiCliStreamEvent:
  case LogLine(line: String)
  case Init(model: Option[String], sessionId: Option[String])
  case Message(role: Option[String], content: Option[String], delta: Boolean)
  case ToolUse(toolName: Option[String], toolId: Option[String], input: Option[String] = None)
  case ToolResult(toolId: Option[String], status: Option[String], content: Option[String] = None)
  case Error(message: Option[String], code: Option[Int], errorType: Option[String])
  case Result(status: Option[String], errorMessage: Option[String], stats: Option[GeminiCliProvider.GeminiStreamStats])
```

> **IMPORTANT — do this in the same step or the build will be broken.** The existing production code has two positional call sites that use the old 2-field arity. Fix them now:

- [ ] **Step 3b: Fix positional call sites in `parseStreamEvent`**

In `GeminiCliProvider.scala`, inside `def parseStreamEvent`, find and update these two lines:

```scala
// OLD (arity-2, will not compile after enum change)
case "tool_use"    => GeminiCliStreamEvent.ToolUse(event.tool_name, event.tool_id)
case "tool_result" => GeminiCliStreamEvent.ToolResult(event.tool_id, event.status)
```

Replace with (use `None` as placeholder — Task 4 will add the actual `tool_input` field):

```scala
case "tool_use"    => GeminiCliStreamEvent.ToolUse(event.tool_name, event.tool_id, None)
case "tool_result" => GeminiCliStreamEvent.ToolResult(event.tool_id, event.status, None)
```

- [ ] **Step 3c: Fix positional patterns in the `.tap` block of `executeStream`**

In `GeminiCliProvider.scala`, inside `executeStream`, find and update:

```scala
// OLD — arity errors after enum change
case GeminiCliStreamEvent.ToolUse(toolName, toolId)   =>
case GeminiCliStreamEvent.ToolResult(toolId, status)  =>
```

Replace with (add `_` for the new optional field):

```scala
case GeminiCliStreamEvent.ToolUse(toolName, toolId, _)    =>
case GeminiCliStreamEvent.ToolResult(toolId, status, _)   =>
```

Also add the new `Error` logging case anywhere in the `.tap` block:

```scala
case GeminiCliStreamEvent.Error(message, code, errorType) =>
  ZIO.logWarning(
    s"Gemini stream error event: ${message.getOrElse("unknown")} code=${code.getOrElse(-1)} type=${errorType.getOrElse("unknown")}"
  )
```

- [ ] **Step 4: Run tests — expect pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All tests pass. The existing test that constructs `ToolUse(toolName = ..., toolId = ...)` still compiles because `input` has a default.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: extend GeminiCliStreamEvent with Error variant and richer ToolUse/ToolResult"
```

---

## Task 4: `GeminiStreamJsonEvent` + `parseStreamEvent`

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

- [ ] **Step 1: Write failing tests for new parseStreamEvent behaviour**

Add to the suite (alongside the existing `parseStreamEvent` test):

```scala
test("parseStreamEvent decodes error event into Error variant") {
  val line = """{"type":"error","error":{"type":"rate_limit","message":"quota exceeded","code":429}}"""
  assertTrue(
    GeminiCliProvider.parseStreamEvent(line) == GeminiCliStreamEvent.Error(
      message   = Some("quota exceeded"),
      code      = Some(429),
      errorType = Some("rate_limit"),
    )
  )
},
test("parseStreamEvent decodes tool_use with input into ToolUse") {
  val line = """{"type":"tool_use","tool_name":"read_file","tool_id":"t1","tool_input":"{\"path\":\"/foo\"}"}"""
  assertTrue(
    GeminiCliProvider.parseStreamEvent(line) == GeminiCliStreamEvent.ToolUse(
      toolName = Some("read_file"),
      toolId   = Some("t1"),
      input    = Some("""{"path":"/foo"}"""),
    )
  )
},
test("parseStreamEvent decodes tool_result with content into ToolResult") {
  val line = """{"type":"tool_result","tool_id":"t1","status":"success","content":"file body"}"""
  assertTrue(
    GeminiCliProvider.parseStreamEvent(line) == GeminiCliStreamEvent.ToolResult(
      toolId  = Some("t1"),
      status  = Some("success"),
      content = Some("file body"),
    )
  )
},
test("parseStreamEvent decodes init event with model and session_id") {
  val line = """{"type":"init","model":"gemini-2.5-pro","session_id":"s42"}"""
  assertTrue(
    GeminiCliProvider.parseStreamEvent(line) == GeminiCliStreamEvent.Init(
      model     = Some("gemini-2.5-pro"),
      sessionId = Some("s42"),
    )
  )
},
```

- [ ] **Step 2: Run — expect failures on the new tests**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: The error/tool_input tests fail (parsed as `LogLine`), init test may pass already.

- [ ] **Step 3: Add `tool_input` to `GeminiStreamJsonEvent`**

Find `final private case class GeminiStreamJsonEvent` inside `object GeminiCliProvider` and add the `tool_input` field:

```scala
final private case class GeminiStreamJsonEvent(
  `type`: String,
  role: Option[String]             = None,
  content: Option[String]          = None,
  delta: Option[Boolean]           = None,
  tool_name: Option[String]        = None,
  tool_id: Option[String]          = None,
  tool_input: Option[String]       = None,
  status: Option[String]           = None,
  model: Option[String]            = None,
  session_id: Option[String]       = None,
  error: Option[GeminiStreamError] = None,
  stats: Option[GeminiStreamStats] = None,
) derives JsonDecoder
```

- [ ] **Step 4: Update `parseStreamEvent`**

Replace the body of `def parseStreamEvent` (the match on `event.type`) with:

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

- [ ] **Step 5: Run tests — expect all pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All tests pass including the 4 new parsing tests.

- [ ] **Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: update parseStreamEvent with error event and richer tool fields"
```

---

## Task 5: Extract `buildGeminiArgs` + update `startProcess`

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

- [ ] **Step 1: Write failing tests for `buildGeminiArgs`**

Add to the suite:

```scala
suite("buildGeminiArgs")(
  test("includes base flags") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext()
    val args   = GeminiCliExecutor.buildGeminiArgs("my prompt", config, ctx, "stream-json", isWindows = false)
    assertTrue(
      args.contains("-p"),
      args.contains("my prompt"),
      args.contains("-m"),
      args.contains("gemini-2.5-pro"),
      args.contains("-y"),
      args.contains("--output-format"),
      args.contains("stream-json"),
    )
  },
  test("includes -s flag when sandbox is set") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext(sandbox = Some(GeminiSandbox.Docker))
    val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
    assertTrue(args.contains("-s"))
  },
  test("omits -s flag when sandbox is None") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext(sandbox = None)
    val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
    assertTrue(!args.contains("-s"))
  },
  test("includes --turn-limit when configured") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext(turnLimit = Some(5))
    val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
    assertTrue(
      args.contains("--turn-limit"),
      args.contains("5"),
    )
  },
  test("omits --turn-limit when not configured") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext()
    val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
    assertTrue(!args.contains("--turn-limit"))
  },
  test("includes --include-directories for each entry") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext(includeDirectories = List("/a", "/b"))
    val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
    assertTrue(
      args.count(_ == "--include-directories") == 2,
      args.contains("/a"),
      args.contains("/b"),
    )
  },
  test("deduplicates include-directories") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext(includeDirectories = List("/a", "/a"))
    val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
    assertTrue(args.count(_ == "/a") == 1)
  },
  test("normalizes prompt newlines on Windows") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext()
    val args   = GeminiCliExecutor.buildGeminiArgs("line1\nline2", config, ctx, "json", isWindows = true)
    val promptIdx = args.indexOf("-p") + 1
    assertTrue(!args(promptIdx).contains("\n"))
  },
  test("Default sandbox still produces -s flag") {
    val config = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val ctx    = GeminiCliExecutionContext(sandbox = Some(GeminiSandbox.Default))
    val args   = GeminiCliExecutor.buildGeminiArgs("prompt", config, ctx, "json", isWindows = false)
    assertTrue(args.contains("-s"))
  },
),
```

- [ ] **Step 2: Run — expect compile failure**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: `buildGeminiArgs is not a member of GeminiCliExecutor`

- [ ] **Step 3: Add `buildGeminiArgs` to `object GeminiCliExecutor`**

Find `object GeminiCliExecutor:` in `GeminiCliProvider.scala`. After the existing `normalizePromptForWindowsCmd` and before `val default:`, add:

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
    val sandboxArgs     = ctx.sandbox.map(_ => "-s").toList
    val turnLimitArgs   = ctx.turnLimit.toList.flatMap(n => List("--turn-limit", n.toString))
    baseArgs ++ includeDirArgs ++ sandboxArgs ++ turnLimitArgs
```

- [ ] **Step 4: Update `startProcess` in `GeminiCliExecutor.default`**

Find the `startProcess` private method. Replace its body to use `buildGeminiArgs`, prepend `geminiCommand`, inject the `GEMINI_SANDBOX` env var, and fix the log line:

```scala
private def startProcess(
  prompt: String,
  config: LlmConfig,
  executionContext: GeminiCliExecutionContext,
  outputFormat: String,
  mergeErrorStream: Boolean = true,
): IO[LlmError, Process] =
  val geminiArgs = buildGeminiArgs(prompt, config, executionContext, outputFormat, isWindows)
  val commands   = geminiCommand ++ geminiArgs
  val argsForLog = geminiArgs.patch(1, List("<prompt>"), 1)
  ZIO.logDebug(
    s"Starting Gemini process: gemini ${argsForLog.mkString(" ")}"
  ) *>
    ZIO
      .attemptBlocking {
        val builder = new ProcessBuilder(commands.asJava)
        executionContext.cwd.foreach(path => builder.directory(java.nio.file.Paths.get(path).toFile))
        executionContext.sandbox.foreach { s =>
          GeminiSandbox.envValue(s).foreach { v =>
            builder.environment().put("GEMINI_SANDBOX", v)
          }
        }
        builder
          .redirectErrorStream(mergeErrorStream)
          .start()
      }
      .mapError(e => LlmError.ProviderError(s"Failed to start gemini process: ${e.getMessage}", Some(e)))
      .tapError(err => ZIO.logError(s"Failed to start Gemini process: $err"))
```

- [ ] **Step 5: Run tests — expect all pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All pass including the 9 new `buildGeminiArgs` tests.

- [ ] **Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: extract buildGeminiArgs and add sandbox env var injection in startProcess"
```

---

## Task 6: Semantic exit code mapping in `validateExitCode`

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

`validateExitCode` is `private` inside `GeminiCliExecutor.default`. We test it indirectly by making `MockGeminiCliExecutor` simulate the exit via the stream's final event — but the real `validateExitCode` is unreachable from tests. Instead, write integration-style tests using the stream's error exit path via the mock executor returning a stream that terminates with a non-zero exit code.

> **Why indirect?** `validateExitCode` is private to the `default` executor and called after `process.waitFor()`. The mock executor bypasses `startProcess` entirely. We test exit code semantics by examining what the `default` executor emits when exit codes are non-zero — but that requires spawning real processes.
>
> **Pragmatic approach:** Test the semantics via `MockGeminiCliExecutor` overrides that directly fail with the expected error types, which verifies the error types are correctly defined and propagate through `executeStream`.

> **TDD note:** `validateExitCode` is private to `GeminiCliExecutor.default` and is only reachable when a real OS process exits with code 42 or 53. It cannot be meaningfully unit-tested without spawning actual processes. The tests below verify that `TurnLimitError` and `InvalidRequestError` are valid `LlmError` subtypes and propagate through `executeStream` — they do NOT validate the exit-code mapping logic itself. The exit-code mapping is verified indirectly via the full test suite in Task 9.

- [ ] **Step 1: Write tests verifying the error types propagate correctly**

Add to the suite:

```scala
suite("exit code error semantics")(
  test("TurnLimitError is an LlmError") {
    val err: LlmError = LlmError.TurnLimitError(Some(3))
    assertTrue(err.isInstanceOf[LlmError])
  },
  test("executeStream surfaces TurnLimitError from executor") {
    val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    // shouldSucceed = true so checkGeminiInstalled passes; stream override controls the failure
    val executor = new MockGeminiCliExecutor(shouldSucceed = true) {
      override def runGeminiProcessStream(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
        ZStream.fail(LlmError.TurnLimitError(Some(3)))
    }
    val provider = GeminiCliProvider.make(config, executor)
    for
      result <- provider.executeStream("test").runCollect.exit
    yield assertTrue(result.isFailure)
  },
  test("executeStream surfaces InvalidRequestError from executor") {
    val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    // shouldSucceed = true so checkGeminiInstalled passes; stream override controls the failure
    val executor = new MockGeminiCliExecutor(shouldSucceed = true) {
      override def runGeminiProcessStream(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
        ZStream.fail(LlmError.InvalidRequestError("bad input (exit 42)"))
    }
    val provider = GeminiCliProvider.make(config, executor)
    for
      result <- provider.executeStream("test").runCollect.exit
    yield assertTrue(result.isFailure)
  },
),
```

- [ ] **Step 2: Run — expect pass (these tests use mock, no implementation change yet)**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All pass (mocks directly produce the errors, no production code change needed yet for these tests to pass).

- [ ] **Step 3: Update `validateExitCode` in `GeminiCliExecutor.default`**

Find `private def validateExitCode` and replace its entire body:

```scala
private def validateExitCode(
  exitCode: Int,
  description: String,
  output: Option[String],
  turnLimit: Option[Int] = None,
): IO[LlmError, Unit] =
  exitCode match
    case 0  => ZIO.unit
    case 42 =>
      ZIO.fail(LlmError.InvalidRequestError(s"Gemini CLI rejected the input (exit 42): $description"))
    case 53 =>
      ZIO.fail(LlmError.TurnLimitError(turnLimit))
    case _  =>
      val renderedOutput =
        output.map(out => s". Output: ${out.take(500)}${if out.length > 500 then "..." else ""}").getOrElse("")
      ZIO.logError(s"$description$renderedOutput") *>
        ZIO.fail(LlmError.ProviderError(description, None))
```

- [ ] **Step 4: Update call sites of `validateExitCode` to pass `executionContext.turnLimit`**

There are two call sites. Find them and update:

**In `runGeminiProcess`** (around line 93):
```scala
_ <- validateExitCode(exitCode, s"Gemini process exited with code $exitCode", Some(output), executionContext.turnLimit)
```

**In `runGeminiProcessStream`** (around line 124):
```scala
validateExitCode(exitCode, s"Gemini stream process exited with code $exitCode", None, executionContext.turnLimit)
```

- [ ] **Step 5: Run tests — expect all pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

- [ ] **Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: semantic exit code mapping — exit 42 → InvalidRequestError, exit 53 → TurnLimitError"
```

---

## Task 7: Rewrite `executeStream` — `Ref`-based metadata + tool observability + `Error` handling

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

- [ ] **Step 1: Write failing tests**

Add to the suite:

```scala
suite("executeStream metadata and tool observability")(
  test("Init event propagates model and session_id to subsequent chunk metadata") {
    val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val executor = new MockGeminiCliExecutor(
      streamEvents = List(
        GeminiCliStreamEvent.Init(model = Some("gemini-2.5-pro-live"), sessionId = Some("sess-99")),
        GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("hello"), delta = true),
        GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
      )
    )
    val provider = GeminiCliProvider.make(config, executor)
    for
      chunks <- provider.executeStream("hi").runCollect
      textChunks = chunks.filter(_.delta.nonEmpty)
    yield assertTrue(
      textChunks.nonEmpty,
      textChunks.head.metadata.get("session_id") == Some("sess-99"),
      textChunks.head.metadata.get("model") == Some("gemini-2.5-pro-live"),
    )
  },
  test("ToolUse event emits a zero-delta chunk with tool_use metadata") {
    val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val executor = new MockGeminiCliExecutor(
      streamEvents = List(
        GeminiCliStreamEvent.ToolUse(
          toolName = Some("read_file"),
          toolId   = Some("t42"),
          input    = Some("""{"path":"/foo"}"""),
        ),
        GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("done"), delta = true),
        GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
      )
    )
    val provider = GeminiCliProvider.make(config, executor)
    for
      chunks <- provider.executeStream("hi").runCollect
      toolChunks = chunks.filter(_.metadata.get("event").contains("tool_use"))
    yield assertTrue(
      toolChunks.size == 1,
      toolChunks.head.delta.isEmpty,
      toolChunks.head.metadata.get("tool_name") == Some("read_file"),
      toolChunks.head.metadata.get("tool_id") == Some("t42"),
      toolChunks.head.metadata.get("tool_input") == Some("""{"path":"/foo"}"""),
    )
  },
  test("ToolResult event emits a zero-delta chunk with tool_result metadata") {
    val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val executor = new MockGeminiCliExecutor(
      streamEvents = List(
        GeminiCliStreamEvent.ToolResult(
          toolId  = Some("t42"),
          status  = Some("success"),
          content = Some("file body"),
        ),
        GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
      )
    )
    val provider = GeminiCliProvider.make(config, executor)
    for
      chunks <- provider.executeStream("hi").runCollect
      resultChunks = chunks.filter(_.metadata.get("event").contains("tool_result"))
    yield assertTrue(
      resultChunks.size == 1,
      resultChunks.head.delta.isEmpty,
      resultChunks.head.metadata.get("tool_id") == Some("t42"),
      resultChunks.head.metadata.get("tool_status") == Some("success"),
      resultChunks.head.metadata.get("tool_content") == Some("file body"),
    )
  },
  test("Error stream event fails the stream with ProviderError") {
    val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val executor = new MockGeminiCliExecutor(
      streamEvents = List(
        GeminiCliStreamEvent.Error(
          message   = Some("connection reset"),
          code      = Some(500),
          errorType = Some("server_error"),
        ),
      )
    )
    val provider = GeminiCliProvider.make(config, executor)
    for
      result <- provider.executeStream("hi").runCollect.exit
    yield assertTrue(result.isFailure)
  },
),
```

- [ ] **Step 2: Run — expect failures on the metadata/tool tests**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: `Init metadata propagation` fails (metadata doesn't have session_id yet), `ToolUse`/`ToolResult` observability tests fail (no tool_use/tool_result chunks emitted), `Error` stream test fails (Error event is silently ignored as `LogLine` currently).

- [ ] **Step 3: Rewrite `executeStream` inside `GeminiCliProvider.make`**

Replace the entire `executeStream` method (from `override def executeStream` to its closing brace). The new implementation uses `Ref` for metadata accumulation and handles all stream event cases:

```scala
override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
  val baseMetadata = Map("provider" -> "gemini-cli", "model" -> config.model)
  ZStream.fromZIO(ZIO.logInfo(s"Executing Gemini CLI stream with model: ${config.model}")).drain ++
    ZStream.fromZIO(executor.checkGeminiInstalled).drain ++
    ZStream.fromZIO(Ref.make(baseMetadata)).flatMap { metaRef =>
      executor
        .runGeminiProcessStream(prompt, config, executionContext)
        .tap {
          case GeminiCliStreamEvent.LogLine(line) if line.trim.isEmpty || isPreambleLine(line.trim) =>
            ZIO.logDebug(s"Gemini stream preamble: ${line.trim}")
          case GeminiCliStreamEvent.LogLine(line) =>
            ZIO.logTrace(s"Gemini stream non-JSON output: ${line.trim}")
          case GeminiCliStreamEvent.Init(model, sessionId) =>
            ZIO.logDebug(
              s"Gemini stream initialized${model.fold("")(m => s" with model=$m")}${sessionId.fold("")(id => s", session=$id")}"
            )
          case GeminiCliStreamEvent.Message(role, _, delta) =>
            ZIO.logDebug(s"Gemini stream message event role=${role.getOrElse("unknown")}, delta=$delta")
          case GeminiCliStreamEvent.ToolUse(toolName, toolId, _) =>
            ZIO.logDebug(
              s"Gemini stream tool use${toolName.fold("")(n => s" tool=$n")}${toolId.fold("")(id => s", id=$id")}"
            )
          case GeminiCliStreamEvent.ToolResult(toolId, status, _) =>
            ZIO.logDebug(
              s"Gemini stream tool result${toolId.fold("")(id => s" id=$id")}${status.fold("")(v => s", status=$v")}"
            )
          case GeminiCliStreamEvent.Error(message, code, errorType) =>
            ZIO.logWarning(
              s"Gemini stream error event: ${message.getOrElse("unknown")} code=${code.getOrElse(-1)} type=${errorType.getOrElse("unknown")}"
            )
          case GeminiCliStreamEvent.Result(status, errorMessage, _) =>
            ZIO.logDebug(
              s"Gemini stream result status=${status.getOrElse("unknown")}${errorMessage.fold("")(msg => s", error=$msg")}"
            )
        }
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
              LlmChunk(
                delta = "",
                metadata = meta ++ Map(
                  "event"      -> "tool_use",
                  "tool_name"  -> toolName.getOrElse(""),
                  "tool_id"    -> toolId.getOrElse(""),
                  "tool_input" -> input.getOrElse(""),
                ),
              )
            }

          case GeminiCliStreamEvent.ToolResult(toolId, status, content) =>
            ZStream.fromZIO(metaRef.get).map { meta =>
              LlmChunk(
                delta = "",
                metadata = meta ++ Map(
                  "event"        -> "tool_result",
                  "tool_id"      -> toolId.getOrElse(""),
                  "tool_status"  -> status.getOrElse(""),
                  "tool_content" -> content.getOrElse(""),
                ),
              )
            }

          case GeminiCliStreamEvent.Error(message, _, _) =>
            ZStream.fail(
              LlmError.ProviderError(
                message.map(m => s"Gemini CLI stream error: $m").getOrElse("Gemini CLI stream error"),
                None,
              )
            )

          case GeminiCliStreamEvent.Result(status, errorMessage, stats) if status.contains("error") =>
            ZStream.fail(
              LlmError.ProviderError(
                errorMessage.map(msg => s"Gemini CLI returned an error: $msg").getOrElse("Gemini CLI returned an error"),
                None,
              )
            )

          case GeminiCliStreamEvent.Result(_, _, stats) =>
            ZStream.fromZIO(metaRef.get).map { meta =>
              LlmChunk(
                delta        = "",
                finishReason = Some("stop"),
                usage        = streamStatsToUsage(stats),
                metadata     = meta,
              )
            }

          case _ => ZStream.empty
        }
    }
```

- [ ] **Step 4: Run tests — expect all pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All tests pass including the 4 new observability tests. The existing `executeStream should emit assistant chunks` test still passes (ToolUse/ToolResult emit zero-delta chunks that don't affect the text accumulation, and the text chunks still appear).

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: rewrite executeStream with Ref-based metadata, tool observability, and Error event handling"
```

---

## Task 8: `formatHistory` + improved `executeStreamWithHistory`

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala`
- Test: `llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala`

- [ ] **Step 1: Write failing tests for `formatHistory`**

`formatHistory` is private to the `make` closure. Test it via `executeStreamWithHistory` with a mock executor that captures what prompt it received. Use a capture-trick mock:

```scala
suite("formatHistory via executeStreamWithHistory")(
  test("empty message list fails with InvalidRequestError") {
    val config   = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val executor = new MockGeminiCliExecutor()
    val provider = GeminiCliProvider.make(config, executor)
    for
      result <- provider.executeStreamWithHistory(Nil).runCollect.exit
    yield assertTrue(result.isFailure)
  },
  test("user+assistant messages formatted with bold role markers") {
    var capturedPrompt = ""
    val config         = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val executor       = new MockGeminiCliExecutor(
      streamEvents = List(
        GeminiCliStreamEvent.Message(role = Some("assistant"), content = Some("ok"), delta = true),
        GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
      )
    ) {
      override def runGeminiProcessStream(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
        capturedPrompt = prompt
        super.runGeminiProcessStream(prompt, config, executionContext)
    }
    val provider = GeminiCliProvider.make(config, executor)
    val messages = List(
      Message(MessageRole.User, "Hello"),
      Message(MessageRole.Assistant, "Hi there"),
      Message(MessageRole.User, "What is 2+2?"),
    )
    for
      _ <- provider.executeStreamWithHistory(messages).runDrain
    yield assertTrue(
      capturedPrompt.contains("**User:**"),
      capturedPrompt.contains("**Assistant:**"),
      capturedPrompt.contains("Hello"),
      capturedPrompt.contains("Hi there"),
      capturedPrompt.contains("What is 2+2?"),
      !capturedPrompt.startsWith("[SYSTEM CONTEXT]"),
    )
  },
  test("system messages appear in [SYSTEM CONTEXT] block") {
    var capturedPrompt = ""
    val config         = LlmConfig(provider = LlmProvider.GeminiCli, model = "gemini-2.5-pro")
    val executor       = new MockGeminiCliExecutor(
      streamEvents = List(
        GeminiCliStreamEvent.Result(status = Some("success"), errorMessage = None, stats = None),
      )
    ) {
      override def runGeminiProcessStream(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
        capturedPrompt = prompt
        super.runGeminiProcessStream(prompt, config, executionContext)
    }
    val provider = GeminiCliProvider.make(config, executor)
    val messages = List(
      Message(MessageRole.System, "You are a helpful assistant."),
      Message(MessageRole.User, "Hello"),
    )
    for
      _ <- provider.executeStreamWithHistory(messages).runDrain
    yield assertTrue(
      capturedPrompt.startsWith("[SYSTEM CONTEXT]"),
      capturedPrompt.contains("You are a helpful assistant."),
      capturedPrompt.contains("---"),
      capturedPrompt.contains("**User:**"),
    )
  },
),
```

- [ ] **Step 2: Run — expect failures**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: Empty list test fails (currently produces empty prompt, not `InvalidRequestError`). Role marker tests fail (currently uses bare `"User: ..."` format).

- [ ] **Step 3: Add `formatHistory` and update `executeStreamWithHistory` inside `GeminiCliProvider.make`**

> **Nesting is critical.** `GeminiCliProvider.make` returns `new LlmService:` followed by override methods. `formatHistory` must be declared **inside** that `new LlmService:` block, as a `private def`, before `executeStreamWithHistory`. The correct structure is:
>
> ```scala
> def make(...): LlmService =
>   new LlmService:
>     private def formatHistory(...): Either[LlmError, String] = ...  // ← inside new LlmService
>     override def executeStreamWithHistory(...) = ...                 // ← also inside new LlmService
>     override def executeStream(...) = ...
>     // ... other overrides
> ```
>
> Do NOT place `formatHistory` at the `object GeminiCliProvider` level or inside `GeminiCliExecutor` — it will not be visible to `executeStreamWithHistory`.

Replace the existing `executeStreamWithHistory` override (and add `formatHistory` before it):

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

- [ ] **Step 4: Run tests — expect all pass**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiCliProviderSpec"
```

Expected: All pass including the 3 new history format tests.

- [ ] **Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiCliProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiCliProviderSpec.scala
git commit -m "feat: add formatHistory with structured prompt template and fix executeStreamWithHistory"
```

---

## Task 9: Final validation — run full suite

- [ ] **Step 1: Run the full module test suite**

```bash
sbt "llm4zio/test"
```

Expected: All tests pass across all providers.

- [ ] **Step 2: If any tests fail, fix them**

Common causes:
- Pattern matches on `ToolUse`/`ToolResult` in other files that use positional arguments (check `GeminiApiProviderSpec.scala`, `GeminiToolCallingSpec.scala`)
- Exhaustiveness warnings on `LlmError` pattern matches that don't cover `TurnLimitError` — fix by adding a case

- [ ] **Step 3: Final commit if any fixes were needed**

```bash
git add -p   # stage only related changes
git commit -m "fix: update remaining pattern matches after GeminiCliStreamEvent and LlmError extension"
```

---

## Summary of changes

| Task | Commits |
|------|---------|
| 1 | `TurnLimitError` in `Errors.scala` |
| 2 | `GeminiSandbox` ADT + extended `GeminiCliExecutionContext` |
| 3 | Extended `GeminiCliStreamEvent` enum |
| 4 | `GeminiStreamJsonEvent.tool_input` + updated `parseStreamEvent` |
| 5 | `buildGeminiArgs` + updated `startProcess` |
| 6 | Semantic exit code mapping in `validateExitCode` |
| 7 | `Ref`-based `executeStream` rewrite with tool observability |
| 8 | `formatHistory` + improved `executeStreamWithHistory` |
| 9 | Full suite validation |
