# A2A Integration Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add integration tests with mocked AI to validate A2A dialogue workflows and proactive agent pipelines, plus the `AgentDialogueRunner` production abstraction that connects `LlmService` to the turn-taking coordinator.

**Architecture:** New `AgentDialogueRunner` trait in `conversation-domain`, `AgentDialogueRunnerLive` in root (needs `LlmService`). Two test suites: `AgentDialogueRunnerSpec` (3 tests for A2A dialogue flow) and `ProactiveAgentWorkflowSpec` (2 tests for daemon pipelines). All tests use Ref-based stubs with pop-queue mock LLM — no external services.

**Tech Stack:** Scala 3, ZIO 2.x, ZIO Test, zio-json, zio-schema

---

### Task 1: Add `MaxTurnsReached` to `DialogueOutcome`

The spec requires `DialogueOutcome.MaxTurnsReached` for when the runner exhausts its turn budget. This variant doesn't exist yet.

**Files:**
- Modify: `modules/conversation-domain/src/main/scala/conversation/entity/AgentDialogue.scala`

- [ ] **Step 1: Add the new enum variant**

In `modules/conversation-domain/src/main/scala/conversation/entity/AgentDialogue.scala`, add `MaxTurnsReached` to `DialogueOutcome`:

```scala
enum DialogueOutcome derives JsonCodec, Schema:
  case Approved(summary: String)
  case ChangesRequested(comments: List[String])
  case Escalated(reason: String)
  case Completed(summary: String)
  case MaxTurnsReached(turnsUsed: Int)
```

- [ ] **Step 2: Compile to verify**

Run: `sbt --client conversationDomain/compile`

Expected: clean compile. No other code references `DialogueOutcome` exhaustively yet (the hub publishes it opaquely, match cases use specific variants).

- [ ] **Step 3: Commit**

```bash
git add modules/conversation-domain/src/main/scala/conversation/entity/AgentDialogue.scala
git commit -m "feat: add MaxTurnsReached variant to DialogueOutcome"
```

---

### Task 2: Update `concludeDialogue` to unblock waiting agents

When one agent concludes the dialogue, the other agent may be blocked on `awaitTurn`. Currently `concludeDialogue` removes the turn state but doesn't fail the waiting `Promise`, leaving the other fiber stuck forever. Fix this by failing all pending promises for the concluded conversation.

**Files:**
- Modify: `modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueCoordinator.scala`
- Modify: `modules/conversation-domain/src/test/scala/conversation/control/AgentDialogueCoordinatorSpec.scala`

- [ ] **Step 1: Write a failing test for concluded-unblock**

In `AgentDialogueCoordinatorSpec.scala`, add a new test after the existing `awaitTurn` test:

```scala
    test("awaitTurn fails with NotFound when dialogue is concluded by other agent") {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        convId      <- coordinator.startDialogue(issueId, reviewer, author, "Review", "Checking code")
        fiber       <- coordinator.awaitTurn(convId, "review-agent").fork
        _           <- ZIO.yieldNow
        _           <- coordinator.concludeDialogue(convId, DialogueOutcome.Approved("All good"))
        result      <- fiber.await
      yield assert(result)(fails(isSubtype[PersistenceError.NotFound](anything)))
    },
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `sbt --client 'testOnly conversation.control.AgentDialogueCoordinatorSpec'`

Expected: the new test hangs or times out because the promise is never fulfilled or failed.

- [ ] **Step 3: Update `concludeDialogue` to fail pending promises**

In `AgentDialogueCoordinator.scala`, modify the `concludeDialogue` method in `AgentDialogueCoordinatorLive`:

```scala
  def concludeDialogue(
    conversationId: ConversationId,
    outcome: DialogueOutcome,
  ): IO[PersistenceError, Unit] =
    val now = Instant.now
    for
      _ <- repo.append(ConversationEvent.Closed(conversationId, now, now))
      _ <- turns.update(_ - conversationId)
      _ <- failPendingPromises(conversationId)
      _ <- dialogueHub.publish(DialogueEvent.DialogueConcluded(conversationId, outcome, now)).ignore
    yield ()
```

Add the helper method to `AgentDialogueCoordinatorLive`:

```scala
  private def failPendingPromises(conversationId: ConversationId): UIO[Unit] =
    promises.modify { m =>
      val (toFail, toKeep) = m.partition { case ((cid, _), _) => cid == conversationId }
      (toFail.values.toList, toKeep)
    }.flatMap { pending =>
      ZIO.foreachDiscard(pending)(_.fail(PersistenceError.NotFound("Dialogue", conversationId.value)).ignore)
    }
