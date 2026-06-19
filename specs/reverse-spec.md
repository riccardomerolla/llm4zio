# llm4zio — Behavioral Specification

This document specifies *what llm4zio does* as a set of capabilities with testable acceptance criteria in Given/When/Then form. It is the contract a reimplementation must satisfy. Every criterion is grounded in the repository's source and tests; file paths are relative to the repository root.

Scope: the whole library — `llm4zio-core` (LLM plumbing), `llm4zio-flow` (the agentic flow layer), `llm4zio-runner` (entry points, wiring, terminal UI), and the `examples/*.sc` flow scripts. The build cache and the self-documentation tool are out of scope.

Conventions used throughout the system, asserted as global invariants:

- **Typed errors only.** Core operations fail with `LlmError`; flow operations fail with `FlowError`. No `Throwable` appears in effect signatures.
- **Recoverable vs. catastrophic split.** Expected, handleable outcomes are returned in the *value* channel as typed results; only genuinely unexpected failures fail the effect.
- **Stateless, plain-file persistence.** No datastore; resumable state is Markdown under `.llm4zio/`.

---

## Part I — `llm4zio-core`: the provider-agnostic LLM layer

### 1. Unified LLM service surface

`modules/llm4zio-core/src/main/scala/llm4zio/core/LlmService.scala`

**Capability:** Every backend is reachable through one trait — `executeStream`, `executeStreamWithHistory`, `executeWithTools`, `executeStructured[A]`, `executeStructuredWithUsage[A]`, `isAvailable` — so callers depend on the capability, never the backend.

- **Given** any `LlmService`, **When** `executeStream(prompt)` is called, **Then** it returns `Stream[LlmError, LlmChunk]` of delta chunks, the last carrying a finish reason and (where supported) usage.
- **Given** a provider that does not override `executeStructuredWithUsage`, **When** it is called, **Then** it delegates to `executeStructured` and returns `(value, None, None)` (no usage, no model name).
- **Given** a provider that surfaces usage (e.g. a streaming CLI), **When** `executeStructuredWithUsage` is called, **Then** it returns the decoded value plus `Some(TokenUsage)` and `Some(modelName)`.
- **Given** the `LlmService` companion, **When** an accessor (e.g. `LlmService.executeStructured(...)`) is used, **Then** it produces a `ZIO[LlmService, …]` mirroring the method; and `fromConfig` selects the provider by matching on `LlmConfig.provider`.

### 2. Connector contract and the API/CLI split

`modules/llm4zio-core/src/main/scala/llm4zio/core/Connector.scala`

**Capability:** `Connector extends LlmService` adds `id`, `kind`, `healthCheck → HealthStatus`, and `capabilities`. `ApiConnector` (`kind = Api`) inherits a concrete provider's full implementation; `CliConnector` (`kind = Cli`) implements only `complete`/`completeStream`/argv-builders and gets the rich surface for free.

- **Given** a `CliConnector` that does not override `executeWithTools`, **When** it is called, **Then** it fails with `LlmError.InvalidRequestError` ("CLI connector does not support tool calling").
- **Given** a `CliConnector`, **When** `executeStructured[A](prompt, schema)` is called, **Then** it injects a schema hint into `complete` and parses the returned text back into `A` (via `StructuredOutputs`).
- **Given** a `CliConnector` whose `interactionSupport == InteractiveStdin`, **When** `capabilities` is read, **Then** `interactiveSessions = true`; otherwise it is false by default.
- **Given** a `CliConnector`, **When** `executeStreamWithHistory(messages)` is called, **Then** the message list is flattened to a single string (`CliConnector.flattenHistory`) and passed to `completeStream`.

### 3. Per-connector capability matrix (not uniform)

`modules/llm4zio-core/src/main/scala/llm4zio/core/Connector.scala`, `modules/llm4zio-core/src/test/scala/llm4zio/providers/ConnectorCapabilitiesSpec.scala`

**Capability:** Each connector declares `ConnectorCapabilities(streaming, resumableSessions, interactiveSessions, askUser, approval, structuredOutput, usageReporting)` so a flow can refuse an unsupported workflow up front.

| Capability | Claude CLI | Gemini CLI | OpenCode / Copilot CLI | API connectors (Mock ref.) |
|---|---|---|---|---|
| streaming | ✓ | ✓ | ✓ | ✓ |
| structuredOutput | ✓ | ✓ | ✓ | ✓ |
| usageReporting | ✓ | ✓ | ✓ | ✓ |
| interactiveSessions | ✓ | ✓ | ✗ | ✗ |
| askUser | ✓ | ✗ | ✗ | ✗ |
| approval | ✓ | ✗ | ✗ | ✗ |
| resumableSessions | ✓ | ✗ | ✗ | ✗ |

- **Given** the Claude CLI connector, **When** `capabilities` is read, **Then** streaming, resumableSessions, interactiveSessions, askUser, and approval are all true.
- **Given** the Gemini CLI connector, **When** `capabilities` is read, **Then** `interactiveSessions = true` but `askUser = false` and `approval = false` (it cannot expose an ask-user tool headless).
- **Given** OpenCode or Copilot CLI, **When** `capabilities` is read, **Then** `interactiveSessions = false` and `askUser = false` (continuation-only).

### 4. Read-only enforcement per CLI backend

`modules/llm4zio-core/src/main/scala/llm4zio/providers/ClaudeCliConnector.scala`, `modules/llm4zio-core/src/test/scala/llm4zio/providers/ReadOnlySpec.scala`

**Capability:** A `CliConnectorConfig.readOnly` flag prevents the agent from editing files, per backend: Claude `--disallowed-tools Write,Edit,NotebookEdit` (default permission mode), Codex `--sandbox read-only`, Gemini `--approval-mode plan`.

- **Given** Claude with `flags = {"permission-mode" -> "acceptEdits"}` and `readOnly = true`, **When** argv is built, **Then** argv contains `--permission-mode default` and `--disallowed-tools Write,Edit,NotebookEdit`, contains neither `acceptEdits` nor `plan`, and preserves the trailing positional prompt.
- **Given** read-only Claude, **When** the reasoning seat issues `executeStructured`, **Then** Claude answers directly (plan mode is *not* used, which would make it propose a plan and break one-shot structured calls).
- **Given** read-only argv, **When** flags are ordered, **Then** `--disallowed-tools` (single comma-joined value) precedes `--permission-mode` so it never swallows the trailing prompt.

### 5. Connector configuration and registry resolution

`modules/llm4zio-core/src/main/scala/llm4zio/core/ConnectorConfig.scala`, `modules/llm4zio-core/src/main/scala/llm4zio/core/ConnectorRegistry.scala`, `modules/llm4zio-core/src/main/scala/llm4zio/providers/ConnectorFactories.scala`

