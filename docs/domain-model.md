## 1. The core domain

llm4zio's core domain is **agentic software-development flows**: expressing, as an ordinary typed effect program, the act of *planning a change, delegating the code-editing to an AI agent, having other agents review the resulting diff, and committing/pushing/raising a PR* — with **provider-agnostic LLM access** as the supporting subdomain underneath.

The strategic split is visible directly in the module graph (`build.sbt`, dependency direction `runner → flow → core`):

- **Core domain — Agentic Flow** (`llm4zio-flow`): plans, tasks, the coder conversation, the review-and-fix loop, the git/PR side-effect tools, flow events and errors. This is the differentiating model and where the ubiquitous language is richest.
- **Generic supporting subdomain — LLM Access** (`llm4zio-core`): a uniform `LlmService` over many providers, connectors, capabilities, tools, structured output. Replaceable in principle, but the thing every flow stands on.
- **Driving/Application layer** (`llm4zio-runner`): entry points, connector presets, context wiring, terminal rendering. It *uses* the domain; it adds little domain vocabulary of its own (mostly presentation and configuration).

The pervasive modelling discipline (from `CLAUDE.md` and visible everywhere): **recoverable situations are typed values, genuine failures are typed errors.** `GitTool.commitAll` returns `Commit.NothingToCommit` in the value channel (`modules/llm4zio-flow/src/main/scala/llm4zio/flow/GitTool.scala:105`), it does not fail; only catastrophic problems become `FlowError.Process` (`GitTool.scala:106`).

---

## 2. Bounded contexts

```mermaid
graph TD
  subgraph Flow["Agentic Flow Context  (llm4zio-flow — CORE)"]
    FC[FlowContext]
    PL[Plan / Task]
    CH[Chat]
    RV[Review system]
    EV[FlowEvent / FlowEvents]
    GT[GitTool / GhTool / AdoTool]
    HITL[Interaction / Approval / McpServer]
  end
  subgraph LLM["LLM Access Context  (llm4zio-core — SUPPORTING)"]
    LS[LlmService]
    CN[Connector / Capabilities]
    MO[Message / TokenUsage / LlmChunk]
    TO[Tool / ToolRegistry]
    CV[Conversation*]
  end
  subgraph Run["Driving / Application  (llm4zio-runner)"]
    EP[flow / Llm4zio.run]
    PR[Connectors presets]
    TUI[Terminal rendering]
  end

  Run -->|builds & drives| Flow
  Flow -->|reasoning / coder seats| LLM
  Run -->|resolves connectors| LLM
```

The two contexts share a **conformist** relationship: the Flow context depends on the LLM context's published language (`LlmService`, `Message`, `TokenUsage`, `ConnectorCapabilities`, `LlmError`) and conforms to it — it does not wrap it in an anti-corruption layer. The one place the two error vocabularies meet is `FlowError.Llm`, which carries a required `message: String` plus an `Option[LlmError]` (`flow/FlowError.scala:23`).

A nuance worth flagging: `core/Conversation.scala` defines a rich `ConversationThread`/`ConversationCheckpoint`/`ConversationMessage`/`PromptTemplate`/`PromptRegistry`/`ConversationStore` vocabulary (with an in-memory store and prompt registry), but the *flow* layer does **not** use it — `Chat` (flow) models conversation independently as a `Ref[List[Message]]`. So core carries two conversation vocabularies; only the primitive `Message` is actually consumed by the core domain. This is a remnant model, not a wired part of the flow.

---

## 3. Ubiquitous language

