# llm4zio 3.0.0 Direct-Style Surface — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make llm4zio flow scripts read like orca's examples (two-line frame, bare names, no error-mapping boilerplate) while staying honestly ZIO-native — shipped as one clean-break 3.0.0 release.

**Architecture:** A new script entry point `llm4zio.runner.flow(args) { body }` holds the library's only `unsafeRun`; the body is a `FlowContext ?=> ZIO[Any, FlowError, Any]` context function. Bare-name accessors (`git`, `gh`, `coder`, `reasoning`, `userPrompt`, `workDir`) and a `FlowEvents`-from-`FlowContext` derivation given (in the `FlowEvents` companion, so no extra import) remove all in-body ceremony. `Chat.ask` switches to `FlowError` (the one breaking change). Examples flatten from `plans/*.scala` + `examples/NN-*/` to orca-style `examples/*.sc` with one shared `seed.sh`.

**Tech Stack:** Scala 3.8.3, ZIO 2.1, sbt multi-module (`llm4zioCore` → `llm4zioFlow` → `llm4zioRunner`), zio-test, scala-cli for examples. Version comes from git tags (dynver); 3.0.0 = tag `v3.0.0`.

**Build commands used throughout:**
```bash
sbt llm4zioFlow/test          # flow unit tests
sbt llm4zioRunner/test        # runner unit tests
sbt "llm4zioFlow/It/test"     # flow integration tests (spawn real git)
sbt fmt                       # scalafmt + scalafix (run before each commit; -Werror means unused imports FAIL the build)
sbt compile                   # all modules
```

**Conventions that bite:** `-Wunused:all` + fatal warnings — never leave an unused import. A wildcard `import zio.*` shadows `llm4zio.flow.Task` in type position; in files that name `Task`, import `zio.ZIO` (or specific names) instead.

---

## File Structure (what's created / modified)

```
modules/llm4zio-flow/src/main/scala/llm4zio/flow/
  FlowContext.scala        MODIFY  add userPrompt + workDir fields
  FlowEvents.scala         MODIFY  add companion given: FlowContext => FlowEvents
  ContextAccess.scala      CREATE  bare-name accessors (git/gh/coder/reasoning/userPrompt/workDir)
  Chat.scala               MODIFY  ask returns IO[FlowError, String]
  Plan.scala               MODIFY  add Plan.defaultPath(prompt)
  Planner.scala            MODIFY  add chaining extensions .reviewed / .briefed
  LlmReview.scala          MODIFY  drop now-redundant mapError on coder.ask (line ~177)

modules/llm4zio-flow/src/test/scala/llm4zio/flow/
  ContextAccessSpec.scala  CREATE
  ChatSpec.scala           MODIFY  add FlowError-mapping test
  PlanSpec.scala           MODIFY  add defaultPath tests
  PlannerSpec.scala        MODIFY  add chaining tests
  PlanExecutionFailureSpec.scala MODIFY drop mapError (line ~66)

modules/llm4zio-runner/src/main/scala/llm4zio/runner/
  Connectors.scala         CREATE  claude/codex/gemini presets, withModel, Connectors.coderFromEnv
  Flow.scala               CREATE  top-level def flow(...) — the unsafe entry
  Llm4zio.scala            MODIFY  add script(...), resolvePrompt, ScriptUsage
  DefaultFlowContext.scala MODIFY  thread workDir into FlowContext
  ExampleFlow.scala        MODIFY  drop mapError

modules/llm4zio-runner/src/test/scala/llm4zio/runner/
  ConnectorsSpec.scala     CREATE
  ScriptSpec.scala         CREATE  resolvePrompt + script usage-error path

examples/                  RESTRUCTURE
  implement.sc, implement-interactive.sc, implement-enhanced.sc,
  implement-live.sc, epic.sc, issue-pr.sc, issue-pr-bugfix.sc   CREATE (from plans/*.scala, rewritten)
  seed.sh                  CREATE  (absorbs _seed_lib.sh + 7 per-example scripts)
  README.md                REWRITE
  starters/calculator-rs/        MOVE from examples/01-simple/test-project (== 07)
  starters/calculator-rs-open/   MOVE from examples/02-interactive/test-project (== 05 modulo doc comment)
  starters/calculator-scala/     MOVE from examples/03-bugfix/test-project (== 06)
  starters/todo-java/            MOVE from examples/04-epic/test-project
  01-simple/ … 07-enhanced/, _seed_lib.sh   DELETE
plans/                     DELETE (scripts move to examples/)

README.md                  MODIFY  front example + run instructions
CLAUDE.md                  MODIFY  "A flow reads top-to-bottom" snippet + conventions bullet
```

---

### Task 1: FlowContext gains `userPrompt`/`workDir`; FlowEvents derives from FlowContext

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowContext.scala`
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/FlowEvents.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextAccessSpec.scala` (created here, extended in Task 2)

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextAccessSpec.scala`:

```scala
package llm4zio.flow

import java.nio.file.Path

import zio.*
import zio.json.JsonCodec
import zio.stream.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

object ContextAccessSpec extends ZIOSpecDefault:

  /** Inert service — these tests never call the LLM. */
  final class StubService extends LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          = ZStream.empty
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.dieMessage("unused")
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      ZIO.dieMessage("unused")
    def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)

  private val dir = Path.of("/tmp/ctx-access-spec")

  private def ctxWith(events: FlowEvents): FlowContext =
    FlowContext(
      reasoning = StubService(),
      coder = StubService(),
      git = GitTool(dir),
      gh = GhTool(dir),
      events = events,
      userPrompt = "add multiply",
      workDir = dir,
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlowContext as the single given")(
    test("stage resolves FlowEvents from an in-scope FlowContext (no `given FlowEvents` line)") {
      for
        ev  <- FlowEvents.collecting
        _   <- {
                 given FlowContext = ctxWith(ev)
                 stage("build")(ZIO.unit)
               }
        rec <- ev.recorded
      yield assertTrue(rec == Chunk(FlowEvent.StageStarted("build"), FlowEvent.StageCompleted("build")))
    },
    test("FlowContext carries the user prompt and working directory") {
      val ctx = ctxWith(FlowEvents.noop)
      assertTrue(ctx.userPrompt == "add multiply", ctx.workDir == dir)
    },
  )
```

- [ ] **Step 2: Run it to verify it fails**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ContextAccessSpec'`
Expected: COMPILE ERROR — `userPrompt`/`workDir` are not members of `FlowContext`, and `stage` finds no `FlowEvents` given.

- [ ] **Step 3: Implement**

In `FlowContext.scala`, add an import and the two fields (append after `coderCapabilities`):

```scala
package llm4zio.flow

import java.nio.file.Path

import llm4zio.core.{ ConnectorCapabilities, LlmService }
```

```scala
final case class FlowContext(
  reasoning: LlmService,
  coder: LlmService,
  git: GitTool,
  gh: GhTool,
  events: FlowEvents,
  // Extra review backends (cross-agent review). The reasoning connector is the
  // default reviewer; these are run alongside it.
  reviewers: List[LlmService] = Nil,
  // What the coder can do (interactive/ask-user/approval/…), so a flow can refuse an unsupported workflow up front.
  coderCapabilities: ConnectorCapabilities = ConnectorCapabilities(),
  // The free-form prompt a flow script was started with (first CLI arg, or the script's default).
  userPrompt: String = "",
  // The repository the flow operates on; tools and connectors are rooted here.
  workDir: Path = Path.of(".").toAbsolutePath.normalize,
):
  /** Expose the event sink as a given so `stage`/`fail` resolve it implicitly. */
  given FlowEvents = events
```

In `FlowEvents.scala`, add to `object FlowEvents` (e.g. right after `val noop`):