```

- [ ] **Step 4: Run tests to verify all pass**

Run: `sbt --client 'testOnly conversation.control.AgentDialogueCoordinatorSpec'`

Expected: all 6 tests pass (5 existing + 1 new).

- [ ] **Step 5: Commit**

```bash
git add modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueCoordinator.scala \
       modules/conversation-domain/src/test/scala/conversation/control/AgentDialogueCoordinatorSpec.scala
git commit -m "fix: unblock waiting agents when dialogue is concluded"
```

---

### Task 3: Create `AgentResponse` entity

**Files:**
- Create: `modules/conversation-domain/src/main/scala/conversation/entity/AgentResponse.scala`

- [ ] **Step 1: Create the file**

Create `modules/conversation-domain/src/main/scala/conversation/entity/AgentResponse.scala`:

```scala
package conversation.entity

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

final case class AgentResponse(
  content: String,
  concluded: Boolean,
  outcome: Option[DialogueOutcome],
) derives JsonCodec, Schema
```

- [ ] **Step 2: Compile**

Run: `sbt --client conversationDomain/compile`

Expected: clean compile.

- [ ] **Step 3: Commit**

```bash
git add modules/conversation-domain/src/main/scala/conversation/entity/AgentResponse.scala
git commit -m "feat: add AgentResponse entity for structured LLM dialogue output"
```

---

### Task 4: Create `AgentDialogueRunner` trait

**Files:**
- Create: `modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueRunner.scala`

- [ ] **Step 1: Create the trait file**

Create `modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueRunner.scala`:

```scala
package conversation.control

import zio.*

import conversation.entity.{ AgentRole, DialogueOutcome }
import shared.errors.PersistenceError
import shared.ids.Ids.ConversationId

trait AgentDialogueRunner:
  def runDialogue(
    conversationId: ConversationId,
    agentRole: AgentRole,
    maxTurns: Int = 10,
  ): IO[PersistenceError, DialogueOutcome]
```

- [ ] **Step 2: Compile**

Run: `sbt --client conversationDomain/compile`

Expected: clean compile.

- [ ] **Step 3: Commit**

```bash
git add modules/conversation-domain/src/main/scala/conversation/control/AgentDialogueRunner.scala
git commit -m "feat: add AgentDialogueRunner trait for agent turn-taking loop"
```

---

### Task 5: Implement `AgentDialogueRunnerLive`

**Files:**
- Create: `src/main/scala/conversation/control/AgentDialogueRunnerLive.scala`

- [ ] **Step 1: Create the implementation file**

Create `src/main/scala/conversation/control/AgentDialogueRunnerLive.scala`:

```scala
package conversation.control

import zio.*
import zio.json.*

import conversation.entity.*
import llm4zio.core.{ LlmError, LlmService }
import llm4zio.tools.JsonSchema
import shared.errors.PersistenceError
import shared.ids.Ids.ConversationId