| Term | Meaning in code | Where |
|---|---|---|
| **Flow** | An ordinary `ZIO[Any, FlowError, Any]` program, run with a `FlowContext` in scope, that plans → implements → reviews → ships. | `runner/Flow.scala:29` |
| **FlowContext** | The bag of everything a flow needs; summoned by bare names in scripts. | `flow/FlowContext.scala:14` |
| **Reasoning / Coder (the role split)** | Two `LlmService` "seats": reasoning (planning, review — usually an API connector), coder (edits the tree — usually a CLI agent). A single all-CLI backend can fill both. | `FlowContext.scala:15-16` |
| **Plan / Epic / Task** | A unit of work: an `epicId` + ordered `Task`s; persisted as resumable Markdown. | `flow/Plan.scala:10,17` |
| **Brief** | An optional codebase summary attached to a Plan to ground a cold coder; prepended to each task's prompt by `Plan.taskPrompt`. | `Plan.scala:17,29`; `Planner.briefed` |
| **Chat** | A stateful coder conversation (accumulating history); seeded *not* to touch git. | `flow/Chat.scala`, `CoderSystem.gitOwnership` |
| **git ownership** | The rule that the runtime, not the coder, commits/branches/pushes. | `flow/CoderSystem.scala` |
| **Reviewer / lens** | A named review perspective = system prompt + optional changed-file regex scope, loaded from classpath Markdown. | `flow/Reviewer.scala:15,48`; `resources/llm4zio/review/reviewers/*.md` |
| **Review-and-fix loop** | Reviewers judge the diff → coder fixes → re-review, up to `maxRounds`. | `flow/LlmReview.scala:151` |
| **Reviewer selector** | A *policy* deciding which lenses run a given round (`allEveryRound`/`whileDirty`/`llmDriven`). | `flow/LlmReview.scala:15,28,32,39` |
| **Stage** | A named, observable step that brackets an effect with start/complete/fail events. | `flow/Stage.scala` |
| **FlowEvent** | A progress fact (StageStarted, ToolUse, TokensUsed, …) published to a sink. | `flow/FlowEvents.scala:9` |
| **Verdict / Triage / PlanningStep** | Structured LLM judgements you inspect before acting (`Proceed`/`Blocked`; `NotABug`/`Untestable`/`Testable`; `AskUser`/`Proposed`). | `flow/Verdict.scala:7`, `flow/Triage.scala:21`, `flow/Interactive.scala:21` |
| **Connector** | An `LlmService` with identity, kind, health, capabilities. | `core/Connector.scala:13` |
| **Capability** | A declared, per-connector ability (streaming, askUser, approval, …) — *not uniform*. | `core/Connector.scala:102` |
| **Recoverable outcome** | An expected result returned as a value, not a failure (`AlreadyExists`, `NothingToCommit`). | `GitTool.scala:121-125` |
| **Interaction / Approval** | The HITL primitives: ask a human a question / allow-or-deny a tool call. | `flow/Interactive.scala:9`, `flow/Approval.scala` |

---

## 4. Central entities, aggregates & value objects

### 4.1 Agentic Flow context (the core)

**`FlowContext`** (`flow/FlowContext.scala:14`) is the **aggregate root of a running flow** — it composes the two LLM seats, the side-effect tools, the event sink, optional reviewers, declared coder capabilities, the prompt and the working directory:

```scala
final case class FlowContext(
  reasoning: LlmService, coder: LlmService,
  git: GitTool, gh: GhTool, events: FlowEvents,
  reviewers: List[LlmService] = Nil,
  coderCapabilities: ConnectorCapabilities = ConnectorCapabilities(),
  userPrompt: String = "", workDir: Path = …)
```

It also exposes `given FlowEvents = events` (`FlowContext.scala:31`) so `stage`/`fail` resolve their sink implicitly inside a `FlowContext ?=>` body.

**`Plan`** (`flow/Plan.scala:17`) is the **work aggregate**: `epicId`, `List[Task]`, optional `brief`. `Task` (`Plan.scala:10`) is an entity-within-aggregate (`title`, `description`, `completed`). The Plan owns task-completion transitions (`complete`, `nextIncomplete`, `Plan.scala:20,23`), task-prompt assembly (`taskPrompt` prepends the brief, `Plan.scala:29`), and Markdown rendering (`render`, `Plan.scala:35`). **`PlanStore`** (`flow/PlanStore.scala`) is its **repository** — plain-file persistence (`.llm4zio/plan-<hash>.md`) keyed by a deterministic path from the prompt (`Plan.defaultPath`, `Plan.scala:53`), enabling crash-resume via `recoverOrCreate`. A non-empty brief round-trips inside a trailing `# Brief` section — no sidecar (`Plan.scala:42`).

**`Chat`** (`flow/Chat.scala`) is an entity holding mutable conversation state (`Ref[List[Message]]`) — the coder's working memory across tasks. Continuity is *replayed history*, not a backend session token. Its invariant — *the coder never owns git* — is enforced at construction (`Chat.start` prepends `CoderSystem.gitOwnership`); opt out with `manageGit = true`.