```scala
  /** Derive the sink from an in-scope [[FlowContext]], so a flow body written as `FlowContext ?=> …` never needs a
    * `given FlowEvents = ctx.events` line. Lives in the companion: implicit scope finds it with no extra import.
    */
  given fromContext(using ctx: FlowContext): FlowEvents = ctx.events
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `sbt llm4zioFlow/test` (full module — guards against an ambiguous-given regression in existing specs)
Expected: PASS, including `ContextAccessSpec`.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow
git commit -m "feat(flow): FlowContext carries userPrompt/workDir; FlowEvents derives from FlowContext"
```

---

### Task 2: Bare-name accessors (`git`, `gh`, `coder`, `reasoning`, `userPrompt`, `workDir`)

**Files:**
- Create: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/ContextAccess.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ContextAccessSpec.scala`

- [ ] **Step 1: Add failing tests**

Append to the suite in `ContextAccessSpec.scala` (inside the `suite(...)` list):

```scala
    test("bare-name accessors return the context members") {
      val ctx = ctxWith(FlowEvents.noop)
      given FlowContext = ctx
      assertTrue(
        git == ctx.git,
        gh == ctx.gh,
        coder == ctx.coder,
        reasoning == ctx.reasoning,
        userPrompt == "add multiply",
        workDir == dir,
      )
    },
```

- [ ] **Step 2: Run to verify failure**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ContextAccessSpec'`
Expected: COMPILE ERROR — `git`/`gh`/… not found.

- [ ] **Step 3: Implement**

Create `modules/llm4zio-flow/src/main/scala/llm4zio/flow/ContextAccess.scala`:

```scala
package llm4zio.flow

import java.nio.file.Path

import llm4zio.core.LlmService

/** Bare-name access to the [[FlowContext]] members, orca-style: inside a flow body (`FlowContext ?=> …`) write
  * `git.push(…)`, `gh.createPr(…)`, `Chat.start(coder, …)` instead of `ctx.git.push(…)`. Each is a one-line summon —
  * go-to-definition lands here, not in macro territory.
  */

def git(using ctx: FlowContext): GitTool = ctx.git

def gh(using ctx: FlowContext): GhTool = ctx.gh

def coder(using ctx: FlowContext): LlmService = ctx.coder

def reasoning(using ctx: FlowContext): LlmService = ctx.reasoning

def userPrompt(using ctx: FlowContext): String = ctx.userPrompt

def workDir(using ctx: FlowContext): Path = ctx.workDir
```

- [ ] **Step 4: Run tests**

Run: `sbt llm4zioFlow/test`
Expected: PASS. If any existing flow-module file now reports an ambiguity between a local `coder`/`reasoning` parameter and the new top-level defs, the local name shadows — only a genuinely *ambiguous reference* error needs fixing (qualify the call site); none is expected.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow
git commit -m "feat(flow): bare-name context accessors (git/gh/coder/reasoning/userPrompt/workDir)"
```

---

### Task 3: `Chat.ask` speaks `FlowError` (the breaking change)

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Chat.scala`
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/LlmReview.scala:177`
- Modify: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PlanExecutionFailureSpec.scala:66`
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/ExampleFlow.scala:33-34`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/ChatSpec.scala`

- [ ] **Step 1: Write the failing test**

Add to `ChatSpec.scala`'s suite:

```scala
    test("ask wraps an LlmError into FlowError.Llm carrying the typed cause") {
      final class FailingService extends LlmService:
        def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = ZStream.empty
        def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          =
          ZStream.fail(LlmError.ProviderError("boom"))
        def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
          ZIO.dieMessage("unused")
        def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
          ZIO.dieMessage("unused")
        def isAvailable: UIO[Boolean]                                                              = ZIO.succeed(true)
      for
        chat <- Chat.start(FailingService(), manageGit = true)
        res  <- chat.ask("x").either
      yield assertTrue(res == Left(FlowError.Llm("boom", Some(LlmError.ProviderError("boom")))))
    },
```

- [ ] **Step 2: Run to verify failure**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.ChatSpec'`
Expected: FAIL — `res` is `Left(ProviderError("boom"))` (an `LlmError`), and the `assertTrue` comparison against `FlowError.Llm` does not typecheck/succeed.

- [ ] **Step 3: Implement**

In `Chat.scala`, change `ask` (the only signature change in 3.0.0):

```scala
  /** Send `prompt`, append both the user turn and the assistant reply to the running history, and return the
    * assistant's text. Fails with [[FlowError.Llm]] carrying the typed [[llm4zio.core.LlmError]] cause — Chat is
    * flow-layer API, so it speaks the flow-layer error.
    */
  def ask(prompt: String): IO[FlowError, String] =
    (for
      msgs  <- history.updateAndGet(_ :+ Message(MessageRole.User, prompt))
      reply <- Streaming.collect(service.executeStreamWithHistory(msgs))
      _     <- history.update(_ :+ Message(MessageRole.Assistant, reply.content))
    yield reply.content).mapError(e => FlowError.Llm(e.message, Some(e)))
```

Then remove the now-redundant call-site mapping (each becomes a direct call):

- `LlmReview.scala:177`: `coder.ask(Reviewers.fixPrompt(result)).mapError(e => FlowError.Llm(e.message)) *>` → `coder.ask(Reviewers.fixPrompt(result)) *>`
- `PlanExecutionFailureSpec.scala:66`: `chat.ask(t.description).mapError(e => FlowError.Llm(e.message, Some(e))).unit` → `chat.ask(t.description).unit`
- `ExampleFlow.scala:33-34`: delete the `.mapError(e => FlowError.Llm(e.message))` line so it reads `reply <- coder.ask(s"Implement ${task.title}: ${task.description}")`

- [ ] **Step 4: Run tests**

