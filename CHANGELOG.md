# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.10.0] - 2026-06-11

### Added

- **Read-only reasoning** (orca parity — ADR 0016's read-only gate). `CliConnectorConfig.readOnly` (and
  `GeminiCliExecutionContext.readOnly`) runs a CLI agent with no edit capability, overriding any edit flag:
  claude `--permission-mode plan`, codex `--sandbox read-only`, gemini `--approval-mode plan` (instead of `-y`). Every
  example now gives its `reasoning` connector `readOnly = true`, so planning and review can't touch files. (llm4zio
  needs none of orca's fragile `NetworkOnly` axis — the flow reads issues via `gh.readIssue`, not agent web access.)

### Changed

- **PR flows return to the start branch.** `issue-pr` and `issue-pr-bugfix` capture the current branch up front and
  check it back out once the PR is open, so a run leaves you where you started instead of on the work branch.

[2.10.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.10.0

## [2.9.1] - 2026-06-08

### Fixed

- **Multi-line assistant prose keeps its formatting in the terminal tree.** Assistant text (e.g. a long codebase
  brief) was flattened to a single run-on line — `oneLine` collapsed every newline/tab to a space. It now preserves
  the model's line breaks and tabs, and the renderer hang-indents continuation lines under the glyph so the block
  stays aligned in the tree. Backend/LLM text is still control-sanitized first (no cursor/title escapes).

[2.9.1]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.9.1

## [2.9.0] - 2026-06-08

### Added

- **Plan self-review + codebase brief** (orca parity — `implement-enhanced`). `Planner.reviewed` critiques a draft plan
  along four dimensions (correctness, completeness, simplicity, conciseness) and returns an improved one;
  `Planner.briefed` / `Planner.brief` write a one-off codebase brief. `Plan` gains an optional `brief` (round-tripped in
  a trailing `# Brief` section, so it persists/resumes/cleans up with the single plan file) and `taskPrompt(task)`,
  which prepends the brief to a task so a cold coder doesn't re-discover what the planner learned.
- **Two new examples complete orca example parity.** `07-enhanced` (plan review + codebase brief + format/lint after
  every edit) and `06-issue-pr` (autonomous GitHub-issue → PR: assess → branch → implement+review → push → open PR).
  The `03-bugfix` flow now self-reviews + briefs its fix plan too.

### Changed

- **Aligned with orca's latest:** the plan-review prompt uses orca's four dimensions; the formatter hook runs via a
  shell (`bash -c`) so `LLM4ZIO_FORMAT` can be a pipeline; `gh.readIssue` now retries transient GitHub blips with a
  bounded backoff (idempotent read).

[2.9.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.9.0

## [2.8.0] - 2026-06-08

### Added

- **CLI connector capability contracts (orca-parity #1).** New `ConnectorCapabilities` (streaming, resumable sessions,
  interactive sessions, ask-user, approval, structured output, usage reporting). Every connector declares its surface —
  derived from `interactionSupport` by default; claude declares full interactive/ask-user/approval/resumable support;
  gemini stays `askUser = false` (headless gemini can't expose MCP tools — verified). Exposed on
  `FlowContext.coderCapabilities` so a flow can refuse an unsupported interactive/approval workflow before runtime.
- **Formatter hook in the review/fix loop (orca-parity #9).** `reviewAndFixLoop` gains an optional `format` step run
  before each review round; new `Formatter.step(command, workDir)` runs a project formatter best-effort (a failure is
  surfaced but never aborts the flow). The 04-epic example wires it via `LLM4ZIO_FORMAT` (e.g. `"sbt fmt"`, off unless
  set) and also formats once more before each per-task commit.

### Security

- **Terminal control-sequence sanitization (orca-parity #11).** All backend/LLM-derived text (stage and tool names,
  tool args, assistant text, error details, info messages) is stripped of ANSI CSI/OSC escapes and C0/C1 control bytes
  (tabs and newlines preserved) before terminal styling — so crafted tool/stderr/model output can't move the cursor,
  clear the screen, set the title, or corrupt the rendered tree.

[2.8.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.8.0

## [2.7.8] - 2026-06-06

### Changed

- **Gemini stream anomalies surface in the log instead of vanishing** (inspired by orca's gemini driver). Previously a
  JSON-looking line that failed to decode was logged at `trace`, and all stderr at `debug` — both below the default
  log level, so genuine protocol/stderr errors were effectively invisible. Now: an unparseable JSON-looking stdout
  line is logged at `WARN`, and stderr lines that aren't known benign chatter (YOLO/256-color/cwd-reset/cached/
  extension/IDEClient notices) are logged at `WARN`. Benign noise stays at `debug`.

[2.7.8]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.8

## [2.7.7] - 2026-06-06

### Fixed

- **Gemini's intermittent empty-stream error is now retried.** The gemini CLI occasionally closes the stream with no
  candidates — `Invalid stream: The model returned an empty response or malformed tool call` — a non-deterministic
  glitch a fresh run almost always recovers. `TransientRetry.isTransient` now classifies `empty response`,
  `malformed tool call`, and `invalid stream` (and `code=500`) as transient, so the existing retry path re-runs the
  turn (a fresh `gemini` process) instead of failing fast — bounded by `LLM4ZIO_RETRIES` (default 3, `0` = fail fast).
- **Gemini error events with a flat top-level `message` are surfaced cleanly.** `GeminiStreamJsonEvent` now decodes a
  flat `message` field (in addition to `text`), so the error reads `Invalid stream: …` rather than the raw JSON
  envelope — and gives the classifier a stable string to match.

[2.7.7]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.7

## [2.7.6] - 2026-06-06

### Added

- **`LLM4ZIO_RETRIES` — configurable transient-retry count.** Sets how many times a transient provider blip is retried
  before failing: unset → **3**, `0` → **fail fast** (no retries), `<n>` → that many. Applies to every connector via
  `TransientRetry`. Gemini's catch-all `[API Error: An unknown error occurred.]` (and similar "API error" / "unknown
  error" messages) is now classified transient, so it's retried with a visible `⟳` notice instead of failing the run
  on the first hiccup.

[2.7.6]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.6

## [2.7.5] - 2026-06-06

### Fixed

- **A failed gemini turn no longer hangs the run (and Ctrl+C works again).** The gemini provider drove the child
  process with a raw `ProcessBuilder` and **uninterruptible** blocking reads (`attemptBlocking(readLine())`); on a
  stream failure or Ctrl+C, ZIO couldn't interrupt the parked stderr-drain fiber, so teardown deadlocked, the process
  was never killed, and the run wedged. The provider now drives gemini through **zio-process** (`Command`/`Process`),
  matching the rest of the codebase: streams are interruptible and the child is force-killed when the scope closes, so
  a failed turn or a Ctrl+C tears gemini down in ~1s. Verified against real gemini (streams + parses, and interrupt →
  teardown in ~1s).
- **Error detail is no longer empty.** An unrecognised gemini error event used to surface as
  `Llm(Gemini CLI stream error,None)`. Now: the decoder falls back to the **raw event line** when no `text`/message
  field is present (never an empty message), `stage`/the failure banner render a `FlowError`'s **`message`** instead of
  its noisy case-class `toString`, and the underlying cause is preserved through the example flow's `mapError`.

[2.7.5]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.5

## [2.7.4] - 2026-06-06

### Fixed

- **gemini failures are no longer silent.** A gemini stream error could pass with no visible sign: the flow tree
  looked clean and the cost summary still printed, while only the log file hinted at trouble. Three compounding bugs,
  now fixed:
  - **Real error text was dropped.** Gemini emits errors as a flat `{ "type":"error", "text":… }`, but the parser
    read a nested `error` object gemini never sends — so the message became a useless `unknown` / generic "Gemini CLI
    stream error". The decoder now reads the real `text` (confirmed against gemini-cli's own bundle).
  - **The failure event was rendered into the void.** The terminal renderer consumes the event hub on a fire-and-
    forget fiber that the run's teardown interrupted before the just-published `StageFailed` was drawn. The hub now
    tracks a published count, the listener a processed count, and the runner drains trailing events (bounded) before
    teardown — and a failed run ends with an authoritative `✖ flow failed: …` banner written straight to the surface.
  - **The tree and the log diverged.** The rendered tree now tees into the log file (ANSI-stripped), so the log is a
    complete record of tree + provider output instead of two disjoint halves.

### Added

- **Bounded retry for transient provider errors.** `TransientRetry` wraps every connector (reasoning, coder,
  reviewers) and retries transient blips — timeouts, short rate limits, connection resets, 5xx — up to twice with
  exponential backoff, publishing a visible `⟳` notice each time. Bad requests, parse/config errors, and usage caps
  are never retried (usage caps stay with `UsageLimitAware`).

[2.7.4]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.4

## [2.7.3] - 2026-06-06

### Fixed

- **gemini-cli coder tool calls now show their grey detail, matching codex.** The flow tree rendered gemini tool
  calls as a bare name (`read_file`, `run_shell_command`) with no dim `(…)` summary, while codex showed
  `Bash (…)`. Cause: real gemini `stream-json` carries a tool call's arguments under `parameters` (a JSON object)
  and a tool result's body under `output`, but the decoder only knew the never-emitted `tool_input`/`content`
  fields — so the detail was silently dropped. `GeminiStreamJsonEvent` now decodes `parameters` (serialized into
  the existing `tool_input` metadata) and `output`, and `ToolInputSummary` gained `title` as a lowest-priority
  headline so gemini's `update_topic` shows its topic. Grounded by a real captured `gemini-stream.jsonl` fixture
  (gemini 0.45.2), matching the codex/claude convention.

[2.7.3]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.3

## [2.7.2] - 2026-06-05

### Fixed

- **claude and codex "Argument list too long" (follows #702).** Like gemini in 2.7.1, the `claude` and `codex` CLI
  providers passed the prompt as a command-line argument, so a large prompt (a review of a big diff, accumulated
  epic context) exceeded `ARG_MAX` and the process failed to start. The prompt is now fed via the process **stdin**
  for all prompt-carrying paths (`complete`, `completeStream`, and codex's `--output-schema` structured path). New
  `CliProcessExecutor.runWithStdin` / `runStreamingWithStdin` (the real `LiveCliProcessExecutor` feeds stdin; the
  defaults delegate to the non-stdin variants). Verified with real claude + codex on a 300 KB prompt.
  _Copilot and OpenCode connectors have the same latent limit — tracked as a follow-up._

[2.7.2]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.2

## [2.7.1] - 2026-06-05

### Fixed

- **gemini "Argument list too long" (#702).** The gemini CLI provider passed the prompt as a command-line argument
  (`-p <prompt>`), so a large prompt (e.g. a review of a big diff, or accumulated epic context) exceeded the OS
  `ARG_MAX` and the process failed to start (`error=7, Argument list too long`). The prompt is now fed via the
  process **stdin** instead, bypassing the limit (and removing the need for Windows command-line prompt escaping).
  _Note: claude and codex still pass the prompt on argv — same latent limit — tracked as a follow-up._

[2.7.1]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.1

## [2.7.0] - 2026-06-04

### Added

- **Usage-limit handling.** A typed `LlmError.UsageLimitError(resetAt, provider, message)` (distinct from the
  transient `RateLimitError`), produced by a per-provider `UsageLimits.classify` matcher (codex/claude wall-clock
  caps → `UsageLimitError`; gemini's short "reset after Ns" → `RateLimitError`). Opt-in "patient mode"
  (`UsageLimitPolicy`, via `Llm4zio.run(usageLimit = …)` or `LLM4ZIO_USAGE_WAIT=4h`) makes a flow **sleep until the
  reset and continue** instead of failing: the `UsageLimitAware` decorator waits in place on idempotent
  planning/review/tool calls, and the `withUsageLimitRetry` combinator sleeps-and-re-enters for the streaming coder
  and interactive `Drive`. Bounded by a `maxWait` cap (default 4h). Off by default (usage limits then fail fast, but
  with the typed error).

### Changed

- `FlowError.Llm` gains an optional `cause: Option[LlmError]` (defaulted) so the typed error survives into the flow layer.

[2.7.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.7.0

## [2.6.1] - 2026-06-04

### Fixed

- **codex structured output now works.** `codex exec --output-schema` uses OpenAI strict structured
  outputs, which require every object in the schema to carry `additionalProperties: false` and list all
  its properties in `required`. llm4zio's schemas didn't, so codex returned `400 invalid_json_schema`,
  emitted an `error`/`turn.failed` event the parser silently dropped, and produced no `agent_message` —
  surfacing as an opaque `ParseError: no JSON candidate found in output`. This had broken **every** codex
  structured call (planning, review, PR summary). `CodexConnector` now makes the schema strict-compliant
  before passing it, and surfaces codex's actual error reason (`error`/`turn.failed`) instead of the
  opaque parse failure.
- **Examples:** the codex backend now uses `--sandbox workspace-write` instead of the deprecated
  `--full-auto` (codex 0.136 deprecates the latter; the new flag is the recommended equivalent).

[2.6.1]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.6.1

## [2.6.0] - 2026-06-04

### Changed

- **`LlmError` and `RateLimitError` are pure ADTs** — they no longer extend `Throwable`. Domain
  errors travel ZIO's typed error channel only; `ProviderError.cause: Option[Throwable]` still carries
  an underlying JVM exception when one exists. `LlmError` now exposes a `message: String`, and the flow
  layer surfaces `e.message` instead of `e.toString`. This also fixes opaque error reporting (failures
  previously rendered as bare class names like `Llm(llm4zio.core.LlmError$ProviderError)` because
  `Throwable.toString` masked the real message). *Source-breaking* for downstream code that relied on
  `LlmError <: Throwable` (e.g. `.orDie` on an `IO[LlmError, _]`, or catching it as a `Throwable`).

### Added

- **`reviewAndFixLoop(..., parallelism: Int = 0)`** — caps how many reviewer lenses run concurrently.
  `0` keeps the default unbounded fan-out; a positive value throttles it so rate-limited backends
  (e.g. the gemini free tier) don't get `429`d when all lenses fire at once.

### Fixed

- **Example `04-epic` now runs on a single backend** selectable via `LLM4ZIO_CODER=claude|codex|gemini`
  (default `claude`), like `01-simple`. Removed the dead cross-agent codex wiring (it was configured but
  never invoked); gemini review is throttled to one lens at a time to stay under quota.
- **`examples/_seed_lib.sh` is now Linux-portable** — replaced the macOS-only `mktemp -t` and BSD `sed`
  append with forms that work on both GNU and BSD.

[2.6.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.6.0

## [2.5.0] - 2026-06-04

### Added

- **Interactive live-coding runtime (claude).** A steerable alternative to the one-shot coder:
  each task drives a held `claude --input-format stream-json` session that streams its work,
  can ask the human questions mid-task, and gates tool calls through an approval policy.
  - `AgentSession` (core) — a long-lived, steerable session (`events` stream of `SessionEvent`,
    `sendUserMessage`, `respondToAsk`, `awaitResult`, `cancel`) alongside the one-shot `LlmService`.
    `ClaudeAgentSession` parses claude's bidirectional stream-json and frames user turns; built on a
    new `CliProcessExecutor.runBidirectional` (held-process stdin queue + stdout stream).
  - `McpServer` (flow, transport-free MCP JSON-RPC) exposing `ask_user` (bridged to `Interaction`)
    and `approve` (claude's `--permission-prompt-tool` target, returning its allow/deny verdict),
    served in-process over HTTP by `McpHttpServer` and registered via `--mcp-config`.
  - `ApprovalPolicy` (`autoApprove` / `interactive`) decides each tool call.
  - `Drive.run` relays `SessionEvent`s to the `FlowEvents` sink and answers questions; the new
    `implementTaskLoopLive` runs each plan task on a live session (resumable, per-task commit).
  - `InteractiveCoder` wires it all together; `examples/05-interactive-live` +
    `plans/implement-live.scala` demonstrate it end-to-end. Interactive mode is claude-only;
    codex/gemini stay autonomous-parity.

[2.5.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.5.0

## [2.4.0] - 2026-06-03

### Added

- **Reviewer roster + file-scoping + LLM-driven selection.** Seven shipped review lenses
  (`code-functionality`, `test`, `readability`, `code-structure`, `performance`, `security`,
  `scala-zio`) loaded from prompt resources, `Reviewers.all`/`minimal` presets, `Reviewer.asService`
  to run a lens on any connector, per-file scoping via `Reviewer.files`, and a
  `ReviewerSelector.llmDriven` strategy. `reviewAndFixLoop` now takes `List[Reviewer]` + a reviewer
  service + a changed-file list.
- **Derived + enforced structured output.** `SchemaDerivation.derive[A]` generates an advisory JSON
  schema from a case class (replacing hand-written literals for `Plan`/`PrSummary`); codex enforces
  it natively via `--output-schema`, other CLI backends embed it in the prompt.
- **`Planner.assessThenPlan`** returning `Verdict[Plan]` (`Proceed` | `Blocked`) — a worth-doing gate.
- **`GitTool.diffVsBase` / `defaultBase` / `changedFilesVsBase`** for branch-accurate (merge-base)
  diffs and reviewer file-scoping.
- **Animated terminal surface** — a braille spinner + active-stage label pinned to the bottom line
  (TTY only; `NO_COLOR`/non-TTY fall back to plain output).

### Changed

- **Centralized runtime-owns-git coder prompt.** `Chat.start` prepends a "don't commit/push/branch"
  instruction by default (`manageGit = true` opts out), replacing the ad-hoc per-example variants.
- **Complete cost accounting.** The footer now tracks cached tokens and captures usage from
  structured planner/reviewer calls (`executeStructuredWithUsage`), not just the streamed coder, for
  backends that surface usage.

[2.4.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.4.0

## [2.3.0] - 2026-06-02

### Added

- **Orca-like terminal UI.** Flow progress now renders as a colored, depth-nested tree:
  a `llm4zio <version>, logs: <path>` header banner, magenta stage markers, per-coder
  tool-call lines (e.g. `● Edit (src/lib.rs)`, `● Bash (cargo test)`), assistant-message
  bullets, and a `By agent / By model / Total` cost footer. Noisy `INFO` logs are routed
  to a temp file so stdout stays clean. Color degrades gracefully under `NO_COLOR` or a
  non-TTY. New flow events `ToolUse`/`AssistantMessage`/`TokensUsed`, an
  `EventTappingService` that publishes them from every role connector, a pure
  `ToolInputSummary`, and `CostTracker` + `PriceList`. Styling uses `fansi` (runner only).
- **stream-json parsing for the Claude and Codex CLI coders.** `ClaudeCliConnector`
  (`claude -p --output-format stream-json --verbose`) and `CodexConnector`
  (`codex exec --json`) now parse their event streams into the shared `LlmChunk` metadata
  contract — so tool calls and token usage surface in the tree for all three coders, not
  just Gemini. Shared `CliStreamJson` helper; non-streaming `complete` paths unchanged.

### Fixed

- **Gemini headless runs no longer abort with exit 55** (`FatalUntrustedWorkspaceError`)
  in untrusted/temporary working directories. `GEMINI_CLI_TRUST_WORKSPACE=true` is now set
  on every spawned gemini process, consistent with the `-y` auto-approval the library
  already uses.

[2.3.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.3.0

## [2.2.0] - 2026-06-02

### Changed

- **Pluggable reasoning backend.** `DefaultFlowContext.build` and `Llm4zio.run` now
  accept `reasoning: ConnectorConfig` (was `ApiConnectorConfig`), resolved via
  `registry.resolve` — so a **CLI model can be the reasoner with no API key** (e.g.
  an all-gemini run). New `DefaultFlowContext.prepare` readies a config (enrich API
  base-URL/key, or root a CLI config in the work dir). Source-compatible for callers
  passing an `ApiConnectorConfig`.

[2.2.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.2.0

## [2.1.0] - 2026-06-02

### Added

- **`reviewAndFixLoop` v2** — a `ReviewerSelector` strategy (`allEveryRound` default,
  `whileDirty`) controls which reviewers run each round, and an optional **lint gate**
  (`Reviewers.lintCommand`, over zio-process) short-circuits LLM review when a
  compile/lint command fails, so a broken build is fixed before spending LLM turns.

### Changed

- `reviewAndFixLoop` gained defaulted `selector`/`lint` parameters (source-compatible;
  binary-incompatible vs 2.0.0 — hence the minor bump).
- CI opts JS actions into Node 24 (`FORCE_JAVASCRIPT_ACTIONS_TO_NODE24`) ahead of the
  GitHub Actions Node 20 removal.

[2.1.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.1.0

## [2.0.0] - 2026-06-01

**llm4zio was forked from a ~39-module agentic-software-house *product* down to a
small, focused, ZIO-native LLM library** — the ZIO counterpart to VirtusLab's
[orca](https://github.com/VirtusLab/orca). The full pre-fork product is preserved
on the `archive/product-2026-06` branch.

### Changed (breaking)

- **New artifact coordinates.** The project now publishes three modules:
  - `io.github.riccardomerolla::llm4zio-core` — LLM plumbing: `Connector`/`LlmService`,
    providers (API: OpenAI/Anthropic/Gemini/Ollama/LM Studio; CLI: claude/codex/gemini/
    opencode/copilot; Mock), streaming, tool-calling, structured output, observability.
  - `io.github.riccardomerolla::llm4zio-flow` — the orca-shaped flow layer: `Plan`/`Task`,
    resumable plain-file `PlanStore`, `Chat`, `stage`/`fail` + `FlowEvent` stream,
    `fixLoop`/`reviewAndFixLoop`, `GitTool`/`GhTool` over zio-process, `Planner`
    (`from`/`triage`/`interactive`), `summarisePr`, `implementTaskLoop`, `Interaction`.
  - `io.github.riccardomerolla::llm4zio-runner` — `TerminalListener`, `DefaultFlowContext`,
    `LiveCliProcessExecutor`, `Llm4zio.run` entry point, and worked example flows.
- The previous single `io.github.riccardomerolla::llm4zio` artifact (1.0.3) is
  **frozen** and superseded by `llm4zio-core`; it is no longer published.

### Removed

- The product: board, governance, SPDD/canvas, Telegram HITL, web UI, daemons,
  EclipseStore event-sourcing, bankmod, and ~36 domain modules — all archived on
  `archive/product-2026-06`.

### Added

- Four runnable example flows (scala-cli `.scala`) mirroring orca's examples, with
  per-example seeders (`--local`/`--run`): 01-simple (claude/codex/gemini coders),
  02-interactive, 03-bugfix, 04-epic.

## Pre-2.0 history

Versions 1.0.x and earlier describe the archived agentic-software-house product;
see the `archive/product-2026-06` branch.

[2.0.0]: https://github.com/riccardomerolla/llm4zio/releases/tag/v2.0.0
