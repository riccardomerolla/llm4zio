# llm4zio

[![Maven Central](https://img.shields.io/maven-central/v/io.github.riccardomerolla/llm4zio-core.svg)](https://mvnrepository.com/artifact/io.github.riccardomerolla)
[![Scala 3](https://img.shields.io/badge/Scala-3.8-red)](https://www.scala-lang.org/)
[![ZIO 2](https://img.shields.io/badge/ZIO-2.1-blue)](https://zio.dev/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**A ZIO-native library for talking to LLMs and running agentic development flows.**

llm4zio is the ZIO counterpart to VirtusLab's
[orca](https://github.com/VirtusLab/orca). orca is direct-style (Ox); llm4zio
takes the same values — thin, readable, no ceremony, errors as data — and
expresses them in `ZIO[R, E, A]`, with first-class **direct-API providers**
(OpenAI, Anthropic, Gemini, Ollama, LM Studio) alongside **CLI coding agents**
(claude, codex, gemini).

> Design credit: the flow layer's shape (plan → implement → review → PR,
> resumable plans, recoverable-vs-catastrophic errors) is inspired by orca and
> reimplemented clean-room in ZIO. orca is Apache-2.0; llm4zio is MIT.

---

## Modules

| Artifact | What it gives you |
|---|---|
| `llm4zio-core` | The LLM plumbing: a `LlmService`/`Connector` abstraction over 10 providers, streaming (`ZStream`), tool-calling, structured output, observability. |
| `llm4zio-flow` | The agentic flow layer: `Plan`/`Task`, resumable plain-file plans, `Chat`, `stage`/`fail` + a `FlowEvent` stream, `fixLoop` review, `GitTool`/`GhTool` over zio-process, and `implementTaskLoop`. |
| `llm4zio-runner` | Entry point, a terminal progress renderer, and a worked `ExampleFlow`. |

```scala
libraryDependencies += "io.github.riccardomerolla" %% "llm4zio-core" % "<version>"
libraryDependencies += "io.github.riccardomerolla" %% "llm4zio-flow" % "<version>"
```

---

## Talking to an LLM (core)

```scala
import zio.*
import llm4zio.core.*
import llm4zio.providers.MockProvider

val connector: LlmService = MockProvider.make(LlmConfig(LlmProvider.Mock, "mock"))

val text: IO[LlmError, String] =
  Streaming.collect(connector.executeStream("Explain ZLayer in one line")).map(_.content)
```

Real providers are built from a `ConnectorRegistry`
(`ConnectorFactories.createRegistry(http, cli)`), wired with a zio-http `Client`
and a `CliProcessExecutor`. The library imposes no datastore and no web stack.

---

## Running a flow (flow + runner)

A flow reads top-to-bottom like a script:

```scala
import llm4zio.flow.*

given FlowEvents = ctx.events            // ctx: FlowContext
for
  _     <- stage("branch")(ctx.git.createBranch(plan.epicId).unit)
  coder <- Chat.start(ctx.coder, system = Some("You implement one task at a time."))
  _     <- implementTaskLoop(planPath, plan) { task =>          // resumable
             coder.ask(task.description).mapError(e => FlowError.Llm(e.toString)) *>
               ctx.git.commitAll(s"${plan.epicId}: ${task.title}").unit
           }
  _     <- stage("push")(ctx.git.push("origin", plan.epicId))
  url   <- ctx.gh.createPr(plan.epicId, body = "Automated changes", base = Some("main"))
yield url
```

- **Role split.** `FlowContext.reasoning` (an API connector) does planning and
  review; `FlowContext.coder` (a CLI coding agent) edits files.
- **Resumable.** `implementTaskLoop` persists progress to `.llm4zio/plan-*.md`
  after each task; re-running resumes from the first incomplete task.
- **Errors as data.** Recoverable git outcomes come back as typed values
  (`CreateBranch.AlreadyExists`, `Commit.NothingToCommit`); unexpected failures
  fail the effect with `FlowError`.

See [`ExampleFlow`](modules/llm4zio-runner/src/main/scala/llm4zio/runner/ExampleFlow.scala)
and its end-to-end test for a complete, runnable example.

---

## Build & test

```bash
sbt compile
sbt test                     # unit tests
sbt "llm4zioFlow/It/test"    # integration (spawns real git; no network)
```

## License

MIT © Riccardo Merolla. See [LICENSE](LICENSE).