Run: `sbt llm4zioFlow/test llm4zioRunner/test`
Expected: PASS (ChatSpec's existing tests still compile — only the error type widened).

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules
git commit -m "feat(flow)!: Chat.ask returns IO[FlowError, String] with the typed LlmError cause"
```

---

### Task 4: `Plan.defaultPath`

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Plan.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PlanSpec.scala`

- [ ] **Step 1: Write the failing test**

Add to `PlanSpec.scala`'s suite:

```scala
    test("defaultPath is deterministic in the prompt and lives under .llm4zio") {
      val a = Plan.defaultPath("Add a multiply function")
      val b = Plan.defaultPath("Add a multiply function")
      val c = Plan.defaultPath("Different prompt entirely")
      assertTrue(
        a == b,
        a != c,
        a.getParent == java.nio.file.Path.of(".llm4zio"),
        a.getFileName.toString.startsWith("plan-"),
        a.getFileName.toString.endsWith(".md"),
      )
    },
```

- [ ] **Step 2: Run to verify failure**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.PlanSpec'`
Expected: COMPILE ERROR — `defaultPath` is not a member of object `Plan`.

- [ ] **Step 3: Implement**

In `Plan.scala`, add `import java.nio.file.Path` at the top and this to `object Plan`:

```scala
  /** Deterministic plan path for a prompt — `.llm4zio/plan-<hash>.md`. Same prompt, same path, so a re-run of the same
    * script resolves its own crashed plan file without the user computing paths (orca's `Plan.defaultPath`).
    */
  def defaultPath(prompt: String, dir: Path = Path.of(".llm4zio")): Path =
    val hash = Integer.toHexString(scala.util.hashing.MurmurHash3.stringHash(prompt))
    dir.resolve(s"plan-$hash.md")
```

- [ ] **Step 4: Run tests**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.PlanSpec'`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow
git commit -m "feat(flow): Plan.defaultPath — deterministic .llm4zio/plan-<hash>.md from the prompt"
```

---

### Task 5: Chainable plan transforms — `.reviewed(llm)` / `.briefed(llm, prompt)`

**Files:**
- Modify: `modules/llm4zio-flow/src/main/scala/llm4zio/flow/Planner.scala`
- Test: `modules/llm4zio-flow/src/test/scala/llm4zio/flow/PlannerSpec.scala`

- [ ] **Step 1: Write the failing test**

Add to `PlannerSpec.scala`'s suite (it already defines `StubStructured(json)` — decodes canned JSON for any structured call — and `StubText(text)` — streams canned text for `brief`):

```scala
    test("extensions chain: from(...).reviewed(...).briefed(...) reads like orca") {
      val draft    =
        """{"epicId":"add-multiply","tasks":[{"title":"draft","description":"d","completed":false}]}"""
      val improved =
        """{"epicId":"add-multiply","tasks":[{"title":"better","description":"sharper","completed":false}]}"""
      for plan <- Planner
                    .from(StubStructured(draft), "Add multiply")
                    .reviewed(StubStructured(improved))
                    .briefed(StubText("the brief"), "Add multiply")
      yield assertTrue(
        plan.tasks.map(_.title) == List("better"),
        plan.brief == Some("the brief"),
      )
    },
```

- [ ] **Step 2: Run to verify failure**

Run: `sbt 'llm4zioFlow/testOnly llm4zio.flow.PlannerSpec'`
Expected: COMPILE ERROR — `reviewed` is not a member of `IO[FlowError, Plan]`.

- [ ] **Step 3: Implement**

Append at the bottom of `Planner.scala`, *after* `object Planner` (top-level extension; `import zio.{ IO, ZIO }` is already present — extend it to include `ZIO` only if not already imported):

```scala
/** Chain plan transforms off the planning effect, orca-style:
  * `Planner.from(reasoning, prompt).reviewed(reasoning).briefed(reasoning, prompt)`. Thin sugar over the
  * [[Planner.reviewed]] / [[Planner.briefed]] functions — the LLM stays an explicit argument because which model
  * critiques the plan is a real decision.
  */
extension [R](plan: ZIO[R, FlowError, Plan])
  def reviewed(reasoning: LlmService): ZIO[R, FlowError, Plan] =
    plan.flatMap(p => Planner.reviewed(reasoning, p))
  def briefed(reasoning: LlmService, prompt: String): ZIO[R, FlowError, Plan] =
    plan.flatMap(p => Planner.briefed(reasoning, p, prompt))
```

- [ ] **Step 4: Run tests**

Run: `sbt llm4zioFlow/test`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-flow
git commit -m "feat(flow): chainable .reviewed/.briefed extensions on the planning effect"
```

---

### Task 6: Runner presets — `claude` / `codex` / `gemini`, `withModel`, `Connectors.coderFromEnv`

**Files:**
- Create: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Connectors.scala`
- Test: `modules/llm4zio-runner/src/test/scala/llm4zio/runner/ConnectorsSpec.scala`

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-runner/src/test/scala/llm4zio/runner/ConnectorsSpec.scala`:

```scala
package llm4zio.runner

import zio.test.*

import llm4zio.core.ConnectorId

object ConnectorsSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Connectors")(
    test("presets target the right CLI with edit-capable defaults") {
      assertTrue(
        claude.connectorId == ConnectorId.ClaudeCli,
        claude.flags == Map("permission-mode" -> "acceptEdits"),
        codex.connectorId == ConnectorId.Codex,
        codex.flags == Map("sandbox" -> "workspace-write"),
        gemini.connectorId == ConnectorId.GeminiCli,
      )
    },
    test("withModel pins a model") {
      assertTrue(claude.withModel("opus").model == Some("opus"))
    },
    test("coderFromEnv honours LLM4ZIO_CODER and defaults to claude") {
      assertTrue(
        Connectors.coderFromEnv(Map.empty) == claude,
        Connectors.coderFromEnv(Map("LLM4ZIO_CODER" -> "codex")) == codex,
        Connectors.coderFromEnv(Map("LLM4ZIO_CODER" -> "gemini")) == gemini,
        Connectors.coderFromEnv(Map("LLM4ZIO_CODER" -> "anything-else")) == claude,
      )
    },
  )
```

- [ ] **Step 2: Run to verify failure**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.ConnectorsSpec'`
Expected: COMPILE ERROR — `claude`/`codex`/`gemini`/`Connectors` not found.

- [ ] **Step 3: Implement**

Create `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Connectors.scala`:

```scala
package llm4zio.runner

import llm4zio.core.{ CliConnectorConfig, ConnectorId }

/** Ready-made CLI coding-agent configs, orca-style: a flow script references `claude` / `codex` / `gemini` directly
  * (`flow(args, coder = codex)`) instead of hand-rolling a `CliConnectorConfig` match. Each is edit-capable; derive the
  * read-only reasoning twin with `.copy(readOnly = true)` (which [[flow]] does for you by default).
  */

val claude: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))

val codex: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.Codex, flags = Map("sandbox" -> "workspace-write"))

// gemini auto-approves edits via its built-in -y.
val gemini: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.GeminiCli)

extension (config: CliConnectorConfig)
  /** Pin a specific model, e.g. `claude.withModel("opus")`. */
  def withModel(name: String): CliConnectorConfig = config.copy(model = Some(name))

object Connectors:
  /** The coder selected by `LLM4ZIO_CODER` (claude|codex|gemini), defaulting to [[claude]] — the swap-backend-without-
    * editing-the-script knob every example used to hand-roll.
    */
  def coderFromEnv(env: Map[String, String] = sys.env): CliConnectorConfig =
    env.getOrElse("LLM4ZIO_CODER", "claude") match
      case "codex"  => codex
      case "gemini" => gemini
      case _        => claude
```

- [ ] **Step 4: Run tests**

Run: `sbt llm4zioRunner/test`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-runner
git commit -m "feat(runner): claude/codex/gemini presets, withModel, Connectors.coderFromEnv"
```

---

### Task 7: `Llm4zio.script` — the testable ZIO core of the script entry

**Files:**
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Llm4zio.scala`
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/DefaultFlowContext.scala:37`
- Test: `modules/llm4zio-runner/src/test/scala/llm4zio/runner/ScriptSpec.scala`

- [ ] **Step 1: Write the failing test**

Create `modules/llm4zio-runner/src/test/scala/llm4zio/runner/ScriptSpec.scala`:

```scala
package llm4zio.runner

import zio.*
import zio.test.*

object ScriptSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Llm4zio.script")(
    test("resolvePrompt prefers the first arg, falls back to the default, then to a usage error") {
      assertTrue(
        Llm4zio.resolvePrompt(List("do it", "extra"), Some("dflt")) == Right("do it"),
        Llm4zio.resolvePrompt(Nil, Some("dflt")) == Right("dflt"),
        Llm4zio.resolvePrompt(List("  "), Some("dflt")) == Right("dflt"),
        Llm4zio.resolvePrompt(Nil, None).isLeft,
      )
    },
    test("script fails with ScriptUsage before touching any connector when no prompt is available") {
      for exit <- Llm4zio.script(Nil, claude)(ZIO.unit).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Llm4zio.ScriptUsage]))
    },
  )