final case class AgentDialogueRunnerLive(
  coordinator: AgentDialogueCoordinator,
  repo: ConversationRepository,
  llm: LlmService,
) extends AgentDialogueRunner:

  def runDialogue(
    conversationId: ConversationId,
    agentRole: AgentRole,
    maxTurns: Int = 10,
  ): IO[PersistenceError, DialogueOutcome] =
    loop(conversationId, agentRole, agentName = "", maxTurns, turnsTaken = 0)

  private def loop(
    conversationId: ConversationId,
    agentRole: AgentRole,
    agentName: String,
    maxTurns: Int,
    turnsTaken: Int,
  ): IO[PersistenceError, DialogueOutcome] =
    if turnsTaken >= maxTurns then
      val outcome = DialogueOutcome.MaxTurnsReached(turnsTaken)
      coordinator.concludeDialogue(conversationId, outcome).as(outcome)
    else
      val waitForTurn =
        coordinator.awaitTurn(conversationId, resolvedName(conversationId, agentRole, agentName))

      waitForTurn.foldZIO(
        {
          case PersistenceError.NotFound("Dialogue", _) =>
            // Dialogue was concluded by the other agent
            repo.get(conversationId).map { conv =>
              conv.state match
                case ConversationState.Closed(_, _) => DialogueOutcome.Completed("Concluded by peer")
                case _                              => DialogueOutcome.Completed("Concluded")
            }
          case other => ZIO.fail(other)
        },
        msg =>
          for
            conv       <- repo.get(conversationId)
            name        = resolveAgentName(conv, agentRole)
            prompt      = buildPrompt(conv, agentRole, name)
            response   <- callLlm(prompt)
            _          <- coordinator.respondInDialogue(conversationId, name, response.content)
            outcome    <- if response.concluded then
                            val out = response.outcome.getOrElse(DialogueOutcome.Approved("Concluded"))
                            coordinator.concludeDialogue(conversationId, out).as(out)
                          else loop(conversationId, agentRole, name, maxTurns, turnsTaken + 1)
          yield outcome,
      )

  private def resolvedName(
    conversationId: ConversationId,
    role: AgentRole,
    knownName: String,
  ): String =
    if knownName.nonEmpty then knownName
    else s"${role.toString.toLowerCase}-agent"

  private def resolveAgentName(conv: Conversation, role: AgentRole): String =
    conv.channel match
      case ChannelInfo.AgentToAgent(_, participants) =>
        participants.find(_.role == role).map(_.agentName).getOrElse(s"${role.toString.toLowerCase}-agent")
      case _ => s"${role.toString.toLowerCase}-agent"

  private def buildPrompt(conv: Conversation, role: AgentRole, agentName: String): String =
    val history = conv.messages
      .map(m => s"[${m.sender}]: ${m.content}")
      .mkString("\n")
    s"""You are $agentName with role ${role.toString} in an A2A dialogue.
       |
       |Conversation history:
       |$history
       |
       |Respond with a JSON object: {"content": "your message", "concluded": true/false, "outcome": null or {"Approved": {"summary": "..."}} or {"ChangesRequested": {"comments": ["..."]}} }
       |""".stripMargin

  private def callLlm(prompt: String): IO[PersistenceError, AgentResponse] =
    llm
      .executeStructured[AgentResponse](prompt, zio.json.ast.Json.Null)
      .mapError(err => PersistenceError.QueryFailed("LLM call failed", err.toString))

object AgentDialogueRunnerLive:
  val live: ZLayer[AgentDialogueCoordinator & ConversationRepository & LlmService, Nothing, AgentDialogueRunner] =
    ZLayer {
      for
        coordinator <- ZIO.service[AgentDialogueCoordinator]
        repo        <- ZIO.service[ConversationRepository]
        llm         <- ZIO.service[LlmService]
      yield AgentDialogueRunnerLive(coordinator, repo, llm)
    }
```

**Key design notes:**
- The `awaitTurn` call catches `PersistenceError.NotFound("Dialogue", _)` — this is the signal from Task 2 that the dialogue was concluded by the other agent.
- `buildPrompt` constructs a simple prompt from conversation history. In production this would be richer, but it's sufficient for the mock LLM tests.
- `callLlm` maps `LlmError` to `PersistenceError.QueryFailed` to stay in the typed error channel.
- `JsonSchema` is `Json` (type alias) — we pass `Json.Null` since the mock LLM ignores it.

- [ ] **Step 2: Compile**

Run: `sbt --client compile`

Expected: clean compile. The file lives in root `src/main/scala/` which can see all modules.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/conversation/control/AgentDialogueRunnerLive.scala
git commit -m "feat: implement AgentDialogueRunnerLive with turn-taking loop"
```

---

### Task 6: Write `AgentDialogueRunnerSpec` — Test 1: Review passes

**Files:**
- Create: `src/test/scala/conversation/control/AgentDialogueRunnerSpec.scala`

- [ ] **Step 1: Create the test file with shared stubs and first test**

Create `src/test/scala/conversation/control/AgentDialogueRunnerSpec.scala`:

```scala
package conversation.control

import java.time.Instant

import zio.*
import zio.json.*
import zio.stream.ZStream
import zio.test.*
import zio.test.Assertion.*

import conversation.entity.*
import llm4zio.core.{ LlmChunk, LlmError, LlmService }
import llm4zio.tools.{ AnyTool, JsonSchema, ToolCallResponse }
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, ConversationId }

object AgentDialogueRunnerSpec extends ZIOSpecDefault:

  private val issueId = BoardIssueId("issue-1")

  private def reviewer(at: Instant) = AgentParticipant("reviewer-agent", AgentRole.Reviewer, at)
  private def author(at: Instant)   = AgentParticipant("author-agent", AgentRole.Author, at)

  // ── Mock LLM factory ────────────────────────────────────────────

  private def mockLlm(responses: List[AgentResponse]): UIO[LlmService] =
    Ref.make(responses).map { ref =>
      new LlmService:
        override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
          for
            raw    <- ref.modify {
                        case h :: t => (h.toJson, t)
                        case Nil    => ("""{"content":"","concluded":true,"outcome":null}""", Nil)
                      }
            result <- ZIO
                        .fromEither(raw.fromJson[A])
                        .mapError(msg => LlmError.ParseError(msg, raw))
          yield result

        override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk]                                    = ZStream.empty
        override def executeStreamWithHistory(messages: List[llm4zio.core.Message]): ZStream[Any, LlmError, LlmChunk]    = ZStream.empty
        override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse]              =
          ZIO.fail(LlmError.ProviderError("not used in test"))
        override def isAvailable: UIO[Boolean]                                                                            = ZIO.succeed(true)
    }

  // ── Event collector ──────────────────────────────────────────────

  private def collectEvents(hub: Hub[DialogueEvent]): ZIO[Scope, Nothing, Ref[List[DialogueEvent]]] =
    for
      ref   <- Ref.make(List.empty[DialogueEvent])
      deq   <- hub.subscribe
      _     <- deq.take.flatMap(e => ref.update(_ :+ e)).forever.forkScoped
    yield ref

  // ── Conversation repository stub (same as AgentDialogueCoordinatorSpec) ──

  private def makeConversationRepo: UIO[ConversationRepository] =
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
                  case Left(err)   => ZIO.fail(PersistenceError.QueryFailed("rebuild", err))
              case None         =>
                ZIO.fail(PersistenceError.NotFound("Conversation", id.value))
          }

        def list(filter: ConversationFilter): IO[PersistenceError, List[Conversation]] =
          store.get.map(_.values.toList.flatMap(events => Conversation.fromEvents(events).toOption))
    }

  // ── Helper: run a two-agent dialogue ─────────────────────────────

  private def runTwoAgentDialogue(
    reviewerResponses: List[AgentResponse],
    authorResponses: List[AgentResponse],
    maxTurns: Int = 10,
  ) =
    ZIO.scoped {
      for
        now          <- Clock.instant
        hub          <- Hub.unbounded[DialogueEvent]
        repo         <- makeConversationRepo
        eventsRef    <- collectEvents(hub)
        coordinator  <- ZIO.succeed(AgentDialogueCoordinatorLive).flatMap { _ =>
                          for
                            turns    <- Ref.make(Map.empty[ConversationId, TurnState])
                            promises <- Ref.make(Map.empty[(ConversationId, String), Promise[PersistenceError, conversation.entity.Message]])
                          yield AgentDialogueCoordinatorLive(repo, hub, turns, promises)
                        }
        reviewerLlm  <- mockLlm(reviewerResponses)
        authorLlm    <- mockLlm(authorResponses)
        reviewerRunner = AgentDialogueRunnerLive(coordinator, repo, reviewerLlm)
        authorRunner   = AgentDialogueRunnerLive(coordinator, repo, authorLlm)
        convId       <- coordinator.startDialogue(
                          issueId,
                          reviewer(now),
                          author(now),
                          "Code review for issue-1",
                          "Starting review of the changes",
                        )
        reviewerFib  <- reviewerRunner.runDialogue(convId, AgentRole.Reviewer, maxTurns).fork
        _            <- ZIO.yieldNow
        authorFib    <- authorRunner.runDialogue(convId, AgentRole.Author, maxTurns).fork
        reviewerOut  <- reviewerFib.join
        authorOut    <- authorFib.join
        _            <- ZIO.sleep(50.millis) // let event collector pick up final events
        events       <- eventsRef.get
        conv         <- repo.get(convId)
      yield (reviewerOut, authorOut, events, conv)
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentDialogueRunnerSpec")(
    test("review passes after 1 round") {
      for
        (reviewerOut, authorOut, events, conv) <- runTwoAgentDialogue(
                                                    reviewerResponses = List(
                                                      AgentResponse("Found minor style issues in line 42", false, None),
                                                      AgentResponse("Issues addressed, approving", true, Some(DialogueOutcome.Approved("No issues found"))),
                                                    ),
                                                    authorResponses = List(
                                                      AgentResponse("Fixed style issues as suggested", false, None),
                                                    ),
                                                  )
      yield assertTrue(
        // Outcomes
        reviewerOut == DialogueOutcome.Approved("No issues found"),
        // Conversation messages: opening + reviewer critique + author fix + reviewer approval = 4
        conv.messages.size == 4,
        conv.messages(1).content == "Found minor style issues in line 42",
        conv.messages(2).content == "Fixed style issues as suggested",
        conv.messages(3).content == "Issues addressed, approving",
        // Events include started, messages, turn changes, concluded
        events.exists(_.isInstanceOf[DialogueEvent.DialogueStarted]),
        events.exists(_.isInstanceOf[DialogueEvent.DialogueConcluded]),
        events.collect { case m: DialogueEvent.MessagePosted => m }.size >= 3,
      )
    },
  ) @@ TestAspect.withLiveClock
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `sbt --client 'testOnly conversation.control.AgentDialogueRunnerSpec'`

Expected: 1 test passes. If it fails, debug the turn-taking coordination — the most likely issue is the `awaitTurn` / `respondInDialogue` promise handshake timing.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/conversation/control/AgentDialogueRunnerSpec.scala
git commit -m "test: add AgentDialogueRunnerSpec with review-passes scenario"
```

