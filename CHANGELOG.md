# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