```

- [ ] **Step 2: Run to verify failure**

Run: `sbt 'llm4zioRunner/testOnly llm4zio.runner.ScriptSpec'`
Expected: COMPILE ERROR — `resolvePrompt`/`script`/`ScriptUsage` not members of `Llm4zio`.

- [ ] **Step 3: Implement**

In `Llm4zio.scala`, add inside `object Llm4zio` (after the existing `run`). Also extend the existing imports with `import llm4zio.flow.FlowContext` if not already covered by `llm4zio.flow.*` (it is — `import llm4zio.flow.*` is already present):

```scala
  /** A script was started without a usable prompt. Carried as a Throwable so [[script]] composes with [[run]]'s
    * `ZIO[Any, Throwable, Unit]`; [[flow]] renders `message` as the usage line and exits 2.
    */
  final case class ScriptUsage(usage: String) extends RuntimeException(usage)

  /** First non-blank CLI arg, else the script's default, else a usage error. */
  def resolvePrompt(args: List[String], defaultPrompt: Option[String] = None): Either[String, String] =
    args.headOption
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(defaultPrompt)
      .toRight("""usage: scala-cli run <script>.sc -- "<prompt>"""")

  /** The pure-ZIO core of [[flow]]: resolve the prompt, derive the read-only reasoning twin when none is given, then
    * delegate to [[run]] with the prompt riding in the [[FlowContext]]. Kept separate from [[flow]] so everything up
    * to the single `unsafeRun` is an ordinary testable effect.
    */
  def script(
    args: List[String],
    coder: CliConnectorConfig,
    reasoning: Option[ConnectorConfig] = None,
    defaultPrompt: Option[String] = None,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
    workDir: Path = Path.of(".").toAbsolutePath.normalize,
  )(body: FlowContext ?=> ZIO[Any, FlowError, Any]): ZIO[Any, Throwable, Unit] =
    resolvePrompt(args, defaultPrompt) match
      case Left(usage)   => ZIO.fail(ScriptUsage(usage))
      case Right(prompt) =>
        run(workDir, reasoning.getOrElse(coder.copy(readOnly = true)), coder, reviewers, usageLimit) { ctx =>
          body(using ctx.copy(userPrompt = prompt))
        }
```

In `DefaultFlowContext.scala` line 37, thread `workDir` into the context so `workDir`-the-accessor is real (add the named argument):

```scala
      (
        FlowContext(reasoningT, coderT, GitTool(workDir), GhTool(workDir), hub, reviewersT, coderCapabilities, workDir = workDir),
        hub,
      )
```

- [ ] **Step 4: Run tests**

Run: `sbt llm4zioRunner/test`
Expected: PASS (the usage-error test must not require network/CLIs — `script` fails before `run` builds anything).

- [ ] **Step 5: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-runner
git commit -m "feat(runner): Llm4zio.script — prompt resolution + reasoning twin over run()"
```

---

### Task 8: `flow(...)` — the unsafe script entry point

**Files:**
- Create: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Flow.scala`

This is the one function that may not be unit-tested in-process (it calls `sys.exit`); everything it does beyond process control lives in `Llm4zio.script` (Task 7). It is exercised end-to-end in Task 10's `--local` verification.

- [ ] **Step 1: Implement**

Create `modules/llm4zio-runner/src/main/scala/llm4zio/runner/Flow.scala`:

```scala
package llm4zio.runner

import zio.*

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.{ FlowContext, FlowError, UsageLimitPolicy }

/** Script entry point — the library's only `unsafeRun`. A flow script is two lines of frame:
  *
  * {{{
  * import llm4zio.flow.*
  * import llm4zio.runner.*
  *
  * flow(args, defaultPrompt = Some("Add a multiply function")):
  *   for
  *     plan <- Planner.from(reasoning, userPrompt)
  *     ...
  *   yield ()
  * }}}
  *
  * The body is an ordinary ZIO effect with the [[FlowContext]] in given scope (so `git`/`gh`/`coder`/`reasoning`/
  * `userPrompt` resolve bare, and `stage`/`implementTaskLoop` find their event sink). The coder defaults to the
  * `LLM4ZIO_CODER` selection (claude|codex|gemini, claude when unset); reasoning defaults to the coder's read-only
  * twin.
  *
  * Process behaviour: Ctrl-C interrupts the flow fiber (stages unwind, the ✖ banner renders, JVM exits 130); a missing
  * prompt prints usage and exits 2; a failed flow exits 1 (the runner has already rendered the failure).
  */
def flow(
  args: Seq[String],
  coder: CliConnectorConfig = Connectors.coderFromEnv(),
  reasoning: Option[ConnectorConfig] = None,
  defaultPrompt: Option[String] = None,
  reviewers: List[ConnectorConfig] = Nil,
  usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
)(body: FlowContext ?=> ZIO[Any, FlowError, Any]): Unit =
  val effect =
    Llm4zio.script(args.toList, coder, reasoning, defaultPrompt, reviewers, usageLimit)(body)
  Unsafe.unsafe { implicit unsafe =>
    val runtime = Runtime.default
    val fiber   = runtime.unsafe.fork(effect)
    // Ctrl-C → interrupt the flow fiber and wait for it to unwind (stages close, banner renders).
    val hook    = new Thread(() => Unsafe.unsafe(implicit u => runtime.unsafe.run(fiber.interrupt)): Unit)
    java.lang.Runtime.getRuntime.addShutdownHook(hook)
    val exit    = runtime.unsafe.run(fiber.join)
    try java.lang.Runtime.getRuntime.removeShutdownHook(hook)
    catch case _: IllegalStateException => () // shutdown already in progress (Ctrl-C path)
    exit match
      case Exit.Success(_)                            => ()
      case Exit.Failure(cause) if cause.isInterrupted => () // SIGINT path: the JVM itself exits 130
      case Exit.Failure(cause)                        =>
        cause.failureOption match
          case Some(usage: Llm4zio.ScriptUsage) =>
            System.err.println(usage.getMessage)
            sys.exit(2)
          case _                                =>
            sys.exit(1) // run() already rendered the ✖ banner + reason
  }
```

- [ ] **Step 2: Compile**

Run: `sbt llm4zioRunner/compile`
Expected: success, no warnings.

- [ ] **Step 3: Run the full runner suite (regression)**

Run: `sbt llm4zioRunner/test`
Expected: PASS.

- [ ] **Step 4: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-runner
git commit -m "feat(runner): flow() script entry — single unsafeRun, SIGINT-aware, usage/failure exit codes"
```

---

### Task 9: Modernize `ExampleFlow` and run the integration suites

**Files:**
- Modify: `modules/llm4zio-runner/src/main/scala/llm4zio/runner/ExampleFlow.scala`

The `mapError` was already removed in Task 3. Remaining check: the scaladoc on `Llm4zio.run` (`Llm4zio.scala:12-21`) still shows the old `object Main extends ZIOAppDefault` frame — update it to mention `flow(...)` as the script entry and `run` as the embedding entry.

- [ ] **Step 1: Update the `Llm4zio` scaladoc**

Replace the doc comment on `object Llm4zio` (lines 12-21) with:

```scala
/** Runner entry points. [[flow]] (in this package) is the script surface — top-level in a `.sc`, one `unsafeRun`
  * inside. [[Llm4zio.run]] is the embedding surface for real ZIO apps: builds a [[FlowContext]], streams progress to
  * the terminal, runs the body, and provides the zio-http client layers.
  *
  * {{{
  * object MyApp extends zio.ZIOAppDefault:
  *   def run = Llm4zio.run(workDir, reasoning, coder) { ctx =>
  *     // ... a flow over ctx ...
  *   }
  * }}}
  */
```

- [ ] **Step 2: Run every suite including integration**

Run: `sbt compile test "llm4zioFlow/It/test" "llm4zioRunner/It/test"`
Expected: ALL PASS. (`ExampleFlowSpec` lives in `llm4zioRunner/It` and exercises `ExampleFlow` end-to-end with a temp git repo + Mock LLM — this is the regression gate for Tasks 1–8.)

- [ ] **Step 3: Format and commit**

```bash
sbt fmt
git add modules/llm4zio-runner
git commit -m "docs(runner): Llm4zio doc reflects flow() vs run() split; ExampleFlow on new Chat API"
```

---

### Task 10: Restructure examples — flat `.sc` scripts, shared `seed.sh`, starters

**Files:**
- Create: `examples/implement.sc`, `examples/implement-interactive.sc`, `examples/implement-enhanced.sc`, `examples/implement-live.sc`, `examples/epic.sc`, `examples/issue-pr.sc`, `examples/issue-pr-bugfix.sc`
- Create: `examples/seed.sh`
- Move: starters (see Step 1)
- Delete: `plans/`, `examples/01-simple` … `examples/07-enhanced`, `examples/_seed_lib.sh`
- Rewrite: `examples/README.md`

- [ ] **Step 1: Move the starters (git mv, preserving history)**

```bash
mkdir -p examples/starters
git mv examples/01-simple/test-project        examples/starters/calculator-rs
git mv examples/02-interactive/test-project   examples/starters/calculator-rs-open
git mv examples/03-bugfix/test-project        examples/starters/calculator-scala
git mv examples/04-epic/test-project          examples/starters/todo-java
git rm -r examples/05-interactive-live/test-project examples/06-issue-pr/test-project examples/07-enhanced/test-project
```

(05 == 02 modulo a doc comment; 06 == 03 byte-for-byte; 07 == 01 byte-for-byte — verified by `diff -r` before this plan was written.) Then neutralize the example-specific doc header in `examples/starters/calculator-rs-open/src/lib.rs`: replace its first comment lines with:

```rust
//! A tiny calculator crate with an open-ended starting point — the interactive
//! examples aim a vague prompt at it so the planner has to ask questions.
```

- [ ] **Step 2: Write the seven scripts**

Each script: dep pinned to `3.0.0`, doc header, `flow(...)` frame. Create exactly these files.

`examples/implement.sc`:

```scala
//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

/** Persistent planning + coding flow (autonomous planning) — the ZIO-native
  * counterpart of orca's `implement.sc`.
  *
  * The reasoner breaks the prompt into a `Plan`, persisted at
  * `.llm4zio/plan-<hash>.md` so a re-run resumes from the first incomplete task.
  * Each task is implemented on one epic branch, reviewed via `reviewAndFixLoop`,
  * and committed. Backend selectable via LLM4ZIO_CODER=claude|codex|gemini
  * (default claude); no API key — one CLI login is enough.
  *
  * Seed a starter:  examples/seed.sh implement
  * Run:             scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  */

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

`examples/implement-interactive.sc`:

```scala
//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

/** Interactive planning + coding — the ZIO-native counterpart of orca's
  * `implement-interactive.sc`. Same shape as `implement.sc`, but the planner may
  * ask clarifying questions on the terminal before proposing the plan. Use it
  * for open-ended prompts.
  *
  * Seed a starter:  examples/seed.sh implement-interactive
  * Run:             scala-cli run implement-interactive.sc -- "Make the calculator crate more useful"
  */

import llm4zio.flow.*
import llm4zio.runner.*

flow(args, defaultPrompt = Some("Make the calculator crate more useful")):
  val planPath = Plan.defaultPath(userPrompt)
  for
    plan      <- PlanStore.recoverOrCreate(planPath)(
                   Planner.interactive(reasoning, userPrompt, TerminalInteraction.live)
                 )
    _         <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     reviewAndFixLoop(Reviewers.minimal, reasoning, coderChat, task.title, git.diff) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
  yield ()
```

`examples/implement-enhanced.sc`:

```scala
//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

/** Persistent planning + coding, enhanced with a plan self-review and a shared
  * codebase brief — the ZIO-native counterpart of orca's `implement-enhanced.sc`.
  *
  * Two steps chain onto planning, both on the reasoning connector:
  * `.reviewed(reasoning)` critiques the draft plan; `.briefed(reasoning, prompt)`
  * writes a one-off codebase brief that `plan.taskPrompt(task)` prepends to every
  * task. The brief rides in the single plan file (a trailing `# Brief` section),
  * so resume reuses it — no sidecar.
  *
  * Formatting runs before every review round and commit (LLM4ZIO_FORMAT, e.g.
  * "cargo fmt"); an optional lint runs each round (LLM4ZIO_LINT, e.g.
  * "cargo check --tests").
  *
  * Seed a starter:  examples/seed.sh implement-enhanced
  * Run:             scala-cli run implement-enhanced.sc -- "Add a multiply function to the calculator crate"
  */

import llm4zio.core.ConnectorId
import llm4zio.flow.*
import llm4zio.runner.*

// gemini's free tier 429s under concurrent reviewers; throttle it (0 = unbounded for the rest).
val coderCfg          = Connectors.coderFromEnv()
val reviewParallelism = if coderCfg.connectorId == ConnectorId.GeminiCli then 1 else 0

flow(args, coder = coderCfg, defaultPrompt = Some("Add a multiply function to the calculator crate")):
  val planPath = Plan.defaultPath(userPrompt)
  val format   = Formatter.step(sys.env.get("LLM4ZIO_FORMAT"), workDir)
  val lint     = sys.env.get("LLM4ZIO_LINT").map(c => Reviewers.lintCommand(List("bash", "-c", c), workDir))
  for
    plan      <- stage("Plan (review + brief)") {
                   PlanStore.recoverOrCreate(planPath) {
                     Planner.from(reasoning, userPrompt).reviewed(reasoning).briefed(reasoning, userPrompt)
                   }
                 }
    _         <- stage("Branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   for
                     _ <- coderChat.ask(plan.taskPrompt(task))
                     _ <- reviewAndFixLoop(
                            Reviewers.all,
                            reasoning,
                            coderChat,
                            task.title,
                            git.diff,
                            parallelism = reviewParallelism,
                            lint = lint,
                            format = format,
                          )
                     _ <- format // format once more before committing
                     _ <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
                   yield ()
                 }
  yield ()
```

`examples/implement-live.sc`:

```scala
//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

/** Interactive *live-coding* flow — the steerable counterpart of `implement.sc`.
  *
  * Each task drives a held `claude` session: it streams thinking and tool calls
  * live, can ask clarifying questions mid-task (`ask_user`), and routes tool
  * calls through an approval gate — over an in-process MCP server the runtime
  * starts for the run. Planning is interactive too.
  *
  * Seed a starter:  examples/seed.sh implement-live
  * Run:             scala-cli run implement-live.sc -- "Make the calculator crate more useful"
  */

import zio.ZIO

import llm4zio.flow.*
import llm4zio.runner.*

// The live path doesn't use ctx.coder — InteractiveCoder opens a fresh claude AgentSession per task.
flow(args, defaultPrompt = Some("Make the calculator crate more useful")):
  val interaction = TerminalInteraction.live
  val planPath    = Plan.defaultPath(userPrompt)
  for
    plan <- PlanStore.recoverOrCreate(planPath)(Planner.interactive(reasoning, userPrompt, interaction))
    _    <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    // autoApprove keeps the demo flowing (claude runs in the runtime-owned branch sandbox); swap in
    // ApprovalPolicy.interactive(interaction) to confirm each tool call on the terminal.
    _    <- ZIO.scoped {
              InteractiveCoder.openSessions(workDir, interaction, ApprovalPolicy.autoApprove).flatMap { openSession =>
                implementTaskLoopLive(planPath, plan, interaction, openSession) { (task, _) =>
                  git.commitAll(s"${plan.epicId}: ${task.title}").unit
                }
              }
            }
  yield ()
```

`examples/epic.sc`:

```scala
//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

/** Run an epic: a resumable multi-task workstream with the full review roster —
  * the ZIO-native counterpart of orca's `epic.sc`.
  *
  * `.llm4zio/plan-<hash>.md` holds the task list; a re-run resumes from the first
  * incomplete task (each checkbox is committed as it lands). After each task the
  * seven review lenses (`Reviewers.all`) run and the coder fixes their findings.
  * At the end the docs are updated and the plan file is cleaned up.
  *
  * Seed a starter:  examples/seed.sh epic
  * Run:             scala-cli run epic.sc -- "<a multi-task change request>"
  */

import llm4zio.core.ConnectorId
import llm4zio.flow.*
import llm4zio.runner.*

val coderCfg          = Connectors.coderFromEnv()
// gemini's free tier 429s when the seven lenses fan out concurrently; serialize its reviews.
val reviewParallelism = if coderCfg.connectorId == ConnectorId.GeminiCli then 1 else 0

flow(
  args,
  coder = coderCfg,
  defaultPrompt = Some(
    "Persist tasks to a JSON file (load on startup, save on every change), add 'done <id>' and " +
      "'delete <id>' commands, and support priority levels (low/medium/high) with a 'list --priority' filter"
  ),
):
  val planPath = Plan.defaultPath(userPrompt)
  val format   = Formatter.step(sys.env.get("LLM4ZIO_FORMAT"), workDir)
  for
    plan      <- stage("Acquire epic")(PlanStore.recoverOrCreate(planPath)(Planner.from(reasoning, userPrompt)))
    _         <- stage("Branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   for
                     _ <- coderChat.ask(task.description)
                     _ <- reviewAndFixLoop(
                            Reviewers.all,
                            reasoning,
                            coderChat,
                            task.title,
                            git.diff,
                            parallelism = reviewParallelism,
                            format = format,
                          )
                     _ <- format
                     _ <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
                   yield ()
                 }
    _         <- stage("Update documentation") {
                   coderChat.ask(
                     "All tasks are done. Update the project docs (README, doc-comments) for the changes made — " +
                       "only what's affected, no new sections."
                   ) *> git.commitAll("docs: update for completed epic").unit
                 }
    _         <- stage("Clean up epic file")(PlanStore.delete(planPath))
  yield ()
```

`examples/issue-pr.sc`:

```scala
//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

/** GitHub-issue → PR flow, fully autonomous — the ZIO-native counterpart of
  * orca's `issue-pr.sc`.
  *
  * Given an `owner/repo#number` reference: read the issue; resume a persisted
  * plan or skeptically assess (`Planner.assessThenPlan`) — Blocked posts the
  * reason on the issue and stops; Proceed branches and persists. Implement each
  * task with the review loop, push, summarise the diff, open the PR, and return
  * to the starting branch.
  *
  * Run: scala-cli run issue-pr.sc -- "owner/repo#number"
  * Requires `claude` and `gh` authenticated, and a repo with a remote.
  */

import zio.{ IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

flow(args): // no default: an issue reference is required
  IssueRef.parse(userPrompt) match
    case None      => fail("usage: scala-cli run issue-pr.sc -- \"owner/repo#number\"")
    case Some(ref) => issueToPr(ref)

def issueToPr(ref: IssueRef)(using FlowContext): IO[FlowError, Unit] =
  for
    start     <- git.currentBranch // return here at the end
    issue     <- stage(s"Read issue ${ref.shortRef}")(gh.readIssue(ref))
    payload    = s"Issue: ${issue.title}\n\nReporter: ${issue.author}\n\n${issue.body}"
    planPath   = workDir.resolve(s".llm4zio/issue-${ref.number}.md")
    maybePlan <- stage("Acquire plan") {
                   PlanStore.load(planPath).flatMap {
                     case Some(plan) => ZIO.some(plan)
                     case None       =>
                       Planner.assessThenPlan(reasoning, payload).flatMap {
                         case Verdict.Blocked(why)  =>
                           stage("Post assessment on the issue")(gh.writeIssueComment(ref, why)).as(None)
                         case Verdict.Proceed(plan) =>
                           git.checkoutOrCreate(plan.epicId) *> PlanStore.save(planPath, plan).as(Some(plan))
                       }
                   }
                 }
    _         <- maybePlan match
                   case None       => ZIO.unit // Blocked: never switched branch
                   case Some(plan) => implementAndOpen(ref, issue, plan, planPath, start)
  yield ()

def implementAndOpen(
  ref: IssueRef,
  issue: Issue,
  plan: Plan,
  planPath: java.nio.file.Path,
  startBranch: String,
)(using FlowContext): IO[FlowError, Unit] =
  for
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     reviewAndFixLoop(Reviewers.all, reasoning, coderChat, task.title, git.diff) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
    _         <- stage("Push branch")(git.push("origin", plan.epicId))
    base      <- git.defaultBase
    diff      <- git.diffVsBase(base)
    summary   <- stage("Summarise PR")(
                   summarisePr(reasoning, diff, context = Some(s"Originating issue: ${ref.shortRef}\nTitle: ${issue.title}"))
                 )
    _         <- stage("Open PR")(
                   gh.createPr(summary.title, s"${summary.body}\n\nCloses ${ref.shortRef}.", base = Some(base))
                 )
    _         <- PlanStore.delete(planPath)
    _         <- stage(s"Return to $startBranch")(git.checkout(startBranch))
  yield ()
```

`examples/issue-pr-bugfix.sc`:

```scala
//> using dep "io.github.riccardomerolla::llm4zio-runner:3.0.0"
//> using scala "3.8.3"
//> using jvm 21

/** Bug-report → fix flow for a Scala project — the ZIO-native counterpart of
  * orca's `issue-pr-bugfix.sc`.
  *
  * Given an `owner/repo#number` reference: read + triage the issue (NotABug /
  * Untestable verdicts are commented and the flow stops). For a Testable bug:
  * branch, write the failing test, push, open a tentative PR, wait for CI to go
  * red (fail loudly on green — the repro is wrong), then plan + implement the
  * fix (reviewed + briefed), push, and regenerate the PR title/body from the
  * full diff.
  *
  * Run: scala-cli run issue-pr-bugfix.sc -- "owner/repo#number"
  * Requires `claude` and `gh` authenticated; the repo must have CI that runs the tests.
  */

import zio.{ durationInt, IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

val CiTimeout = 30.minutes

flow(args): // no default: an issue reference is required
  IssueRef.parse(userPrompt) match
    case None      => fail("usage: scala-cli run issue-pr-bugfix.sc -- \"owner/repo#number\"")
    case Some(ref) => bugfix(ref)

def bugfix(ref: IssueRef)(using FlowContext): IO[FlowError, Unit] =
  for
    issue   <- stage(s"Read issue ${ref.shortRef}")(gh.readIssue(ref))
    verdict <- stage("Triage")(Planner.triage(reasoning, issue.title, issue.body))
    _       <- verdict match
                 case Triage.NotABug(explanation) =>
                   stage("Comment: not a bug")(gh.writeIssueComment(ref, explanation))
                 case Triage.Untestable(_, steps) =>
                   stage("Comment: reproduction steps")(gh.writeIssueComment(ref, s"## Reproduction\n\n$steps"))
                 case Triage.Testable(summary, branchName, failingTestPath) =>
                   fixTestable(ref, issue, summary, branchName, failingTestPath)
  yield ()

def fixTestable(
  ref: IssueRef,
  issue: Issue,
  summary: String,
  branchName: String,
  failingTestPath: String,
)(using FlowContext): IO[FlowError, Unit] =
  for
    start     <- git.currentBranch // return here at the end
    _         <- stage("Branch")(git.checkoutOrCreate(branchName))
    coderChat <- Chat.start(coder, system = Some("You write code in the current repo."))
    _         <- stage("Write the failing test") {
                   coderChat.ask(
                     s"Write a failing unit test at `$failingTestPath` that reproduces: ${issue.title}\n\n${issue.body}"
                   ) *> git.commitAll(s"Add failing test: $summary").unit
                 }
    _         <- stage("Push branch")(git.push("origin", branchName))
    tentative <- stage("Tentative PR summary") {
                   summarisePr(
                     reasoning,
                     diff = "", // only the failing test has landed; let the model lead on the issue context
                     context = Some(
                       s"Originating issue: ${ref.shortRef}\nTitle: ${issue.title}\n(Only a failing test has been added so far.)"
                     ),
                   )
                 }
    pr        <- stage("Open PR")(gh.createPr(tentative.title, s"${tentative.body}\n\nCloses ${ref.shortRef}."))
    status    <- stage("Wait for CI to fail")(gh.waitForBuild(pr, CiTimeout))
    _         <- ZIO.when(status == BuildOutcome.Success)(
                   fail("CI passed on the failing-test commit — the reproduction doesn't reproduce.")
                 )
    _         <- stage("Comment on PR")(gh.writePrComment(pr, s"CI is red as expected for: $summary. Implementing the fix."))
    fixPrompt  = s"Fix ${ref.shortRef} on branch $branchName so the failing test passes without regressing others."
    fixPlan   <- stage("Plan the fix (review + brief)") {
                   Planner.from(reasoning, fixPrompt).reviewed(reasoning).briefed(reasoning, fixPrompt)
                 }
    planPath   = workDir.resolve(s".llm4zio/fix-${ref.number}.md")
    _         <- PlanStore.save(planPath, fixPlan)
    _         <- implementTaskLoop(planPath, fixPlan) { task =>
                   coderChat.ask(fixPlan.taskPrompt(task)) *>
                     reviewAndFixLoop(Reviewers.minimal, reasoning, coderChat, task.title, git.diff) *>
                     git.commitAll(s"$branchName: ${task.title}").unit
                 }
    _         <- stage("Push the fix")(git.push("origin", branchName))
    diff      <- git.diff
    finalSum  <- stage("Final PR summary")(
                   summarisePr(
                     reasoning,
                     diff,
                     context = Some(s"Issue ${ref.shortRef}: ${issue.title}\nThe branch now has the failing test AND the fix."),
                   )
                 )
    _         <- stage("Update PR")(gh.updatePr(pr, finalSum.title, s"${finalSum.body}\n\nCloses ${ref.shortRef}."))
    _         <- PlanStore.delete(planPath)
    _         <- stage(s"Return to $start")(git.checkout(start))
  yield ()
```

- [ ] **Step 3: Write the shared seeder**

Create `examples/seed.sh` (mode 755 — `chmod +x examples/seed.sh`):

```bash
#!/usr/bin/env bash
#
# Seed a runnable test project for an llm4zio example.
#
#   examples/seed.sh <example>                # mktemp dest, deps from Maven Central
#   examples/seed.sh <example> /path/to/dir   # explicit dest
#   examples/seed.sh <example> --local        # sbt publishLocal + pin the local version
#   examples/seed.sh <example> --run          # seed, then run the flow
#
# Examples: implement, implement-interactive, implement-enhanced, implement-live,
#           epic, issue-pr, issue-pr-bugfix

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

EXAMPLE="${1:-}"
[ -n "$EXAMPLE" ] || { echo "usage: examples/seed.sh <example> [dest] [--local] [--run]" >&2; exit 2; }
shift

# example → starter project + demo prompt ("" = the flow needs a real argument, e.g. an issue ref)
case "$EXAMPLE" in
  implement)             STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  implement-enhanced)    STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  implement-interactive) STARTER="calculator-rs-open"; PROMPT="Make the calculator crate more useful" ;;
  implement-live)        STARTER="calculator-rs-open"; PROMPT="Make the calculator crate more useful" ;;
  epic)                  STARTER="todo-java";          PROMPT="Persist tasks to a JSON file (load on startup, save on every change), add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) with a 'list --priority' filter" ;;
  issue-pr)              STARTER="calculator-scala";   PROMPT="" ;;
  issue-pr-bugfix)       STARTER="calculator-scala";   PROMPT="" ;;
  *) echo "unknown example: $EXAMPLE" >&2; exit 2 ;;