---

### Task 7: Add Test 2: Review requests rework

**Files:**
- Modify: `src/test/scala/conversation/control/AgentDialogueRunnerSpec.scala`

- [ ] **Step 1: Add the rework test**

Add this test inside the `suite(...)` block, after the first test:

```scala
    test("review requests rework") {
      for
        (reviewerOut, _, events, conv) <- runTwoAgentDialogue(
                                            reviewerResponses = List(
                                              AgentResponse("Critical security vulnerability in auth handler", false, None),
                                              AgentResponse(
                                                "Vulnerability acknowledged but fix is incomplete, requesting rework",
                                                true,
                                                Some(DialogueOutcome.ChangesRequested(List("Auth handler still vulnerable"))),
                                              ),
                                            ),
                                            authorResponses = List(
                                              AgentResponse("Attempted partial fix for auth handler", false, None),
                                            ),
                                          )
      yield assertTrue(
        reviewerOut.isInstanceOf[DialogueOutcome.ChangesRequested],
        conv.messages.size == 4,
        events.exists {
          case DialogueEvent.DialogueConcluded(_, _: DialogueOutcome.ChangesRequested, _) => true
          case _                                                                          => false
        },
      )
    },
```

- [ ] **Step 2: Run tests**

Run: `sbt --client 'testOnly conversation.control.AgentDialogueRunnerSpec'`

Expected: 2 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/conversation/control/AgentDialogueRunnerSpec.scala
git commit -m "test: add review-requests-rework A2A dialogue scenario"
```

---

### Task 8: Add Test 3: Max turns reached

**Files:**
- Modify: `src/test/scala/conversation/control/AgentDialogueRunnerSpec.scala`

- [ ] **Step 1: Add the max-turns test**

Add this test inside the `suite(...)` block:

```scala
    test("max turns reached") {
      for
        (reviewerOut, _, events, conv) <- runTwoAgentDialogue(
                                            reviewerResponses = List(
                                              AgentResponse("Issue 1 in auth module", false, None),
                                              AgentResponse("Issue 2 in auth module", false, None),
                                              AgentResponse("Issue 3 in auth module", false, None),
                                            ),
                                            authorResponses = List(
                                              AgentResponse("Addressed issue 1", false, None),
                                              AgentResponse("Addressed issue 2", false, None),
                                              AgentResponse("Addressed issue 3", false, None),
                                            ),
                                            maxTurns = 2,
                                          )
      yield assertTrue(
        reviewerOut.isInstanceOf[DialogueOutcome.MaxTurnsReached],
        // opening message + 2 runner turns per agent that concludes = varies
        // The key assertion is that it stopped early
        events.exists {
          case DialogueEvent.DialogueConcluded(_, _: DialogueOutcome.MaxTurnsReached, _) => true
          case _                                                                         => false
        },
      )
    },
```

- [ ] **Step 2: Run all 3 tests**

Run: `sbt --client 'testOnly conversation.control.AgentDialogueRunnerSpec'`

Expected: 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/conversation/control/AgentDialogueRunnerSpec.scala
git commit -m "test: add max-turns-reached A2A dialogue scenario"
```