**Capability:** A user-facing `ConnectorConfig` ADT has three cases — `ApiConnectorConfig`, `CliConnectorConfig` (carries `flags`, `sandbox`, `workingDir`, `readOnly`, `turnLimit`, `envVars`), and `FallbackChain(connectors)` — resolved to live connectors by a registry.

- **Given** `ApiConnectorConfig(ConnectorId.OpenAI, Some("gpt-4o"))`, **When** constructed, **Then** defaults are `timeout = 300s`, `maxRetries = 3`, `baseUrl = None`, `apiKey = None`.
- **Given** a config with an unknown `connectorId`, **When** `registry.resolve` is called, **Then** it fails with `ConfigError("Unknown connector: …")`.
- **Given** a `CliConnectorConfig`, **When** `registry.resolveApi` is called on it, **Then** it fails with `ConfigError` (expected ApiConnector, got Cli).
- **Given** a registry, **When** `healthCheckAll` is called, **Then** every registered connector is probed and errors are reported as `Availability.Unknown` rather than failing the effect.
- **Given** `createRegistry(http, cli)`, **When** built, **Then** it registers factories for OpenAI/Anthropic/GeminiApi/LmStudio/Ollama/Mock (API) and ClaudeCli/OpenCode/Codex/Copilot/Pi/GeminiCli (CLI).
- **Given** a `FallbackChain`, **When** inspected, **Then** order is preserved and `nonEmpty`/`isEmpty` reflect membership (connectors are tried in order).

### 6. Structured-output extraction from free text

`modules/llm4zio-core/src/main/scala/llm4zio/core/StructuredOutputs.scala`, `modules/llm4zio-core/src/test/scala/llm4zio/core/StructuredOutputsSpec.scala`

**Capability:** For CLI backends without native JSON enforcement, a schema hint is appended to the prompt and the response is mined for JSON.

- **Given** an empty schema (`{}` or empty), **When** `withSchemaHint(prompt, schema)` is called, **Then** the prompt is returned unchanged.
- **Given** a non-trivial schema, **When** `withSchemaHint` is called, **Then** "Return JSON matching this schema:" plus the schema is appended.
- **Given** raw text that *is* a JSON object, **When** `parseFromText[A]` runs, **Then** it decodes immediately.
- **Given** JSON wrapped in a markdown fence, **When** `parseFromText` runs, **Then** it extracts and decodes the fenced block.
- **Given** stderr/warning chatter prepended to a JSON object, **When** `parseFromText` runs, **Then** it locates the balanced `{…}` substring and decodes it (the balancing handles escaped quotes and nesting).
- **Given** no candidate decodes, **When** `parseFromText` fails, **Then** the error cites the most-deeply-extracted JSON-looking candidate so the caller sees the real shape mismatch.

### 7. CLI stream-JSON parsing

`modules/llm4zio-core/src/main/scala/llm4zio/providers/CliStreamJson.scala`, `modules/llm4zio-core/src/main/scala/llm4zio/providers/ClaudeCliConnector.scala`, fixtures under `modules/llm4zio-core/src/test/resources/*-stream.jsonl`

**Capability:** Shared helpers parse newline-delimited JSON emitted by claude/codex/gemini into `LlmChunk`s, ignoring non-JSON preamble.

- **Given** a blank line or a non-`{` line, **When** `parseLine` runs, **Then** it returns `None` (preambles and stderr are skipped).
- **Given** a Claude `assistant` line with a text block, **When** parsed, **Then** the chunk's delta equals the text (fixture: "Editing the file.").
- **Given** a Claude `assistant` line with a `tool_use` block, **When** parsed, **Then** a chunk with `event = tool_use`, the tool name (e.g. "Edit"), and the tool input (e.g. containing "src/lib.rs") is produced.
- **Given** a Claude `result` line, **When** parsed, **Then** a usage chunk carries `TokenUsage` (fixture: prompt 1200, completion 40) and finish reason "stop".
- **Given** a Claude `system` init line, **When** parsed, **Then** the model name is extracted (fixture: "claude-sonnet-4-6").

### 8. Tool schema generation from method signatures

`modules/llm4zio-core/src/main/scala/llm4zio/tools/Tool.scala`, `modules/llm4zio-core/src/test/scala/llm4zio/tools/ToolSchemaGeneratorSpec.scala`

**Capability:** A Scala method signature string is parsed with scalameta and turned into a JSON Schema.

- **Given** `"def transform(code: String, retries: Int, dryRun: Boolean = false): String"`, **When** `fromMethodSignature` runs, **Then** `properties.code = {"type":"string"}`, `properties.retries = {"type":"integer"}`, `properties.dryRun = {"type":"boolean"}`, and `required = ["code","retries"]` (the defaulted param is not required).
- **Given** a parameter of type `Option[T]`, **When** schema is built, **Then** it maps to the schema of `T` and is omitted from `required`.
- **Given** `List[T]`/`Seq[T]`/`Vector[T]`/`Set[T]`, **When** mapped, **Then** `{"type":"array","items": schema_of_T}`; `Map[_, V]` → `{"type":"object","additionalProperties": schema_of_V}`; unknown types → permissive `{"type":"object"}`.
- **Given** a string that is not a method declaration/definition, **When** parsed, **Then** an error is returned.

### 9. Deterministic mock provider

`modules/llm4zio-core/src/main/scala/llm4zio/providers/MockProvider.scala`

**Capability:** `MockProvider` returns canned, deterministic responses for all `LlmService` methods (the test double).

- **Given** any prompt, **When** `executeStream` runs, **Then** it yields one chunk per word (≈50ms apart), the last carrying `TokenUsage(prompt=10, completion=wordCount, total=…)` and metadata `{"provider":"mock","model":…}`.
- **Given** a schema containing both `"summary"` and `"issues"`, **When** `executeStructured[A]` runs, **Then** it returns a mock issue batch (count parsed from a `"(\d+)\s*issue"` match in the prompt, default 10, cap 100); otherwise it returns `{"result":"mock structured response"}`.
- **Given** `healthCheck`, **When** called, **Then** it succeeds with `Healthy`/`Valid`.

### 10. Usage-limit error classification

`modules/llm4zio-core/src/main/scala/llm4zio/providers/UsageLimits.scala`

**Capability:** Raw provider error text is classified into typed `LlmError`s carrying a reset time where parseable.

- **Given** Claude text "usage limit resets at 3pm" at 2:30pm, **When** classified, **Then** `UsageLimitError` with `resetAt = today 3pm`.
- **Given** Claude text "resets at 2pm" at 2:30pm, **When** classified, **Then** `resetAt = tomorrow 2pm` (wraps to next day).
- **Given** Gemini text "reset after 60s", **When** classified, **Then** `RateLimitError(retryAfter = 60s)`.
- **Given** Gemini quota-exhaustion text (e.g. "quota", "resource_exhausted", "429") with no duration, **When** classified, **Then** `UsageLimitError(resetAt = None)`.
- **Given** unrecognised text, **When** classified, **Then** `None`.

