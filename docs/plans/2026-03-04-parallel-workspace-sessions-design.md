# Parallel Workspace Sessions Design

**Date:** 2026-03-04
**Status:** Approved
**Inspiration:** [Parallel Workspaces for the Age of AI Coding Agents](https://www.ziverge.com/post/parallel-workspaces-for-the-age-of-ai-coding-agents)

## Problem

The gateway can dispatch individual agent runs on workspaces, but lacks a first-class concept for running multiple agents in parallel on isolated worktrees from a single workflow, collecting results, and notifying the user for review. Developers currently orchestrate parallel work manually.

## Approach

Introduce `ParallelWorkspaceSession` as a first-class entity with a `ParallelSessionCoordinator` service that composes existing building blocks (`WorkflowEngine`, `WorkspaceRunService`, `OrchestratorControlPlane`, `AgentRegistry`, `MessageRouter`) without modifying their interfaces.

## Entities & Models

### ParallelWorkspaceSession

```scala
final case class ParallelWorkspaceSession(
  id: String,
  workflowId: String,
  correlationId: String,
  status: ParallelSessionStatus,
  baseBranch: String,
  worktreeRuns: List[WorktreeRunRef],
  createdAt: Instant,
  updatedAt: Instant,
  completedAt: Option[Instant],
  requestedBy: Option[String],
) derives JsonCodec, Schema
```

### WorktreeRunRef

```scala
final case class WorktreeRunRef(
  stepId: String,
  workspaceRunId: String,
  agentName: String,
  branch: String,
  status: WorktreeRunStatus,
  summary: Option[String],
  diffStats: Option[DiffStats],
) derives JsonCodec, Schema
```

### DiffStats

```scala
final case class DiffStats(
  filesChanged: Int,
  linesAdded: Int,
  linesRemoved: Int,
) derives JsonCodec, Schema
```

### ParallelSessionStatus

```
Pending → Running → Collecting → ReadyForReview → Completed
                                                 → Failed
                                                 → Cancelled
```

- **Collecting**: all agents done, gathering diff stats and summaries
- **ReadyForReview**: terminal happy state, human takes over

### WorktreeRunStatus

```
Pending → Running → Completed | Failed | Cancelled
```

### ParallelSessionError

```scala
sealed trait ParallelSessionError derives JsonCodec
object ParallelSessionError:
  case class WorkflowNotFound(workflowId: String)
  case class WorkspaceNotFound(workspaceId: String)
  case class SessionNotFound(sessionId: String)
  case class InsufficientResources(available: Int, required: Int)
  case class AgentAssignmentFailed(stepId: String, reason: String)
  case class WorktreeError(detail: String)
```

## ParallelSessionCoordinator Service

### Interface

```scala
trait ParallelSessionCoordinator:
  def launch(
    workflowId: String,
    workspaceId: String,
    baseBranch: String,
    requestedBy: Option[String],
  ): IO[ParallelSessionError, ParallelWorkspaceSession]

  def status(sessionId: String): IO[ParallelSessionError, ParallelWorkspaceSession]
  def cancel(sessionId: String): IO[ParallelSessionError, Unit]
  def collectResults(sessionId: String): IO[ParallelSessionError, ParallelWorkspaceSession]
  def cleanup(sessionId: String): IO[ParallelSessionError, Unit]
```

### Launch Flow

1. `WorkflowEngine.compile(definition)` → `List[List[WorkflowStepPlan]]` (batched parallel steps)
2. For the first parallel batch, `ZIO.foreachPar(batch)`:
   - `AgentRegistry.findAgentsForStep(step)` → pick best agent
   - `WorkspaceRunService.assign(workspaceId, request)` → creates worktree + branch
   - Return `WorktreeRunRef(stepId, runId, agent, branch, Pending)`
3. Create `ParallelWorkspaceSession(Running, worktreeRuns)`
4. `OrchestratorControlPlane.startWorkflow(...)` → allocate resource slots
5. Fork background fiber: subscribe to ControlPlane events by correlationId, update session state, transition to `ReadyForReview` when all steps complete

### State Management

In-memory `Ref[Map[String, ParallelWorkspaceSession]]` — same pattern as `ActiveRun` in `OrchestratorControlPlane`.

## Event & Notification Flow

### ParallelSessionEvent

```scala
sealed trait ParallelSessionEvent derives JsonCodec:
  def sessionId: String
  def occurredAt: Instant

object ParallelSessionEvent:
  case class SessionStarted(sessionId, workflowId, worktreeCount, occurredAt)
  case class WorktreeAgentStarted(sessionId, stepId, agentName, branch, occurredAt)
  case class WorktreeAgentProgress(sessionId, stepId, agentName, message, occurredAt)
  case class WorktreeAgentCompleted(sessionId, stepId, agentName, branch, diffStats, summary, occurredAt)
  case class WorktreeAgentFailed(sessionId, stepId, agentName, reason, occurredAt)
  case class SessionReadyForReview(sessionId, succeeded, failed, branches, occurredAt)
```

### Notification to User Channels

Events flow through existing infrastructure:

```
ParallelSessionCoordinator (background fiber)
  → OrchestratorControlPlane.publish(event)
    → attachMessageRouterMiddleware (existing)
      → MessageRouter.routeOutbound
        → User's channel (Telegram/Discord/WebSocket)
```

`ParallelSessionFormatter` converts events to channel-appropriate `NormalizedMessage`:

- **SessionStarted** → "Parallel session started: 3 agents dispatched" + branch list
- **WorktreeAgentCompleted** → "Agent 'test-writer' completed: +142/-12 across 8 files"
- **SessionReadyForReview** → "All agents done — 3 succeeded, 0 failed" + branch list

## Integration with Existing Services

### ZLayer Dependencies

```scala
val live: ZLayer[
  WorkflowEngine &
  WorkspaceRunService &
  OrchestratorControlPlane &
  AgentRegistry &
  MessageRouter,
  Nothing,
  ParallelSessionCoordinator,
]
```

### Touch Points (all additive, no interface changes)

| Existing Service | Usage | Changes |
|---|---|---|
| WorkflowEngine | `.compile()` for batched steps | None |
| WorkspaceRunService | `.assign()` per parallel step | None |
| OrchestratorControlPlane | `.startWorkflow()`, `.subscribe()`, `.publish()` | Add ParallelSessionEvent publishing |
| AgentRegistry | `.findAgentsForStep()` | None |
| MessageRouter | `.routeOutbound()` | Add ParallelSessionEvent formatter |

### New Files

```
src/main/scala/orchestration/
  entity/
    ParallelWorkspaceSession.scala    # entities, status enums, errors
  control/
    ParallelSessionCoordinator.scala  # trait + live implementation
    ParallelSessionEvents.scala       # event sealed trait
    ParallelSessionFormatter.scala    # events → NormalizedMessage
```

## Out of Scope (Future Extensions)

- HTTP/MCP endpoints to launch/status/cancel sessions externally
- Persistent session storage for crash recovery
- Multi-batch sequential execution (batch 1 completes → batch 2 starts)
- Automated merge with conflict detection
- Agent-assisted review of parallel outputs