---

### Task 9: Write `ProactiveAgentWorkflowSpec`

**Files:**
- Create: `src/test/scala/daemon/control/ProactiveAgentWorkflowSpec.scala`

This test verifies the daemon scheduler dispatches the new proactive agent keys correctly through `runCustomDaemon`. It reuses the stub infrastructure from `DaemonAgentSchedulerSpec`.

- [ ] **Step 1: Create the test file with both tests**

Create `src/test/scala/daemon/control/ProactiveAgentWorkflowSpec.scala`:

```scala
package daemon.control

import java.nio.file.Files
import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import daemon.entity.*
import issues.entity.*
import project.entity.*
import shared.ids.Ids.*
import shared.testfixtures.TestFixtures.*
import workspace.entity.*

object ProactiveAgentWorkflowSpec extends ZIOSpecDefault:

  private val now       = Instant.parse("2026-04-14T10:00:00Z")
  private val projectId = ProjectId("project-1")

  private def makePlanningSpec(workspaceId: String): DaemonAgentSpec =
    DaemonAgentSpec(
      id = DaemonAgentSpecId("spec-planning"),
      projectId = projectId,
      daemonKey = DaemonAgentSpec.PlanningAgentKey,
      name = "Planning Agent",
      purpose = "Recommend issues for next iteration",
      prompt = "Analyze backlog and recommend top issues for the sprint",
      agentName = "code-agent",
      schedule = DaemonSchedule.IntervalMinutes(60),
      limits = DaemonLimits(maxIssuesPerRun = 5, maxConcurrentRuns = 1),
      workspaceIds = List(workspaceId),
      enabled = true,
      createdAt = now,
      updatedAt = now,
    )

  private def makeTriageSpec(workspaceId: String): DaemonAgentSpec =
    DaemonAgentSpec(
      id = DaemonAgentSpecId("spec-triage"),
      projectId = projectId,
      daemonKey = DaemonAgentSpec.TriageAgentKey,
      name = "Triage Agent",
      purpose = "Auto-categorize new issues",
      prompt = "Categorize, label, and estimate the new issue",
      agentName = "code-agent",
      schedule = DaemonSchedule.IntervalMinutes(30),
      limits = DaemonLimits(maxIssuesPerRun = 10, maxConcurrentRuns = 1),
      workspaceIds = List(workspaceId),
      enabled = true,
      createdAt = now,
      updatedAt = now,
    )

  private def makeScheduler(
    workspacePath: String,
    customSpecs: List[DaemonAgentSpec] = Nil,
  ): ZIO[Any, Nothing, (DaemonAgentSchedulerLive, Ref[Map[IssueId, List[IssueEvent]]], Ref[List[ActivityEvent]])] =
    for
      issueRef    <- Ref.make(Map.empty[IssueId, List[IssueEvent]])
      activityRef <- Ref.make(List.empty[ActivityEvent])
      configRef   <- Ref.make(Map.empty[String, String])
      queue       <- Queue.unbounded[DaemonJob]
      runtimeRef  <- Ref.Synchronized.make(Map.empty[DaemonAgentSpecId, DaemonAgentRuntime])
      project      = Project(
                       id = projectId,
                       name = "TestProject",
                       description = Some("Test"),
                       settings = ProjectSettings(defaultAgent = Some("code-agent")),
                       createdAt = now,
                       updatedAt = now,
                     )
      workspace    = Workspace(
                       id = "ws-1",
                       projectId = projectId,
                       name = "Test Workspace",
                       localPath = workspacePath,
                       defaultAgent = Some("code-agent"),
                       description = Some("Test workspace"),
                       enabled = true,
                       runMode = RunMode.Host,
                       cliTool = "codex",
                       createdAt = now,
                       updatedAt = now,
                     )
      scheduler    = DaemonAgentSchedulerLive(
                       projectRepository = new StubProjectRepository(List(project)),
                       workspaceRepository = new StubWorkspaceRepository(List(workspace)),
                       issueRepository = new MutableIssueRepository(issueRef),
                       activityHub = new StubActivityHub(activityRef),
                       agentPoolManager = new StubAgentPoolManager,
                       configRepository = new MutableConfigRepository(configRef),
                       governanceRepository = new StubGovernancePolicyRepository(None),
                       daemonRepository = new StubDaemonAgentSpecRepository(customSpecs),
                       queue = queue,
                       runtimeState = runtimeRef,
                     )
    yield (scheduler, issueRef, activityRef)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ProactiveAgentWorkflowSpec")(
    test("planning agent creates recommendation maintenance issue") {
      for
        temp                               <- ZIO.attemptBlocking(Files.createTempDirectory("daemon-planning").toString).orDie
        planningSpec                        = makePlanningSpec("ws-1")
        (scheduler, issueRef, activityRef) <- makeScheduler(temp, List(planningSpec))
        _                                  <- scheduler.trigger(planningSpec.id)
        _                                  <- scheduler.worker
        issues                             <- issueRef.get
        allIssues                          <- ZIO.foreach(issues.keys.toList)(id =>
                                                new MutableIssueRepository(issueRef).get(id)
                                              )
        maintenance                         = allIssues.filter(_.issueType == "maintenance")
      yield assertTrue(
        maintenance.nonEmpty,
        maintenance.exists(_.tags.contains(s"daemon:${DaemonAgentSpec.PlanningAgentKey}")),
        maintenance.exists(_.description.contains("Recommend issues for next iteration")),
      )
    },
    test("triage agent creates categorization maintenance issue") {
      for
        temp                               <- ZIO.attemptBlocking(Files.createTempDirectory("daemon-triage").toString).orDie
        triageSpec                          = makeTriageSpec("ws-1")
        (scheduler, issueRef, activityRef) <- makeScheduler(temp, List(triageSpec))
        _                                  <- scheduler.trigger(triageSpec.id)
        _                                  <- scheduler.worker
        issues                             <- issueRef.get
        allIssues                          <- ZIO.foreach(issues.keys.toList)(id =>
                                                new MutableIssueRepository(issueRef).get(id)
                                              )
        maintenance                         = allIssues.filter(_.issueType == "maintenance")
      yield assertTrue(
        maintenance.nonEmpty,
        maintenance.exists(_.tags.contains(s"daemon:${DaemonAgentSpec.TriageAgentKey}")),
      )
    },
  ) @@ TestAspect.sequential
```