### 11. Observability decorators (opt-in)

`modules/llm4zio-core/src/main/scala/llm4zio/observability/MeteredLlmService.scala`

**Capability:** Decorators wrap any `LlmService` to record metrics; they are present but not wired by the runner by default.

- **Given** a `MeteredLlmService`, **When** `executeStream` runs, **Then** a request and the latency are recorded, and only chunks bearing usage contribute token counts.
- **Given** a wrapped IO call that fails with `LlmError`, **When** it completes, **Then** the error count is incremented and latency is still recorded.

---

## Part II — `llm4zio-flow`: the agentic flow layer

### 12. FlowContext and bare-name accessors

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowContext.scala`, `modules/llm4zio-flow/src/main/scala/llm4zio/flow/ContextAccess.scala`

**Capability:** `FlowContext` bundles `reasoning`, `coder` (both `LlmService`), `git`, `gh`, `events`, optional `reviewers`, `coderCapabilities`, `userPrompt`, `workDir`, and exposes `given FlowEvents = events`. Bare names resolve from a `FlowContext ?=>` context function.

- **Given** a `FlowContext` in implicit scope, **When** `stage("build")(ZIO.unit)` is invoked without an explicit `given FlowEvents`, **Then** the `events` sink resolves implicitly and `StageStarted`/`StageCompleted` are published.
- **Given** a `FlowContext` in scope, **When** bare `git`/`gh`/`coder`/`reasoning`/`reviewers`/`userPrompt`/`workDir` are referenced, **Then** each returns the exact corresponding context member.

### 13. Plan and Task

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/Plan.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PlanSpec.scala`

**Capability:** A `Plan(epicId, tasks, brief?)` of `Task(title, description, completed)` renders to / parses from canonical Markdown, with a deterministic path derived from the prompt.

- **Given** a plan with mixed task-completion state and an optional brief, **When** `render` then `parse` run, **Then** the plan round-trips exactly (tasks as `## [ ] Title` / `## [x] Title`; brief as a trailing `# Brief` section).
- **Given** a plan, **When** `nextIncomplete` is read, **Then** it returns the first not-completed task, or `None` when all are done.
- **Given** a plan and a title, **When** `complete(title)` is called, **Then** the matching task is marked completed and others are unchanged.
- **Given** a plan with a brief, **When** `taskPrompt(task)` is called, **Then** the brief is prepended to the task description separated by `\n\n---\n\n`; with no brief, the bare description is returned.
- **Given** the same prompt, **When** `Plan.defaultPath(prompt)` is called twice, **Then** it returns an identical path under `.llm4zio/` named `plan-<hash>.md`; a different prompt yields a different path.
- **Given** malformed header Markdown, **When** `parse` runs, **Then** it returns `Left`.

### 14. PlanStore: resumable plain-file persistence

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/PlanStore.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PlanStoreSpec.scala`

**Capability:** Plans persist as Markdown; a crashed run resumes from the saved file.

- **Given** a plan, **When** `save` then `load` run, **Then** the loaded plan equals the saved one; parent directories are created as needed.
- **Given** no file at the path, **When** `load` runs, **Then** it returns `None`.
- **Given** an existing plan file, **When** `recoverOrCreate(path)(create)` runs, **Then** the existing plan is loaded and `create` is *not* run.
- **Given** no file, **When** `recoverOrCreate` runs, **Then** `create` runs, the result is persisted, and the persisted plan is returned.
- **Given** a path, **When** `delete` is called twice, **Then** the file is removed and the second call is a no-op (no error).
- **Given** a read failure, **When** any operation runs, **Then** it fails with `FlowError.Persistence`; a parse failure fails with `FlowError.PlanParse`.

### 15. Planner

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/Planner.scala`, `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Verdict.scala`, `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Triage.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PlannerSpec.scala`

**Capability:** The reasoning connector produces structured plans/verdicts via `executeStructured`. `defaultInstructions` push for thin outcome slices (split by outcome, described as behaviour not mechanism).

- **Given** a reasoning model returning a plan JSON, **When** `Planner.from(reasoning, prompt)` runs, **Then** the JSON is decoded into a `Plan` matching the epic and task list.
- **Given** a plan with an attached brief, **When** `Planner.reviewed(reasoning, plan)` runs, **Then** an improved plan is returned with the brief preserved.
- **Given** a prompt, **When** `Planner.brief(reasoning, prompt)` runs, **Then** a free-text codebase brief string is returned; `briefed(reasoning, plan, prompt)` attaches it as `brief = Some(b)` so `taskPrompt` prepends it.
- **Given** a request, **When** `Planner.assessThenPlan(reasoning, prompt)` runs, **Then** it returns a `Verdict[Plan]` of `Proceed(plan)` or `Blocked(reason)`.
- **Given** an interaction, **When** `Planner.interactive(reasoning, prompt, interaction, maxTurns)` runs, **Then** the model may ask clarifying questions before proposing a plan; if it never proposes within `maxTurns`, it fails with `FlowError.Aborted`.
- **Given** a bug title/body, **When** `Planner.triage` runs, **Then** it returns `NotABug`, `Untestable`, or `Testable` (JSON-discriminated by `"kind"`).
- **Given** the extension methods, **When** `Planner.from(r, p).reviewed(r).briefed(r, p)` is chained, **Then** the operations compose orca-style.
- **Given** `"owner/repo#42"`, **When** `IssueRef.parse` runs, **Then** it returns `IssueRef(owner, repo, 42)` with `shortRef = "owner/repo#42"`; malformed input returns `None`.

### 16. Chat: stateful conversation with enforced git ownership

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/Chat.scala`, `modules/llm4zio-flow/src/main/scala/llm4zio/flow/CoderSystem.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ChatSpec.scala`

**Capability:** A `Chat` accumulates a `List[Message]` replayed through `executeStreamWithHistory` (no backend session token); `Chat.start` injects the "runtime owns git" instruction unless opted out.

- **Given** a `Chat`, **When** `ask` is called repeatedly, **Then** each call sends all prior messages and both user and assistant turns accumulate in history.
- **Given** `Chat.start(service)` with the default `manageGit = false`, **When** a chat begins, **Then** `CoderSystem.gitOwnership` is prepended to the system prompt (instructing the agent not to run `git commit`/`push` or create/switch branches — the flow handles git).
- **Given** `Chat.start(service, manageGit = true)`, **When** a chat begins, **Then** the git-ownership instruction is omitted.
- **Given** a chat whose backend fails with `LlmError`, **When** `ask` runs, **Then** the failure is wrapped as `FlowError.Llm(message, Some(cause))`.

### 17. implementTaskLoop: resumable per-task execution

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/ImplementLoop.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ImplementLoopSpec.scala`

**Capability:** Runs each incomplete task inside a `stage`, persisting the plan after each so a crash resumes.

