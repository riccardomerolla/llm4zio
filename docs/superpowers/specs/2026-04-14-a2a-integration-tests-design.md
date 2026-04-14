# A2A Integration Tests with Mocked AI Provider

**Date:** 2026-04-14
**Status:** Design approved

---

## Goal

Add integration tests with mocked AI provider calls to validate the proactive agent workflows (Planning, Review, Triage, Refactor) and A2A dialogue interactions. Introduce a small production abstraction (`AgentDialogueRunner`) that encapsulates the agent-side turn-taking loop — the missing piece between `LlmService` and `AgentDialogueCoordinator`.

---

## Production Code

### `AgentResponse` entity

**File:** `modules/conversation-domain/src/main/scala/conversation/entity/AgentResponse.scala`

A small case class representing the structured output from an LLM during a dialogue turn:

```scala
case class AgentResponse(
  content: String,
  concluded: Boolean,
  outcome: Option[DialogueOutcome],
) derives JsonCodec, Schema
```

- `content` — the agent's message text
- `concluded` — whether the agent wants to end the dialogue
- `outcome` — present only when `concluded = true`; the final verdict (e.g., `Approved`, `ChangesRequested`, `MaxTurnsReached`)

Lives in `conversation.entity` alongside `AgentDialogue.scala` and `DialogueEvent.scala`.

### `AgentDialogueRunner` trait

**File:** `modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueRunner.scala`

```scala
trait AgentDialogueRunner:
  def runDialogue(
    conversationId: ConversationId,
    agentRole: AgentRole,
    maxTurns: Int = 10,
  ): IO[PersistenceError, DialogueOutcome]
```

Single-method trait. The runner represents one agent's participation in a dialogue — call it once per agent, each in its own fiber.

### `AgentDialogueRunnerLive` implementation

**File:** `src/main/scala/conversation/control/AgentDialogueRunnerLive.scala`

Lives in root because it depends on `LlmService` (from the `llm4zio` module), which `conversation-domain` does not depend on. Same package (`conversation.control`) across sbt module boundaries — Scala allows same-package access.

**Dependencies:** `AgentDialogueCoordinator & ConversationRepository & LlmService`

**Loop logic:**

1. `coordinator.awaitTurn(conversationId)` — blocks until it's this agent's turn
2. `repository.get(conversationId)` — load conversation with full message history
3. Build prompt from conversation history (role-aware: reviewer sees code context, author sees review comments)
4. `llmService.executeStructured[AgentResponse](prompt, schema)` — get structured response from LLM
5. `coordinator.respondInDialogue(conversationId, response.content, agentName)` — post message and advance turn
6. If `response.concluded`, call `coordinator.concludeDialogue(conversationId, response.outcome.get)` and return the outcome
7. If turn count reaches `maxTurns`, conclude with `DialogueOutcome.MaxTurnsReached` and return
8. Otherwise loop back to step 1

**ZLayer:**

```scala
val live: ZLayer[AgentDialogueCoordinator & ConversationRepository & LlmService, Nothing, AgentDialogueRunner]
```

---

## Test Suite 1: `AgentDialogueRunnerSpec`

**File:** `src/test/scala/conversation/control/AgentDialogueRunnerSpec.scala`

Tests the full A2A dialogue loop with mocked LLM responses. Each test wires:

- `AgentDialogueCoordinator.live` with Ref-based `ConversationRepository` stub and `Hub[DialogueEvent]`
- Two `AgentDialogueRunnerLive` instances, each with its own mock `LlmService` (separate response queues for Reviewer vs Author)

### Mock LLM factory

```scala
def mockLlmForDialogue(responses: List[AgentResponse]): UIO[LlmService]
```

Wraps a `Ref[List[AgentResponse]]`. Each `executeStructured[AgentResponse]` call pops the next response. Other `LlmService` methods return `ZIO.fail(LlmError.Unsupported("not used in test"))` or similar. Same pop-queue pattern as `IntegrationFixtures.stubLlm`.

### Event collector utility

```scala
def collectDialogueEvents(hub: Hub[DialogueEvent]): ZIO[Scope, Nothing, Ref[List[DialogueEvent]]]
```

Subscribes to the hub, forks a fiber that appends incoming events to a `Ref[List[DialogueEvent]]`, returns the ref for post-test assertions.

### Test 1: Review passes after 1 round

**Setup:**
- Start dialogue with two participants: `reviewer-agent` (Reviewer) and `author-agent` (Author)
- Reviewer mock queue: `AgentResponse("Found minor style issues in line 42", false, None)` → `AgentResponse("Issues addressed, approving", true, Some(Approved))`
- Author mock queue: `AgentResponse("Fixed style issues as suggested", false, None)`

**Execution:**
1. `coordinator.startDialogue(conversationId, issueId, participants, "Code review for issue-123")` — sets first turn to Reviewer
2. Fork `reviewerRunner.runDialogue(convId, Reviewer, maxTurns=10)`
3. Fork `authorRunner.runDialogue(convId, Author, maxTurns=10)`
4. Join both fibers

