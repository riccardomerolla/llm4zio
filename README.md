# llm4zio

[![Maven Central](https://img.shields.io/maven-central/v/io.github.riccardomerolla/llm4zio-core.svg)](https://mvnrepository.com/artifact/io.github.riccardomerolla)
[![Scala 3](https://img.shields.io/badge/Scala-3.8-red)](https://www.scala-lang.org/)
[![ZIO 2](https://img.shields.io/badge/ZIO-2.1-blue)](https://zio.dev/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Deterministic, AI-driven development flows — ZIO-native.

llm4zio lets you programmatically define software-development workflows where AI
agents do the coding. If you want AI-generated code to always be reviewed by
another agent, don't coerce the agents — express that requirement in code. Don't
spend tokens on formatting, committing, or opening PRs; an ordinary `ZIO`
program handles all of that.

It is the ZIO counterpart to VirtusLab's
[orca](https://github.com/VirtusLab/orca). orca is direct-style (Ox); llm4zio
takes the same values — thin, readable, no ceremony, errors as data — and
expresses them in `ZIO[R, E, A]`, with first-class **direct-API providers**
(OpenAI, Anthropic, Gemini, Ollama, LM Studio) alongside **CLI coding agents**
(claude, codex, gemini, opencode, copilot).

llm4zio is both a **library** you embed in a ZIO app and a way to run single-file
**flow scripts** with one [scala-cli](https://scala-cli.virtuslab.org) command —
artifacts are fetched on first run, nothing else to install. You can orchestrate
development in any language and ecosystem.

A flow assumes configured, logged-in access to the backend you use (`claude`,
`codex`, `gemini`, …) plus `gh` and `git`; API-backed providers read their key
from the environment (e.g. `ANTHROPIC_API_KEY`).

> Design credit: the flow layer's shape (plan → implement → review → PR,
> resumable plans, recoverable-vs-catastrophic errors) is inspired by orca and
> reimplemented clean-room in ZIO. orca is Apache-2.0; llm4zio is MIT.

---

## An example flow

Save this as `implement.sc` and run it with your task:

```scala
//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

import llm4zio.flow.*
import llm4zio.runner.*

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

```bash
scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
```

Seven worked flows live under [`examples/`](examples/). Seed a starter and run
with one command:

| Example | What it shows |
| ------- | ------------- |
| `implement.sc` | Autonomous plan → per-task implement → review-and-fix → commit. Resumable plan. |
| `implement-interactive.sc` | Same shape; the planner asks clarifying questions first. |
| `issue-pr-bugfix.sc` | Issue → triage → failing test → PR → wait for CI red → fix → update PR (GitHub). |
| `epic.sc` | Multi-task epic with the full reviewer roster, doc-update stage, and cleanup. |
| `implement-live.sc` | Held, steerable claude session per task (stream / `ask_user` / approvals over MCP). |
| `issue-pr.sc` | Autonomous issue → assess → implement+review → push → open PR (GitHub). |
| `implement-enhanced.sc` | Plan self-review + codebase brief, format/lint after every edit. |
| `sdd.sc` | Spec-driven development: Spec → tests-first → implement → verify; per-role gemini models; mvn as the gate. |

```bash
examples/seed.sh implement --run            # seed + run against Maven Central
examples/seed.sh implement --local --run    # run against your in-tree build
```

For editing flow scripts with code-completion, the
[Metals](https://scalameta.org/metals/) VS Code extension works well.

---

## Modules

llm4zio is published to Maven Central under `io.github.riccardomerolla`. Embed the
layers you need:

| Artifact | What it gives you |
|---|---|
| `llm4zio-core` | LLM plumbing: a `LlmService`/`Connector` abstraction over the providers, streaming (`ZStream`), tool-calling, structured output, observability. |
| `llm4zio-flow` | The agentic flow layer: `Plan`/`Task`, resumable plain-file plans, `Chat`, `stage`/`fail` + a `FlowEvent` stream, the review loop, `GitTool`/`GhTool` over zio-process, `implementTaskLoop`. |
| `llm4zio-runner` | Script entry point (`flow(args) { ... }` in `examples/*.sc`), embedding entry (`Llm4zio.run` for ZIO apps), terminal renderer, ask-user MCP server, and a worked `ExampleFlow`. |

```scala
libraryDependencies += "io.github.riccardomerolla" %% "llm4zio-flow" % "<version>"
```

---

## Talking to an LLM (core)

Every connector — API- or CLI-backed — implements the full `LlmService` surface
(`executeStream`, `executeStreamWithHistory`, `executeWithTools`,
`executeStructured`, `isAvailable`), so callers depend on the capability, not the
backend:

```scala
import zio.*
import llm4zio.core.*
import llm4zio.providers.MockProvider

val connector: LlmService = MockProvider.make(LlmConfig(LlmProvider.Mock, "mock"))

val text: IO[LlmError, String] =
  Streaming.collect(connector.executeStream("Explain ZLayer in one line")).map(_.content)
```

Real providers are resolved from a `ConnectorRegistry`
(`ConnectorFactories.createRegistry(http, cli)`), wired with a zio-http `Client`
and a `CliProcessExecutor`. The library imposes no datastore and no web stack;
errors are typed (`LlmError`), never thrown.

---

## Built-in tools

A flow body receives a `FlowContext` with everything it needs:

| Tool | Methods | Purpose |
|---|---|---|
| `ctx.reasoning` | `LlmService` (planning, review, structured output) | The reasoning connector — typically an API provider; does planning and review. |
| `ctx.coder` | `LlmService` (drive via `Chat`) | The coding agent — a CLI agent that edits files in the repo. |
| `ctx.git` | `createBranch`, `checkout`, `checkoutOrCreate`, `commitAll`, `push`, `diff`, `diffVsBase`, `changedFilesVsBase`, `defaultBase`, `currentBranch`, `addRemote`, `init`/`initBare` | Git over the working tree. Recoverable outcomes are values, not failures: `commitAll` → `Commit.NothingToCommit`, `createBranch` → `CreateBranch.AlreadyExists`. |
| `ctx.gh` | `createPr`, `updatePr`, `readIssue`, `writeIssueComment`, `writePrComment`, `prChecks`, `waitForBuild` | GitHub PR + CI via the `gh` CLI. `readIssue` retries transient blips; `waitForBuild` polls checks to a terminal state. |
| `ctx.events` | `FlowEvents` (a `FlowEvent` hub) | Progress sink; the runner renders it and tees it to the log. |
| `ctx.coderCapabilities` | `ConnectorCapabilities` | What the coder can do (interactive / ask-user / approval / resumable / structured / usage), so a flow can refuse an unsupported workflow up front. |

**The runtime owns git.** Every coder `Chat` is seeded with an instruction not to
commit, push, or switch branches — the agent edits the working tree and the flow
commits/branches/pushes via `ctx.git.*`. This keeps `reviewAndFixLoop`'s
diff-based review working (a self-committing agent would leave an empty
`git.diff`). Pass `manageGit = true` to `Chat.start` to opt out.

### Connectors

`ConnectorId` selects the backend; `CliConnectorConfig` / `ApiConnectorConfig`
carry model + flags:

- **API providers** (`ApiConnector`): `OpenAI`, `Anthropic`, `GeminiApi`,
  `LmStudio`, `Ollama` — streaming, structured output, usage reporting.
- **CLI coding agents** (`CliConnector`): `ClaudeCli`, `Codex`, `GeminiCli`,
  `OpenCode`, `Copilot` — claude declares full interactive/ask-user/approval
  support; gemini is interactive but can't expose an ask-user tool in headless
  mode (`capabilities.askUser = false`); opencode/copilot are continuation-only.
- `Mock` — deterministic, for tests.

**Role split.** Reasoning (planning, review, structured output) runs over
`reasoning` — typically an API connector — while file-editing runs over `coder`,
a CLI agent. A single all-CLI backend (e.g. all-claude) is fine: it does both.

---

## Coding-agent safety

> [!WARNING]
> The example flows auto-approve the coder's edits (`claude --permission-mode
> acceptEdits`, `codex --sandbox workspace-write`, `gemini -y`): write-capable
> turns edit files and run shell commands without prompting.

For an unattended run the practical safety boundary is **process isolation** —
run the flow in a sandbox (e.g. [Docker
sandboxes](https://docs.docker.com/ai/sandboxes/)). For attended runs, supply an
`Interaction` (`TerminalInteraction.live`) and gate tool calls through an
`ApprovalPolicy`; the held-session path (`InteractiveCoder`, see
[`examples/implement-live.sc`](examples/implement-live.sc)) routes a claude agent's
`ask_user` / approval requests back to you over an in-process MCP server.

Transient provider blips (timeouts, 5xx, connection resets, gemini's
intermittent empty stream) are retried automatically — bounded by
`LLM4ZIO_RETRIES` (default 3, `0` = fail fast); long usage caps are waited out
when `LLM4ZIO_USAGE_WAIT` is set.

---

## Flow methods

Top-level, via `import llm4zio.flow.*`:

| Method | Use |
|---|---|
| `stage(name)(body)` | Wrap an operation in a named stage; emits `StageStarted` then `StageCompleted`/`StageFailed`, shown in the status line. |
| `fail(message)` | Abort the flow: publish `Aborted` and fail with `FlowError.Aborted`. |
| `implementTaskLoop(planPath, plan)(perTask)` | Run each incomplete task through `perTask`, persisting progress to `planPath` after each (resumable). |
| `reviewAndFixLoop(reviewers, reasoning, coder, task, diff, …)` | Run reviewers over the diff, hand findings to the coder to fix, re-review; `format` runs before each round, optional `lint`. |
| `withUsageLimitRetry(policy)(flow)` | Re-enter `flow` after sleeping out a provider usage cap. |

**Planning** (`Planner`):

| Operation | Result | Notes |
|---|---|---|
| `Planner.from(reasoning, prompt)` | `Plan` | Plan in one structured call. |
| `Planner.interactive(reasoning, prompt, interaction)` | `Plan` | The planner may ask clarifying questions first. |
| `Planner.assessThenPlan(reasoning, prompt)` | `Verdict[Plan]` | `Proceed(plan)` to implement, or `Blocked(reason)` to surface back. |
| `Planner.triage(reasoning, title, body)` | `Triage` | Classify a bug report: `NotABug` / `Untestable` / `Testable`. |
| `Planner.reviewed(reasoning, plan)` | `Plan` | Self-critique a draft along four dimensions (correctness, completeness, simplicity, conciseness). |
| `Planner.briefed(reasoning, plan, prompt)` | `Plan` | Attach a one-off codebase brief; `plan.taskPrompt(task)` prepends it to each task. |

**Persistence** (`PlanStore`): `save`, `load`, `delete`, and
`recoverOrCreate(path)(generate)` — resume from `path` if present, else generate
and persist. Plans are plain Markdown under `.llm4zio/` (no datastore); the
optional brief rides in a trailing `# Brief` section.

**Review** (`Reviewers` / `ReviewerSelector`): `Reviewers.all` (full roster),
`Reviewers.minimal`, `Reviewers.lintCommand(cmd, workDir)` (run a lint, turn a
failure into a finding); `ReviewerSelector.allEveryRound` (default) or
`ReviewerSelector.llmDriven(picker)` (a cheap model picks the per-task subset).
`Formatter.step(command, workDir)` builds the format step (e.g.
`LLM4ZIO_FORMAT="sbt scalafmtAll"`).

**PR**: `summarisePr(reasoning, diff, context?)` folds a diff into a
`PrSummary(title, body)` for `ctx.gh.createPr`.

---

## Data structures

Common types in flow scripts (all `derives JsonCodec`, so an agent can generate
them as structured output):

- **`Plan(epicId, tasks, brief?)`** — the task list; `epicId` is the kebab-case
  git branch. `taskPrompt(task)` prepends the brief; `complete(title)` /
  `nextIncomplete` drive the loop.
- **`Task(title, description, completed?)`** — one unit of work.
- **`Verdict[A]`** — `Proceed(value)` or `Blocked(reason)`; returned by
  `assessThenPlan` as `Verdict[Plan]`.
- **`Triage`** — `NotABug` / `Untestable` / `Testable`, each carrying its
  branch's fields.
- **`PrSummary(title, body)`** — what `summarisePr` returns; feeds
  `gh.createPr` directly.
- **`ReviewResult` / `ReviewIssue`** — what reviewers return (severity,
  confidence, title shown, description sent to the fixer).
- **`IssueRef` / `Issue` / `PullRequest` / `BuildOutcome`** — the `gh` value
  types. `IssueRef.parse("owner/repo#42")`.
- **`ConnectorCapabilities`** — a connector's declared surface.
- **`FlowError`** — the flow error ADT: `Persistence`, `PlanParse`, `Aborted`,
  `Process`, `Llm` (carries the underlying `LlmError`).

---

## Output

While a flow runs the terminal is split into an **event log** that grows
top-to-bottom and a **status line** pinned to the bottom showing the active stage
with a braille spinner; nested stages are indented. The full tree is also teed
into a per-run log file.

| Glyph | Meaning |
| ----- | ------- |
| `▶` | Stage start |
| `✔` | Stage completed |
| `✖` | Stage failed / abort / `flow failed` banner |
| `●` | Assistant prose; tool call (name in yellow, args in grey) |
| `·` | Info note (retry `⟳`, usage-limit wait, formatter, …) |

Colour and animation auto-disable when stdout isn't a terminal; set `NO_COLOR=1`
to force plain output.

**Environment knobs:** `LLM4ZIO_CODER` (claude\|codex\|gemini),
`LLM4ZIO_RETRIES` (transient-retry count; `0` = fail fast),
`LLM4ZIO_USAGE_WAIT` (`off`\|`on`\|`<n>h`\|`<n>m`), `LLM4ZIO_FORMAT` /
`LLM4ZIO_LINT` (project formatter / lint for the review loop).

---

## Authenticating the agents

Each CLI manages its own auth; llm4zio stores no secrets. Before running a flow,
log in to the backend you use (`claude`, `codex`, `gemini`, …) and to `gh` (for
the GitHub helpers), each per its own instructions. API-backed providers read
their key from the environment (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
`GEMINI_API_KEY`/`GOOGLE_API_KEY`).

---

## Getting set up

For a flow script, `scala-cli` fetches the artifacts on first run — nothing else
to install beyond the JDK and the agent CLIs:

```bash
scala-cli run implement.sc -- "your task here"
```

To embed llm4zio as a library (the `Llm4zio.run` embedding surface), add the
dependency and build with sbt:

```scala
// In a ZIO app — Llm4zio.run builds the FlowContext, streams progress to the
// terminal, and provides the http/process layers.
object MyApp extends ZIOAppDefault:
  def run = Llm4zio.run(workDir, reasoning = claude.copy(readOnly = true), coder = claude) { ctx =>
    // ... a flow over ctx.git / ctx.gh / ctx.coder / ctx.reasoning ...
  }
```

```bash
sbt compile
sbt test                     # unit tests
sbt "llm4zioFlow/It/test"    # integration (spawns real git; no network)
```

---

## Documentation

- [`examples/`](examples/) — seven runnable flows, the fastest way in.
- [`CLAUDE.md`](CLAUDE.md) — architecture, conventions, build/test recipes; the
  same file AI assistants pick up.
- [`CHANGELOG.md`](CHANGELOG.md) — release history.

## License

MIT © Riccardo Merolla. See [LICENSE](LICENSE).