- **Given** a plan with some tasks already completed, **When** `implementTaskLoop` runs, **Then** completed tasks are skipped, incomplete tasks run in order, and the plan is persisted to disk after each completes.
- **Given** a `perTask` that fails on a task, **When** the loop runs, **Then** it stops with that error and the plan on disk reflects all tasks completed before the failure (enabling resume).

### 18. fixLoop: generic evaluate → fix → re-evaluate

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/ReviewLoop.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReviewLoopSpec.scala`

**Capability:** A bounded converge-or-give-up primitive: evaluate, and if dirty and rounds remain, fix and re-evaluate.

- **Given** `maxRounds = 3` and a result that is dirty in round 1 then clean, **When** `fixLoop` runs, **Then** `evaluate` runs twice and `fix` once; the final result is clean.
- **Given** a result that stays dirty for all rounds, **When** `fixLoop` runs with `maxRounds = 3`, **Then** `evaluate` runs 3×, `fix` runs 2×, and the final (still dirty) result is returned.
- **Given** a result clean on first evaluation, **When** `fixLoop` runs, **Then** `evaluate` runs once and `fix` not at all.
- **Given** each round, **When** it resolves, **Then** an `Info` event reports the verdict ("clean" or "N issue(s) remaining/fixing").

### 19. reviewAndFixLoop: lint gate, fan-out reviewers, fix rounds

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/LlmReview.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/LlmReviewSpec.scala`

**Capability:** The review centrepiece: optional format step → optional lint gate → reviewer selection → parallel structured reviews of the diff → merge → coder fix → re-review, up to `maxRounds`.

- **Given** a `format` step, **When** each round begins, **Then** `format` runs before reviewers see the diff (best-effort; failures do not abort — see §28).
- **Given** a `lint` gate that is not clean, **When** the round runs, **Then** the lint result is returned immediately and LLM reviewers are *not* invoked.
- **Given** multiple reviewers, **When** they run, **Then** their structured reviews fan out via `ZIO.foreachPar` and their findings are merged.
- **Given** `parallelism = 1`, **When** reviewers run, **Then** at most one reviewer call is in flight at a time; with `parallelism = 0` (default) the fan-out is unbounded.
- **Given** a reviewer whose file-scope regex matches none of the changed files, **When** the round runs, **Then** that reviewer is not invoked.
- **Given** a merged dirty result with rounds remaining, **When** the round ends, **Then** the coder is asked to address the findings (`fixPrompt`) and the loop re-reviews.

### 20. Reviewer selection strategies

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/LlmReview.scala`

- **Given** `ReviewerSelector.allEveryRound`, **When** selecting, **Then** all file-matching reviewers are chosen every round.
- **Given** `ReviewerSelector.whileDirty`, **When** selecting in round > 1, **Then** reviewers are chosen only if the previous result was dirty; if clean, none are chosen.
- **Given** `ReviewerSelector.llmDriven(picker)` and >1 candidate, **When** selecting, **Then** the picker chooses relevant reviewers by name; on parse/LLM failure or an empty pick, it falls back to all file-matching reviewers (never an empty set).

### 21. Reviewer lenses (classpath Markdown)

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/Reviewer.scala`, `modules/llm4zio-flow/src/main/scala/llm4zio/flow/LlmReview.scala`, `modules/llm4zio-flow/src/main/resources/llm4zio/review/reviewers/*.md`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ReviewerRosterSpec.scala`

**Capability:** A reviewer is a named lens (system prompt + optional changed-file regex) loaded from classpath Markdown. Ten lenses ship; rosters are composable.

- **Given** `Reviewers.all`, **When** read, **Then** it has 7 lenses (code-functionality, test, readability, code-structure, performance, security, scala-zio), each with a non-empty prompt.
- **Given** `Reviewers.minimal`, **When** read, **Then** it is the subset {code-functionality, readability, test} of `all`.
- **Given** the opt-in lenses `tddDiscipline`, `domainLanguage`, `effectShape`, **When** read, **Then** they load with non-empty prompts, are not in `all`/`minimal`, and can be appended to a roster (e.g. `Reviewers.minimal :+ Reviewers.tddDiscipline`).
- **Given** slug `"security"`, **When** `Reviewer.fromResource` runs, **Then** it loads `llm4zio/review/reviewers/security.md`, parses any `files:` regex from frontmatter, and returns a `Reviewer(name, systemPrompt, files)`.
- **Given** a reviewer with no scope or an empty changed-file list, **When** `matches` is evaluated, **Then** it returns true (cannot scope without files); the scala-zio lens scopes to `.scala` files.
- **Given** `reviewer.asService(base)`, **When** `executeStructured` is called, **Then** the reviewer's system prompt is prepended to the prompt.

### 22. Review value types and merge

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/Review.scala`

- **Given** a `ReviewResult`, **When** `isClean` is read, **Then** it is true iff `issues` is empty.
- **Given** several `ReviewResult`s, **When** merged, **Then** the merged result flattens all issues and joins non-empty summaries with `"; "`.
- A `ReviewIssue` carries `severity` (Critical/Warning/Info), `title`, and optional `description`, `file`, `line`, `suggestion`, `confidence` (default 1.0).

### 23. GitTool: errors-as-values, non-interactive, scoped credentials

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/GitTool.scala`, `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Proc.scala`, `modules/llm4zio-flow/src/it/scala/llm4zio/flow/GitToolSpec.scala`

**Capability:** A thin `git` wrapper bound to `workDir`. Recoverable outcomes are returned as values; every call runs non-interactively; GitHub pushes attach a scoped credential helper read at runtime.

- **Given** a new branch name, **When** `createBranch` runs, **Then** it returns `CreateBranch.Created`; if the branch exists, it returns `CreateBranch.AlreadyExists` (not a failure).
- **Given** `checkoutOrCreate(name)`, **When** the branch exists, **Then** it checks out; otherwise it creates.
- **Given** staged changes, **When** `commitAll(message)` runs, **Then** it returns `Commit.Committed`; with nothing staged it returns `Commit.NothingToCommit`.
- **Given** tracked changes, **When** `diff()` runs, **Then** only tracked changes appear; `diffAll()` additionally includes untracked files (via `--intent-to-add`) and a subsequent `commitAll` still works.
- **Given** any git invocation, **When** it runs, **Then** the environment includes `GIT_TERMINAL_PROMPT=0` and ssh `BatchMode=yes`, so a TTY-less run fails fast rather than hanging on a credential/passphrase prompt.
- **Given** a github.com remote and `GH_TOKEN`/`GITHUB_TOKEN` in the environment, **When** `push` runs, **Then** a github-scoped credential helper supplies the token at helper runtime (never in argv/logs); absent a token it falls back to `gh auth git-credential`.
- **Given** a temp repo with a local bare remote (no network), **When** the integration tests run, **Then** `push` updates `refs/heads/<branch>` on the bare remote; `diffVsBase`/`changedFilesVsBase` show the delta vs base; `defaultBase` falls back to "main" when no remote is configured.

### 24. GhTool: PRs, issues, CI polling over `gh`

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/GhTool.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/GhToolSpec.scala`

