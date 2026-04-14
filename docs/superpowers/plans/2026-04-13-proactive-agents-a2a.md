# Proactive Board Agents with A2A Conversation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add four proactive AI agent roles (Planning, Review, Triage, Refactor) to the board, coordinated through a new A2A conversation infrastructure with real-time human observation and intervention.

**Architecture:** Extend the existing `DaemonAgentScheduler` for agent lifecycle and the `Conversation` domain for A2A dialogue. A new `AgentDialogueCoordinator` manages turn-taking between agents via `Ref`/`Promise`. Dialogue events flow through the existing `WorkReportEventBus` pattern (ZIO `Hub`) to an SSE endpoint for live timeline updates. Each proactive agent is a `DaemonAgentSpec` with its own trigger condition.

**Tech Stack:** Scala 3, ZIO 2.x, zio-json, zio-schema, Scalatags, HTMX, Lit 3 web components, EventStore (EclipseStore)

**Spec:** `docs/superpowers/specs/2026-04-13-proactive-agents-a2a-design.md`

---

## File Structure

### New files

| File | Responsibility |
|------|---------------|
| `modules/conversation-domain/src/main/scala/conversation/entity/AgentDialogue.scala` | `AgentParticipant`, `AgentRole`, `DialogueOutcome`, `TurnState` entity types |
| `modules/conversation-domain/src/main/scala/conversation/entity/DialogueEvent.scala` | `DialogueEvent` sealed trait for SSE bus |
| `modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueCoordinator.scala` | Trait + Live impl for A2A turn-taking |
| `modules/conversation-domain/src/test/scala/conversation/control/AgentDialogueCoordinatorSpec.scala` | Unit tests for coordinator |
| `src/main/resources/static/client/components/ab-a2a-panel.js` | Lit 3 web component for A2A conversation panel |

### Modified files

| File | Changes |
|------|---------|
| `modules/conversation-domain/src/main/scala/conversation/entity/Conversation.scala` | Add `ChannelInfo.AgentToAgent` variant, `SenderType.Agent` case |
| `modules/orchestration-domain/src/main/scala/orchestration/control/WorkReportEventBus.scala` | Add `dialogueHub: Hub[DialogueEvent]` + publish/subscribe methods |
| `modules/board-domain/src/main/scala/board/entity/TimelineEntry.scala` | Add `A2ADialogueStarted`, `A2ADialogueConcluded`, `PlanningRecommendation`, `TriageCompleted` |
| `modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala` | Add A2A panel rendering, "Start AI Review" button, planning recommendation rendering |
| `modules/daemon-domain/src/main/scala/daemon/entity/DaemonAgentSpec.scala` | Add 4 new daemon keys |
| `build.sbt` | Add `boardDomain` dependency to `conversationDomain` for `BoardIssueId` in `AgentDialogue` |

---

## Task 1: Conversation Domain — A2A Entity Types

**Files:**
- Create: `modules/conversation-domain/src/main/scala/conversation/entity/AgentDialogue.scala`
- Modify: `modules/conversation-domain/src/main/scala/conversation/entity/Conversation.scala`
- Modify: `build.sbt` (add `boardDomain` dep on `conversationDomain` — but we need `BoardIssueId` from `sharedIds`, which conversation-domain already depends on, so no build.sbt change needed here)

- [ ] **Step 1: Create the A2A entity types file**

Create `modules/conversation-domain/src/main/scala/conversation/entity/AgentDialogue.scala`:

```scala
package conversation.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ BoardIssueId, ConversationId }

final case class AgentParticipant(
  agentName: String,
  role: AgentRole,
  joinedAt: Instant,
) derives JsonCodec, Schema

enum AgentRole derives JsonCodec, Schema:
  case Author
  case Reviewer
  case Planner
  case Triager
  case Refactorer
  case Human

enum DialogueOutcome derives JsonCodec, Schema:
  case Approved(summary: String)
  case ChangesRequested(comments: List[String])
  case Escalated(reason: String)
  case Completed(summary: String)

final case class TurnState(
  conversationId: ConversationId,
  currentParticipant: String,
  turnNumber: Int,
  awaitingResponse: Boolean,
  pausedByHuman: Boolean,
) derives JsonCodec, Schema
```

- [ ] **Step 2: Add `ChannelInfo.AgentToAgent` and `SenderType.Agent`**

In `modules/conversation-domain/src/main/scala/conversation/entity/Conversation.scala`, add the new `ChannelInfo` variant (line 17, after `case Internal`):

```scala
enum ChannelInfo derives JsonCodec, Schema:
  case Telegram(channelName: String)
  case Web(sessionId: String)
  case Internal
  case AgentToAgent(issueId: BoardIssueId, participants: List[AgentParticipant])
```

Add the new `SenderType.Agent` case (after line 23, after `System()`):

```scala
sealed trait SenderType derives JsonCodec, Schema
object SenderType:
  final case class User()               extends SenderType
  final case class Assistant()          extends SenderType
  final case class System()             extends SenderType
  final case class Agent(role: AgentRole) extends SenderType
  final case class Unknown(raw: String) extends SenderType
```

Add import for `BoardIssueId` to the imports block (line 8):

```scala
import shared.ids.Ids.{ BoardIssueId, ConversationId, MessageId, ProjectId, TaskRunId }
```

- [ ] **Step 3: Verify compile**

Run: `sbt conversationDomain/compile`
Expected: BUILD SUCCESSFUL — `BoardIssueId` is from `sharedIds` which `conversationDomain` already depends on.