esac
SCRIPT_NAME="$EXAMPLE.sc"

LOCAL=0; RUN=0; DEST=""
for arg in "$@"; do
  case "$arg" in
    --local) LOCAL=1 ;;
    --run)   RUN=1 ;;
    --*)     echo "unknown flag: $arg" >&2; exit 2 ;;
    *)       DEST="$arg" ;;
  esac
done

if [ -z "$DEST" ]; then
  tmp="${TMPDIR:-/tmp}"
  DEST="$(mktemp -d "${tmp%/}/llm4zio-$EXAMPLE.XXXXXXXX")"
fi
mkdir -p "$DEST"

cp -R "$SCRIPT_DIR/starters/$STARTER/." "$DEST/"
cp "$SCRIPT_DIR/$SCRIPT_NAME" "$DEST/$SCRIPT_NAME"
( cd "$DEST" \
    && git init -q -b main \
    && git add -A \
    && git -c user.email=seed@llm4zio.dev -c user.name=llm4zio commit -q -m "Seed $EXAMPLE starter" )

if [ "$LOCAL" -eq 1 ]; then
  echo "Publishing llm4zio locally (sbt publishLocal)…"
  ( cd "$REPO_ROOT" && sbt -batch -Dsbt.log.noformat=true publishLocal >/dev/null )
  ivy="$HOME/.ivy2/local/io.github.riccardomerolla/llm4zio-runner_3"
  version="$(ls -t "$ivy" 2>/dev/null | head -1)"
  [ -n "$version" ] || { echo "no locally published llm4zio-runner under $ivy" >&2; exit 1; }
  echo "Pinning script to local version $version"
  sed -i.bak -E "s#(io\.github\.riccardomerolla::llm4zio-runner:)[^\"]+#\1$version#" "$DEST/$SCRIPT_NAME"
  rm -f "$DEST/$SCRIPT_NAME.bak"
  if ! grep -q 'using repository ivy2Local' "$DEST/$SCRIPT_NAME"; then
    printf '%s\n' '//> using repository ivy2Local' | cat - "$DEST/$SCRIPT_NAME" > "$DEST/$SCRIPT_NAME.tmp"
    mv "$DEST/$SCRIPT_NAME.tmp" "$DEST/$SCRIPT_NAME"
  fi