- **Given** title/body (+ optional base/draft), **When** `createPr` runs, **Then** it invokes `gh pr create` with the corresponding args and parses the PR URL from stdout (`owner/repo/number`); a missing URL fails the effect.
- **Given** a PR, **When** `updatePr` runs, **Then** it issues a REST `PATCH` via `gh api` (avoiding `gh pr edit`, which breaks on Projects-classic repos).
- **Given** an issue ref, **When** `readIssue` runs, **Then** it calls `gh issue view --json title,body,author` and parses `Issue(title, body, author.login)`, retrying transient blips with bounded exponential backoff.
- **Given** a PR, **When** `prChecks` runs, **Then** exit 0 → `Success`, exit 8 → `Pending`, else → `Failure`; `waitForBuild` polls every 15s to a terminal `BuildOutcome` or returns `TimedOut`.

### 25. AdoTool: Azure DevOps REST client

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/AdoTool.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/AdoToolSpec.scala`

**Capability:** Pure request-builders + parsers (unit-tested) with thin effectful methods over `HttpClient`, for Azure Boards work items and Azure Repos PRs.

- **Given** a PAT, **When** `authHeader` is built, **Then** it is HTTP Basic `base64(":" + pat)`.
- **Given** a work-item id, **When** `readWorkItem` runs, **Then** the request targets the work-items endpoint with `$expand=relations` and the response parses to `WorkItem(id, title, description, acceptanceCriteria, state, tags)`.
- **Given** field changes, **When** `setFields`/`setState`/`setAcceptanceCriteria` run, **Then** a json-patch `PATCH` is built per field; `addTag` reads the item, appends to `System.Tags`, and writes back.
- **Given** a WIQL query, **When** `wiqlIds` runs, **Then** a POST is built and the result parses to a list of work-item ids.
- **Given** source/target refs, **When** `createPr` runs, **Then** a PR is created and parsed to `AdoPullRequest(id, repoId, projectId, webUrl)`; `linkPr` adds an `ArtifactLink` relation pointing at the PR's vstfs artifact.

### 26. Flow events and stages

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowEvents.scala`, `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Stage.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/StageSpec.scala`

**Capability:** Every step emits `FlowEvent`s (`StageStarted/Completed/Failed`, `Aborted`, `Info`, `ToolUse`, `AssistantMessage`, `TokensUsed`) to a `FlowEvents` sink with three implementations: `noop`, `Collecting` (Ref buffer), `Hub` (bounded broadcast with back-pressure).

- **Given** `stage(name)(effect)`, **When** the effect succeeds, **Then** `StageStarted` then `StageCompleted` are published and the value passes through; when it fails, `StageFailed(name, message)` is published and the same error propagates.
- **Given** a `FlowError` failure inside a stage, **When** the failure is described, **Then** the event carries the error's `.message` (not the noisy case-class `toString`).
- **Given** `fail(message)`, **When** invoked, **Then** `Aborted(message)` is published and the effect fails with `FlowError.Aborted(message)`.
- **Given** a `Hub` sink and a subscriber, **When** events are published, **Then** they are delivered to the subscriber, and `publishedCount` tracks the total for draining before teardown.

### 27. Tool-input summarisation

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/ToolInputSummary.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ToolInputSummarySpec.scala`

**Capability:** A tool call's raw JSON args are compressed into a compact `(…)` summary for the terminal tree.

- **Given** `{"file_path":"src/lib.rs"}`, **When** summarised, **Then** the result is `(src/lib.rs)`.
- **Given** an absolute path field, **When** summarised, **Then** it is relativised against `workDir`.
- **Given** multiple fields, **When** summarised, **Then** the headline is chosen by priority `file_path → path → command → pattern → query → url → description → title`; whitespace is collapsed and long values are truncated with `…`.
- **Given** no headline field or invalid JSON, **When** summarised, **Then** the result is an empty string.

### 28. Formatter (best-effort)

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/Formatter.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FormatterSpec.scala`

- **Given** `None` or a blank command, **When** `Formatter.step` runs, **Then** it is a no-op (no events).
- **Given** a command, **When** it runs, **Then** it executes via `bash -c` in `workDir` and emits an `Info` event; a non-zero exit or exception is surfaced as `Info` but the step still succeeds (formatter failure never aborts the flow).

### 29. FlowError ADT and recoverability discipline

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowError.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/FlowErrorSpec.scala`

- The ADT has `Persistence`, `PlanParse`, `Aborted`, `Process`, and `Llm(message, cause: Option[LlmError] = None)`.
- **Given** an `Llm` error, **When** constructed without a cause, **Then** `cause` defaults to `None`; a typed `LlmError` can be carried for inspection.
- **Given** an expected outcome (branch exists, nothing to commit, transient blip), **When** it occurs, **Then** it is returned in the value channel, never as a `FlowError`.

### 30. MCP server, approval, interaction, session driving

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/McpServer.scala`, `Approval.scala`, `Interactive.scala`, `Drive.scala`, and their specs

**Capability:** A transport-free MCP JSON-RPC handler exposes `ask_user` and `approve` tools to a CLI agent; an approval policy gates tool calls; `Drive` runs a held session turn.

- **Given** an `initialize` request, **When** handled, **Then** the response echoes the client's `protocolVersion` (default "2025-06-18") and advertises tool capability for server "llm4zio".
- **Given** a `tools/call` for `ask_user`, **When** handled, **Then** the call is bridged to `Interaction.ask` and the answer is returned.
- **Given** a notification (no `id`), **When** handled, **Then** no response is produced; an unknown method returns `-32601`; an unknown tool returns `-32602`; a failing tool is reported as an `isError` result (not a failed effect).
- **Given** `ApprovalPolicy.autoApprove`, **When** any tool is checked, **Then** `Allow`; with `ApprovalPolicy.interactive`, an answer of "y"/"yes" yields `Allow`, otherwise `Deny("denied by operator")`.
- **Given** `approvalTool` targeting claude's `--permission-prompt-tool`, **When** invoked, **Then** it returns `{"behavior":"allow","updatedInput":input}` or `{"behavior":"deny","message":reason}`.
- **Given** `Drive.run(session, interaction, message)`, **When** the session streams events, **Then** `TextDelta → AssistantMessage`, `ToolUse → ToolUse`, error `ToolResult → Info`, `AskUser` is bridged to the interaction (answer relayed back), `Usage → TokensUsed("coder", …)`, and a failing interaction fails the turn rather than deadlocking.
- **Given** `Interaction.noninteractive`, **When** `ask` is called, **Then** it fails with `FlowError.Aborted`.