- [ ] **Step 4: Commit**

```bash
git add modules/conversation-domain/src/main/scala/conversation/entity/AgentDialogue.scala \
       modules/conversation-domain/src/main/scala/conversation/entity/Conversation.scala
git commit -m "feat(conversation): add A2A entity types — AgentParticipant, AgentRole, DialogueOutcome, TurnState, ChannelInfo.AgentToAgent, SenderType.Agent"
```

---

## Task 2: DialogueEvent Sealed Trait

**Files:**
- Create: `modules/conversation-domain/src/main/scala/conversation/entity/DialogueEvent.scala`

- [ ] **Step 1: Create the DialogueEvent sealed trait**

Create `modules/conversation-domain/src/main/scala/conversation/entity/DialogueEvent.scala`:

```scala
package conversation.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ BoardIssueId, ConversationId }

sealed trait DialogueEvent derives JsonCodec, Schema:
  def conversationId: ConversationId
  def occurredAt: Instant

object DialogueEvent:
  final case class DialogueStarted(
    conversationId: ConversationId,
    issueId: BoardIssueId,
    participants: List[AgentParticipant],
    topic: String,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema

  final case class MessagePosted(
    conversationId: ConversationId,
    sender: String,
    senderRole: AgentRole,
    content: String,
    turnNumber: Int,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema

  final case class TurnChanged(
    conversationId: ConversationId,
    nextParticipant: String,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema

  final case class HumanIntervened(
    conversationId: ConversationId,
    userId: String,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema

  final case class DialogueConcluded(
    conversationId: ConversationId,
    outcome: DialogueOutcome,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema
```

- [ ] **Step 2: Verify compile**

Run: `sbt conversationDomain/compile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add modules/conversation-domain/src/main/scala/conversation/entity/DialogueEvent.scala
git commit -m "feat(conversation): add DialogueEvent sealed trait for A2A bus"
```

---

## Task 3: Extend WorkReportEventBus with Dialogue Hub

**Files:**
- Modify: `modules/orchestration-domain/src/main/scala/orchestration/control/WorkReportEventBus.scala`
- Modify: `build.sbt` (orchestration-domain needs conversationDomain dep for DialogueEvent)

- [ ] **Step 1: Add conversationDomain dependency to orchestrationDomain in build.sbt**

In `build.sbt`, find the `orchestrationDomain` definition (line 359) and add `conversationDomain` to `dependsOn`:

```scala
lazy val orchestrationDomain = (project in file("modules/orchestration-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, gatewayDomain, configDomain, planDomain,
    activityDomain, issuesDomain, taskrunDomain, workspaceDomain, sharedServices, conversationDomain)
```

- [ ] **Step 2: Add dialogueHub to WorkReportEventBus**

In `modules/orchestration-domain/src/main/scala/orchestration/control/WorkReportEventBus.scala`, add the import and fourth hub:

```scala
package orchestration.control

import zio.*
import conversation.entity.DialogueEvent
import issues.entity.IssueEvent
import taskrun.entity.TaskRunEvent

final class WorkReportEventBus(
  taskRunHub: Hub[TaskRunEvent],
  issueHub: Hub[IssueEvent],
  parallelSessionHub: Hub[ParallelSessionEvent],
  dialogueHub: Hub[DialogueEvent],
):
  def publishTaskRun(event: TaskRunEvent): UIO[Unit]                 = taskRunHub.publish(event).unit
  def publishIssue(event: IssueEvent): UIO[Unit]                     = issueHub.publish(event).unit
  def publishParallelSession(event: ParallelSessionEvent): UIO[Unit] =
    parallelSessionHub.publish(event).unit
  def publishDialogue(event: DialogueEvent): UIO[Unit]               = dialogueHub.publish(event).unit

  def subscribeTaskRun: URIO[Scope, Dequeue[TaskRunEvent]]                 = taskRunHub.subscribe
  def subscribeIssue: URIO[Scope, Dequeue[IssueEvent]]                     = issueHub.subscribe
  def subscribeParallelSession: URIO[Scope, Dequeue[ParallelSessionEvent]] = parallelSessionHub.subscribe
  def subscribeDialogue: URIO[Scope, Dequeue[DialogueEvent]]               = dialogueHub.subscribe

object WorkReportEventBus:

  def make: UIO[WorkReportEventBus] =
    for
      taskRunHub         <- Hub.unbounded[TaskRunEvent]
      issueHub           <- Hub.unbounded[IssueEvent]
      parallelSessionHub <- Hub.unbounded[ParallelSessionEvent]
      dialogueHub        <- Hub.unbounded[DialogueEvent]
    yield WorkReportEventBus(taskRunHub, issueHub, parallelSessionHub, dialogueHub)

  val layer: ULayer[WorkReportEventBus] = ZLayer.fromZIO(make)
```

- [ ] **Step 3: Verify compile**

Run: `sbt orchestrationDomain/compile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add build.sbt \
       modules/orchestration-domain/src/main/scala/orchestration/control/WorkReportEventBus.scala
git commit -m "feat(orchestration): add dialogueHub to WorkReportEventBus for A2A events"
```

---

## Task 4: AgentDialogueCoordinator — Failing Tests

**Files:**
- Create: `modules/conversation-domain/src/test/scala/conversation/control/AgentDialogueCoordinatorSpec.scala`

- [ ] **Step 1: Write the failing test suite**

Create `modules/conversation-domain/src/test/scala/conversation/control/AgentDialogueCoordinatorSpec.scala`:

