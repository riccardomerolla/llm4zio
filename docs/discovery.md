# llm4zio — Orientation Brief for New Contributors

## 1. What this system is

**llm4zio** is a **ZIO-native Scala 3 library for talking to LLMs and orchestrating agentic software-development flows.** You write an ordinary `ZIO[R, E, A]` program that plans work, hands coding to an AI agent, reviews the agent's diff with other agents, and commits / pushes / opens a PR — all expressed in code rather than coaxed out of a chatbot.

It is explicitly the **ZIO counterpart to VirtusLab's [orca](https://github.com/VirtusLab/orca)** (which is Ox/direct-style): same values — thin, readable, errors-as-data, no ceremony — re-expressed in the ZIO effect system. The flow layer's shape is a clean-room reimplementation inspired by orca (orca is Apache-2.0; llm4zio is MIT — see `LICENSE`, `README.md`). Several flow pieces are noted as direct ports of orca heuristics (e.g. `flow/ToolInputSummary.scala`).

The README's framing captures the philosophy: *"If you want AI-generated code to always be reviewed by another agent, don't coerce the agents — express that requirement in code. Don't spend tokens on formatting, committing, or opening PRs; an ordinary ZIO program handles all of that."*

> **History note.** Per `CLAUDE.md`, llm4zio was once a ~39-module "agentic software house" product and was forked down in 2026 to this focused 3-module library. The full product lives on the `archive/product-2026-06` branch. The current tree is the library only.

Two consumption modes:
- **A library** you embed in a ZIO app (`Llm4zio.run`, `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala`).
- **Single-file flow scripts** (`examples/*.sc`) run with one `scala-cli run` command; artifacts are fetched on first run.

---

## 2. Tech stack

| Concern | Choice | Evidence |
|---|---|---|
| Language | Scala **3.8.3** | `build.sbt:1` |
| Effect system | **ZIO 2.1.25** (`zio`, `zio-streams`) | `build.sbt` (`zioVersion`) |
| Subprocesses | **zio-process 0.8.0** (never raw `ProcessBuilder`) | `build.sbt`, `flow/Proc.scala` |
| JSON | **zio-json 0.9.0** (`derives JsonCodec` everywhere) | `build.sbt` |
| HTTP | **zio-http 3.10.1** (API providers, MCP-over-HTTP, ADO REST) | `build.sbt` |
| Logging | **zio-logging 2.4.0** | `build.sbt` |
| Schema/source parsing | **scalameta 4.13.6** (tool-schema generation from method signatures) | `build.sbt`, `tools/Tool.scala` |
| Terminal colour | **fansi 0.5.0** (runner only) | `build.sbt` |
| Testing | **zio-test** (+ `-sbt`, `-magnolia`), in `test` and `it` | `build.sbt` (`zioTestDeps`) |
| Build tool | **sbt 2.0.0** | `project/build.properties:1` |
| Lint/format | sbt-scalafix 0.14.6, sbt-scalafmt 2.6.1, sbt-tpolecat 0.5.6; publishing via sbt-ci-release 1.11.2 + sbt-dynver 5.1.1 | `project/plugins.sbt` |
| JVM | 21 (CI + scripts) | `.github/workflows/ci.yml`, `examples/*.sc` |

Compiler is strict: `-Wunused:all`, `-Werror` (via tpolecat), `-explain`, `-Xmax-inlines 128` (`build.sbt`). Note `build.sbt` excludes the Scala-2.13 `sourcecode` that scalameta drags in and silences the deprecated-`-Xfatal-warnings` warning so it doesn't itself fail under `-Werror`. **A wildcard `import zio.*` pulls in `zio.Task`, which shadows the library's flow `Task` type — import specific names in files that name `Task`** (`CLAUDE.md` conventions).

---

## 3. Module layout & dependency direction

Three published modules under `modules/`, aggregated by a non-published root (`build.sbt`):

