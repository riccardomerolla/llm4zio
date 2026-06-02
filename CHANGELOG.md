# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