```scala
package conversation.control

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import conversation.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, ConversationId, MessageId }

object AgentDialogueCoordinatorSpec extends ZIOSpecDefault:

  private val reviewer = AgentParticipant("review-agent", AgentRole.Reviewer, Instant.now)
  private val author   = AgentParticipant("code-agent", AgentRole.Author, Instant.now)
  private val issueId  = BoardIssueId("issue-1")

  def spec = suite("AgentDialogueCoordinator")(
    test("startDialogue creates a conversation and returns its id") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Code review", "LGTM overall but found 2 issues")
        turn        <- coordinator.currentTurn(convId)
      yield assertTrue(
        convId.value.nonEmpty,
        turn.currentParticipant == "code-agent",
        turn.turnNumber == 1,
        turn.awaitingResponse,
        !turn.pausedByHuman,
      )
    },
    test("respondInDialogue advances the turn to the other participant") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "Please fix the null check")
        _           <- coordinator.respondInDialogue(convId, "code-agent", "Fixed in commit abc123")
        turn        <- coordinator.currentTurn(convId)
      yield assertTrue(
        turn.currentParticipant == "review-agent",
        turn.turnNumber == 2,
      )
    },
    test("humanIntervene pauses the dialogue") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "Checking changes")
        _           <- coordinator.humanIntervene(convId, "riccardo", "Hold on, let me look at this first")
        turn        <- coordinator.currentTurn(convId)
      yield assertTrue(
        turn.pausedByHuman,
      )
    },
    test("concludeDialogue closes the conversation with outcome") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "All good")
        _           <- coordinator.concludeDialogue(convId, DialogueOutcome.Approved("No issues found"))
        repo        <- ZIO.service[ConversationRepository]
        conv        <- repo.get(convId)
      yield assertTrue(
        conv.state.isInstanceOf[ConversationState.Closed],
      )
    },
    test("awaitTurn resolves when the other agent responds") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "Please explain line 42")
        fiber       <- coordinator.awaitTurn(convId, "review-agent").fork
        _           <- coordinator.respondInDialogue(convId, "code-agent", "Line 42 handles the edge case")
        message     <- fiber.join
      yield assertTrue(
        message.content == "Line 42 handles the edge case",
        message.sender == "code-agent",
      )
    },
  ).provide(
    AgentDialogueCoordinator.live,
    stubConversationRepositoryLayer,
    stubDialogueEventBusLayer,
  )

  // ── Stubs ──────────────────────────────────────────────────────────

  private val stubConversationRepositoryLayer: ULayer[ConversationRepository] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[ConversationId, List[ConversationEvent]]).map { store =>
        new ConversationRepository:
          def append(event: ConversationEvent): IO[PersistenceError, Unit] =
            store.update { m =>
              val existing = m.getOrElse(event.conversationId, Nil)
              m.updated(event.conversationId, existing :+ event)
            }

          def get(id: ConversationId): IO[PersistenceError, Conversation] =
            store.get.flatMap { m =>
              m.get(id) match
                case Some(events) =>
                  Conversation.fromEvents(events) match
                    case Right(conv) => ZIO.succeed(conv)
                    case Left(err)   => ZIO.fail(PersistenceError.StorageError(err, None))
                case None         =>
                  ZIO.fail(PersistenceError.NotFound("Conversation", id.value))
            }

          def list(filter: ConversationFilter): IO[PersistenceError, List[Conversation]] =
            store.get.map { m =>
              m.values.toList.flatMap(events => Conversation.fromEvents(events).toOption)
            }
      }
    )

  /** A stub that captures published dialogue events into a Ref for assertions. */
  private val stubDialogueEventBusLayer: ULayer[Hub[DialogueEvent]] =
    ZLayer.fromZIO(Hub.unbounded[DialogueEvent])
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbt 'conversationDomain/testOnly conversation.control.AgentDialogueCoordinatorSpec'`
Expected: FAIL — `AgentDialogueCoordinator` trait and `AgentDialogueCoordinator.live` do not exist yet.

- [ ] **Step 3: Commit the failing tests**

```bash
git add modules/conversation-domain/src/test/scala/conversation/control/AgentDialogueCoordinatorSpec.scala
git commit -m "test(conversation): add failing tests for AgentDialogueCoordinator"
```

---

## Task 5: AgentDialogueCoordinator — Implementation

**Files:**
- Create: `modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueCoordinator.scala`

- [ ] **Step 1: Create the trait and Live implementation**

Create `modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueCoordinator.scala`:

```scala
package conversation.control

import java.time.Instant
import java.util.UUID

import zio.*

import conversation.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, ConversationId, MessageId }

trait AgentDialogueCoordinator:
  def startDialogue(
    issueId: BoardIssueId,
    initiator: AgentParticipant,
    respondent: AgentParticipant,
    topic: String,
    openingMessage: String,
  ): IO[PersistenceError, ConversationId]

  def respondInDialogue(
    conversationId: ConversationId,
    agentName: String,
    message: String,
  ): IO[PersistenceError, Unit]

  def humanIntervene(
    conversationId: ConversationId,
    userId: String,
    message: String,
  ): IO[PersistenceError, Unit]

  def concludeDialogue(
    conversationId: ConversationId,
    outcome: DialogueOutcome,
  ): IO[PersistenceError, Unit]

  def currentTurn(conversationId: ConversationId): IO[PersistenceError, TurnState]

  def awaitTurn(conversationId: ConversationId, agentName: String): IO[PersistenceError, Message]

object AgentDialogueCoordinator:
  val live: ZLayer[ConversationRepository & Hub[DialogueEvent], Nothing, AgentDialogueCoordinator] =
    ZLayer {
      for
        repo     <- ZIO.service[ConversationRepository]
        hub      <- ZIO.service[Hub[DialogueEvent]]
        turns    <- Ref.make(Map.empty[ConversationId, TurnState])
        promises <- Ref.make(Map.empty[(ConversationId, String), Promise[PersistenceError, Message]])
      yield AgentDialogueCoordinatorLive(repo, hub, turns, promises)
    }

final case class AgentDialogueCoordinatorLive(
  repo: ConversationRepository,
  dialogueHub: Hub[DialogueEvent],
  turns: Ref[Map[ConversationId, TurnState]],
  promises: Ref[Map[(ConversationId, String), Promise[PersistenceError, Message]]],
) extends AgentDialogueCoordinator:

  def startDialogue(
    issueId: BoardIssueId,
    initiator: AgentParticipant,
    respondent: AgentParticipant,
    topic: String,
    openingMessage: String,
  ): IO[PersistenceError, ConversationId] =
    val convId = ConversationId(UUID.randomUUID().toString)
    val now    = Instant.now
    val msgId  = MessageId(UUID.randomUUID().toString)
    for
      _ <- repo.append(
        ConversationEvent.Created(
          conversationId = convId,
          channel = ChannelInfo.AgentToAgent(issueId, List(initiator, respondent)),
          title = topic,
          description = s"A2A dialogue: ${initiator.agentName} (${initiator.role}) ↔ ${respondent.agentName} (${respondent.role})",
          runId = None,
          createdBy = Some(initiator.agentName),
          occurredAt = now,
        )
      )
      msg = Message(
        id = msgId,
        sender = initiator.agentName,
        senderType = SenderType.Agent(initiator.role),
        content = openingMessage,
        messageType = MessageType.Text(),
        createdAt = now,
      )
      _ <- repo.append(ConversationEvent.MessageSent(convId, msg, now))
      turnState = TurnState(
        conversationId = convId,
        currentParticipant = respondent.agentName,
        turnNumber = 1,
        awaitingResponse = true,
        pausedByHuman = false,
      )
      _ <- turns.update(_.updated(convId, turnState))
      _ <- dialogueHub.publish(
        DialogueEvent.DialogueStarted(convId, issueId, List(initiator, respondent), topic, now)
      ).ignore
      _ <- dialogueHub.publish(
        DialogueEvent.MessagePosted(convId, initiator.agentName, initiator.role, openingMessage, 0, now)
      ).ignore
    yield convId

  def respondInDialogue(
    conversationId: ConversationId,
    agentName: String,
    message: String,
  ): IO[PersistenceError, Unit] =
    val now   = Instant.now
    val msgId = MessageId(UUID.randomUUID().toString)
    for
      state <- getTurnOrFail(conversationId)
      msg = Message(
        id = msgId,
        sender = agentName,
        senderType = SenderType.Agent(AgentRole.Author), // role resolved from conversation participants
        content = message,
        messageType = MessageType.Text(),
        createdAt = now,
      )
      _        <- repo.append(ConversationEvent.MessageSent(conversationId, msg, now))
      conv     <- repo.get(conversationId)
      nextName  = resolveOtherParticipant(conv, agentName)
      nextTurn  = state.copy(
        currentParticipant = nextName,
        turnNumber = state.turnNumber + 1,
        awaitingResponse = true,
        pausedByHuman = false,
      )
      _        <- turns.update(_.updated(conversationId, nextTurn))
      _        <- dialogueHub.publish(
        DialogueEvent.MessagePosted(conversationId, agentName, AgentRole.Author, message, state.turnNumber, now)
      ).ignore
      _        <- dialogueHub.publish(
        DialogueEvent.TurnChanged(conversationId, nextName, now)
      ).ignore
      // Fulfill any promise waiting for this agent's response
      _        <- fulfillPromise(conversationId, agentName, msg)
    yield ()

  def humanIntervene(
    conversationId: ConversationId,
    userId: String,
    message: String,
  ): IO[PersistenceError, Unit] =
    val now   = Instant.now
    val msgId = MessageId(UUID.randomUUID().toString)
    val msg = Message(
      id = msgId,
      sender = userId,
      senderType = SenderType.User(),
      content = message,
      messageType = MessageType.Text(),
      createdAt = now,
    )
    for
      _ <- repo.append(ConversationEvent.MessageSent(conversationId, msg, now))
      _ <- turns.update(_.updatedWith(conversationId)(_.map(_.copy(pausedByHuman = true))))
      _ <- dialogueHub.publish(DialogueEvent.HumanIntervened(conversationId, userId, now)).ignore
    yield ()

  def concludeDialogue(
    conversationId: ConversationId,
    outcome: DialogueOutcome,
  ): IO[PersistenceError, Unit] =
    val now = Instant.now
    for
      _ <- repo.append(ConversationEvent.Closed(conversationId, now, now))
      _ <- turns.update(_ - conversationId)
      _ <- dialogueHub.publish(DialogueEvent.DialogueConcluded(conversationId, outcome, now)).ignore
    yield ()

  def currentTurn(conversationId: ConversationId): IO[PersistenceError, TurnState] =
    getTurnOrFail(conversationId)

  def awaitTurn(conversationId: ConversationId, agentName: String): IO[PersistenceError, Message] =
    for
      promise <- Promise.make[PersistenceError, Message]
      _       <- promises.update(_.updated((conversationId, agentName), promise))
      msg     <- promise.await
    yield msg

  // ── Internal helpers ────────────────────────────────────────────────

  private def getTurnOrFail(conversationId: ConversationId): IO[PersistenceError, TurnState] =
    turns.get.flatMap { m =>
      m.get(conversationId) match
        case Some(state) => ZIO.succeed(state)
        case None        => ZIO.fail(PersistenceError.NotFound("TurnState", conversationId.value))
    }

  private def resolveOtherParticipant(conv: Conversation, currentAgent: String): String =
    conv.channel match
      case ChannelInfo.AgentToAgent(_, participants) =>
        participants.find(_.agentName != currentAgent).map(_.agentName).getOrElse(currentAgent)
      case _ => currentAgent

  private def fulfillPromise(conversationId: ConversationId, senderName: String, msg: Message): UIO[Unit] =
    promises.get.flatMap { m =>
      // Fulfill promises for agents waiting for a message from senderName
      val waiting = m.collect {
        case ((cid, waiter), p) if cid == conversationId && waiter != senderName => (cid, waiter, p)
      }
      ZIO.foreachDiscard(waiting) { case (cid, waiter, p) =>
        p.succeed(msg) *> promises.update(_ - ((cid, waiter)))
      }
    }
```