```
llm4zio-runner  →  llm4zio-flow  →  llm4zio-core
```
Dependency arrows never reverse (`build.sbt`: `llm4zioFlow.dependsOn(llm4zioCore)`, `llm4zioRunner.dependsOn(llm4zioFlow, llm4zioCore)`). All three publish to Maven Central under `io.github.riccardomerolla`; the root is `publish / skip := true`.

### `llm4zio-core` — LLM plumbing
*Package roots: `llm4zio.core`, `llm4zio.providers`, `llm4zio.tools`, `llm4zio.observability`.*

The provider-agnostic layer. Everything talks through one trait, **`LlmService`** (`core/LlmService.scala`), whose surface is: `executeStream`, `executeStreamWithHistory`, `executeWithTools`, `executeStructured[A]` (+ a `…WithUsage` variant), and `isAvailable`. Callers depend on the capability, not the backend.

- **`Connector`** (`core/Connector.scala`) extends `LlmService` and adds `id`, `kind`, `healthCheck` (→ `HealthStatus`), and `capabilities`. Two sub-traits: **`ApiConnector`** (`kind = Api`) and **`CliConnector`** (`kind = Cli`), the latter also declaring `interactionSupport: InteractionSupport` (the enum the default `capabilities` branches on — e.g. why gemini ends up `askUser = false`). CLI connectors implement two primitives (`complete`, `completeStream`) and get the richer `LlmService` methods for free via defaults — e.g. `executeStructured` is derived by injecting a schema hint into `complete` and parsing the text back (`core/Connector.scala`, `core/StructuredOutputs.scala`).
- **Models** (`core/Models.scala`): `LlmProvider` enum, `ConnectorId`, `Message`/`MessageRole`, `TokenUsage`, `LlmChunk`, `LlmConfig`, plus the `LlmResponse(content, usage, metadata)` and `StreamProgress(…)` value objects. `ConnectorConfig` (`core/ConnectorConfig.scala`) is the user-facing config ADT with **three** cases: `ApiConnectorConfig`, `CliConnectorConfig` (carries `flags`, `sandbox`, `workingDir`, `readOnly`, `turnLimit`, `envVars`), and `FallbackChain(connectors: List[ConnectorConfig])` (tried in order).
- **Conversation & context** (`core/Conversation.scala`, `core/ContextManagement.scala`): conversation/history value types and context-window management used by the structured/streaming paths. `core/SchemaDerivation.scala` derives JSON schema for `executeStructured`.
- **Held interactive sessions**: `core/AgentSession.scala` (the abstraction) and `providers/ClaudeAgentSession.scala` (concrete bidirectional claude session) — the pointer for adding a new interactive connector.
- **Registry/factories**: `ConnectorRegistry` + `ConnectorRegistryLive` (`core/ConnectorRegistry.scala`) resolve a config to a live connector; `providers/ConnectorFactories.scala` wires the concrete map and exposes `createRegistry(http, cli)` and a `live` ZLayer.
- **Providers** (`llm4zio.providers`, ~22 files):
  - *API:* `OpenAIProvider`, `AnthropicProvider`, `GeminiApiProvider`, `LmStudioProvider`, `OllamaProvider` — streaming, structured output, usage reporting. `OpenCodeProvider` is also API-shaped.
  - *CLI:* `ClaudeCliConnector`, `CodexConnector`, `GeminiCliProvider`, `PiConnector`, `OpenCodeCliConnector`, `CopilotConnector`.
  - *Test:* `MockProvider` — deterministic.
  - Shared infra: `HttpClient.scala` (incl. `reliableClient` with idle-timeout disabled so a slow local model's per-request `timeout` is the only bound — `CHANGELOG.md` 3.4.1; plus a generic `send(method, url, body, headers, contentType)` added for the ADO REST client), `CliProcessExecutor`, `CliStreamJson` (parses claude/codex/gemini stream-JSON; fixtures in `src/test/resources/*-stream.jsonl`), `RateLimiter`, `RetryPolicy`, `UsageLimits`, and `AnthropicModels`/`OpenAIModels`/`GeminiModels`/`LmStudioModels` catalogues.
  - **claude read-only nuance**: when `readOnly` is set, `ClaudeCliConnector` uses **default** permission mode plus `disallowed-tools=Write,Edit,NotebookEdit` so the reasoning seat answers directly (plan mode would instead make claude *propose a plan*, breaking `executeStructured`) — `providers/ClaudeCliConnector.scala`, fix in commit `0b34a15f`.
  - **Capability matrix** is not uniform — declared per connector via `ConnectorCapabilities` (`core/Connector.scala`): claude declares full interactive/ask-user/approval; gemini is interactive but cannot expose ask-user headless (`askUser = false`); opencode/copilot are continuation-only; pi runs headless "YOLO". See `README.md`.
- **`llm4zio.tools`** (`tools/Tool.scala`, `ToolRegistry`, `BuiltInTools`): tool-calling model. Notable: `ToolSchemaGenerator.fromMethodSignature` uses **scalameta to turn a Scala method signature string into a JSON Schema** — Scala types → JSON types, `Option`/defaults → not-required.
- **`llm4zio.observability`**: optional decorators — `MeteredLlmService` (wraps any `LlmService`, records counts/tokens/latency into `LlmMetrics`), `Langfuse`, `Tracing`, `LlmLogger`, `Metrics`, `Logging`. These exist as wrappers; the runner does **not** wire them by default (it uses flow-layer `EventTappingService`/`CostTracker` instead).

### `llm4zio-flow` — the orca-shaped agentic layer
*Package: `llm4zio.flow`.* This is where the "flow" concepts live (35 source files).

- **`FlowContext`** (`flow/FlowContext.scala`) bundles everything a flow needs: `reasoning` + `coder` (both `LlmService`), `git`, `gh`, `events`, optional `reviewers`, `coderCapabilities`, `userPrompt`, `workDir`. It exposes `given FlowEvents = events` so `stage`/`fail` resolve the sink implicitly. Bare-name accessors live in `flow/ContextAccess.scala`.
- **Plan / Task / PlanStore** (`flow/Plan.scala`, `flow/PlanStore.scala`): a `Plan(epicId, tasks, brief?)` persists as **plain Markdown** at `.llm4zio/plan-<hash>.md` (deterministic path from the prompt, `Plan.defaultPath`). `parse`/`render` round-trip; `recoverOrCreate` resumes a crashed run. No datastore.
- **Planner** (`flow/Planner.scala`): `from`, `interactive`, `assessThenPlan` (→ `Verdict[Plan]`), `triage` (→ `Triage`), `reviewed` (self-critique), `brief` (returns the codebase-brief *string*) and `briefed` (attaches that brief to a `Plan`). Its `defaultInstructions` now push for **thin outcome slices** — "each a thin slice that delivers an observable outcome (split by outcome, not by technical layer), described in terms of behaviour, not mechanism" (commit `dad215d9`). Returns structured types via `executeStructured`. `Triage.scala` also defines `IssueRef` — a small parsed value type with a `parse` method. `Verdict.scala` is the shared assess result.
- **Chat** (`flow/Chat.scala`): a stateful conversation = an accumulating `List[Message]` threaded through `executeStreamWithHistory` (there's no backend session token — continuity is replayed history). **Crucially, `Chat.start` prepends a "runtime owns git" instruction** (`flow/CoderSystem.scala`, `CoderSystem.gitOwnership`) so the coder edits the working tree but does *not* commit/branch/push — keeping diff-based review meaningful. Opt out with `manageGit = true`.
- **The loops** (top-level functions):
  - `implementTaskLoop` (`flow/ImplementLoop.scala`) — run each incomplete task in a `stage`, persist plan after each (resumable).
  - `reviewAndFixLoop` (`flow/LlmReview.scala`) — the centrepiece: optional lint gate → `ReviewerSelector` picks reviewers → fan-out structured reviews over the diff → merge findings → coder fixes → re-review, up to `maxRounds`. A `format` step — `Formatter.step(command, workDir)`, an `IO[FlowError, Unit]` defaulting to `ZIO.unit` — runs before each round; `parallelism` throttles reviewer calls for rate-limited/local backends (e.g. `local.sc` runs `parallelism = 1`).
  - `fixLoop` (`flow/ReviewLoop.scala`) — the generic evaluate→fix→re-evaluate primitive. **File split:** `ReviewLoop.scala` holds *only* `fixLoop`; `ReviewerSelector`, `Reviewers`, and `reviewAndFixLoop` live in `LlmReview.scala`.
- **Review system**: `Review.scala` (`Severity`, `ReviewIssue`, `ReviewResult`), `Reviewer.scala` (a named lens = system prompt + optional changed-file regex scope, **loaded from classpath Markdown** via `Reviewer.fromResource`, under `src/main/resources/llm4zio/review/reviewers/*.md`), `LlmReview.scala` (`Reviewers.all`/`.minimal` + opt-in lenses; `ReviewerSelector.allEveryRound`/`whileDirty`/`llmDriven(picker)`). **Ten shipped lenses** (one MD each): code-functionality, test, readability, code-structure, performance, security, scala-zio, plus three opt-ins added later — `tdd-discipline`, `domain-language`, `effect-shape` (`Reviewers.tddDiscipline`/`domainLanguage`/`effectShape`, commits `d1c54ad5`, `7695a7a5`); append them to a roster, e.g. `Reviewers.minimal :+ Reviewers.tddDiscipline`.
- **Side-effect tools over zio-process** (`flow/Proc.scala`):
  - **`GitTool`** (`flow/GitTool.scala`) — branch/commit/diff/push, bound to `workDir`. **Recoverable outcomes are values, not failures**: `createBranch → CreateBranch.AlreadyExists`, `commitAll → Commit.NothingToCommit`. Every git call carries a non-interactive env (`GIT_TERMINAL_PROMPT=0`, ssh `BatchMode=yes`) so a TTY-less flow fails fast instead of hanging on a credential prompt; github pushes append a last-resort, github-scoped `GH_TOKEN`/`GITHUB_TOKEN` credential helper read at helper runtime (never in argv/logs), falling back to `gh auth git-credential` (`CHANGELOG.md` 3.3.0).
  - **`GhTool`** (`flow/GhTool.scala`) — PR create/update, issue read/comment, CI polling, over the `gh` CLI. `readIssue` retries transient blips; `waitForBuild` polls `gh pr checks` to a terminal `BuildOutcome`; `updatePr` uses a REST `PATCH` via `gh api` to dodge the GitHub Projects-classic sunset that breaks `gh pr edit` (`CHANGELOG.md` 3.3.0).
- **Events & terminal-tree helpers**: `FlowEvent` enum + `FlowEvents` sink (`flow/FlowEvents.scala`) — `noop`, `Collecting` (tests), `Hub` (bounded broadcast w/ back-pressure). `stage(name)(effect)` and `fail(message)` (`flow/Stage.scala`) publish events around effects. `flow/ToolInputSummary.scala` compresses a tool call's raw JSON args into a compact `(…)` summary for the terminal tree (e.g. `{"file_path":"src/lib.rs"}` → `(src/lib.rs)`; ported from orca).
- **Errors**: `FlowError` ADT (`flow/FlowError.scala`) — `Persistence`, `PlanParse`, `Aborted`, `Process`, `Llm(message: String, cause: Option[LlmError] = None)`. The **recoverable-vs-catastrophic split** (orca-flavoured): expected outcomes go in the value channel; genuine failures fail the effect.
- **Interactivity & safety**: `Interactive.scala` (`Interaction` abstraction), `Approval.scala` (`ApprovalPolicy`/`ApprovalDecision`), `McpServer.scala` (transport-free MCP JSON-RPC handler exposing `ask_user` and `approve` tools to a CLI agent), `Drive.scala` (drives a held `AgentSession` turn, relaying events + bridging questions).
- **Resilience**: `TransientRetry`; `UsageLimitAware` + `UsageLimitPolicy` (presets `off` (default) / `patient`) + `UsageLimitRetry.withUsageLimitRetry`; `CostTracker`/`PriceList` (token cost accounting); `EventTappingService` (taps an `LlmService` to emit `FlowEvent`s).
- **PR summarisation** (`flow/PrSummary.scala`): `summarisePr` produces a `PrSummary(title, body)` from a diff — used by the PR-creating examples.
- **Azure DevOps** (`flow/AdoTool.scala`): a REST client for Azure Boards work items (read/WIQL/comment/set fields-state-tags) + Azure Repos PRs and work-item↔PR linking — pure request-builders + parsers (unit-tested), thin effectful methods over `HttpClient`. Drives a board-state-gated SDD flow. Added in v3.4.0 (`CHANGELOG.md`).

### `llm4zio-runner` — entry points, terminal UI, wiring
*Package: `llm4zio.runner` (18 source files).*

- **`flow(args){ body }`** (`runner/Flow.scala`) — the **script surface** and the library's *only* `unsafeRun`. Handles arg/prompt resolution, the Ctrl-C shutdown hook, and exit codes (2 = usage, 1 = failure, 130 = SIGINT).
- **`Llm4zio.run` / `Llm4zio.script`** (`runner/Llm4zio.scala`) — the **embedding surface** for real `ZIOAppDefault` apps: builds the `FlowContext`, streams progress to the terminal, provides the http/process layers, wraps the body in usage-limit retry, and renders a final ✖ banner on failure. `script` is the pure-ZIO core of `flow` (testable up to the single unsafe run).
- **`DefaultFlowContext`** (`runner/DefaultFlowContext.scala`) — wiring: resolves connectors from the registry, roots the CLI coder in `workDir`, and **taps each connector** through `TransientRetry → EventTappingService → (optional) UsageLimitAware`. API configs get base URL + env API key filled in (`enrichApi`, reads `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`/`GEMINI_API_KEY`).
- **`Connectors`** (`runner/Connectors.scala`) — ready-made presets so scripts reference `claude`/`codex`/`gemini`/`pi`/`lmStudio` bare; `coderFromEnv()` reads `LLM4ZIO_CODER`, accepting `"codex"`/`"gemini"`/`"pi"` and defaulting to `claude`. Each preset carries the edit-enabling flag (e.g. claude `permission-mode=acceptEdits`).
- **Resilience env knobs**: `RetryEnv` (`runner/RetryEnv.scala`) reads `LLM4ZIO_RETRIES` (default 3); `UsageWaitEnv` (`runner/UsageWaitEnv.scala`) reads `LLM4ZIO_USAGE_WAIT` (`off`/`on`/`Nh`/`Nm`) to configure usage-limit waiting.
- **Terminal rendering**: `TerminalListener`, `TerminalSurface`/`TerminalSafe`, `Palette`, `Banner`, `RunnerLog` — an event log that grows top-down plus a pinned status line with a spinner; teed to a per-run log file. Auto-disables colour off-TTY / under `NO_COLOR`. `TerminalSafe` is security-relevant: it strips ANSI CSI/OSC escapes and C0/C1 control bytes from all untrusted text (backend stderr, assistant messages, tool output) before styling.
- **MCP over HTTP**: `McpHttpServer` binds `flow.McpServer` over zio-http and registers it with a claude agent; `InteractiveCoder` + `TerminalInteraction` route `ask_user`/approval back to the operator (powers `examples/implement-live.sc`). `LiveCliProcessExecutor` is the real subprocess executor used at runtime.
- **`ExampleFlow`** (`runner/ExampleFlow.scala`) — the embedded `ZIOAppDefault` variant with an end-to-end integration test.
- **`Ado`** (`runner/Ado.scala`) — `Ado.withTool`/`Ado.configFrom` build an `AdoTool` from ADO pipeline env vars (`SYSTEM_*`, `LLM4ZIO_ADO_*` overrides) and provide a live HTTP client for the flow's duration; `FlowContext` is untouched.

---

## 4. Entry points (where to start reading)

| You want to… | Start here |
|---|---|
| See the canonical flow shape | `examples/implement.sc` and the matching README snippet |
| Understand the script runtime | `runner/Flow.scala` → `runner/Llm4zio.scala` (`script`→`run`) |
| Understand the context object | `flow/FlowContext.scala` + `flow/ContextAccess.scala` |
| Understand the review loop | `flow/LlmReview.scala` (`reviewAndFixLoop`) |
| Understand provider abstraction | `core/LlmService.scala` → `core/Connector.scala` → `providers/ConnectorFactories.scala` |
| Embed in a ZIO app | `runner/ExampleFlow.scala` + `Llm4zio.run` |

A flow reads top-to-bottom (`examples/implement.sc`):
```scala
flow(args, defaultPrompt = Some("Add a multiply function to the calculator crate")):
  val planPath = Plan.defaultPath(userPrompt)
  for
    plan      <- PlanStore.recoverOrCreate(planPath)(Planner.from(reasoning, userPrompt))
    _         <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     reviewAndFixLoop(Reviewers.minimal, reasoning, coderChat, task.title, git.diff) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
  yield ()
```
Bare names (`git`, `gh`, `coder`, `reasoning`, `reviewers`, `userPrompt`, `workDir`) resolve from the `FlowContext ?=>` context function via accessor extensions in `flow/ContextAccess.scala`.

### Worked examples (`examples/`, see `examples/README.md`)
**15 scripts**, each a self-documenting `.sc` whose header comment is the docs. They currently pin `llm4zio-runner:3.5.0`. Each maps to a starter project in `examples/starters/` (`calculator-rs`, `calculator-rs-open`, `calculator-scala`, `todo-java`); `examples/seed.sh <name>` seeds a temp dir (`--run` to run, `--local` to test the in-tree `publishLocal` build).

| Script | What it shows |
|---|---|
| `implement.sc` | Autonomous plan → implement → review loop |
| `implement-interactive.sc` | Planner asks clarifying questions first |
| `implement-enhanced.sc` | Plan self-review + shared codebase brief (`.reviewed`/`.briefed`) |
| `implement-enhanced-pr.sc` | Enhanced plan → branch → implement → push → open PR (needs remote + `gh`) |
| `implement-live.sc` | Held, steerable claude session, streaming + `ask_user` over MCP |
| `epic.sc` | Multi-task epic, full reviewer roster, doc update at the end |
| `issue-pr.sc` | GitHub issue → assess → implement → PR |
| `issue-pr-bugfix.sc` | Bug report → failing test → red CI → fix → PR |
| `sdd.sc` | Spec → tests-first → implement → verify; per-role gemini models; `mvn` as the gate |
| `pipeline.sc` | Specify → design → acceptance → implement → verify; outside-in, one scenario per commit |
| `reverse-engineer.sc` | Read-only: discover → architecture → domain → ADRs → reverse-spec → review |
| `local.sc` | Fully local — reasoning on LM Studio, coding on pi; no cloud/API key |
| `local-claude.sc` | Fully local — reasoning on LM Studio, coding on Claude Code routed to LM Studio |
| `ado-spec.sc` | Azure DevOps: card→Refine → draft spec onto the work item → Spec Review |
| `ado-implement.sc` | Azure DevOps: card→Approved → spec→tests→implement → PR linked to work item |

---

## 5. Build, test, run

Built with **sbt 2.x**. Note sbt 2's `test` is **incremental/cached** — use `testFull` to force the full run that CI does (`CLAUDE.md`, `.github/workflows/ci.yml`).

```bash
sbt compile                          # all modules
sbt test                             # unit tests (incremental)
sbt testFull                         # force full unit run (CI behaviour)
sbt "llm4zioFlow/It/testFull"        # integration tests (spawn real git; no network)
sbt fmt                              # scalafixAll + scalafmtAll (apply)
sbt check                            # scalafixAll --check + scalafmtCheckAll (verify)

# Per-module / single spec:
sbt llm4zioCore/test
sbt 'llm4zioFlow/testOnly llm4zio.flow.PlanSpec'
```

**Running a flow script** (no install beyond JDK + the agent CLIs):
```bash
scala-cli run examples/implement.sc -- "your task here"
examples/seed.sh implement --run            # seed a starter + run vs Maven Central
examples/seed.sh implement --local --run    # run vs your in-tree build (sbt publishLocal)
```

**Testing approach (TDD-first).** zio-test throughout; the `Mock` provider gives deterministic LLM behaviour. **Integration tests** spawn real `git` and use a temp repo + a local *bare* remote — **no network** (`src/it/scala`, config `lazy val It = config("it") extend Test`, `build.sbt`). Examples: `flow/GitToolSpec.scala` (It), `runner/ExampleFlowSpec.scala` (It, end-to-end), `runner/McpHttpServerItSpec.scala`. Unit specs are extensive (≈one per source file) under each module's `src/test/scala`.

**CI** (`.github/workflows/ci.yml`): one sbt invocation — `; check ; testFull ; llm4zioFlow/It/testFull ; llm4zioRunner/It/testFull` — on JDK 21/Temurin. A tag `v*` triggers the `publish` job (`sbt ci-release` to Maven Central, GPG-signed, + a GitHub Release).

**Versions are a moving target — check the tag, not this doc.** Git tags reach **v3.6.1**, the example scripts pin **3.5.0**, and `CHANGELOG.md`'s top entry is **3.4.1** (the changelog lags the tags). Verify the actual published tag rather than trusting any single number here.

---

## 6. Conventions a contributor must internalize (from `CLAUDE.md`)

- **ZIO-native throughout** — no `Future`, no blocking-by-default; wrap blocking work in `ZIO.attemptBlocking`; subprocesses go through `flow.Proc` (zio-process), never raw `ProcessBuilder`.
- **Typed errors, no `Throwable` in signatures** — core uses `LlmError`; flow uses `FlowError`.
- **Recoverable vs catastrophic** — expected, handleable outcomes are returned as typed *values* (`Commit.NothingToCommit`, `CreateBranch.AlreadyExists`); genuine failures fail the effect.
- **No `var`** — use `Ref`/`Queue`/`Hub`.
- **Stateless + plain files** — no datastore; resumable plans are Markdown under `.llm4zio/`.
- **Role split** — *reasoning* (planning/review/structured output) runs over an **API** connector (`reasoning`); *code-editing* runs over a **CLI** coding agent (`coder`). `FlowContext` carries both. A single all-CLI backend (e.g. all-claude) can do both.
- **The runtime owns git** — coder chats are seeded not to commit/push/branch; the flow does that via `ctx.git.*`.
- **`-Werror`/`-Wunused:all`** — unused imports are fatal; mind the `zio.Task` vs flow `Task` shadowing.
- **TDD** — every behaviour is driven by a test first; use `Mock` for determinism.

---

## 7. Mental model in one paragraph

A flow is an ordinary ZIO program handed a `FlowContext`. It asks the **reasoning** connector to produce a structured `Plan` (persisted as resumable Markdown), checks out a branch via `GitTool`, then loops over tasks: the **coder** CLI agent edits the working tree through a `Chat`, the **reviewers** judge the resulting `git.diff` and feed findings back to the coder until clean, and the runtime commits/pushes/opens the PR via `GitTool`/`GhTool`. Every step emits `FlowEvent`s that the runner renders to a terminal tree (with control sequences stripped for safety) and tees to a log. Providers are uniform behind `LlmService`; recoverable situations are typed values, real failures are typed errors; nothing touches a database and no secrets are stored — the agent CLIs and `gh`/`git` manage their own auth.