**Review subdomain** — value objects all derive `JsonCodec` so the LLM can return them structurally:
- `Severity` (`Critical|Warning|Info`, `flow/Review.scala:6`), `ReviewIssue` (with `file`/`line`/`confidence`, `Review.scala:10`), `ReviewResult` (with `isClean`, `Review.scala:21`).
- `Reviewer` (a named lens: `name` + `systemPrompt` + optional file-scope regex; `flow/Reviewer.scala:15`, loaded via `Reviewer.fromResource`, `Reviewer.scala:48`).
- `ReviewerSelector` (a domain *policy* — `allEveryRound`/`whileDirty`/`llmDriven(picker)`, `flow/LlmReview.scala:28,32,39`).
- **Ten shipped lenses** (one Markdown file each under `resources/llm4zio/review/reviewers/`): `code-functionality`, `test`, `readability`, `code-structure`, `performance`, `security`, `scala-zio`, plus three opt-ins — `tddDiscipline`, `domainLanguage`, `effectShape` (`LlmReview.scala:74,79,85`). Rosters: `Reviewers.all`/`.minimal`, e.g. `Reviewers.minimal :+ Reviewers.tddDiscipline`.

**Planner outputs** — `Verdict[A]` (`Proceed`/`Blocked`, `flow/Verdict.scala:7`), `Triage` (`NotABug`/`Untestable`/`Testable`, `flow/Triage.scala:21`), `PlanningStep` (`AskUser`/`Proposed`, `flow/Interactive.scala:21`). These encode the "preview-returns-a-plan" pattern: a structured judgement to inspect before acting. `IssueRef` (`owner/repo#number`, parsed via `IssueRef.parse`) also lives here (`flow/Triage.scala:6`).

**Side-effect tools** (services over `zio-process` via `flow/Proc.scala`, bound to `workDir`):
- `GitTool` with recoverable-outcome enums `CreateBranch{Created,AlreadyExists}` and `Commit{Committed,NothingToCommit}` (`GitTool.scala:121-125`). Every git call runs under a non-interactive env so a TTY-less flow fails fast (`GitTool.scala:127`); github pushes append a last-resort `GH_TOKEN`/`GITHUB_TOKEN` credential helper (`GitTool.scala:109`).
- `GhTool` with value objects `Issue` (`flow/GhTool.scala:9`), `PullRequest` (`GhTool.scala:12`), and CI state enum `BuildOutcome{Success,Failure,Pending,TimedOut}` (`GhTool.scala:25`); `waitForBuild` polls to a terminal outcome (`GhTool.scala:72`).
- `AdoTool` with `WorkItem`, `AdoPullRequest`, `AdoConfig`, `AdoRequest` for the Azure Boards/Repos board-gated SDD flow — pure request-builders + parsers over `HttpClient` (`flow/AdoTool.scala`, added v3.4.0).
- `PrSummary` (`summarisePr` → `PrSummary(title, body)` from a diff, `flow/PrSummary.scala`).

**Observability & control** — `FlowEvent` (enum: `StageStarted`/`StageCompleted`/`StageFailed`, `Aborted`, `Info`, `ToolUse`, `AssistantMessage`, `TokensUsed`; `FlowEvents.scala:9-17`) with sinks `noop`/`Collecting`/`Hub` (`FlowEvents.scala:27,35,43`); `CostTracker`+`PriceList` accumulate `TokenUsage` per agent/model from `TokensUsed` events. **HITL**: `Interaction` (`flow/Interactive.scala:9`), `ApprovalPolicy`/`ApprovalDecision` (`flow/Approval.scala`), `McpServer` (a transport-free MCP handler exposing `ask_user`/`approve`), `Drive` over an `AgentSession`.

**Error model** — `FlowError` ADT (`sealed trait`, `flow/FlowError.scala:6`): `Persistence`, `PlanParse`, `Aborted`, `Process`, `Llm`.

### 4.2 LLM Access context (supporting)

**`LlmService`** (`core/LlmService.scala:17`) is the **published capability** the whole system depends on: `executeStream`, `executeStreamWithHistory`, `executeWithTools`, `executeStructured`/`WithUsage`, `isAvailable`. **`Connector`** (`core/Connector.scala:13`) refines it with identity (`id`, `kind`) and metadata (`healthCheck`, `capabilities`); `ApiConnector` (`Connector.scala:25`) and `CliConnector` (`Connector.scala:34`) are the two kinds — `CliConnector` *derives* the richer methods from two primitives, getting `executeStructured` via a schema hint and `executeWithTools` failing unless overridden.