- [ ] **Step 2: Run the tests**

Run: `sbt 'conversationDomain/testOnly conversation.control.AgentDialogueCoordinatorSpec'`
Expected: All 5 tests PASS

- [ ] **Step 3: Commit**

```bash
git add modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueCoordinator.scala
git commit -m "feat(conversation): implement AgentDialogueCoordinator with Ref-based turn-taking"
```

---

## Task 6: Board Domain — New Timeline Entry Types

**Files:**
- Modify: `modules/board-domain/src/main/scala/board/entity/TimelineEntry.scala`
- Modify: `modules/board-domain/src/main/scala/board/entity/LinkedContext.scala`

- [ ] **Step 1: Add new TimelineEntry variants**

In `modules/board-domain/src/main/scala/board/entity/TimelineEntry.scala`, add these after the existing `AnalysisDocAttached` case class (after line 109):

```scala
  final case class A2ADialogueStarted(
    conversationId: String,
    participantNames: List[String],
    topic: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class A2ADialogueConcluded(
    conversationId: String,
    outcomeType: String,
    outcomeSummary: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class PlanningRecommendation(
    recommendations: List[IssueRecommendationSummary],
    occurredAt: Instant,
  ) extends TimelineEntry

  final case class IssueRecommendationSummary(
    issueId: BoardIssueId,
    title: String,
    rank: Int,
    score: Double,
    reasoning: String,
  )

  final case class TriageCompleted(
    issueId: BoardIssueId,
    suggestedLabels: List[String],
    suggestedPriority: String,
    suggestedCapabilities: List[String],
    reasoning: String,
    occurredAt: Instant,
  ) extends TimelineEntry
```

Note: We use `String` types for `conversationId`, `outcomeType`, `outcomeSummary`, and `suggestedPriority` to avoid coupling `board-domain` to `conversation-domain` types. The board module should remain decoupled.

- [ ] **Step 2: Verify compile**

Run: `sbt boardDomain/compile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add modules/board-domain/src/main/scala/board/entity/TimelineEntry.scala
git commit -m "feat(board): add A2ADialogueStarted, A2ADialogueConcluded, PlanningRecommendation, TriageCompleted timeline entries"
```

---

## Task 7: IssueTimelineView — Render New Timeline Entries

**Files:**
- Modify: `modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala`

- [ ] **Step 1: Add pattern match cases for new TimelineEntry variants**

In `modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala`, within the `timelineEntry` method (after the `case e: AnalysisDocAttached =>` block at line 291), add:

```scala
      case e: A2ADialogueStarted     =>
        (
          "A2A",
          s"Dialogue started: ${e.topic}",
          div(cls := "space-y-2")(
            p(cls := "text-sm text-slate-300")(s"Participants: ${e.participantNames.mkString(", ")}"),
            a(
              href := s"#a2a-${e.conversationId}",
              cls  := "inline-flex text-xs font-medium text-indigo-300 hover:text-indigo-200",
            )("View conversation"),
          ),
          "bg-purple-400",
        )
      case e: A2ADialogueConcluded   =>
        val (label, color) = e.outcomeType match
          case "Approved"         => ("Approved", "bg-emerald-400")
          case "ChangesRequested" => ("Changes Requested", "bg-amber-400")
          case "Escalated"        => ("Escalated", "bg-red-400")
          case _                  => ("Completed", "bg-blue-400")
        (
          "A2A",
          s"Dialogue concluded: $label",
          mutedText(e.outcomeSummary),
          color,
        )
      case e: PlanningRecommendation =>
        (
          "Planning",
          s"${e.recommendations.size} issue${if e.recommendations.size == 1 then "" else "s"} recommended for Todo",
          div(cls := "space-y-2")(
            e.recommendations.sortBy(_.rank).map { rec =>
              div(cls := "flex items-center gap-3 rounded-lg border border-white/5 bg-white/[0.03] px-3 py-2")(
                span(cls := "text-xs font-bold text-indigo-300")(s"#${rec.rank}"),
                div(cls := "flex-1")(
                  span(cls := "text-sm text-white")(rec.title),
                  p(cls := "text-xs text-slate-400 line-clamp-1")(rec.reasoning),
                ),
                span(cls := "text-xs font-mono text-slate-500")(f"${rec.score}%.0f%%"),
              )
            }
          ),
          "bg-cyan-400",
        )
      case e: TriageCompleted        =>
        (
          "Triage",
          "AI triage completed",
          div(cls := "space-y-2")(
            div(cls := "flex flex-wrap gap-2")(
              e.suggestedLabels.map(l => tag("ab-badge")(attr("text") := s"[AI] $l", attr("variant") := "gray")),
            ),
            div(cls := "flex flex-wrap gap-2 text-xs")(
              chip(s"Priority: ${e.suggestedPriority}"),
              e.suggestedCapabilities.map(c => chip(c)),
            ),
            p(cls := "text-xs text-slate-400")(e.reasoning),
          ),
          "bg-teal-400",
        )
```