### 31. Resilience: transient retry, usage-limit waiting

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/TransientRetry.scala`, `UsageLimitAware.scala`, `UsageLimitPolicy.scala`, `UsageLimitRetry.scala`, and their specs

- **Given** a transient failure (timeout, rate limit, 5xx, "connection reset", "overloaded", etc.), **When** wrapped by `TransientRetry`, **Then** it retries up to `maxRetries` with exponential backoff, publishing an `Info` notice per retry; streaming restarts from scratch.
- **Given** a non-transient failure (bad request, parse, config, usage cap), **When** wrapped, **Then** it is *not* retried.
- **Given** `maxRetries = 0`, **When** a transient failure occurs, **Then** it fails fast with no retry notice.
- **Given** an enabled `UsageLimitPolicy` and a `UsageLimitError` on an idempotent IO call, **When** wrapped by `UsageLimitAware`, **Then** it sleeps until `resetAt + 30s` (or polls every `pollInterval` if reset is unknown) and retries; it gives up and re-raises if total wait would exceed `maxWait`. Streaming and `isAvailable` pass through.
- **Given** a flow that fails with `FlowError.Llm(_, Some(UsageLimitError))` and an enabled policy, **When** wrapped by `withUsageLimitRetry`, **Then** it waits and re-enters the flow (bounded by `maxReentries` and `maxWait`), relying on plan/session resumability to skip completed work.
- `UsageLimitPolicy` presets: `off` (`enabled = false`) and `patient` (`enabled = true`, `maxWait = 4h`, `pollInterval = 2m`).

### 32. Cost tracking and event tapping

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/CostTracker.scala`, `PriceList.scala`, `EventTappingService.scala`, and their specs

- **Given** a stream of `TokensUsed` events, **When** consumed by `CostTracker`, **Then** usage accumulates per agent and per model; unknown models bucket as "(unknown)" with no cost; cached tokens are shown separately and summed; non-token events are ignored.
- **Given** a known model and usage, **When** `PriceList.costUsd` runs, **Then** it prefix-matches the model to a rate and returns a USD estimate; unknown models return `None`.
- **Given** an `EventTappingService` wrapping a streaming call, **When** the stream runs, **Then** `tool_use` chunks emit `ToolUse` (with summarised input), buffered text flushes to a single `AssistantMessage` at stream end, and usage emits `TokensUsed` tagged with the agent. Non-streaming methods delegate untapped.

### 33. PR summarisation

`modules/llm4zio-flow/src/main/scala/llm4zio/flow/PrSummary.scala`, `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PrSummarySpec.scala`

- **Given** a diff, **When** `summarisePr(reasoning, diff, context?)` runs, **Then** it asks the reasoning connector for JSON `{title, body}` and returns a `PrSummary(title, body)`; backend errors become `FlowError.Llm`.

---

## Part III — `llm4zio-runner`: entry points, wiring, terminal UI

### 34. The script entry point and exit codes

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/Flow.scala`, `modules/llm4zio-runner/src/test/scala/llm4zio/runner/ScriptSpec.scala`

**Capability:** `flow(args){ body }` is the library's *only* `unsafeRun`. It resolves the prompt, installs a Ctrl-C hook, and maps results to exit codes.

- **Given** args and `defaultPrompt`, **When** the prompt is resolved, **Then** the first non-blank arg wins; if all args are blank/absent, `defaultPrompt` is used; if neither yields a prompt, the script fails with `ScriptUsage` *before touching any connector* and exits 2.
- **Given** a Ctrl-C, **When** the hook fires, **Then** the flow fiber is interrupted (stages unwind, banner renders) and the JVM exits 130.
- **Given** a body failure, **When** the run completes, **Then** the ✖ banner is rendered and the process exits 1; on success it exits 0.

### 35. Embedding surface and prompt twin

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala`, `modules/llm4zio-runner/src/test/scala/llm4zio/runner/{ScriptSpec,BannerSpec}.scala`

- **Given** `Llm4zio.script`, **When** invoked, **Then** it resolves the prompt (failing with `ScriptUsage` if absent), derives the reasoning twin, wraps the body with the prompt, and delegates to `run` (it is the pure-ZIO core, testable up to the single unsafe run).
- **Given** no explicit reasoning connector, **When** `scriptReasoning(coder, None)` is computed, **Then** it returns `coder.copy(readOnly = true)`; with an explicit reasoning connector it returns that connector.
- **Given** `Llm4zio.run`, **When** it executes, **Then** it creates a log file, prints a banner with version + log path, resolves the usage policy from `LLM4ZIO_USAGE_WAIT`, selects an animated surface when colour is enabled (else plain) teeing to the log, parses `LLM4ZIO_RETRIES`, builds the `FlowContext`, starts the terminal listener and cost tracker on the hub, wraps the body in usage-limit retry, and on failure drains the hub, logs the full cause, and renders a ✖ banner.
- **Given** a gemini "an unknown error occurred" message, **When** `failMessage` renders it, **Then** a quota hint mentioning `LLM4ZIO_USAGE_WAIT` is appended; unrelated errors get no such hint.

### 36. DefaultFlowContext wiring

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/DefaultFlowContext.scala`, `modules/llm4zio-runner/src/test/scala/llm4zio/runner/DefaultFlowContextSpec.scala`

- **Given** reasoning/coder configs + workDir, **When** `build` runs, **Then** it resolves connectors from a registry, roots the CLI coder in `workDir`, and returns a `FlowContext` plus a fresh `FlowEvents.Hub`.
- **Given** each connector, **When** `make` wires it, **Then** it is wrapped `TransientRetry → EventTappingService → (optional, if policy enabled) UsageLimitAware`; the context's `events` is the same hub exposed to the caller, and both `reasoning` and `coder` are `EventTappingService` instances.
- **Given** an `ApiConnectorConfig` with no base URL, **When** `enrichApi` runs, **Then** the provider default base URL is filled; an explicit base URL is left untouched.
- **Given** an `ApiConnectorConfig` with no API key, **When** `enrichApi` runs, **Then** the key is read from env per provider: Anthropic → `ANTHROPIC_API_KEY`, OpenAI → `OPENAI_API_KEY`, GeminiApi → `GEMINI_API_KEY`/`GOOGLE_API_KEY`.

### 37. Connector presets and selection

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/Connectors.scala`, `modules/llm4zio-runner/src/test/scala/llm4zio/runner/ConnectorsSpec.scala`

- **Given** the presets, **When** read, **Then** `claude` targets ClaudeCli with `permission-mode = acceptEdits`, `codex` targets Codex with `sandbox = workspace-write`, `gemini` targets GeminiCli (auto-approves edits), `pi` targets Pi (runs headless YOLO), and `lmStudio` targets the local API at `http://localhost:1234/v1`.
- **Given** `LLM4ZIO_CODER`, **When** `coderFromEnv()` runs, **Then** "codex"/"gemini"/"pi" select those presets and anything else (or unset) defaults to claude.
- **Given** a preset, **When** `withModel(name)` is applied, **Then** the config's model is pinned.