The decisive domain rule: **capabilities are declared, not uniform** — `ConnectorCapabilities` (`Connector.scala:102`) records `streaming`, `resumableSessions`, `interactiveSessions`, `askUser`, `approval`, `structuredOutput`, `usageReporting`, so a flow can refuse an unsupported workflow up front (e.g. gemini declares `InteractiveStdin` yet `askUser = false` headless). The branch driver is the `InteractionSupport` enum (`InteractiveStdin`/`ContinuationOnly`, `Connector.scala:99`).

Value objects: `LlmProvider`, `ConnectorId`, `Message`/`MessageRole`, `TokenUsage`, `LlmChunk`, `LlmConfig`, `LlmResponse`, `StreamProgress` (`core/Models.scala`); config ADT `ConnectorConfig` → `ApiConnectorConfig`/`CliConnectorConfig`/`FallbackChain` (`core/ConnectorConfig.scala`); error ADT `LlmError` (`core/Errors.scala`). **`ConnectorRegistry`** (`core/ConnectorRegistry.scala`) + **`ConnectorFactories`** (`providers/ConnectorFactories.scala`) form the factory/repository resolving a config to a live `Connector`. **Tools**: `Tool`/`AnyTool`, `JsonSchema`, `ToolCall`/`ToolCallResponse`, `ToolRegistry`; held interactive sessions via `AgentSession`/`ClaudeAgentSession`.

---

## 5. Domain model — class diagram

```mermaid
classDiagram
  direction LR

  %% ===== Agentic Flow (core domain) =====
  class FlowContext {
    +reasoning: LlmService
    +coder: LlmService
    +git: GitTool
    +gh: GhTool
    +events: FlowEvents
    +reviewers: List~LlmService~
    +coderCapabilities: ConnectorCapabilities
    +userPrompt: String
    +workDir: Path
  }
  class Plan {
    +epicId: String
    +brief: Option~String~
    +nextIncomplete() Option~Task~
    +complete(title) Plan
    +taskPrompt(task) String
  }
  class Task {
    +title: String
    +description: String
    +completed: Boolean
  }
  class PlanStore {
    <<repository>>
    +recoverOrCreate(path)(create) Plan
  }
  class Chat {
    <<entity>>
    -history: Ref~List~Message~~
    +ask(prompt) String
  }
  class Reviewer {
    +name: String
    +systemPrompt: String
    +files: Option~String~
  }
  class ReviewResult { +issues; +summary; +isClean() }
  class ReviewIssue { +severity; +title; +file; +line; +confidence }
  class Severity { <<enum>> Critical; Warning; Info }
  class ReviewerSelector { <<policy>> allEveryRound; whileDirty; llmDriven }
  class Verdict~A~ { <<enum>> Proceed; Blocked }
  class Triage { <<enum>> NotABug; Untestable; Testable }
  class GitTool {
    +createBranch() CreateBranch
    +commitAll() Commit
    +diff() String
  }
  class GhTool { +createPr() PullRequest; +waitForBuild() BuildOutcome }
  class AdoTool { +readWorkItem() WorkItem; +createPr() AdoPullRequest }
  class FlowEvents { <<sink>> +publish(FlowEvent) }
  class FlowEvent { <<enum>> StageStarted; ToolUse; TokensUsed; ... }
  class FlowError { <<ADT>> Persistence; PlanParse; Aborted; Process; Llm }
  class CostTracker { +record(FlowEvent) }
  class Interaction { <<service>> +ask(q) String }
  class ApprovalPolicy { <<policy>> +decide() ApprovalDecision }

  FlowContext "1" *-- "2" LlmService : reasoning + coder
  FlowContext "1" *-- "1" GitTool
  FlowContext "1" *-- "1" GhTool
  FlowContext "1" *-- "1" FlowEvents
  Plan "1" *-- "*" Task
  PlanStore ..> Plan : persists / recovers
  Chat ..> LlmService : threads history through
  Reviewer ..> LlmService : asService(base)
  ReviewResult "1" *-- "*" ReviewIssue
  ReviewIssue --> Severity
  ReviewerSelector ..> Reviewer : selects
  FlowEvents ..> FlowEvent : publishes
  CostTracker ..> FlowEvent : consumes TokensUsed
  FlowError ..> LlmError : Llm wraps

  %% ===== LLM Access (supporting) =====
  class LlmService {
    <<published capability>>
    +executeStreamWithHistory(msgs) Stream
    +executeStructured~A~(prompt, schema) A
    +isAvailable() Boolean
  }
  class Connector { +id: ConnectorId; +kind; +capabilities }
  class ApiConnector
  class CliConnector
  class ConnectorCapabilities {
    +streaming; +askUser; +approval
    +structuredOutput; +usageReporting
  }
  class ConnectorRegistry { <<factory>> +resolve(cfg) Connector }
  class ConnectorConfig { <<ADT>> Api; Cli; FallbackChain }
  class Message { +role: MessageRole; +content }
  class TokenUsage { +prompt; +completion; +total }
  class LlmError { <<enum>> ProviderError; UsageLimitError; ... }

  Connector --|> LlmService
  ApiConnector --|> Connector
  CliConnector --|> Connector
  Connector --> ConnectorCapabilities
  ConnectorRegistry ..> ConnectorConfig : resolves
  ConnectorRegistry ..> Connector : produces
  LlmService ..> Message : consumes
  LlmService ..> TokenUsage : reports
  LlmService ..> LlmError : fails with
```