- [ ] **Step 2: Verify compile**

Run: `sbt boardDomain/compile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala
git commit -m "feat(board): render A2A dialogue, planning recommendation, and triage timeline entries"
```

---

## Task 8: "Start AI Review" Button in Timeline View

**Files:**
- Modify: `modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala`

- [ ] **Step 1: Add "Start AI Review" button to the review action form**

In `modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala`, modify the `page` method to add the AI review button. Replace the conditional at line 28:

```scala
        if issue.column == BoardColumn.Review then
          frag(
            aiReviewPanel(workspaceId, issue),
            reviewActionForm(workspaceId, issue),
          )
        else frag(),
```

Then add the `aiReviewPanel` method after the `reviewActionForm` method:

```scala
  private def aiReviewPanel(workspaceId: String, issue: BoardIssue): Frag =
    val issueUrl = s"/board/$workspaceId/issues/${issue.frontmatter.id.value}"
    div(
      cls := "rounded-xl border border-indigo-400/20 bg-indigo-500/5 p-5",
    )(
      div(cls := "flex items-center justify-between")(
        div(cls := "space-y-1")(
          h3(cls := "text-sm font-semibold text-white")("AI Code Review"),
          p(cls := "text-xs text-slate-400")("Start an A2A dialogue between the review agent and the coding agent"),
        ),
        button(
          `type` := "button",
          cls    := "rounded-md bg-indigo-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500 transition-colors",
          attr("hx-post")    := s"$issueUrl/start-ai-review",
          attr("hx-swap")    := "outerHTML",
          attr("hx-target")  := "closest div",
        )("Start AI Review"),
      ),
      // A2A conversation panel placeholder — populated via SSE
      div(
        id  := s"a2a-panel-${issue.frontmatter.id.value}",
        cls := "mt-4",
      )(),
    )
```

- [ ] **Step 2: Verify compile**

Run: `sbt boardDomain/compile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala
git commit -m "feat(board): add Start AI Review button to issue timeline view"
```

---

## Task 9: Register New Daemon Agent Spec Keys

**Files:**
- Modify: `modules/daemon-domain/src/main/scala/daemon/entity/DaemonAgentSpec.scala`

- [ ] **Step 1: Add the four new daemon keys**

In `modules/daemon-domain/src/main/scala/daemon/entity/DaemonAgentSpec.scala`, add after line 77 (`DebtDetectorKey`):

```scala
object DaemonAgentSpec:
  val TestGuardianKey: String  = "test-guardian"
  val DebtDetectorKey: String  = "debt-detector"
  val PlanningAgentKey: String = "planning-agent"
  val ReviewAgentKey: String   = "review-agent"
  val TriageAgentKey: String   = "triage-agent"
  val RefactorAgentKey: String = "refactor-agent"
```

- [ ] **Step 2: Verify compile**

Run: `sbt daemonDomain/compile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add modules/daemon-domain/src/main/scala/daemon/entity/DaemonAgentSpec.scala
git commit -m "feat(daemon): register PlanningAgent, ReviewAgent, TriageAgent, RefactorAgent daemon keys"
```

---

## Task 10: A2A SSE Endpoint

**Files:**
- Create: `src/main/scala/conversation/boundary/DialogueSseController.scala`

This controller provides an SSE endpoint that the `ab-a2a-panel` web component subscribes to for live dialogue updates.

- [ ] **Step 1: Create the SSE controller**

Create `src/main/scala/conversation/boundary/DialogueSseController.scala`:

```scala
package conversation.boundary

import zio.*
import zio.http.*
import zio.json.*
import zio.stream.ZStream

import conversation.entity.DialogueEvent
import orchestration.control.WorkReportEventBus
import shared.ids.Ids.ConversationId

object DialogueSseController:

  def routes(eventBus: WorkReportEventBus): Routes[Any, Nothing] =
    Routes(
      Method.GET / "api" / "dialogues" / string("conversationId") / "sse" -> handler {
        (conversationId: String, _: Request) =>
          val convId = ConversationId(conversationId)
          ZIO.scoped {
            eventBus.subscribeDialogue.map { dequeue =>
              val stream: ZStream[Any, Nothing, String] =
                ZStream.fromQueue(dequeue)
                  .filter(_.conversationId == convId)
                  .map { event =>
                    val eventType = event match
                      case _: DialogueEvent.DialogueStarted  => "dialogue-started"
                      case _: DialogueEvent.MessagePosted    => "message-posted"
                      case _: DialogueEvent.TurnChanged      => "turn-changed"
                      case _: DialogueEvent.HumanIntervened  => "human-intervened"
                      case _: DialogueEvent.DialogueConcluded => "dialogue-concluded"
                    s"event: $eventType\ndata: ${event.toJson}\n\n"
                  }
              Response(
                status = Status.Ok,
                headers = Headers(
                  Header.ContentType(MediaType.text.`event-stream`),
                  Header.CacheControl.NoCache,
                  Header.Custom("Connection", "keep-alive"),
                ),
                body = Body.fromCharSequenceStreamChunked(stream),
              )
            }
          }
      },
    )
```