**Note:** `execute` is private on `DaemonAgentSchedulerLive`. The test uses the public `trigger` + `worker` pattern (same as `DaemonAgentSchedulerSpec`). `worker` is `private[control]` so it's accessible from the same package.

- [ ] **Step 2: Run the tests**

Run: `sbt --client 'testOnly daemon.control.ProactiveAgentWorkflowSpec'`

Expected: 2 tests pass. If there are compilation errors about private methods or missing stubs, check the `DaemonAgentSchedulerSpec` for the exact stub class names (they may be defined locally in that file rather than in `TestFixtures`).

- [ ] **Step 3: Fix any compilation issues**

Common issues:
- Stub classes may be private to `DaemonAgentSchedulerSpec` — copy the definitions you need
- `execute` may be private — use `trigger` + `worker` pattern instead
- `DaemonAgentRuntime` import path — check exact package

- [ ] **Step 4: Run all tests to verify no regressions**

Run: `sbt --client test`

Expected: all tests pass (existing 1089+ plus 5 new).

- [ ] **Step 5: Commit**

```bash
git add src/test/scala/daemon/control/ProactiveAgentWorkflowSpec.scala
git commit -m "test: add ProactiveAgentWorkflowSpec for planning and triage agents"
```

---

### Task 10: Format and final verification

**Files:**
- All modified files

- [ ] **Step 1: Run formatter**

Run: `sbt --client fmt`

- [ ] **Step 2: Run full test suite**

Run: `sbt --client test`

Expected: all tests pass.

- [ ] **Step 3: Commit any formatting changes**

```bash
git add -A
git commit -m "style: format new A2A test files"
```

(Skip if `fmt` changed nothing.)

---

## Task Dependency Graph

```
Task 1 (MaxTurnsReached) ──┐
                           ├── Task 3 (AgentResponse) ── Task 4 (Trait) ── Task 5 (Live) ── Task 6 (Test 1) ── Task 7 (Test 2) ── Task 8 (Test 3) ──┐
Task 2 (Conclude unblock) ─┘                                                                                                                        ├── Task 10 (Format + Verify)
                                                                                                         Task 9 (Daemon tests) ─────────────────────┘
```

Tasks 1 and 2 are independent of each other but both must precede Task 3+.
Tasks 6, 7, 8 are sequential (each adds a test to the same file).
Task 9 is independent of Tasks 3-8 (different test suite, no shared code).