fi

echo
echo "Test project ready at: $DEST"
if [ "$RUN" -eq 1 ]; then
  echo "Running: scala-cli run $SCRIPT_NAME -- \"$PROMPT\""
  cd "$DEST"
  exec scala-cli run "$SCRIPT_NAME" -- "$PROMPT"
fi
cat <<EOF

Next steps:
  cd $DEST
  scala-cli run $SCRIPT_NAME -- "$PROMPT"

Requires: JDK 21+, scala-cli, the starter's toolchain (cargo / sbt / maven), and the
chosen agent CLI logged in (claude by default; LLM4ZIO_CODER=codex|gemini to swap).
EOF
```

- [ ] **Step 4: Delete the old layout**

```bash
git rm -r plans examples/_seed_lib.sh \
  examples/01-simple examples/02-interactive examples/03-bugfix examples/04-epic \
  examples/05-interactive-live examples/06-issue-pr examples/07-enhanced
```

(The `test-project` dirs inside 01–04 were already `git mv`'d in Step 1; `git rm -r` removes what's left — READMEs and per-example seeders.)

- [ ] **Step 5: Rewrite `examples/README.md`**

Replace its content with:

```markdown
# Examples

Flat, orca-shaped flow scripts. Each is a single `.sc` file: `//> using dep` pins
llm4zio, `flow(args)` opens the flow, and the body is an ordinary ZIO
for-comprehension with `git`/`gh`/`coder`/`reasoning`/`userPrompt` available bare.
Documentation lives in each script's header comment.