- [ ] **Step 2: Verify compile**

Run: `sbt compile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/conversation/boundary/DialogueSseController.scala
git commit -m "feat(conversation): add SSE endpoint for A2A dialogue events"
```

---

## Task 11: A2A Panel Web Component

**Files:**
- Create: `src/main/resources/static/client/components/ab-a2a-panel.js`

- [ ] **Step 1: Create the Lit 3 web component**

Create `src/main/resources/static/client/components/ab-a2a-panel.js`:

```javascript
import { LitElement, html, css } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbA2aPanel extends LitElement {
  createRenderRoot() { return this; }

  static properties = {
    conversationId: { type: String, attribute: 'conversation-id' },
    messages: { type: Array, state: true },
    concluded: { type: Boolean, state: true },
    outcomeSummary: { type: String, state: true },
    outcomeType: { type: String, state: true },
  };

  constructor() {
    super();
    this.messages = [];
    this.concluded = false;
    this.outcomeSummary = '';
    this.outcomeType = '';
    this._eventSource = null;
  }

  connectedCallback() {
    super.connectedCallback();
    if (this.conversationId) {
      this._connect();
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._disconnect();
  }

  _connect() {
    this._eventSource = new EventSource(`/api/dialogues/${this.conversationId}/sse`);

    this._eventSource.addEventListener('message-posted', (e) => {
      const data = JSON.parse(e.data);
      this.messages = [...this.messages, {
        sender: data.sender,
        senderRole: data.senderRole,
        content: data.content,
        turnNumber: data.turnNumber,
        occurredAt: data.occurredAt,
      }];
    });

    this._eventSource.addEventListener('human-intervened', (e) => {
      const data = JSON.parse(e.data);
      this.messages = [...this.messages, {
        sender: data.userId,
        senderRole: 'Human',
        content: '[Human intervention]',
        turnNumber: -1,
        occurredAt: data.occurredAt,
      }];
    });

    this._eventSource.addEventListener('dialogue-concluded', (e) => {
      const data = JSON.parse(e.data);
      this.concluded = true;
      this.outcomeType = Object.keys(data.outcome)[0] || 'Completed';
      this.outcomeSummary = Object.values(data.outcome)[0]?.summary ||
                            Object.values(data.outcome)[0] || '';
      this._disconnect();
    });
  }

  _disconnect() {
    if (this._eventSource) {
      this._eventSource.close();
      this._eventSource = null;
    }
  }

  _roleBadgeClass(role) {
    const map = {
      'Author': 'border-cyan-400/30 bg-cyan-500/10 text-cyan-200',
      'Reviewer': 'border-violet-400/30 bg-violet-500/10 text-violet-200',
      'Human': 'border-amber-400/30 bg-amber-500/10 text-amber-200',
    };
    return map[role] || 'border-slate-400/30 bg-slate-500/10 text-slate-200';
  }

  _outcomeBannerClass() {
    const map = {
      'Approved': 'border-emerald-400/20 bg-emerald-500/10 text-emerald-200',
      'ChangesRequested': 'border-amber-400/20 bg-amber-500/10 text-amber-200',
      'Escalated': 'border-red-400/20 bg-red-500/10 text-red-200',
    };
    return map[this.outcomeType] || 'border-blue-400/20 bg-blue-500/10 text-blue-200';
  }

  _handleIntervene() {
    const input = this.querySelector('#a2a-human-input');
    if (!input || !input.value.trim()) return;
    const message = input.value.trim();
    input.value = '';

    fetch(`/api/dialogues/${this.conversationId}/intervene`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
    });
  }

  render() {
    if (this.messages.length === 0 && !this.concluded) {
      return html`
        <div class="rounded-lg border border-dashed border-white/10 bg-white/[0.03] px-4 py-6 text-center text-sm text-slate-400">
          Waiting for dialogue to begin...
        </div>
      `;
    }

    return html`
      <div class="space-y-3">
        ${this.messages.map(msg => html`
          <div class="rounded-lg border border-white/5 bg-white/[0.03] px-3 py-2">
            <div class="mb-1 flex items-center gap-2">
              <span class="rounded-full border px-2 py-0.5 text-[10px] font-semibold ${this._roleBadgeClass(msg.senderRole)}">
                ${msg.senderRole}
              </span>
              <span class="text-xs font-medium text-slate-200">${msg.sender}</span>
              <span class="ml-auto text-[10px] text-slate-500">${msg.occurredAt}</span>
            </div>
            <p class="whitespace-pre-wrap text-sm leading-6 text-slate-300">${msg.content}</p>
          </div>
        `)}

        ${this.concluded ? html`
          <div class="rounded-lg border px-4 py-3 text-sm font-medium ${this._outcomeBannerClass()}">
            ${this.outcomeType}: ${this.outcomeSummary}
          </div>
        ` : html`
          <div class="flex gap-2">
            <input id="a2a-human-input" type="text" placeholder="Intervene in the dialogue..."
              class="flex-1 rounded-lg border border-white/10 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-400/50" />
            <button @click=${() => this._handleIntervene()}
              class="rounded-md bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-amber-500">
              Intervene
            </button>
          </div>
        `}
      </div>
    `;
  }
}

customElements.define('ab-a2a-panel', AbA2aPanel);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/client/components/ab-a2a-panel.js
git commit -m "feat(ui): add ab-a2a-panel Lit 3 web component for live A2A dialogue"
```

