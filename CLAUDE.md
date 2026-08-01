# CLAUDE.md — llm4zio

llm4zio is a **ZIO-native library for talking to LLMs and running agentic
development flows**. It is the ZIO counterpart to VirtusLab's
[orca](https://github.com/VirtusLab/orca) (which is Ox/direct-style): orca's
values — thin, readable, no ceremony — expressed in `ZIO[R, E, A]`.

> History: llm4zio was once a large agentic-software-house *product* (board,
> governance, SPDD, Telegram HITL, web UI — ~39 modules). In 2026 it was forked
> down to this focused library. The full product is preserved on the
> `archive/product-2026-06` branch. The plan lives at
> `.claude/plans/orca-shaped-shedding.md`.

---

## Build

Built with **sbt 2.x**. Note: sbt 2's `test` is incremental/cached (runs only what
changed) — use `testFull` to force a full run, which is what CI does.

```bash
sbt compile                     # all modules
sbt test                        # unit tests (incremental); use testFull to force all
sbt "llm4zioFlow/It/testFull"   # integration tests (spawn real git; no network)
sbt fmt                         # scalafmt + scalafix
sbt check                       # verify formatting

# Per-module:
sbt llm4zioCore/test
sbt llm4zioFlow/test
sbt 'llm4zioFlow/testOnly llm4zio.flow.PlanSpec'
```

---

## Modules

```
modules/
  llm4zio-core/     # LLM plumbing: Connector/LlmService, providers (API + CLI),
                    #   streaming, tool-calling, structured output, observability
  llm4zio-flow/     # the agentic flow layer (orca-shaped, ZIO-native):
                    #   Plan/Task, PlanStore (resumable plain-file), Chat,
                    #   FlowEvent + stage/fail, fixLoop + Review, GitTool/GhTool/AdoTool
                    #   over Proc (zio-process), FlowContext, implementTaskLoop,
                    #   Pack + SpecChecks (modernization packs: knowledge as data dirs)
  llm4zio-runner/   # flow() script entry point (examples/*.sc), Llm4zio.run embedding entry,
                    #   TerminalListener, Connectors presets, worked ExampleFlow
  llm4zio-java/     # the Java authoring surface: a blocking, exception-based facade
                    #   (Scala-authored, Java-shaped) so flows can be written in .java
                    #   files (examples/java/*.java) and run via scala-cli
```

Dependency direction: `java → runner → flow → core`. Never the reverse.

Published artifacts (Maven Central, `io.github.riccardomerolla`):
`llm4zio-core`, `llm4zio-flow`, `llm4zio-runner`, and `llm4zio-java`
(`crossPaths := false`, so its coordinate has no `_3` suffix). The root project
is `publish / skip := true`.

---

## Packages

```
llm4zio.core         LlmService, Connector{,Api,Cli}, Models (Message, LlmChunk,
                     LlmConfig, LlmProvider), Streaming, Errors (LlmError),
                     ConnectorRegistry, ConnectorFactories, Conversation
llm4zio.providers    OpenAI/Anthropic/GeminiApi/LmStudio/Ollama (API),
                     ClaudeCli/Codex/Copilot/GeminiCli/OpenCode/Pi (CLI), Mock, HttpClient
llm4zio.tools        Tool, AnyTool, JsonSchema, tool-calling executor
llm4zio.observability  StreamRecorder — the ambient stream-recording hook the flow trace recorder installs
llm4zio.flow         the flow layer (see modules table)
llm4zio.runner       flow entry point, Connectors presets (claude/codex/gemini), Llm4zio.run/script/unsafeMain, TerminalListener, ExampleFlow
llm4zio.javaapi      the Java facade: Llm4zioJava.flow entry, JavaFlow handle, Bridge (blocking boundary),
                     Llm4zioException/ErrorCategory, outcome enums (BuildResult/CommitResult), helper objects
                     (Connectors/Plans/Evals/Reviewers/Refs/Adrs)
```