| Script                    | What it shows                                                | Starter           |
| ------------------------- | ------------------------------------------------------------ | ----------------- |
| `implement.sc`            | Autonomous plan → implement → review loop                    | calculator-rs     |
| `implement-interactive.sc`| Planner asks clarifying questions first                      | calculator-rs-open|
| `implement-enhanced.sc`   | Plan self-review + shared codebase brief (`.reviewed/.briefed`) | calculator-rs  |
| `implement-live.sc`       | Held, steerable claude session, streaming + ask_user over MCP | calculator-rs-open|
| `epic.sc`                 | Multi-task epic, full reviewer roster, doc update at the end | todo-java         |
| `issue-pr.sc`             | GitHub issue → assess → implement → PR                       | calculator-scala  |
| `issue-pr-bugfix.sc`      | Bug report → failing test → red CI → fix → PR                | calculator-scala  |

## Running one

```bash
examples/seed.sh implement          # seed a starter into a temp dir
examples/seed.sh implement --run    # seed + run
examples/seed.sh implement --local  # test against the in-tree build (sbt publishLocal)
```

Or by hand: copy a starter from `examples/starters/`, drop the script next to it,
`git init`, then

```bash
scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
```

Backend: `LLM4ZIO_CODER=claude|codex|gemini` (default claude). No API key —
one CLI login is enough. The issue-pr flows additionally need `gh` authenticated
and a repo with a remote.
```

- [ ] **Step 6: Verify end-to-end against the in-tree build**

```bash
examples/seed.sh implement --local /tmp/llm4zio-e2e
cd /tmp/llm4zio-e2e && scala-cli compile implement.sc
```

Expected: `scala-cli compile` succeeds (this proves the `.sc` top-level `flow(...)` frame, the bare-name accessors, and the chaining extensions all typecheck against the locally published 3.0.0-SNAPSHOT). Repeat for the trickiest other two:

```bash
cd /Users/riccardo/git/github/riccardomerolla/llm4zio
examples/seed.sh implement-enhanced --local /tmp/llm4zio-e2e-enh && (cd /tmp/llm4zio-e2e-enh && scala-cli compile implement-enhanced.sc)
examples/seed.sh issue-pr-bugfix --local /tmp/llm4zio-e2e-bug && (cd /tmp/llm4zio-e2e-bug && scala-cli compile issue-pr-bugfix.sc)
```

Expected: both compile. If `flow` is reported ambiguous with the `llm4zio.flow` package name, qualify the call as `llm4zio.runner.flow(...)` in the script and file an API note — but this is not expected (packages are not term-applicable).

- [ ] **Step 7: Commit**

```bash
git add examples
git commit -m "feat(examples)!: flat orca-shaped .sc scripts, shared seed.sh, consolidated starters"
```

---

### Task 11: Documentation — README and CLAUDE.md

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update README.md's front example**

In the "An example flow" section (~line 40), replace "Save this as `implement.scala` and run it with your task:" and the old-style code block with "Save this as `implement.sc` and run it with your task:" followed by the full new `examples/implement.sc` body (the exact script from Task 10 Step 2, including the `//> using` directives, minus the doc comment). After the code block, ensure the run line reads:

