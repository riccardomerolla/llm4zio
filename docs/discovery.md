# llm4zio — Orientation Brief for New Contributors

## 1. What this system is

**llm4zio** is a **ZIO-native Scala 3 library for talking to LLMs and orchestrating agentic software-development flows.** You write an ordinary `ZIO[R, E, A]` program that plans work, hands coding to an AI agent, reviews the agent's diff with other agents, and commits / pushes / opens a PR — all expressed in code rather than coaxed out of a chatbot.

It is explicitly the **ZIO counterpart to VirtusLab's [orca](https://github.com/VirtusLab/orca)** (which is Ox/direct-style): same values — thin, readable, errors-as-data, no ceremony — re-expressed in the ZIO effect system. The flow layer's shape is a clean-room reimplementation inspired by orca (orca is Apache-2.0; llm4zio is MIT — see `LICENSE`, `README.md:32`).

The README's framing (`README.md:8-26`) captures the philosophy: *"If you want AI-generated code to always be reviewed by another agent, don't coerce the agents — express that requirement in code. Don't spend tokens on formatting, committing, or opening PRs; an ordinary ZIO program handles all of that."*

> **History note.** Per `CLAUDE.md`, llm4zio was once a ~39-module "agentic software house" product and was forked down in 2026 to this focused 3-module library. The full product lives on the `archive/product-2026-06` branch. The current tree is the library only.

Two consumption modes:
- **A library** you embed in a ZIO app (`Llm4zio.run`, `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala`).
- **Single-file flow scripts** (`examples/*.sc`) run with one `scala-cli run` command; artifacts are fetched on first run.

---

## 2. Tech stack

| Concern | Choice | Evidence |
|---|---|---|
| Language | Scala **3.8.3** | `build.sbt:1` |
| Effect system | **ZIO 2.1.25** (`zio`, `zio-streams`) | `build.sbt:13,21-24` |
| Subprocesses | **zio-process 0.8.0** (never raw `ProcessBuilder`) | `build.sbt:14`, `flow/Proc.scala` |
| JSON | **zio-json 0.9.0** (`derives JsonCodec` everywhere) | `build.sbt:15` |
| HTTP | **zio-http 3.10.1** (API providers, MCP server) | `build.sbt:16` |
| Logging | **zio-logging 2.4.0** | `build.sbt:17` |
| Schema/source parsing | **scalameta 4.13.6** (tool-schema generation from method signatures) | `build.sbt:18`, `tools/Tool.scala:75` |
| Terminal colour | **fansi 0.5.0** (runner only) | `build.sbt:29,128` |
| Testing | **zio-test** (+ `-sbt`, `-magnolia`) | `build.sbt:35-39` |
| Build tool | **sbt 2.0.0** | `project/build.properties:1` |
| Lint/format | scalafix, scalafmt, sbt-tpolecat; publishing via sbt-ci-release + sbt-dynver | `project/plugins.sbt` |
| JVM | 21 (CI + scripts) | `.github/workflows/ci.yml:36`, `examples/implement.sc:3` |

Compiler is strict: `-Wunused:all`, `-Werror` (via tpolecat), `-explain` (`build.sbt:67-77`). **A wildcard `import zio.*` pulls in `zio.Task`, which shadows the library's flow `Task` type — import specific names in files that name `Task`** (`CLAUDE.md` conventions).

---

## 3. Module layout & dependency direction

Three published modules under `modules/`, aggregated by a non-published root (`build.sbt:133-138`):

```
llm4zio-runner  →  llm4zio-flow  →  llm4zio-core
```
Dependency arrows never reverse (`build.sbt:103,120-121`). All three publish to Maven Central under `io.github.riccardomerolla`; the root is `publish / skip := true`.

### `llm4zio-core` — LLM plumbing
*Package roots: `llm4zio.core`, `llm4zio.providers`, `llm4zio.tools`, `llm4zio.observability`.*

The provider-agnostic layer. Everything talks through one trait, **`LlmService`** (`core/LlmService.scala:17`), whose surface is: `executeStream`, `executeStreamWithHistory`, `executeWithTools`, `executeStructured[A]` (+ a `…WithUsage` variant), and `isAvailable`. Callers depend on the capability, not the backend.

