# llm4zio Codebase Refactoring — Design Spec

**Date:** 2026-03-18
**Status:** Approved
**Milestone:** v2.0 Codebase Cleanup

## Problem

The llm4zio codebase has accumulated technical debt in three areas:

1. **Hardcoded prompts** — Agent system prompts, routing prompts, and analysis templates are embedded as multi-line Scala strings in source files, requiring recompilation for any prompt change.
2. **UI sprawl** — 32 ScalaTags view files with inline HTML/Tailwind, while reusable primitives in `Components.scala` remain server-rendered instead of being Lit web components.
3. **Inconsistent streaming** — The `LlmService` trait offers both streaming and non-streaming methods, but only GeminiApi/GeminiCli have real streaming; OpenAI, Anthropic, Ollama, and LmStudio have stubbed implementations.

## Goals

- **Streaming-first**: Real token-by-token streaming for all providers. Remove non-streaming `execute`/`executeWithHistory` from the `LlmService` trait.
- **Externalized prompts**: All agent prompts and skills as markdown files in `src/main/resources/prompts/` with mustache-style `{{placeholder}}` interpolation.
- **Design system**: Extract `Components.scala` ScalaTags primitives into reusable Lit web components (`ab-*` prefix), with thin Scala wrappers.

## Execution Order

Risk-first: **Streaming** (highest architectural risk) → **Prompts** (medium, internal refactor) → **UI** (lowest risk, incremental).

---

## Epic 1: Streaming-First

### Trait Change
Remove `execute(prompt): IO[LlmError, LlmResponse]` and `executeWithHistory(messages): IO[LlmError, LlmResponse]` from `LlmService`. Callers needing full responses use `executeStream(prompt).via(Streaming.collect)`. `executeWithTools` and `executeStructured` remain (inherently request/response).

### Provider Streaming
| Provider | Format | Key Fields |
|----------|--------|------------|
| OpenAI | SSE (`data: ...`, `data: [DONE]`) | `choices[0].delta.content` |
| Anthropic | SSE (typed events) | `content_block_delta` → `delta.text` |
| Ollama | NDJSON | `response` field per line |
| LmStudio | SSE (OpenAI-compatible) | Same as OpenAI |

### HttpClient Enhancement
Enhance `postJsonStream()` with SSE-aware parsing (strip `data:` prefix, skip empty lines/comments) alongside existing NDJSON support.

### Caller Migration
- `PlannerAgentService.plannerPrompt()` → `executeStream` + `Streaming.collect`
- `IntentParser.buildPrompt()` → `executeStream` + `Streaming.collect`
- `AnalysisAgentRunner` profiles → `executeStream` + `Streaming.collect`

---

## Epic 2: Prompts Externalization

### PromptLoader Service
```scala
trait PromptLoader:
  def load(name: String, context: Map[String, String] = Map.empty): IO[PromptError, String]
```

- Loads `.md` files from classpath `prompts/` directory
- Caches in `Ref[Map[String, String]]` after first load
- Interpolates `{{key}}` placeholders from context map
- Two layers: `PromptLoader.live` (cached) and `PromptLoader.reloading` (dev mode)

### Resource Files
```
src/main/resources/prompts/
  planner-agent.md          # Task planner system prompt
  planner-schema.json       # Planner JSON response schema
  intent-router.md          # Request router ({{agentList}})
  agent-execution.md        # Agent template ({{agentName}}, {{sessionId}}, {{message}})
  analysis/
    code-review.md          # Code review analysis prompt
    architecture.md         # Architecture analysis prompt
```

### Template Format
Mustache-style placeholders: `{{key}}` replaced at runtime from a `Map[String, String]`.

---

## Epic 3: Web Components Design System

### Component Mapping
| ScalaTags (Components.scala) | Lit Component | Key Props |
|------------------------------|---------------|-----------|
| `badge(text, color)` | `<ab-badge>` | text, variant, color |
| `spinner(size)` | `<ab-spinner>` | size, label |
| `statusIndicator(status)` | `<ab-status>` | status (healthy/degraded/down) |
| `card(title, body)` | `<ab-card>` | title; slot for body |
| `modal(title, body)` | `<ab-modal>` | open, title; slot for content |
| `progressBar(value, max)` | `<ab-progress-bar>` | value, max, label |
| `dataTable(headers, rows)` | `<ab-data-table>` | headers, rows; sortable |
| toast/notification | `<ab-toast>` | type, message, duration |

### Conventions
- Prefix: `ab-` (established pattern)
- Light DOM: `createRenderRoot() { return this; }` (works with Tailwind)
- Properties: `static properties = { ... }`
- Events: `CustomEvent` dispatch
- Location: `src/main/resources/static/client/components/design-system/`

### Migration Strategy
Replace `Components.scala` methods with thin wrappers emitting web component tags. View files continue using Scala helpers but get web components automatically. Validate with DashboardView migration and `/components` dev catalog route.

---

## Verification

- **Streaming**: `sbt test` passes, manual chat verification per provider, `Streaming.collect()` works for planner/intent/analysis
- **Prompts**: Unit tests for `PromptLoader`, integration verification of same outputs, `.md` files in packaged JAR
- **Design System**: `/components` catalog renders correctly, DashboardView visual parity, cross-browser testing