---

## Conventions

- **ZIO-native throughout.** No `Future`, no blocking-by-default. Wrap blocking
  work in `ZIO.attemptBlocking`. Subprocesses go through **zio-process**
  (`flow.Proc`), never raw `ProcessBuilder`.
- **Typed errors, no `Throwable` in signatures.** Core uses `LlmError`; flow uses
  `FlowError` (`Persistence`, `PlanParse`, `Aborted`, `Process`, `Llm`).
- **Recoverable vs catastrophic** (the orca split, ZIO-flavoured): expected,
  handleable outcomes are returned in the **value channel** as typed results
  (e.g. `GitTool.CreateBranch.AlreadyExists`, `Commit.NothingToCommit`); genuinely
  unexpected failures fail the effect (`FlowError.Process`).
- **No `var`** — `Ref`/`Queue`/`Hub` for state.
- **Stateless + plain files.** No datastore. Resumable plans persist as Markdown
  via `PlanStore` (`.llm4zio/plan-*.md`).
- **Role split.** Reasoning (planning, review) runs over an **API** connector;
  code-editing runs over a **CLI** coding agent (claude/codex/gemini). `FlowContext`
  carries both as `reasoning` and `coder`.
- **`-Werror` / `-Wunused:all`** — unused imports are fatal. NB: a wildcard
  `import zio.*` brings `zio.Task`, which shadows the library's `flow.Task` in
  *type* position; import `zio.ZIO` (or specific names) in files that name `Task`.
- **Packs are data.** The `modernize-*.sc` pipeline is estate-agnostic; everything
  estate-specific (prompts, judge rubrics, coverage regexes, gate commands, lessons,
  and `programFiles:` — the regex locating a program's target implementation files,
  which is what makes per-program judging possible) lives in a `flow.Pack` directory
  under `examples/packs/`, demoed against the synthetic estate in `examples/fixtures/`
  — see `docs/legacy-modernization.md`.
- **Bounded prompts.** Every LLM call in the modernize pipeline is budgeted through
  `flow.Context` (`cap` / `capped` / `withShrink`, `LLM4ZIO_CONTEXT_BUDGET`, default
  400k chars). Oversized prompts shrink (full → ½ → ¼) rather than failing, and every
  truncation is published as an event *and* recorded in `provenance.json`. Prefer
  decomposing a call (per program, per file) over raising the budget.
- **Script surface.** Examples are flat `examples/*.sc` files: `llm4zio.runner.flow(args) { body }`
  frames the body over `Llm4zio.unsafeMain`, the one process-entry `unsafeRun` shared with the Java
  surface (`Llm4zioJava.flow`); the `.javaapi.Bridge` additionally collapses effects at the Java
  boundary per call. The body is `FlowContext ?=> ZIO[Any, FlowError, Any]`.
  Bare names (`git`, `gh`, `coder`, `reasoning`, `userPrompt`, `workDir`) summon the context;
  `FlowEvents` derives from `FlowContext` via the companion given. Embedders use `Llm4zio.run`.
- **TDD.** Every behaviour is driven by a test first; integration tests that spawn
  `git` live under `src/it/scala` and use a temp repo + local bare remote (no
  network). Use the `Mock` provider for deterministic LLM behaviour in tests.

---

## A flow reads top-to-bottom

```scala
import llm4zio.flow.*
import llm4zio.runner.*

flow(args, defaultPrompt = Some("Add a multiply function")):
  val planPath = Plan.defaultPath(userPrompt)
  for
    plan      <- PlanStore.recoverOrCreate(planPath)(Planner.from(reasoning, userPrompt))
    _         <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
    _         <- stage("push")(git.push("origin", plan.epicId))
    pr        <- gh.createPr(plan.epicId, body = "…", base = Some("main"))
  yield pr.url
```

See `examples/*.sc` for worked versions; `llm4zio.runner.ExampleFlow` is the embedded (ZIOAppDefault) variant with an end-to-end test.