**Assertions:**
- Reviewer fiber returns `DialogueOutcome.Approved`
- Author fiber returns `DialogueOutcome.Approved` (it observes the conclusion)
- Conversation has 3 messages in order: reviewer critique → author fix → reviewer approval
- Each message has correct `SenderType.Agent(role)`
- Collected events include: `DialogueStarted`, 3× `MessagePosted`, 2× `TurnChanged`, `DialogueConcluded(Approved)`

### Test 2: Review requests rework

**Setup:**
- Same two participants
- Reviewer mock queue: `AgentResponse("Critical security vulnerability in auth handler", false, None)` → `AgentResponse("Vulnerability acknowledged but fix is incomplete, requesting rework", true, Some(ChangesRequested))`
- Author mock queue: `AgentResponse("Attempted partial fix for auth handler", false, None)`

**Assertions:**
- Outcome is `DialogueOutcome.ChangesRequested`
- Conversation has 3 messages
- `DialogueConcluded(ChangesRequested)` event published

### Test 3: Max turns reached

**Setup:**
- `maxTurns = 4`
- Both mock queues have enough non-concluding responses (2 each, all `concluded=false`)

**Assertions:**
- Outcome is `DialogueOutcome.MaxTurnsReached`
- Exactly 4 messages posted (2 per agent)
- Dialogue auto-concluded with `MaxTurnsReached`

### Stub dependencies (shared across tests)

| Dependency | Stub strategy |
|---|---|
| `ConversationRepository` | Ref-based, same as `AgentDialogueCoordinatorSpec` |
| `Hub[DialogueEvent]` | `Hub.unbounded[DialogueEvent]` |
| `LlmService` | Per-agent `Ref[List[AgentResponse]]` pop-queue |
| `AgentDialogueCoordinator` | Real `.live` implementation (this is integration-level) |
| `AgentDialogueRunner` | Real `.live` implementation, one per agent |

---

## Test Suite 2: `ProactiveAgentWorkflowSpec`

**File:** `src/test/scala/daemon/control/ProactiveAgentWorkflowSpec.scala`

Tests that daemon scheduler dispatches proactive agents correctly and produces expected board-level side effects. Uses the existing `DaemonAgentSchedulerSpec` stub infrastructure.

### Test 4: Planning agent creates recommendation issue

**Setup:**
- Seed workspace with 5 backlog issues via `MutableIssueRepository`
- Create `DaemonAgentSpec` with key `planning-agent`, targeting the seeded workspace
- Use existing `makeScheduler` pattern from `DaemonAgentSchedulerSpec`

**Execution:**
- Call `scheduler.runPlanningAgent(spec)` directly

**Assertions:**
- A maintenance issue is created with tag `daemon:planning-agent`
- Issue description contains the spec's purpose and prompt
- `DaemonRunOutcome.issuesCreated >= 1`

### Test 5: Triage agent creates categorization issue

**Setup:**
- Seed workspace with 1 uncategorized backlog issue
- Create `DaemonAgentSpec` with key `triage-agent`

**Execution:**
- Call `scheduler.runTriageAgent(spec)` directly

**Assertions:**
- A maintenance issue is created with tag `daemon:triage-agent`
- `DaemonRunOutcome.issuesCreated >= 1`

---

## File Layout

```
modules/conversation-domain/src/main/scala/conversation/
  entity/
    AgentResponse.scala                    # NEW — LLM response case class
  control/
    AgentDialogueRunner.scala              # NEW — trait only

src/main/scala/conversation/control/
  AgentDialogueRunnerLive.scala            # NEW — Live impl (needs LlmService)

src/test/scala/conversation/control/
  AgentDialogueRunnerSpec.scala            # NEW — Suite 1 (3 tests)

src/test/scala/daemon/control/
  ProactiveAgentWorkflowSpec.scala         # NEW — Suite 2 (2 tests)
```

### Build dependency changes

None. Root project already depends on all modules including `conversation-domain` and `llm4zio`. The trait lives in the module; the `Live` impl and tests live in root.

---

## Design Decisions

1. **Trait in module, Live in root** — follows the `orchestration-domain` pattern where trait interfaces live in the domain module and `*Live` implementations with richer dependencies live in root.

2. **Two runners per test, not one** — each agent in a dialogue gets its own `AgentDialogueRunner` instance with its own mock `LlmService`. This matches production where each agent process has its own LLM context.

3. **Real coordinator, mocked LLM** — the coordinator is the system under integration test. Mocking it would defeat the purpose. The LLM is the external boundary we mock.

4. **Root unit tests, not IT tests** — all dependencies are Ref-based stubs. No EclipseStore, no filesystem, no network. These run with `sbt test` for fast feedback.

5. **Pop-queue mock pattern** — proven pattern from `IntegrationFixtures.stubLlm`. Deterministic, sequential, easy to reason about.

6. **`AgentDialogueRunner` as production code** — not test-only. The proactive agents will need this loop to participate in dialogues. Testing it now validates the abstraction before the agents are fully implemented.