- **`Connector`** (`core/Connector.scala:13`) extends `LlmService` and adds `id`, `kind`, `healthCheck` (→ `HealthStatus`), and `capabilities`. Two sub-traits: **`ApiConnector`** (`kind = Api`) and **`CliConnector`** (`kind = Cli`, `core/Connector.scala:34`), the latter also declaring `interactionSupport: InteractionSupport` (the enum the default `capabilities` branches on — e.g. why gemini ends up `askUser = false`). CLI connectors implement two primitives (`complete`, `completeStream`) and get the richer `LlmService` methods for free via defaults — e.g. `executeStructured` is derived by injecting a schema hint into `complete` and parsing the text back (`core/Connector.scala:62-64`, `core/StructuredOutputs.scala`).
- **Models** (`core/Models.scala`): `LlmProvider` enum, `ConnectorId`, `Message`/`MessageRole`, `TokenUsage`, `LlmChunk`, `LlmConfig`, plus the `LlmResponse(content, usage, metadata)` and `StreamProgress(tokensProcessed, tokensPerSecond, elapsedMs, estimatedRemainingMs)` value objects. `ConnectorConfig` (`core/ConnectorConfig.scala`) is the user-facing config ADT with **three** cases: `ApiConnectorConfig`, `CliConnectorConfig` (carries `flags`, `sandbox`, `workingDir`, `readOnly`, `turnLimit`, `envVars`), and `FallbackChain(connectors: List[ConnectorConfig])` (tried in order).
- **Registry/factories**: `ConnectorRegistry` + `ConnectorRegistryLive` (`core/ConnectorRegistry.scala`) resolve a config to a live connector; `providers/ConnectorFactories.scala:9` wires the concrete map and exposes `createRegistry(http, cli)` and a `live` ZLayer.
- **Providers** (`llm4zio.providers`, ~30 files):
  - *API:* `OpenAIProvider`, `AnthropicProvider`, `GeminiApiProvider`, `LmStudioProvider`, `OllamaProvider` — streaming, structured output, usage reporting. `OpenCodeProvider` is also API-shaped.
  - *CLI:* `ClaudeCliConnector`, `CodexConnector`, `GeminiCliProvider`, `PiConnector`, `OpenCodeCliConnector`, `CopilotConnector`.
  - *Test:* `MockProvider` — deterministic.
  - Shared infra: `HttpClient.scala` (incl. `reliableClient` with idle-timeout disabled for slow local models — `CHANGELOG.md:11`), `CliProcessExecutor`, `CliStreamJson` (parses claude/codex/gemini stream-JSON; fixtures in `src/test/resources/*-stream.jsonl`), `RateLimiter`, `RetryPolicy`, `UsageLimits`, `AnthropicModels`/`OpenAIModels`/etc. model catalogues.
  - **Capability matrix** is not uniform — declared per connector via `ConnectorCapabilities` (`core/Connector.scala:103`): claude declares full interactive/ask-user/approval; gemini is interactive but cannot expose ask-user headless (`askUser = false`); opencode/copilot are continuation-only; pi runs headless "YOLO". See `README.md:159-166`.
- **`llm4zio.tools`** (`tools/Tool.scala`, `ToolRegistry`, `BuiltInTools`): tool-calling model. Notable: `ToolSchemaGenerator.fromMethodSignature` (`tools/Tool.scala:65`) uses **scalameta to turn a Scala method signature string into a JSON Schema** — Scala types → JSON types, `Option`/defaults → not-required.
- **`llm4zio.observability`**: optional decorators — `MeteredLlmService` (wraps any `LlmService`, records counts/tokens/latency into `LlmMetrics`, `observability/MeteredLlmService.scala:18`), `Langfuse`, `Tracing`, `LlmLogger`, `Metrics`, `Logging`. These exist as wrappers; the runner does **not** wire them by default (it uses flow-layer `EventTappingService`/`CostTracker` instead).

### `llm4zio-flow` — the orca-shaped agentic layer
*Package: `llm4zio.flow`.* This is where the "flow" concepts live.