### 38. Resilience environment knobs

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/{RetryEnv,UsageWaitEnv}.scala` and their specs

- **Given** `LLM4ZIO_RETRIES`, **When** parsed, **Then** unset/blank/invalid/negative → 3; "0" → fail fast; a positive integer → that count (whitespace trimmed).
- **Given** `LLM4ZIO_USAGE_WAIT`, **When** parsed, **Then** unset/""/"off"/"false" → `off`; "on"/"true" → `patient`; "<n>h" → patient with `maxWait = n.hours`; "<n>m" → patient with `maxWait = n.minutes` (case-insensitive); any other non-empty value → patient default.

### 39. Terminal safety (security-relevant)

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/TerminalSafe.scala`, `modules/llm4zio-runner/src/test/scala/llm4zio/runner/TerminalSafeSpec.scala`

**Capability:** All untrusted text (backend stderr, assistant messages, tool output) is sanitised before styling.

- **Given** text with CSI colour/cursor/clear sequences, **When** `sanitize` runs, **Then** they are stripped (e.g. `\x1b[31mred \x1b[0m\x1b[2J\x1b[1;1Hhi` → "redhi").
- **Given** OSC title-set sequences (BEL- or ST-terminated), **When** `sanitize` runs, **Then** they are stripped.
- **Given** control bytes, **When** `sanitize` runs, **Then** C0/C1 bytes and DEL are dropped *except* tab (`\x09`) and newline (`\x0a`), which are preserved.
- **Given** ordinary text including parens/paths (e.g. "● read_file (src/lib.rs)"), **When** `sanitize` runs, **Then** it is unchanged.

### 40. Terminal rendering tree

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/{TerminalListener,TerminalSurface,Palette,Banner,RunnerLog}.scala` and their specs

- **Given** a `FlowEvent` and palette, **When** `TerminalListener.line` renders it, **Then** dynamic fields are sanitised and styled: `StageStarted → "▶ name"`, `StageCompleted → "✔ name"`, `StageFailed → "✖ name — detail"`, `Aborted → "✖ aborted: msg"`, `Info → "· msg"`, `ToolUse → "● tool (args)"`, `AssistantMessage → "● text"` (line breaks preserved, trimmed at ends); `TokensUsed` renders nothing (consumed by the cost tracker).
- **Given** a sequence of events, **When** depths are computed, **Then** `StageStarted` prints at the current depth then increments; `StageCompleted/Failed/Aborted` decrement before printing; others print at the current depth.
- **Given** a failure published just before scope teardown, **When** the listener drains, **Then** `awaitDrained` ensures the `✖` line still renders (no silent drop), bounded by a timeout.
- **Given** `Palette.auto`, **When** evaluated, **Then** colour is disabled if `NO_COLOR` is set or there is no TTY; otherwise enabled. A plain palette emits glyphs with no ANSI escapes.
- **Given** `TerminalSurface.teeingToLog`, **When** a line is logged, **Then** the styled line goes to the surface and an ANSI-stripped copy goes to the log file. `live` animates a braille spinner on a pinned status line (100ms tick, serialised writes); `plain` writes one line per log with no animation.
- **Given** a version and log path, **When** `Banner.line` renders, **Then** it is `"llm4zio <version>, logs: <path>"`.

### 41. MCP over HTTP and interactive coder

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/{McpHttpServer,InteractiveCoder,TerminalInteraction,LiveCliProcessExecutor}.scala` and their specs (incl. `McpHttpServerItSpec`)

- **Given** a bound port, **When** `mcpConfigJson` is built, **Then** it registers `{"mcpServers": {<server>: {"type":"http","url":"http://127.0.0.1:<port>/mcp"}}}`; `allowedToolName(server, tool)` yields `mcp__<server>__<tool>`.
- **Given** the routes, **When** a `tools/call` POST with an `id` arrives, **Then** it returns 200 with the JSON-RPC response; a notification (no `id`) returns 202 with no body; an unparseable body returns a `-32700` error; `GET /mcp` is Method Not Allowed.
- **Given** a real HTTP round trip (integration), **When** a `tools/call` for `ask_user` is sent, **Then** the human answer is returned over the socket.
- **Given** `InteractiveCoder.sessionFlags`, **When** built, **Then** they include `--mcp-config <path>`, `--permission-prompt-tool mcp__<server>__approve`, and `--allowedTools mcp__<server>__ask_user,mcp__<server>__approve`.
- **Given** `LiveCliProcessExecutor`, **When** a command runs, **Then** stdout lines and exit code are captured; a non-existent program fails the effect; bidirectional/stdin variants feed input and read output, with queue shutdown signalling EOF.