---

## Task 12: Extend DaemonAgentScheduler Dispatch for New Agents

**Files:**
- Modify: `modules/daemon-domain/src/main/scala/daemon/control/DaemonAgentScheduler.scala`

This task adds dispatch routing for the four new daemon keys. The actual agent execution logic (what the agent does when triggered) is intentionally thin here — each just logs and publishes activity events. The real intelligence comes from the CLI agent invocation via `WorkspaceRunService`, which we wire in subsequent tasks.

- [ ] **Step 1: Add dispatch cases for new daemon keys**

In `modules/daemon-domain/src/main/scala/daemon/control/DaemonAgentScheduler.scala`, find the `execute` method (around line 282) and add new cases after the `DebtDetectorKey` case:

```scala
      case DaemonAgentSpec.PlanningAgentKey => runPlanningAgent(spec)
      case DaemonAgentSpec.ReviewAgentKey   => runReviewAgent(spec)
      case DaemonAgentSpec.TriageAgentKey   => runTriageAgent(spec)
      case DaemonAgentSpec.RefactorAgentKey => runRefactorAgent(spec)
```

Then add the four stub execution methods (after `runDebtDetector`, around line 313). These are minimal stubs that follow the existing `runCustomDaemon` pattern and will be enriched in future tasks:

```scala
  private def runPlanningAgent(spec: DaemonAgentSpec): IO[PersistenceError, DaemonRunOutcome] =
    for
      workspaces <- loadWorkspaces(spec.workspaceIds)
      _          <- ZIO.logInfo(s"[PlanningAgent] Analyzing ${workspaces.size} workspace(s) for iteration planning")
      // Stub: delegates to the configured CLI agent via custom daemon pattern
      outcome    <- runCustomDaemon(spec)
    yield outcome

  private def runReviewAgent(spec: DaemonAgentSpec): IO[PersistenceError, DaemonRunOutcome] =
    for
      _       <- ZIO.logInfo(s"[ReviewAgent] Triggered for workspace(s): ${spec.workspaceIds.mkString(", ")}")
      outcome <- runCustomDaemon(spec)
    yield outcome

  private def runTriageAgent(spec: DaemonAgentSpec): IO[PersistenceError, DaemonRunOutcome] =
    for
      _       <- ZIO.logInfo(s"[TriageAgent] Triaging new issues in workspace(s): ${spec.workspaceIds.mkString(", ")}")
      outcome <- runCustomDaemon(spec)
    yield outcome

  private def runRefactorAgent(spec: DaemonAgentSpec): IO[PersistenceError, DaemonRunOutcome] =
    for
      _       <- ZIO.logInfo(s"[RefactorAgent] Scanning for refactoring opportunities")
      outcome <- runCustomDaemon(spec)
    yield outcome
```

- [ ] **Step 2: Verify compile**

Run: `sbt daemonDomain/compile`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run existing daemon tests to verify no regression**

Run: `sbt daemonDomain/test`
Expected: All existing tests PASS

- [ ] **Step 4: Commit**

```bash
git add modules/daemon-domain/src/main/scala/daemon/control/DaemonAgentScheduler.scala
git commit -m "feat(daemon): add dispatch routing for PlanningAgent, ReviewAgent, TriageAgent, RefactorAgent"
```

---

## Task 13: Full Compile and Test Verification

**Files:** None (verification only)

- [ ] **Step 1: Full project compile**

Run: `sbt compile`
Expected: BUILD SUCCESSFUL — all modules compile with the new types, extended event bus, and daemon dispatch.

- [ ] **Step 2: Run all unit tests**

Run: `sbt test`
Expected: All 1121+ tests PASS. The new `AgentDialogueCoordinatorSpec` tests pass. No regressions in existing tests.

- [ ] **Step 3: Run format check**

Run: `sbt fmt`
Expected: All files formatted.

- [ ] **Step 4: Final commit if fmt changed anything**

```bash
git add -A
git commit -m "chore: format after A2A infrastructure additions"
```

---

## Summary

| Task | What it builds | Module(s) |
|------|---------------|-----------|
| 1 | A2A entity types (AgentParticipant, AgentRole, etc.) + ChannelInfo.AgentToAgent | conversation-domain |
| 2 | DialogueEvent sealed trait for SSE bus | conversation-domain |
| 3 | Extend WorkReportEventBus with dialogueHub | orchestration-domain, build.sbt |
| 4 | Failing tests for AgentDialogueCoordinator | conversation-domain (test) |
| 5 | AgentDialogueCoordinator implementation | conversation-domain |
| 6 | New TimelineEntry variants | board-domain |
| 7 | Render new timeline entries in IssueTimelineView | board-domain |
| 8 | "Start AI Review" button | board-domain |
| 9 | Register 4 new DaemonAgentSpec keys | daemon-domain |
| 10 | SSE endpoint for A2A dialogue events | root (boundary) |
| 11 | ab-a2a-panel Lit 3 web component | static resources |
| 12 | Daemon dispatch routing for new agents | daemon-domain |
| 13 | Full compile + test verification | all |