```bash
scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
```

- [ ] **Step 2: Sweep the rest of README.md**

Run: `grep -n 'implement.scala\|Llm4zio.run\|given FlowEvents\|mapError\|plans/\|01-simple\|create-test-project' README.md`

For each hit: update script names to `.sc`, replace `given FlowEvents = ctx.events` lines and `mapError(... FlowError.Llm ...)` from any snippet (they no longer exist in the API), point example instructions at `examples/seed.sh <example>`, and keep exactly one section showing `Llm4zio.run { ctx => ... }` explicitly labelled as the *embedding* surface for real ZIO apps (that API remains). Update any version strings `2.10.0` → `3.0.0`.

- [ ] **Step 3: Update CLAUDE.md**

(a) Replace the entire "A flow reads top-to-bottom" section's code block with:

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
    url       <- gh.createPr(plan.epicId, body = "…", base = Some("main"))
  yield url
```

and change the trailing sentence to: "See `examples/*.sc` for worked versions; `llm4zio.runner.ExampleFlow` is the embedded (ZIOAppDefault) variant with an end-to-end test."

(b) In the **Conventions** section add one bullet:

```markdown
- **Script surface.** Examples are flat `examples/*.sc` files: `llm4zio.runner.flow(args) { body }`
  holds the library's only `unsafeRun`; the body is `FlowContext ?=> ZIO[Any, FlowError, Any]`.
  Bare names (`git`, `gh`, `coder`, `reasoning`, `userPrompt`, `workDir`) summon the context;
  `FlowEvents` derives from `FlowContext` via the companion given. Embedders use `Llm4zio.run`.
```

(c) In the **Modules** table, update the `llm4zio-runner` line to mention `flow entry point` and the example file pattern `examples/*.sc` (replacing any mention of `plans/`).

- [ ] **Step 4: Verify and commit**

Run: `grep -rn 'plans/' README.md CLAUDE.md examples/ .github 2>/dev/null` — expected: no hits.

```bash
git add README.md CLAUDE.md
git commit -m "docs: README + CLAUDE.md on the 3.0.0 flow() script surface"
```

---

### Task 12: Final verification and release

- [ ] **Step 1: Full clean verification**

```bash
sbt check                                  # scalafix + scalafmt verification
sbt compile test "llm4zioFlow/It/test" "llm4zioRunner/It/test"
```

Expected: ALL PASS, zero warnings.

- [ ] **Step 2: One real end-to-end smoke (optional but recommended; needs `claude` logged in)**

```bash
examples/seed.sh implement --local --run
```

Expected: the flow plans, implements multiply in the calculator crate, reviews, and commits on an epic branch; the terminal shows the stage tree and the final ✔ banner. Ctrl-C mid-run must render the ✖ banner and exit 130 (`echo $?`).

- [ ] **Step 3: Release (USER ACTION — do not tag without explicit go-ahead)**

The version comes from the git tag (dynver). When the user says ship:

```bash
git tag v3.0.0
git push origin main v3.0.0   # CI publishes to Maven Central via sbt-ci-release
```

Note: the examples reference `3.0.0` on Maven Central; until the tag is published, only `--local` runs work. This is expected and matches how `2.10.0` examples were developed.

---

## Self-Review Notes

- **Spec coverage** — all nine grilling decisions have tasks: scope/entry (T7–T8), connectors (T6), bare names + given (T1–T2), errors (T3), plan chain + defaultPath (T4–T5), examples (T10), versioning/release (T12), docs (T11), direct-style-only scope (no extra work included).
- **Type consistency** — `FlowContext.userPrompt: String` / `workDir: Path` (T1) match the accessors (T2), `Llm4zio.script`'s `ctx.copy(userPrompt = prompt)` (T7), and `DefaultFlowContext`'s `workDir = workDir` (T7). `Chat.ask: IO[FlowError, String]` (T3) matches the examples calling `coderChat.ask(...)` with no `mapError` (T10). `Planner...reviewed(reasoning)` / `.briefed(reasoning, prompt)` (T5) match every example use. `Connectors.coderFromEnv` / `claude`/`codex`/`gemini` (T6) match `flow`'s default and example overrides.
- **Known risks called out in-task**: given-ambiguity regression (T1 Step 4 runs the whole module), `flow` name vs `llm4zio.flow` package (T10 Step 6 has the fallback), `.sc` `args` is `Array[String]` (hence `flow(args: Seq[String], ...)` — arrays convert implicitly).