### 42. Azure DevOps wiring

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/Ado.scala`, `modules/llm4zio-runner/src/test/scala/llm4zio/runner/AdoSpec.scala`

- **Given** pipeline env vars, **When** `configFrom` runs, **Then** `orgUrl` comes from `SYSTEM_COLLECTIONURI`/`SYSTEM_TEAMFOUNDATIONCOLLECTIONURI`/`LLM4ZIO_ADO_ORG_URL` (trailing slash stripped), `project` from `SYSTEM_TEAMPROJECT`/`LLM4ZIO_ADO_PROJECT`, `repository` from `BUILD_REPOSITORY_NAME`/`LLM4ZIO_ADO_REPO`, and `pat` from `AZURE_DEVOPS_EXT_PAT`/`SYSTEM_ACCESSTOKEN`/`LLM4ZIO_ADO_PAT`; `LLM4ZIO_ADO_*` overrides apply for local runs.
- **Given** a missing required var, **When** `configFrom` runs, **Then** it returns a `Left` naming the missing piece.
- **Given** `Ado.withTool`, **When** invoked, **Then** it provides a live `HttpClient` for the body's duration and constructs an `AdoTool`; the `FlowContext` is untouched. A config failure fails with `FlowError.Aborted`.

### 43. Worked embedded flow

`modules/llm4zio-runner/src/main/scala/llm4zio/runner/ExampleFlow.scala`, `modules/llm4zio-runner/src/it/scala/llm4zio/runner/ExampleFlowSpec.scala`

- **Given** a temp git repo with a local bare remote and a `MockProvider`, **When** `ExampleFlow.run` executes, **Then** it creates a branch, runs each task (coder edit + commit), pushes; afterwards all tasks are completed, `refs/heads/feature-x` exists on the remote, the plan on disk reflects completion, and the event log contains the expected stage events.

---

## Part IV — Worked example flows (`examples/*.sc`)

Each script is a self-documenting flow run with `scala-cli`; all currently pin `llm4zio-runner:3.5.0`. `examples/seed.sh <name> [dest] [--local] [--run]` seeds the mapped starter (`calculator-rs`, `calculator-rs-open`, `calculator-scala`, `todo-java`) into a temp dir, git-inits it, and optionally runs the script; `--local` first `sbt publishLocal`s the in-tree build and repins the script.

Cross-cutting behaviours these flows rely on:
- **Resume:** plans persist under `.llm4zio/` (`plan-*`, `issue-*`, `fix-*`, `wi-*`); re-running the same prompt resumes from the first incomplete task.
- **Coder selection:** `LLM4ZIO_CODER` chooses the coder seat (default claude); the reasoning seat defaults to the read-only twin of the coder unless given explicitly.
- **Reviewer parallelism:** flows set `parallelism = 1` for gemini/local backends (free-tier 429s; single LM Studio instance) and unbounded otherwise.

### 44. implement.sc — autonomous plan → implement → review

- **Given** a prompt, **When** the flow runs, **Then** it recovers-or-creates a plan, checks out the epic branch, starts a coder chat ("implement one task at a time"), and for each task asks the coder, runs `reviewAndFixLoop(Reviewers.minimal, …)`, and commits `"<epicId>: <title>"`. It does not push or open a PR.

### 45. implement-interactive.sc — clarify, then implement

- **Given** a prompt, **When** the flow runs, **Then** the planner may ask clarifying questions on the terminal (`Planner.interactive` + `TerminalInteraction.live`) before proposing the plan; the remainder matches implement.sc.

### 46. implement-enhanced.sc — self-reviewed plan + codebase brief

- **Given** a prompt, **When** the flow runs, **Then** the plan is `from(...).reviewed(...).briefed(...)` (brief stored in the plan file), tasks are prompted with `taskPrompt` (brief prepended), reviews use `Reviewers.all`, and optional `LLM4ZIO_FORMAT`/`LLM4ZIO_LINT` gates run before review/commit.

### 47. implement-enhanced-pr.sc — enhanced plan → push → PR

- **Given** a repo with a remote and authenticated `gh`, **When** the flow runs, **Then** after the enhanced implement loop it pushes the branch, computes the base via `defaultBase`, summarises the PR from `diffVsBase`, opens a PR via `gh.createPr`, deletes the plan file, and checks out the original branch.

### 48. implement-live.sc — steerable held session

- **Given** a prompt, **When** the flow runs, **Then** it plans interactively, opens MCP-backed claude `AgentSession`s (`InteractiveCoder.openSessions`, `ApprovalPolicy.autoApprove`), drives each task via `implementTaskLoopLive` (live streaming + `ask_user` over MCP, tool calls gated by the approval policy), and commits per task. No push/PR.

### 49. epic.sc — multi-task epic + docs update

- **Given** a multi-feature prompt, **When** the flow runs, **Then** it implements each task with the full reviewer roster, then runs a final docs-update step ("update only what's affected") committed as `"docs: update for completed epic"`, and deletes the plan file. No push/PR.

### 50. issue-pr.sc — GitHub issue → assess → implement → PR

- **Given** an `owner/repo#n` arg, **When** the flow runs, **Then** it parses the ref, reads the issue, and `assessThenPlan`: a `Blocked` verdict comments on the issue and stops *without branching*; a `Proceed` verdict branches, implements with `Reviewers.all`, pushes, opens a PR whose body appends "Closes <ref>.", deletes the plan, and returns to the start branch.

### 51. issue-pr-bugfix.sc — failing test → red CI → fix → PR

- **Given** an `owner/repo#n` arg, **When** the flow triages: `NotABug`/`Untestable` comment and stop; `Testable` proceeds.
- **Given** `Testable`, **When** the flow runs, **Then** it writes a failing test, commits and pushes it, opens a tentative PR, and waits for CI; **if CI passes on the failing-test commit it fails loudly** ("the reproduction doesn't reproduce"); otherwise it implements the fix (`from(...).reviewed(...).briefed(...)`, `Reviewers.minimal`), pushes, regenerates the PR summary from the full diff, updates the PR, deletes the plan, and returns to the start branch.

### 52. sdd.sc — spec → tests-first → implement → verify

- **Given** the default (all-gemini) setup, **When** the flow runs, **Then** the specifier/planner run on a Pro model (read-only) and the coder/reviewers on a Flash model; a numbered spec is generated, a plan is derived whose first task encodes the criteria as tests, and the spec is committed to `specs/<epicId>.md`.
- **Given** the task loop, **When** the first (tests) task runs, **Then** its lint gate is `mvn -q test-compile` and, after the review loop, a red-check fails if the new tests pass before implementation; later tasks gate on `mvn -q test`. `parallelism = 1` throughout. A final `mvn -q test` must be green.

### 53. pipeline.sc — outside-in, one scenario per commit

- **Given** the default setup, **When** the flow runs, **Then** it produces a spec and a short design note, derives exactly one task per acceptance criterion (no separate test-writing task), and commits `specs/…` + `docs/design-…`.
- **Given** the acceptance stage (skipped when resuming), **When** it runs, **Then** the coder authors one `@Test` per criterion with all-but-the-first `@Disabled`, the suite must compile, and a red-check fails if the suite is green before implementation; committed as "acceptance tests (red)".
- **Given** the implement stage, **When** each task runs, **Then** the coder enables that one scenario and implements until green while keeping enabled scenarios green, reviewed by `Reviewers.minimal :+ Reviewers.tddDiscipline`, gated on `mvn -q test`, committed one scenario at a time. The verify stage fails if any `@Disabled` remains.

### 54. local.sc / local-claude.sc — fully local

- **Given** no cloud/API key, **When** local.sc runs, **Then** reasoning is LM Studio (`localhost:1234`) and coding is the `pi` CLI routed to LM Studio; `local-claude.sc` instead routes Claude Code at an Anthropic-compatible LM Studio endpoint via `ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN` env vars. Both use `parallelism = 1` and the implement-loop shape, with editable timeouts for slow models. No push/PR.

### 55. ado-spec.sc / ado-implement.sc — Azure DevOps board-gated SDD

- **Given** a numeric work-item id and ADO config, **When** ado-spec.sc runs (gate: **Refine**), **Then** it reads the work item, drafts a spec, writes it to the AcceptanceCriteria field, comments for human review, and moves the item to **Spec Review**. No branch, no code.
- **Given** a numeric id (gate: **Approved**), **When** ado-implement.sc runs, **Then** it reads the human-edited AcceptanceCriteria (falling back to title+description), branches `llm4zio/wi-<id>`, runs the sdd-style spec→tests→implement→verify loop (build/test commands overridable via `LLM4ZIO_BUILD_CMD`/`LLM4ZIO_TEST_CMD`, `parallelism = 1`, red-check on the tests task), opens an Azure Repos PR, links it to the work item, comments the PR URL, and moves the item to **In Review** (merge gated by Azure Repos branch policies).