- **`FlowContext`** (`flow/FlowContext.scala:14`) bundles everything a flow needs: `reasoning` + `coder` (both `LlmService`), `git`, `gh`, `events`, optional `reviewers`, `coderCapabilities`, `userPrompt`, `workDir`. It exposes `given FlowEvents = events` so `stage`/`fail` resolve the sink implicitly.
- **Plan / Task / PlanStore** (`flow/Plan.scala`, `flow/PlanStore.scala`): a `Plan(epicId, tasks, brief?)` persists as **plain Markdown** at `.llm4zio/plan-<hash>.md` (deterministic path from the prompt, `Plan.defaultPath`, `flow/Plan.scala:53`). `parse`/`render` round-trip; `recoverOrCreate` resumes a crashed run. No datastore.
- **Planner** (`flow/Planner.scala`): `from`, `interactive`, `assessThenPlan` (→ `Verdict[Plan]`), `triage` (→ `Triage`), `reviewed` (self-critique), `brief` (returns the codebase-brief *string*) and `briefed` (attaches that brief to a `Plan`). Returns structured types via `executeStructured`. `Triage.scala` also defines `IssueRef` — a small parsed value type with a `parse` method.
- **Chat** (`flow/Chat.scala:13`): a stateful conversation = an accumulating `List[Message]` threaded through `executeStreamWithHistory` (there's no backend session token — continuity is replayed history). **Crucially, `Chat.start` prepends a "runtime owns git" instruction** (`CoderSystem.gitOwnership`) so the coder edits the working tree but does *not* commit/branch/push — keeping diff-based review meaningful. Opt out with `manageGit = true` (`flow/Chat.scala:34`).
- **The loops** (top-level functions):
  - `implementTaskLoop` (`flow/ImplementLoop.scala:13`) — run each incomplete task in a `stage`, persist plan after each (resumable).
  - `reviewAndFixLoop` (`flow/LlmReview.scala:151`) — the centrepiece: optional lint gate → `ReviewerSelector` picks reviewers → fan-out structured reviews over the diff → merge findings → coder fixes → re-review, up to `maxRounds`. A `format` step — `Formatter.step(command, workDir)`, an `IO[FlowError, Unit]` defaulting to `ZIO.unit` — runs before each round; `parallelism` throttles reviewer calls for rate-limited backends.
  - `fixLoop` (`flow/ReviewLoop.scala:11`) — the generic evaluate→fix→re-evaluate primitive. **File split:** `ReviewLoop.scala` holds *only* `fixLoop`; `ReviewerSelector`, `Reviewers`, and `reviewAndFixLoop` live in `LlmReview.scala`.
- **Review system**: `Review.scala` (`Severity`, `ReviewIssue`, `ReviewResult`), `Reviewer.scala` (a named lens = system prompt + optional changed-file regex scope, **loaded from classpath Markdown** under `src/main/resources/llm4zio/review/reviewers/*.md`), `LlmReview.scala` (`Reviewers.all`/`.minimal`/opt-in `tddDiscipline`/`domainLanguage`/`effectShape`; `ReviewerSelector.allEveryRound`/`whileDirty`/`llmDriven`). Shipped lenses: code-functionality, test, readability, code-structure, performance, security, scala-zio, plus the three opt-ins.
- **Side-effect tools over zio-process** (`flow/Proc.scala`):
  - **`GitTool`** (`flow/GitTool.scala`) — branch/commit/diff/push, bound to `workDir`. **Recoverable outcomes are values, not failures**: `createBranch → CreateBranch.AlreadyExists`, `commitAll → Commit.NothingToCommit`. Every git call carries a non-interactive env so a TTY-less flow can't hang on a credential prompt (`GitTool.scala:132`); github pushes get a last-resort `GH_TOKEN` credential helper (`GitTool.scala:170`).
  - **`GhTool`** (`flow/GhTool.scala`) — PR create/update, issue read/comment, CI polling, over the `gh` CLI. `readIssue` retries transient blips; `waitForBuild` polls `gh pr checks` to a terminal `BuildOutcome`; `updatePr` uses a REST PATCH to dodge GitHub Projects-classic sunset (`GhTool.scala:100`).
- **Events**: `FlowEvent` enum + `FlowEvents` sink (`flow/FlowEvents.scala`) — `noop`, `Collecting` (tests), `Hub` (bounded broadcast w/ back-pressure). `stage(name)(effect)` and `fail(message)` (`flow/Stage.scala`) publish events around effects.
- **Errors**: `FlowError` ADT (`flow/FlowError.scala`) — `Persistence`, `PlanParse`, `Aborted`, `Process`, `Llm(message: String, cause: Option[LlmError] = None)` (a required `message`; the underlying `LlmError` is optional). The **recoverable-vs-catastrophic split** (orca-flavoured): expected outcomes go in the value channel; genuine failures fail the effect.
- **Interactivity & safety**: `Interactive.scala` (`Interaction` abstraction), `Approval.scala` (`ApprovalPolicy`/`ApprovalDecision`), `McpServer.scala` (transport-free MCP JSON-RPC handler exposing `ask_user` and `approve` tools to a CLI agent), `Drive.scala` (drives a held `AgentSession` turn, relaying events + bridging questions).
- **Resilience**: `TransientRetry`; `UsageLimitAware` + `UsageLimitPolicy` (presets `off` (default, no waiting) / `patient` (waits up to a cap)) + `UsageLimitRetry.withUsageLimitRetry`; `CostTracker`/`PriceList` (token cost accounting); `EventTappingService` (taps an `LlmService` to emit `FlowEvent`s).
- **PR summarisation** (`flow/PrSummary.scala`): `summarisePr` produces a `PrSummary(title, body)` from a diff — used by the PR-creating examples.
- **Azure DevOps** (`flow/AdoTool.scala`): a REST client for Azure Boards work items + Azure Repos PRs (pure request-builders + parsers, thin effectful methods over `HttpClient`) — drives a board-state-gated SDD flow. Added in v3.4.0 (`CHANGELOG.md:26`).

### `llm4zio-runner` — entry points, terminal UI, wiring
*Package: `llm4zio.runner`.*

- **`flow(args){ body }`** (`runner/Flow.scala:29`) — the **script surface** and the library's *only* `unsafeRun`. Handles arg/prompt resolution, the Ctrl-C shutdown hook, and exit codes (2 = usage, 1 = failure, 130 = SIGINT).
- **`Llm4zio.run` / `Llm4zio.script`** (`runner/Llm4zio.scala`) — the **embedding surface** for real `ZIOAppDefault` apps: builds the `FlowContext`, streams progress to the terminal, provides the http/process layers, wraps the body in usage-limit retry, and renders a final ✖ banner on failure. `script` is the pure-ZIO core of `flow` (testable up to the single unsafe run).
- **`DefaultFlowContext`** (`runner/DefaultFlowContext.scala`) — wiring: resolves connectors from the registry, roots the CLI coder in `workDir`, and **taps each connector** through `TransientRetry → EventTappingService → (optional) UsageLimitAware` (`DefaultFlowContext.scala:28-33`). API configs get base URL + env API key filled in (`enrichApi`, reads `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`/`GEMINI_API_KEY`).
- **`Connectors`** (`runner/Connectors.scala`) — ready-made presets so scripts reference `claude`/`codex`/`gemini`/`pi`/`lmStudio` bare; `coderFromEnv()` reads `LLM4ZIO_CODER`, accepting `"codex"`/`"gemini"`/`"pi"` (mapping to those presets) and defaulting to `claude`. Each preset carries the edit-enabling flag (e.g. claude `permission-mode=acceptEdits`).
- **Resilience env knobs**: `RetryEnv` (`runner/RetryEnv.scala`) reads `LLM4ZIO_RETRIES` (transient-retry count, default 3); `UsageWaitEnv` (`runner/UsageWaitEnv.scala`) reads `LLM4ZIO_USAGE_WAIT` (`off`/`on`/`Nh`/`Nm`) to configure usage-limit waiting. These are the env-var tuning knobs for resilience.
- **Terminal rendering**: `TerminalListener`, `TerminalSurface`/`TerminalSafe`, `Palette`, `Banner`, `RunnerLog` — an event log that grows top-down plus a pinned status line with a spinner; teed to a per-run log file. Auto-disables colour off-TTY / under `NO_COLOR`. `TerminalSafe` is security-relevant: it strips ANSI CSI/OSC escapes and C0/C1 control bytes from all untrusted text (backend stderr, assistant messages, tool output) before styling.
- **MCP over HTTP**: `McpHttpServer` binds `flow.McpServer` over zio-http and registers it with a claude agent; `InteractiveCoder` + `TerminalInteraction` route `ask_user`/approval back to the operator (powers `examples/implement-live.sc`). The bidirectional held-session implementation behind this is `ClaudeAgentSession` (core) — the pointer for adding a new interactive connector.
- **`ExampleFlow`** (`runner/ExampleFlow.scala`) — the embedded `ZIOAppDefault` variant with an end-to-end integration test.
- **`Ado`** (`runner/Ado.scala`) — builds an `AdoTool` from ADO pipeline env vars.

---

## 4. Entry points (where to start reading)

| You want to… | Start here |
|---|---|
| See the canonical flow shape | `examples/implement.sc` (32 lines) and the matching README snippet (`README.md:42-62`) |
| Understand the script runtime | `runner/Flow.scala` → `runner/Llm4zio.scala` (`script`→`run`) |
| Understand the context object | `flow/FlowContext.scala` |
| Understand the review loop | `flow/LlmReview.scala:151` (`reviewAndFixLoop`) |
| Understand provider abstraction | `core/LlmService.scala` → `core/Connector.scala` → `providers/ConnectorFactories.scala` |
| Embed in a ZIO app | `runner/ExampleFlow.scala` + `Llm4zio.run` |

A flow reads top-to-bottom (`examples/implement.sc:21-32`):
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
Bare names (`git`, `gh`, `coder`, `reasoning`, `reviewers`, `userPrompt`, `workDir`) resolve from the `FlowContext ?=>` context function via the accessor extension methods in `flow/ContextAccess.scala`.

### Worked examples (`examples/`, see `examples/README.md`)
15 scripts, each a self-documenting `.sc`. Highlights: `implement.sc` (autonomous), `implement-interactive.sc` (clarifying Qs), `implement-enhanced.sc` (plan self-review + brief), `implement-enhanced-pr.sc` (enhanced plan → branch → implement → push → open PR — the PR step `implement-enhanced.sc` lacks), `implement-live.sc` (held steerable claude session over MCP), `epic.sc` (full roster + doc stage), `issue-pr.sc` / `issue-pr-bugfix.sc` (GitHub issue→PR), `sdd.sc` & `pipeline.sc` (spec-driven), `local.sc` / `local-claude.sc` (fully local via LM Studio), `ado-spec.sc` / `ado-implement.sc` (Azure DevOps). `examples/starters/` holds seed projects (Rust, Scala, Java) and `examples/seed.sh` seeds + runs them.

---

## 5. Build, test, run

Built with **sbt 2.x**. Note sbt 2's `test` is **incremental/cached** — use `testFull` to force the full run that CI does (`CLAUDE.md`, `.github/workflows/ci.yml:47`).

```bash
sbt compile                          # all modules
sbt test                             # unit tests (incremental)
sbt testFull                         # force full unit run (CI behaviour)
sbt "llm4zioFlow/It/testFull"        # integration tests (spawn real git; no network)
sbt fmt                              # scalafix + scalafmt (apply)
sbt check                            # scalafix --check + scalafmtCheck (verify)

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

**Testing approach (TDD-first).** zio-test throughout; the `Mock` provider gives deterministic LLM behaviour. **Integration tests** spawn real `git` and use a temp repo + a local *bare* remote — **no network** (`src/it/scala`, config `lazy val It = config("it") extend Test`, `build.sbt:80`). Examples: `flow/GitToolSpec.scala` (It), `runner/ExampleFlowSpec.scala` (It, end-to-end), `runner/McpHttpServerItSpec.scala`. Unit specs are extensive (~one per source file) under each module's `src/test/scala`.

**CI** (`.github/workflows/ci.yml`): one sbt invocation — `; check ; testFull ; llm4zioFlow/It/testFull ; llm4zioRunner/It/testFull` — on JDK 21/Temurin. A tag `v*` triggers the `publish` job (`sbt ci-release` to Maven Central, GPG-signed, + a GitHub Release). `CHANGELOG.md` tracks releases (top entry 3.4.1, 2026-06-17; scripts in-tree pin newer deps, so verify the current tag rather than trusting any single number).

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

A flow is an ordinary ZIO program handed a `FlowContext`. It asks the **reasoning** connector to produce a structured `Plan` (persisted as resumable Markdown), checks out a branch via `GitTool`, then loops over tasks: the **coder** CLI agent edits the working tree through a `Chat`, the **reviewers** judge the resulting `git.diff` and feed findings back to the coder until clean, and the runtime commits/pushes/opens the PR via `GitTool`/`GhTool`. Every step emits `FlowEvent`s that the runner renders to the terminal and tees to a log. Providers are uniform behind `LlmService`; recoverable situations are typed values, real failures are typed errors; nothing touches a database and no secrets are stored — the agent CLIs and `gh`/`git` manage their own auth.