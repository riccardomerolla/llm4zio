# Proactive Board Agents with A2A Conversation

**Date:** 2026-04-13
**Status:** Draft
**Inspired by:** [Paperclip](https://github.com/paperclipai/paperclip) — centralized agent orchestration with goal-aware coordination

## Problem

The board's issue lifecycle is currently reactive: humans move issues between columns, manually assign agents, and review changes themselves. There is no AI-assisted iteration planning, no automated code review dialogue, and no proactive issue triage. Agent-to-agent communication does not exist — agents work in isolation within their git worktrees.

## Goal

Add four proactive AI agent roles to the board, coordinated through a new A2A conversation infrastructure that allows agents to converse with each other (and with humans) in real-time via the issue timeline.

## Scope

Two deliverables, built in order:

1. **A2A Conversation Infrastructure** — extend the Conversation domain for agent-to-agent dialogue
2. **Four Proactive Agent Roles** — Planning, Review, Triage, Refactor — each as DaemonAgentSpecs

---

## Part 1: A2A Conversation Infrastructure

### 1.1 Conversation Domain Extensions

**File:** `modules/conversation-domain/src/main/scala/conversation/entity/Conversation.scala`

Extend `ChannelInfo` with an agent-to-agent variant:

```scala
enum ChannelInfo:
  case Telegram(channelName: String)
  case Web(sessionId: String)
  case Internal
  case AgentToAgent(issueId: BoardIssueId, participants: List[AgentParticipant])
```

New entity types:

```scala
case class AgentParticipant(
  agentName: String,
  role: AgentRole,
  joinedAt: Instant,
)

enum AgentRole:
  case Author       // The coding agent that worked the issue
  case Reviewer     // Review agent
  case Planner      // Planning agent
  case Triager      // Triage agent
  case Refactorer   // Refactor agent
  case Human        // Human participant who intervened
```

Extend `SenderType` if not already present:

```scala
enum SenderType:
  case User
  case Assistant
  case System
  case Agent        // New: agent participant in A2A
  case Unknown
```

### 1.2 AgentDialogueCoordinator

**New file:** `modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueCoordinator.scala`

Manages turn-taking in A2A conversations:

```scala
trait AgentDialogueCoordinator:
  /** Create a new A2A conversation and post the initiator's opening message. */
  def startDialogue(
    issueId: BoardIssueId,
    initiator: AgentParticipant,
    respondent: AgentParticipant,
    topic: String,
    openingMessage: String,
  ): IO[PersistenceError, ConversationId]

  /** Post a response in an ongoing dialogue, signal the next participant. */
  def respondInDialogue(
    conversationId: ConversationId,
    agentName: String,
    message: String,
  ): IO[PersistenceError, Unit]

  /** Human injects a message, pauses the current agent turn. */
  def humanIntervene(
    conversationId: ConversationId,
    userId: String,
    message: String,
  ): IO[PersistenceError, Unit]

  /** Conclude the dialogue with a structured outcome. */
  def concludeDialogue(
    conversationId: ConversationId,
    outcome: DialogueOutcome,
  ): IO[PersistenceError, Unit]

  /** Check whose turn it is in a dialogue. */
  def currentTurn(conversationId: ConversationId): IO[PersistenceError, TurnState]

  /** Wait for the next message addressed to this agent. */
  def awaitTurn(conversationId: ConversationId, agentName: String): IO[PersistenceError, Message]
```

**Internal state:**

```scala
case class TurnState(
  conversationId: ConversationId,
  currentParticipant: String,       // agentName or "human"
  turnNumber: Int,
  awaitingResponse: Boolean,
  pausedByHuman: Boolean,
)

enum DialogueOutcome:
  case Approved(summary: String)
  case ChangesRequested(comments: List[String])
  case Escalated(reason: String)
  case Completed(summary: String)   // Generic completion (for non-review dialogues)
```

**Implementation:** `AgentDialogueCoordinatorLive` uses:
- `ConversationRepository` for message persistence
- `Ref[Map[ConversationId, TurnState]]` for turn tracking
- `Hub[DialogueEvent]` for SSE push (see 1.3)
- `Promise[PersistenceError, Message]` per waiting agent for `awaitTurn`

### 1.3 DialogueEvent Bus

**Extend:** `orchestration/control/WorkReportEventBus.scala`

Add a fourth hub for dialogue events:

```scala
sealed trait DialogueEvent
object DialogueEvent:
  case class DialogueStarted(conversationId: ConversationId, issueId: BoardIssueId, participants: List[AgentParticipant]) extends DialogueEvent
  case class MessagePosted(conversationId: ConversationId, sender: String, senderRole: AgentRole, content: String, turnNumber: Int) extends DialogueEvent
  case class TurnChanged(conversationId: ConversationId, nextParticipant: String) extends DialogueEvent
  case class HumanIntervened(conversationId: ConversationId, userId: String) extends DialogueEvent
  case class DialogueConcluded(conversationId: ConversationId, outcome: DialogueOutcome) extends DialogueEvent
```

The `WorkReportEventBus` gains:
- `dialogueHub: Hub[DialogueEvent]`
- `publishDialogue(event: DialogueEvent): UIO[Unit]`
- `subscribeDialogue: URIO[Scope, Dequeue[DialogueEvent]]`

### 1.4 Timeline Integration

**Extend:** `board/entity/TimelineEntry.scala`

New timeline entry types:

```scala
sealed trait TimelineEntry:
  // ... existing entries ...
  case class A2ADialogueStarted(conversationId: ConversationId, participants: List[AgentParticipant], topic: String, occurredAt: Instant) extends TimelineEntry
  case class A2ADialogueConcluded(conversationId: ConversationId, outcome: DialogueOutcome, occurredAt: Instant) extends TimelineEntry
  case class PlanningRecommendation(recommendations: List[IssueRecommendation], occurredAt: Instant) extends TimelineEntry
  case class TriageCompleted(issueId: BoardIssueId, suggestedLabels: List[String], suggestedPriority: Priority, suggestedCapabilities: List[String], reasoning: String, occurredAt: Instant) extends TimelineEntry
```

### 1.5 IssueTimelineView A2A Panel

**Extend:** `modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala`

Add an embedded A2A conversation panel within the issue timeline:
- Shows messages from all participants (agents + humans) in chronological order
- Each message shows sender name, role badge (Author, Reviewer, Human), timestamp
- Human intervention input field (always visible when a dialogue is active)
- SSE subscription to `DialogueEvent` for live updates
- "Start AI Review" button (visible when issue is in Review column and no active dialogue)
- Dialogue outcome banner (Approved / Changes Requested / Escalated)

---

## Part 2: Proactive Agent Roles

### 2.1 Planning Agent

**Purpose:** Recommend which backlog issues to move to Todo for the next iteration.

**Registration:** `DaemonAgentSpec.PlanningAgentKey` with `DaemonTriggerCondition.Scheduled(24.hours)` (default daily, configurable via governance).

**Inputs:**
1. **Issue metadata:** All backlog issues — priority, estimate, tags, blockedBy, requiredCapabilities
2. **Code analysis:** For each candidate issue, scan affected codebase areas for complexity, file size, test coverage
3. **Team capacity:** `AgentPoolManager.availableSlots` per agent, active runs, capability matrix from `AgentMatching`
4. **Project context:** Governance policies, project goals (from `ProjectRepository`), past iteration velocity (from `ActivityHub` event history)

**Output:**

```scala
case class IssueRecommendation(
  issueId: BoardIssueId,
  title: String,
  rank: Int,
  score: Double,                    // 0.0–1.0 composite score
  reasoning: String,                // Why this issue was recommended
  estimatedAgentFit: String,        // Best-matching agent name
  factors: PlanningFactors,
)

case class PlanningFactors(
  priorityWeight: Double,
  capacityFit: Double,
  dependencyReadiness: Double,
  codeComplexity: Double,
  goalAlignment: Double,
)
```

**Board UI:**
- "Plan Iteration" button in board header → triggers `DaemonAgentScheduler.trigger(PlanningAgentKey)`
- Shows recommendation list as a modal/panel with accept/reject per issue
- Accepted issues are moved to Todo via `BoardRepository.moveIssue`
- Recommendations also posted as `TimelineEntry.PlanningRecommendation`

**Execution:**
1. Daemon coordinator triggers planning agent
2. Agent acquires pool slot via `AgentPoolManager`
3. Agent runs in workspace context (reads board, codebase, project config)
4. Agent produces JSON recommendation list
5. Result stored and displayed in board UI
6. Human reviews and acts on recommendations

### 2.2 Review Agent

**Purpose:** Conduct AI-powered code review as an A2A dialogue with the original coding agent.

**Registration:** No daemon spec needed for scheduling — triggered on-demand from UI. However, a `DaemonAgentSpec.ReviewAgentKey` is registered for pool management and governance integration.

**Trigger:** User clicks "Start AI Review" in issue timeline view (issue must be in Review column).

**Flow:**

```
Human clicks "Start AI Review"
  → System identifies the issue's coding agent (from TransientState.Assigned)
  → AgentDialogueCoordinator.startDialogue(issueId, reviewerParticipant, authorParticipant, topic, openingReview)
  → Review Agent reads:
      - git diff (worktree vs. base branch) via GitService
      - Full file context in worktree
      - Linked spec (from IssueContext.linkedSpecs)
      - Linked plan (from IssueContext.linkedPlans)
  → Review Agent posts initial review (code quality, spec compliance, bugs, suggestions)
  → If Review Agent has questions or change requests:
      → Message addressed to Author role
      → System re-invokes coding agent in the worktree to respond/apply fixes
      → Coding agent posts response + any new commits
      → Review Agent reads updated diff, continues review
  → Repeat until Review Agent concludes: Approved | ChangesRequested | Escalated
  → DialogueOutcome updates issue TransientState
  → Timeline shows full dialogue + outcome banner
```

**Author agent re-invocation:** When the Review Agent addresses the Author:
- If the original agent run is still active → route message to its stdin (for claude/gemini that support `InteractionSupport.InteractiveStdin`)
- If the run has ended → launch a new agent run in the same worktree via `WorkspaceRunService.continueRun` with the review feedback as the prompt
- If the agent cannot be re-invoked → escalate to human (the human becomes the Author participant)

**Review Agent execution context:**
- Runs in the **same worktree** as the coding agent (read-only access to the diff)
- Has access to `GitService`, `BoardRepository`, spec/plan context
- Does NOT modify files — only posts review comments
- Can run tests (`sbt test` in worktree) to validate changes

### 2.3 Triage Agent

**Purpose:** Auto-categorize, label, and estimate new issues arriving in Backlog.

**Registration:** `DaemonAgentSpec.TriageAgentKey` with `DaemonTriggerCondition.EventDriven("issue.created")`.

**Flow:**

```
New issue created in Backlog
  → Event published to WorkReportEventBus (IssueEvent.IssueCreated)
  → DaemonAgentScheduler picks up event, triggers Triage Agent
  → Agent reads issue body + title
  → Agent scans related codebase areas (keyword matching, file path references)
  → Agent determines:
      - Labels (feature, bugfix, refactoring, docs, etc.)
      - Estimated effort (t-shirt: XS, S, M, L, XL → mapped to numeric estimate)
      - Priority recommendation (Critical, High, Medium, Low)
      - Required capabilities (languages, frameworks, tools needed)
  → Agent updates IssueFrontmatter with suggestions:
      - Prefixed with "[AI]" to indicate AI-suggested values
      - Human can override via normal issue editing
  → Posts TimelineEntry.TriageCompleted with reasoning
  → Publishes ActivityEvent.IssueTriage
```

**Idempotency:** Triage runs once per issue. If re-triggered, checks if triage already completed (via tag `ai-triaged` on issue) and skips.

### 2.4 Refactor Agent

**Purpose:** Proactively identify refactoring opportunities and create issues.

**Registration:** `DaemonAgentSpec.RefactorAgentKey` with `DaemonTriggerCondition.Scheduled(7.days)` (weekly, configurable).

**Relationship to existing DebtDetector:** The Refactor Agent supersedes the existing `DebtDetector` daemon by providing deeper analysis. DebtDetector scans for TODO/FIXME markers; the Refactor Agent additionally analyzes:
- Code duplication (similar code blocks across files)
- Large files exceeding complexity thresholds
- High cyclomatic complexity methods
- Unused imports/dead code patterns
- BCE layer violations (service logic in boundary, etc.)
- Missing test coverage for critical paths

**Flow:**

```
Weekly schedule fires
  → Agent acquires pool slot
  → Agent scans workspace codebase with structured analysis
  → Groups findings into coherent refactoring proposals
  → For each proposal:
      → Generates fingerprint (hash of affected files + finding type)
      → Checks existing issues for same fingerprint (idempotent via ensureMaintenanceIssue pattern)
      → Creates issue in Backlog if new:
          - Title: descriptive refactoring summary
          - Tags: [refactoring, ai-generated]
          - Required capabilities derived from affected file types
          - Body: detailed analysis + suggested approach
      → Posts ActivityEvent.RefactoringProposed
  → Publishes summary as ActivityEvent.RefactorScanCompleted
```

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                        Board UI (Scalatags + HTMX)              │
│  ┌──────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ Plan      │  │ Start AI     │  │ Triage       │              │
│  │ Iteration │  │ Review       │  │ (automatic)  │              │
│  │ [button]  │  │ [button]     │  │              │              │
│  └─────┬────┘  └──────┬───────┘  └──────────────┘              │
│        │               │                                         │
│  ┌─────┴───────────────┴────────────────────────────────┐       │
│  │         Issue Timeline View + A2A Panel               │       │
│  │  (SSE subscription to DialogueEvent for live updates) │       │
│  └───────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────┘
        │                    │
        ▼                    ▼
┌───────────────┐  ┌────────────────────────┐
│ DaemonAgent   │  │ AgentDialogue          │
│ Scheduler     │  │ Coordinator            │
│               │  │                        │
│ Planning ─────┼──┤ startDialogue()        │
│ Review   ─────┼──┤ respondInDialogue()    │
│ Triage   ─────┤  │ humanIntervene()       │
│ Refactor ─────┤  │ concludeDialogue()     │
└───────┬───────┘  └────────┬───────────────┘
        │                    │
        ▼                    ▼
┌───────────────┐  ┌────────────────────────┐
│ AgentPool     │  │ Conversation           │
│ Manager       │  │ Repository             │
│ (slot mgmt)   │  │ (EventStore-backed)    │
└───────────────┘  └────────────────────────┘
        │                    │
        ▼                    ▼
┌───────────────┐  ┌────────────────────────┐
│ Workspace     │  │ WorkReportEventBus     │
│ RunService    │  │ + dialogueHub          │
│ (worktrees)   │  │ (SSE push)             │
└───────────────┘  └────────────────────────┘
```

---

## Module Changes

| Module | Changes |
|--------|---------|
| `conversation-domain` | Add `AgentParticipant`, `AgentRole`, `DialogueOutcome`, `TurnState` to entity. Add `AgentDialogueCoordinator` to control. |
| `board-domain` | Add `TimelineEntry` variants (A2ADialogueStarted, A2ADialogueConcluded, PlanningRecommendation, TriageCompleted). Extend `IssueTimelineView` with A2A panel. Add "Plan Iteration" and "Start AI Review" buttons. |
| `orchestration-domain` | Add `DialogueEvent` to entity. Extend `WorkReportEventBus` with `dialogueHub`. |
| `daemon-domain` | Register 4 new `DaemonAgentSpec` keys (PlanningAgent, ReviewAgent, TriageAgent, RefactorAgent). Extend `DaemonAgentScheduler` execution to dispatch to new agent handlers. |
| `workspace-domain` | No structural changes — existing `WorkspaceRunService.continueRun` supports Review Agent re-invoking the coding agent. |
| `agent-domain` | No structural changes — existing `AgentMatching` supports Planning Agent's capacity analysis. |
| Root `src/main/scala/` | New daemon execution handlers for each agent role. SSE endpoint extensions for dialogue events. |

## Build Sequence

1. **A2A Infrastructure** (conversation-domain + orchestration-domain changes)
2. **Board domain timeline extensions** (new TimelineEntry variants)
3. **Triage Agent** (simplest — event-driven, no A2A dialogue)
4. **Refactor Agent** (scheduled, no A2A, extends DebtDetector pattern)
5. **Planning Agent** (scheduled, board UI integration, recommendation panel)
6. **Review Agent** (most complex — full A2A dialogue, agent re-invocation)

## Testing Strategy

- **Unit tests** for `AgentDialogueCoordinator`: turn-taking, human intervention, conclusion — using in-memory stubs
- **Unit tests** for each agent's analysis logic (isolated from daemon scheduling)
- **Integration tests** for end-to-end flows: issue creation → triage, backlog → planning recommendation, review dialogue lifecycle
- **SSE tests**: verify DialogueEvent delivery to timeline view subscribers

## Out of Scope

- Agent personality / system prompt customization per role (can be added later via Agent entity)
- Multi-agent dialogues with >2 participants (A2A is 1:1 for now, with human intervention as a third party)
- Autonomous approval (Review Agent can recommend approval but human must confirm)
- Cross-workspace agent coordination (agents operate within a single workspace)