---

## 6. How the language flows (the canonical interaction)

The model reads top-to-bottom in `examples/implement.sc`, every noun above appearing as a bare name resolved from the `FlowContext ?=>` context function:

1. `Planner.from(reasoning, userPrompt)` asks the **reasoning** seat for a structured **`Plan`**; **`PlanStore.recoverOrCreate`** resumes it from `.llm4zio/`.
2. `stage("branch")(git.checkoutOrCreate(plan.epicId))` brackets a **`GitTool`** side-effect as an observable **stage**.
3. `Chat.start(coder, …)` opens the **coder** **`Chat`** under the git-ownership invariant.
4. `implementTaskLoop` walks each incomplete **`Task`** (persisting the plan after each, so a crash resumes): the coder edits the tree, `reviewAndFixLoop(Reviewers.minimal, reasoning, …, git.diff)` has a **`ReviewerSelector`** pick **`Reviewer`** lenses, fans them over the diff into **`ReviewResult`**s and converges, then `git.commitAll(...)` returns a **`Commit`** outcome.

Throughout, every step publishes **`FlowEvent`**s to the context's **`FlowEvents`** sink; recoverable git/PR situations come back as values; only catastrophe becomes **`FlowError`**.

---

## 7. Modelling notes (wired vs. merely present)

- **Wired and central:** `FlowContext`, `Plan`/`Task`/`PlanStore`, `Chat`, the `Reviewer`/`ReviewerSelector`/`ReviewResult`/`reviewAndFixLoop` cluster, `GitTool`/`GhTool`, `FlowEvent`/`FlowEvents`, `LlmService`/`Connector`/`ConnectorCapabilities`/`ConnectorRegistry`, `FlowError`/`LlmError`. These carry the ubiquitous language and are exercised by `examples/*.sc` and integration tests.
- **Wired but secondary:** `AdoTool` (only the ADO examples), `Interaction`/`ApprovalPolicy`/`McpServer`/`Drive` (the live/interactive examples), `CostTracker`/`PriceList` (runner cost summary), `Verdict`/`Triage`/`IssueRef`/`PlanningStep` (issue/assess/interactive examples), `PrSummary` (PR-creating examples).
- **Present but not part of the live flow model:** `core/Conversation.scala`'s `ConversationThread`/`ConversationCheckpoint`/`PromptTemplate`/`PromptRegistry`/`ConversationStore` — a parallel, unused conversation vocabulary; the flow uses `Chat` instead. The `core/observability.*` decorators (`MeteredLlmService`, `Langfuse`, `Tracing`) similarly exist but the runner taps connectors via the flow-layer `EventTappingService`/`CostTracker` rather than wiring these.
- **The pervasive invariants** to internalise as the model's spine: *recoverable = value, catastrophic = error* (`CreateBranch`/`Commit` enums vs `FlowError.Process`), *the runtime owns git, the coder owns edits* (`CoderSystem.gitOwnership` in `Chat.start`), and *capabilities are declared, not assumed* (`ConnectorCapabilities`). All three are enforced in the types, not left to convention.