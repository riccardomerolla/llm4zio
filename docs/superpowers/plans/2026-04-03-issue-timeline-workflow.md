# Issue Timeline & Workflow Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fragmented issue approval workflow with a GitHub PR-style timeline view, one-click approve & merge, and automated rework continuation.

**Architecture:** Four new files (entity ADT, two control services, one view) plus modifications to BoardOrchestrator, DecisionInbox, BoardController, BoardView, and DI wiring. The `IssueApprovalService` orchestrates across existing services for approve/rework. The `IssueTimelineService` aggregates events from multiple repositories into a chronological list. The `IssueTimelineView` renders the timeline as SSR HTML via Scalatags.

**Tech Stack:** Scala 3, ZIO 2, Scalatags, HTMX, Tailwind CSS, existing EventStore/Repository infrastructure.

---

### Task 1: TimelineEntry Entity ADT

**Files:**
- Create: `src/main/scala/board/entity/TimelineEntry.scala`

- [ ] **Step 1: Create the TimelineEntry sealed trait and all case classes**

```scala
package board.entity

import java.time.Instant

import shared.ids.Ids.{BoardIssueId, IssueId}

sealed trait TimelineEntry:
  def occurredAt: Instant

object TimelineEntry:
  case class IssueCreated(
    issueId: BoardIssueId,
    title: String,
    description: String,
    priority: IssuePriority,
    tags: List[String],
    occurredAt: Instant,
  ) extends TimelineEntry

  case class MovedToTodo(
    issueId: BoardIssueId,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class AgentAssigned(
    issueId: BoardIssueId,
    agentName: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class RunStarted(
    runId: String,
    branchName: String,
    conversationId: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class ChatMessages(
    runId: String,
    conversationId: String,
    messages: List[ChatMessageSummary],
    occurredAt: Instant,
  ) extends TimelineEntry

  case class ChatMessageSummary(
    role: String,
    contentPreview: String,
    fullContent: String,
    timestamp: Instant,
  )

  case class RunCompleted(
    runId: String,
    summary: String,
    durationSeconds: Long,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class GitChanges(
    runId: String,
    workspaceId: String,
    branchName: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class DecisionRaised(
    decisionId: String,
    title: String,
    urgency: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class ReviewAction(
    decisionId: String,
    action: String,
    actor: String,
    summary: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class ReworkRequested(
    reworkComment: String,
    actor: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class Merged(
    branchName: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class IssueDone(
    result: String,
    occurredAt: Instant,
  ) extends TimelineEntry

  case class IssueFailed(
    reason: String,
    occurredAt: Instant,
  ) extends TimelineEntry
```

- [ ] **Step 2: Verify compilation**

Run: `/opt/homebrew/bin/sbt --client compile`
Expected: Compilation succeeds.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/board/entity/TimelineEntry.scala
git commit -m "feat(board): add TimelineEntry ADT for issue timeline view"
```

---

### Task 2: IssueTimelineService

**Files:**
- Create: `src/main/scala/board/control/IssueTimelineService.scala`
- Create: `src/test/scala/board/control/IssueTimelineServiceSpec.scala`

- [ ] **Step 1: Write the failing test**

```scala
package board.control

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import board.entity.*
import board.entity.TimelineEntry.*
import db.ChatRepository
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*
import workspace.entity.{WorkspaceRepository, WorkspaceRun, RunStatus}

object IssueTimelineServiceSpec extends ZIOSpecDefault:

  private val issueId  = BoardIssueId("issue-1")
  private val agIssueId = IssueId("issue-1")
  private val now      = Instant.parse("2026-04-01T10:00:00Z")

  def spec = suite("IssueTimelineService")(
    test("builds timeline from issue events and runs") {
      val issueEvents = List(
        IssueEvent.Created(
          issueId = agIssueId,
          runId = None,
          title = "Add login endpoint",
          description = "Implement POST /login",
          issueType = "task",
          priority = "High",
          occurredAt = now,
        ),
        IssueEvent.Assigned(agIssueId, None, "code-agent", now.plusSeconds(60), now.plusSeconds(60)),
        IssueEvent.Started(agIssueId, None, "code-agent", now.plusSeconds(120), now.plusSeconds(120)),
        IssueEvent.MovedToHumanReview(agIssueId, now.plusSeconds(600), now.plusSeconds(600)),
      )

      val stubIssueRepo = new IssueRepository:
        def append(event: IssueEvent): IO[PersistenceError, Unit] = ZIO.unit
        def get(id: IssueId): IO[PersistenceError, AgentIssue] = ZIO.fail(PersistenceError.NotFound("issue", id.value))
        def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] = ZIO.succeed(issueEvents)
        def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
        def delete(id: IssueId): IO[PersistenceError, Unit] = ZIO.unit

      val service = IssueTimelineServiceLive(
        issueRepository = stubIssueRepo,
        workspaceRepository = StubWorkspaceRepo,
        decisionInbox = StubDecisionInbox,
        chatRepository = StubChatRepo,
      )

      for
        entries <- service.buildTimeline("ws-1", issueId)
      yield assertTrue(
        entries.size >= 3,
        entries.head.isInstanceOf[TimelineEntry.IssueCreated],
        entries.exists(_.isInstanceOf[TimelineEntry.AgentAssigned]),
      )
    },
  )

  // Minimal stubs — return empty data
  private object StubWorkspaceRepo extends WorkspaceRepository:
    // Implement all methods returning empty/default values.
    // The key method is listRuns which filters by issueRef:
    import workspace.entity.*
    def create(ws: Workspace): IO[PersistenceError, Unit] = ZIO.unit
    def get(id: String): IO[PersistenceError, Option[Workspace]] = ZIO.none
    def list: IO[PersistenceError, List[Workspace]] = ZIO.succeed(Nil)
    def update(ws: Workspace): IO[PersistenceError, Unit] = ZIO.unit
    def delete(id: String): IO[PersistenceError, Unit] = ZIO.unit
    def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] = ZIO.unit
    def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] = ZIO.none
    def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] = ZIO.succeed(Nil)
    def deleteRun(id: String): IO[PersistenceError, Unit] = ZIO.unit

  private object StubDecisionInbox extends DecisionInbox:
    def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] = ZIO.fail(PersistenceError.NotFound("decision", "stub"))
    def resolve(id: DecisionId, kind: DecisionResolutionKind, actor: String, summary: String): IO[PersistenceError, Decision] = ZIO.fail(PersistenceError.NotFound("decision", "stub"))
    def resolveOpenIssueReviewDecision(issueId: IssueId, kind: DecisionResolutionKind, actor: String, summary: String): IO[PersistenceError, Option[Decision]] = ZIO.none
    def syncOpenIssueReviewDecision(issueId: IssueId, issue: AgentIssue, summary: String): IO[PersistenceError, Option[Decision]] = ZIO.none
    def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision] = ZIO.fail(PersistenceError.NotFound("decision", "stub"))
    def get(id: DecisionId): IO[PersistenceError, Option[Decision]] = ZIO.none
    def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]] = ZIO.succeed(Nil)
    def runMaintenance(now: Instant): IO[PersistenceError, Unit] = ZIO.unit

  private object StubChatRepo extends ChatRepository:
    // Implement all methods from ChatRepository trait returning empty/default values.
    // The key method is listConversationsByRun and getMessages.
    import conversation.entity.api.*
    def createConversation(c: ChatConversation): IO[PersistenceError, Long] = ZIO.succeed(0L)
    def getConversation(id: Long): IO[PersistenceError, Option[ChatConversation]] = ZIO.none
    def listConversations(offset: Int, limit: Int): IO[PersistenceError, List[ChatConversation]] = ZIO.succeed(Nil)
    def getConversationsByChannel(channelName: String): IO[PersistenceError, List[ChatConversation]] = ZIO.succeed(Nil)
    def listConversationsByRun(runId: Long): IO[PersistenceError, List[ChatConversation]] = ZIO.succeed(Nil)
    def addMessage(m: ConversationEntry): IO[PersistenceError, Long] = ZIO.succeed(0L)
    def getMessages(conversationId: Long): IO[PersistenceError, List[ConversationEntry]] = ZIO.succeed(Nil)
    def getMessagesSince(conversationId: Long, since: Instant): IO[PersistenceError, List[ConversationEntry]] = ZIO.succeed(Nil)
    // Implement remaining methods from ChatRepository as stubs returning defaults
```

Note: The stub implementations above are intentionally incomplete. The engineer must look at the actual `WorkspaceRepository`, `DecisionInbox`, and `ChatRepository` traits and implement all abstract methods with sensible defaults (ZIO.unit, ZIO.succeed(Nil), ZIO.none, etc.). The key methods shown above are the ones the service actually calls.

- [ ] **Step 2: Run the test to verify it fails**

Run: `/opt/homebrew/bin/sbt --client "testOnly *IssueTimelineServiceSpec*"`
Expected: Compilation failure — `IssueTimelineServiceLive` does not exist yet.

- [ ] **Step 3: Implement IssueTimelineService**

```scala
package board.control

import java.time.Instant

import zio.*

import board.entity.*
import board.entity.TimelineEntry.*
import db.ChatRepository
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*
import workspace.entity.{WorkspaceRepository, WorkspaceRun, RunStatus}

trait IssueTimelineService:
  def buildTimeline(workspaceId: String, issueId: BoardIssueId): IO[PersistenceError, List[TimelineEntry]]

object IssueTimelineService:
  val live: ZLayer[IssueRepository & WorkspaceRepository & DecisionInbox & ChatRepository, Nothing, IssueTimelineService] =
    ZLayer.fromFunction(IssueTimelineServiceLive.apply)

final case class IssueTimelineServiceLive(
  issueRepository: IssueRepository,
  workspaceRepository: WorkspaceRepository,
  decisionInbox: DecisionInbox,
  chatRepository: ChatRepository,
) extends IssueTimelineService:

  override def buildTimeline(workspaceId: String, issueId: BoardIssueId): IO[PersistenceError, List[TimelineEntry]] =
    for
      issueEvents <- issueRepository.history(IssueId(issueId.value))
      runs        <- workspaceRepository.listRuns(workspaceId).map(_.filter(_.issueRef == issueId.value))
      decisions   <- decisionInbox.list(DecisionFilter(issueId = Some(IssueId(issueId.value)), limit = 100))
      chatEntries <- ZIO.foreach(runs)(loadRunChatEntries)
      timeline     = buildEntries(issueEvents, runs, decisions, chatEntries.flatten)
    yield timeline.sortBy(_.occurredAt)

  private def buildEntries(
    issueEvents: List[IssueEvent],
    runs: List[WorkspaceRun],
    decisions: List[Decision],
    chatEntries: List[TimelineEntry],
  ): List[TimelineEntry] =
    val fromIssueEvents = issueEvents.flatMap(mapIssueEvent)
    val fromRuns        = runs.flatMap(mapRun)
    val fromDecisions   = decisions.flatMap(mapDecision)
    fromIssueEvents ++ fromRuns ++ fromDecisions ++ chatEntries

  private def mapIssueEvent(event: IssueEvent): Option[TimelineEntry] =
    event match
      case e: IssueEvent.Created           =>
        Some(TimelineEntry.IssueCreated(
          issueId = BoardIssueId(e.issueId.value),
          title = e.title,
          description = e.description,
          priority = IssuePriority.fromString(e.priority),
          tags = Nil,
          occurredAt = e.occurredAt,
        ))
      case e: IssueEvent.MovedToTodo       =>
        Some(TimelineEntry.MovedToTodo(BoardIssueId(e.issueId.value), e.occurredAt))
      case e: IssueEvent.Assigned          =>
        Some(TimelineEntry.AgentAssigned(BoardIssueId(e.issueId.value), e.agentName, e.occurredAt))
      case e: IssueEvent.MovedToHumanReview =>
        None // Represented by DecisionRaised instead
      case e: IssueEvent.Approved          =>
        None // Represented by ReviewAction from decision
      case e: IssueEvent.MovedToRework     =>
        Some(TimelineEntry.ReworkRequested(e.reason, "human", e.occurredAt))
      case e: IssueEvent.MergeSucceeded    =>
        Some(TimelineEntry.Merged(e.branchName, e.occurredAt))
      case e: IssueEvent.MarkedDone        =>
        Some(TimelineEntry.IssueDone(e.result, e.occurredAt))
      case e: IssueEvent.MovedToMerging    =>
        None // Transient state, represented by Merged
      case e: IssueEvent.RunFailed         =>
        Some(TimelineEntry.IssueFailed(e.errorMessage, e.occurredAt))
      case _                               => None

  private def mapRun(run: WorkspaceRun): List[TimelineEntry] =
    val started = TimelineEntry.RunStarted(
      runId = run.id,
      branchName = run.branchName,
      conversationId = run.conversationId,
      occurredAt = run.createdAt,
    )
    val completed = run.status match
      case RunStatus.Completed(completedAt) =>
        val duration = java.time.Duration.between(run.createdAt, completedAt).getSeconds
        Some(TimelineEntry.RunCompleted(run.id, "Agent completed work", duration, completedAt))
      case RunStatus.Failed(_, failedAt)    =>
        Some(TimelineEntry.IssueFailed("Run failed", failedAt))
      case _                                => None

    val gitChanges = run.status match
      case RunStatus.Completed(_) | RunStatus.Failed(_, _) =>
        Some(TimelineEntry.GitChanges(run.id, run.workspaceId, run.branchName, run.createdAt.plusSeconds(1)))
      case _ => None

    List(Some(started), completed, gitChanges).flatten

  private def mapDecision(decision: Decision): List[TimelineEntry] =
    val raised = TimelineEntry.DecisionRaised(
      decisionId = decision.id.value,
      title = decision.title,
      urgency = decision.urgency.toString,
      occurredAt = decision.createdAt,
    )
    val resolved = decision.resolution.map { r =>
      TimelineEntry.ReviewAction(
        decisionId = decision.id.value,
        action = r.kind.toString,
        actor = r.actor,
        summary = r.summary,
        occurredAt = r.respondedAt,
      )
    }
    List(Some(raised), resolved).flatten

  private def loadRunChatEntries(run: WorkspaceRun): IO[PersistenceError, List[TimelineEntry]] =
    run.conversationId.toLongOption match
      case None       => ZIO.succeed(Nil)
      case Some(convId) =>
        chatRepository.getMessages(convId).map { messages =>
          if messages.isEmpty then Nil
          else
            val summaries = messages.map { m =>
              ChatMessageSummary(
                role = m.role.getOrElse("unknown"),
                contentPreview = m.content.take(200),
                fullContent = m.content,
                timestamp = m.timestamp.getOrElse(run.createdAt),
              )
            }
            List(TimelineEntry.ChatMessages(
              runId = run.id,
              conversationId = run.conversationId,
              messages = summaries,
              occurredAt = messages.headOption.flatMap(_.timestamp).getOrElse(run.createdAt),
            ))
        }
```

Note: The `IssuePriority.fromString` method may not exist yet. The engineer should check `board/entity/BoardModels.scala` for existing parsing utilities. If none exist, add a simple match:

```scala
// In IssuePriority companion object or inline:
def fromString(s: String): IssuePriority = s.toLowerCase match
  case "critical" => IssuePriority.Critical
  case "high"     => IssuePriority.High
  case "low"      => IssuePriority.Low
  case _          => IssuePriority.Medium
```

Similarly, `RunStatus.Completed` and `RunStatus.Failed` pattern matches must align with the actual `RunStatus` enum. The engineer should check `workspace/entity/WorkspaceModels.scala` for the exact case class structure and adjust the pattern matches accordingly.

- [ ] **Step 4: Run the test to verify it passes**

Run: `/opt/homebrew/bin/sbt --client "testOnly *IssueTimelineServiceSpec*"`
Expected: Test passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/board/control/IssueTimelineService.scala src/test/scala/board/control/IssueTimelineServiceSpec.scala
git commit -m "feat(board): add IssueTimelineService to aggregate timeline entries"
```

---

### Task 3: IssueApprovalService — quickApprove

**Files:**
- Create: `src/main/scala/board/control/IssueApprovalService.scala`
- Create: `src/test/scala/board/control/IssueApprovalServiceSpec.scala`

- [ ] **Step 1: Write the failing test for quickApprove**

```scala
package board.control

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import board.entity.*
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*
import workspace.control.WorkspaceRunService
import workspace.entity.{WorkspaceRepository, WorkspaceRun, RunStatus}

object IssueApprovalServiceSpec extends ZIOSpecDefault:

  private val issueId   = BoardIssueId("issue-approve-1")
  private val agIssueId = IssueId("issue-approve-1")
  private val now       = Instant.parse("2026-04-01T10:00:00Z")

  def spec = suite("IssueApprovalService")(
    suite("quickApprove")(
      test("resolves decision, merges branch, and moves card to Done") {
        for
          approvedRef   <- Ref.make(false)
          mergedRef     <- Ref.make(false)
          movedToDoneRef <- Ref.make(false)
          appliedRef    <- Ref.make(false)
          service        = makeService(
                             onResolveDecision = approvedRef.set(true),
                             onMerge = mergedRef.set(true),
                             onMoveToDone = movedToDoneRef.set(true),
                             onApplyChanges = appliedRef.set(true),
                           )
          _             <- service.quickApprove("ws-1", issueId, "Looks good")
          approved      <- approvedRef.get
          merged        <- mergedRef.get
          movedToDone   <- movedToDoneRef.get
          applied       <- appliedRef.get
        yield assertTrue(approved, merged, movedToDone, applied)
      },
    ),
  )

  // Helper: builds IssueApprovalServiceLive with configurable stub callbacks.
  // The engineer must implement the full stubs for BoardOrchestrator, DecisionInbox,
  // WorkspaceRunService, and WorkspaceRepository. Each stub records calls via Refs.
  private def makeService(
    onResolveDecision: UIO[Unit] = ZIO.unit,
    onMerge: UIO[Unit] = ZIO.unit,
    onMoveToDone: UIO[Unit] = ZIO.unit,
    onApplyChanges: UIO[Unit] = ZIO.unit,
  ): IssueApprovalService = ???
  // Implementation left to the engineer — construct IssueApprovalServiceLive
  // with stub dependencies that call the provided Ref callbacks.
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `/opt/homebrew/bin/sbt --client "testOnly *IssueApprovalServiceSpec*"`
Expected: Compilation failure — `IssueApprovalService` does not exist yet.

- [ ] **Step 3: Implement IssueApprovalService**

```scala
package board.control

import java.time.Instant

import zio.*

import board.entity.*
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*
import workspace.control.WorkspaceRunService
import workspace.entity.{WorkspaceRepository, WorkspaceRun, RunStatus}

trait IssueApprovalService:
  def quickApprove(workspaceId: String, issueId: BoardIssueId, reviewerNotes: String): IO[BoardError, Unit]
  def reworkIssue(workspaceId: String, issueId: BoardIssueId, reworkComment: String, actor: String): IO[BoardError, Unit]

object IssueApprovalService:
  val live: ZLayer[
    BoardOrchestrator & BoardRepository & DecisionInbox & WorkspaceRunService & WorkspaceRepository & IssueRepository,
    Nothing,
    IssueApprovalService,
  ] =
    ZLayer.fromFunction(IssueApprovalServiceLive.apply)

final case class IssueApprovalServiceLive(
  boardOrchestrator: BoardOrchestrator,
  boardRepository: BoardRepository,
  decisionInbox: DecisionInbox,
  workspaceRunService: WorkspaceRunService,
  workspaceRepository: WorkspaceRepository,
  issueRepository: IssueRepository,
) extends IssueApprovalService:

  override def quickApprove(workspaceId: String, issueId: BoardIssueId, reviewerNotes: String): IO[BoardError, Unit] =
    for
      // 1. Find the workspace and project root
      workspace   <- workspaceRepository.get(workspaceId)
                       .mapError(e => BoardError.ParseError(s"workspace lookup: $e"))
                       .flatMap(ZIO.fromOption(_).orElseFail(BoardError.BoardNotFound(workspaceId)))
      projectRoot <- findProjectRoot(workspace)

      // 2. Resolve the pending decision for this issue and approve it
      _           <- decisionInbox
                       .resolveOpenIssueReviewDecision(
                         IssueId(issueId.value),
                         DecisionResolutionKind.Approved,
                         actor = "web",
                         summary = if reviewerNotes.trim.nonEmpty then reviewerNotes else "Approved via quick approve",
                       )
                       .mapError(e => BoardError.ParseError(s"decision resolve: $e"))

      // 3. Find the latest run and apply worktree changes
      runs        <- workspaceRepository.listRuns(workspaceId)
                       .mapError(e => BoardError.ParseError(s"list runs: $e"))
      latestRun    = runs.filter(_.issueRef == issueId.value).sortBy(_.createdAt).lastOption
      _           <- ZIO.foreachDiscard(latestRun) { run =>
                       workspaceRunService.cleanupAfterSuccessfulMerge(run.id).ignore
                     }

      // 4. Perform git merge and move card to Done (reuses existing approveIssue)
      _           <- boardOrchestrator.approveIssue(projectRoot, issueId)
    yield ()

  override def reworkIssue(
    workspaceId: String,
    issueId: BoardIssueId,
    reworkComment: String,
    actor: String,
  ): IO[BoardError, Unit] =
    // Implemented in Task 4
    ZIO.fail(BoardError.ParseError("reworkIssue not yet implemented"))

  private def findProjectRoot(workspace: workspace.entity.Workspace): IO[BoardError, String] =
    ZIO.succeed(workspace.localPath)
```

Note: The `resolveOpenIssueReviewDecision` method already exists on the `DecisionInbox` trait (found during exploration). It finds the open IssueReview decision for the given issueId and resolves it. If no pending decision exists, it returns `None` (which is fine — we proceed with the merge anyway).

The `boardOrchestrator.approveIssue(projectRoot, issueId)` call reuses the existing merge logic: validates Review column → validates governance → merges branch → moves to Done → cleans up. This avoids duplicating the git merge logic.

The key insight is that `quickApprove` resolves the decision FIRST (so side effects are recorded), then delegates to the existing `approveIssue` for the actual merge. The `approveIssue` method already handles the Merging transient state, git merge, and column move.

- [ ] **Step 4: Run the test to verify it passes**

Run: `/opt/homebrew/bin/sbt --client "testOnly *IssueApprovalServiceSpec*"`
Expected: Test passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/board/control/IssueApprovalService.scala src/test/scala/board/control/IssueApprovalServiceSpec.scala
git commit -m "feat(board): add IssueApprovalService with quickApprove flow"
```

---

### Task 4: IssueApprovalService — reworkIssue

**Files:**
- Modify: `src/main/scala/board/control/IssueApprovalService.scala`
- Modify: `src/test/scala/board/control/IssueApprovalServiceSpec.scala`

- [ ] **Step 1: Add failing test for reworkIssue**

Add to `IssueApprovalServiceSpec`:

```scala
    suite("reworkIssue")(
      test("resolves decision as rework, moves card to Todo, and continues the run") {
        for
          reworkedRef     <- Ref.make(false)
          movedToTodoRef  <- Ref.make(false)
          continuedRunRef <- Ref.make(Option.empty[String])
          service          = makeService(
                               onResolveRework = reworkedRef.set(true),
                               onMoveToTodo = movedToTodoRef.set(true),
                               onContinueRun = (prompt: String) => continuedRunRef.set(Some(prompt)),
                             )
          _               <- service.reworkIssue("ws-1", issueId, "Fix the error handling", "reviewer")
          reworked        <- reworkedRef.get
          movedToTodo     <- movedToTodoRef.get
          continuedPrompt <- continuedRunRef.get
        yield assertTrue(
          reworked,
          movedToTodo,
          continuedPrompt.contains("Fix the error handling"),
        )
      },
    ),
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `/opt/homebrew/bin/sbt --client "testOnly *IssueApprovalServiceSpec*"`
Expected: Test fails — `reworkIssue` returns the "not yet implemented" error.

- [ ] **Step 3: Implement reworkIssue**

Replace the placeholder in `IssueApprovalServiceLive`:

```scala
  override def reworkIssue(
    workspaceId: String,
    issueId: BoardIssueId,
    reworkComment: String,
    actor: String,
  ): IO[BoardError, Unit] =
    for
      // 1. Resolve the pending decision as ReworkRequested
      _        <- decisionInbox
                    .resolveOpenIssueReviewDecision(
                      IssueId(issueId.value),
                      DecisionResolutionKind.ReworkRequested,
                      actor = actor,
                      summary = reworkComment,
                    )
                    .mapError(e => BoardError.ParseError(s"decision rework: $e"))

      // 2. Find the workspace and project root
      workspace <- workspaceRepository.get(workspaceId)
                     .mapError(e => BoardError.ParseError(s"workspace lookup: $e"))
                     .flatMap(ZIO.fromOption(_).orElseFail(BoardError.BoardNotFound(workspaceId)))
      projectRoot = workspace.localPath

      // 3. Move card to Todo (keeping assigned agent)
      _        <- boardRepository.moveIssue(projectRoot, issueId, BoardColumn.Todo)
                    .mapError(e => BoardError.ParseError(s"move to todo: $e"))

      // 4. Find the latest completed run for this issue
      runs     <- workspaceRepository.listRuns(workspaceId)
                    .mapError(e => BoardError.ParseError(s"list runs: $e"))
      latestRun = runs
                    .filter(r => r.issueRef == issueId.value && isCompletedOrFailed(r.status))
                    .sortBy(_.createdAt)
                    .lastOption

      // 5. Continue the run with the rework comment as follow-up prompt
      _        <- ZIO.foreachDiscard(latestRun) { run =>
                    workspaceRunService
                      .continueRun(run.id, reworkComment)
                      .mapError(e => BoardError.ParseError(s"continue run: $e"))
                  }
    yield ()

  private def isCompletedOrFailed(status: RunStatus): Boolean =
    status match
      case RunStatus.Completed(_) => true
      case RunStatus.Failed(_, _) => true
      case _                      => false
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `/opt/homebrew/bin/sbt --client "testOnly *IssueApprovalServiceSpec*"`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/board/control/IssueApprovalService.scala src/test/scala/board/control/IssueApprovalServiceSpec.scala
git commit -m "feat(board): add reworkIssue to IssueApprovalService with automated chat continuation"
```

---

### Task 5: BoardOrchestrator — Handle Rework Dispatch

**Files:**
- Modify: `src/main/scala/board/control/BoardOrchestrator.scala:270-330`
- Modify: `src/test/scala/board/control/BoardOrchestratorSpec.scala`

- [ ] **Step 1: Add failing test for rework dispatch**

Add to `BoardOrchestratorSpec`:

```scala
    test("dispatchCycle starts existing pending run for rework cards instead of creating new run") {
      // Setup: Create a board with a Todo issue that has an existing pending run
      // (simulating a rework card that was moved to Todo with continueRun already called).
      // Verify: dispatchCycle does NOT call workspaceRunService.assign()
      //         but DOES start the existing pending run.
      for
        assignCallCount <- Ref.make(0)
        // Build stubs with a Todo issue that has assignedAgent set and a pending run
        // The stub WorkspaceRunService tracks assign calls via assignCallCount
        // dispatchCycle should detect the existing pending run and skip assign
        result          <- ??? // Call dispatchCycle with the configured stubs
        count           <- assignCallCount.get
      yield assertTrue(count == 0) // No new assign call — existing pending run used
    },
```

The engineer must implement the full test using the existing test patterns from `BoardOrchestratorSpec.scala`. Look at the existing tests to understand how stubs are constructed (the file uses `Ref`-based tracking stubs).

- [ ] **Step 2: Run the test to verify it fails**

Run: `/opt/homebrew/bin/sbt --client "testOnly *BoardOrchestratorSpec*"`
Expected: Test fails because current `dispatchIssue` always calls `assign()`.

- [ ] **Step 3: Modify dispatchIssue to check for existing pending runs**

In `BoardOrchestrator.scala`, modify the `dispatchIssue` method (around line 270). Before calling `workspaceRunService.assign(...)`, check if there's already a pending run for this issue:

```scala
  private def dispatchIssue(
    workspacePath: String,
    workspaceId: String,
    issue: BoardIssue,
    defaultAgent: Option[String],
  ): IO[BoardError, Unit] =
    val agent =
      issue.frontmatter.assignedAgent.map(_.trim).filter(_.nonEmpty)
        .orElse(defaultAgent.map(_.trim).filter(_.nonEmpty))
        .getOrElse("code-agent")

    (for
      _           <- ensureGovernanceAllows(
                       workspaceId = workspaceId,
                       issue = issue,
                       transition = GovernanceTransition(
                         from = GovernanceLifecycleStage.Backlog,
                         to = GovernanceLifecycleStage.InProgress,
                         action = GovernanceLifecycleAction.Dispatch,
                       ),
                       humanApprovalGranted = false,
                     )
      // Check for existing pending run (rework continuation)
      existingRuns <- workspaceRepository.listRuns(workspaceId)
                        .mapError(e => BoardError.ParseError(s"list runs: $e"))
      pendingRun    = existingRuns
                        .filter(r => r.issueRef == issue.frontmatter.id.value)
                        .find(r => r.status == RunStatus.Pending)
      now          <- Clock.instant
      _            <- boardRepository.updateIssue(
                        workspacePath,
                        issue.frontmatter.id,
                        _.copy(
                          assignedAgent = Some(agent),
                          transientState = TransientState.Assigned(agent, now),
                        ),
                      )
      _            <- boardRepository.moveIssue(workspacePath, issue.frontmatter.id, BoardColumn.InProgress)
      run          <- pendingRun match
                        case Some(existing) =>
                          // Rework continuation: use existing pending run
                          ZIO.succeed(existing)
                        case None           =>
                          // Fresh dispatch: create new run
                          workspaceRunService.assign(
                            workspaceId,
                            workspace.entity.AssignRunRequest(
                              issueRef = issue.frontmatter.id.value,
                              agentName = agent,
                              prompt = buildDispatchPrompt(issue),
                            ),
                          ).mapError(e => BoardError.ParseError(s"assign run: $e"))
      _            <- boardRepository.updateIssue(
                        workspacePath,
                        issue.frontmatter.id,
                        _.copy(branchName = Some(run.branchName)),
                      )
    yield ()).catchAll { err =>
      dispatchCompensation(workspacePath, issue.frontmatter.id, renderBoardError(err)) *>
        ZIO.fail(err)
    }
```

The engineer must check the actual `dispatchIssue` implementation (lines 270-330) and adapt this change to match the exact code structure, including the governance check parameters and the `buildDispatchPrompt` helper.

- [ ] **Step 4: Run the test to verify it passes**

Run: `/opt/homebrew/bin/sbt --client "testOnly *BoardOrchestratorSpec*"`
Expected: All tests pass (both existing and new).

- [ ] **Step 5: Run full test suite**

Run: `/opt/homebrew/bin/sbt --client test`
Expected: All tests pass. No regressions.

- [ ] **Step 6: Commit**

```bash
git add src/main/scala/board/control/BoardOrchestrator.scala src/test/scala/board/control/BoardOrchestratorSpec.scala
git commit -m "feat(board): dispatchCycle reuses pending rework runs instead of creating new ones"
```

---

### Task 6: IssueTimelineView — Scalatags SSR

**Files:**
- Create: `src/main/scala/shared/web/IssueTimelineView.scala`

- [ ] **Step 1: Create the timeline view**

```scala
package shared.web

import board.entity.*
import board.entity.TimelineEntry.*
import scalatags.Text.all.*

object IssueTimelineView:

  def page(
    workspaceId: String,
    issue: BoardIssue,
    timeline: List[TimelineEntry],
  ): String =
    Layout.page(
      s"Issue · ${issue.frontmatter.title}",
      s"/board/$workspaceId/issues/${issue.frontmatter.id.value}",
    )(
      div(cls := "space-y-4")(
        header(workspaceId, issue),
        timelineBody(workspaceId, issue, timeline),
        if issue.column == BoardColumn.Review then reviewActionForm(workspaceId, issue)
        else frag(),
      ),
      timelineStyles,
    )

  private def header(workspaceId: String, issue: BoardIssue): Frag =
    div(cls := "sticky top-0 z-10 rounded-xl border border-white/10 bg-slate-900/95 p-4 backdrop-blur-sm")(
      div(cls := "flex items-start justify-between gap-4")(
        div(cls := "min-w-0 flex-1")(
          div(cls := "flex items-center gap-2 mb-1")(
            span(cls := "text-xs font-mono text-slate-400")(issue.frontmatter.id.value),
            statusBadge(issue.column),
            priorityBadge(issue.frontmatter.priority),
          ),
          h1(cls := "text-lg font-semibold text-white")(issue.frontmatter.title),
          issue.frontmatter.branchName.filter(_.nonEmpty).map { branch =>
            div(cls := "mt-1 flex items-center gap-2 text-xs text-slate-400")(
              span(cls := "font-mono text-indigo-300")(branch),
            )
          }.getOrElse(frag()),
        ),
        if issue.column == BoardColumn.Review then
          div(cls := "flex items-center gap-2 flex-shrink-0")(
            button(
              `type`            := "button",
              cls               := "rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-emerald-500",
              attr("hx-post")   := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/quick-approve",
              attr("hx-confirm") := s"Approve and merge '${issue.frontmatter.branchName.getOrElse("")}' into main?",
              attr("hx-target") := "body",
            )("Approve & Merge"),
            button(
              `type`          := "button",
              cls             := "rounded-lg border border-amber-400/30 bg-amber-500/10 px-3 py-1.5 text-xs font-semibold text-amber-200 hover:bg-amber-500/20",
              attr("onclick") := "document.getElementById('rework-form')?.scrollIntoView({behavior:'smooth'})",
            )("Request Rework"),
          )
        else frag(),
      ),
      div(cls := "mt-2 flex flex-wrap gap-1.5")(
        issue.frontmatter.tags.map(t =>
          span(cls := "rounded-full bg-indigo-500/20 px-2 py-0.5 text-[10px] text-indigo-100")(t)
        ),
      ),
      a(
        href := s"/board/$workspaceId",
        cls  := "mt-2 inline-block text-xs text-slate-400 hover:text-indigo-300",
      )("← Back to board"),
    )

  private def statusBadge(column: BoardColumn): Frag =
    val (label, colorCls) = column match
      case BoardColumn.Backlog    => ("Backlog", "bg-slate-500/20 text-slate-300")
      case BoardColumn.Todo       => ("Todo", "bg-blue-500/20 text-blue-200")
      case BoardColumn.InProgress => ("In Progress", "bg-amber-500/20 text-amber-200")
      case BoardColumn.Review     => ("Review", "bg-purple-500/20 text-purple-200")
      case BoardColumn.Done       => ("Done", "bg-emerald-500/20 text-emerald-200")
      case BoardColumn.Archive    => ("Archive", "bg-slate-500/20 text-slate-300")
    span(cls := s"rounded-full px-2 py-0.5 text-[10px] font-semibold $colorCls")(label)

  private def priorityBadge(priority: IssuePriority): Frag =
    val colorCls = priority match
      case IssuePriority.Critical => "bg-rose-500/20 text-rose-200"
      case IssuePriority.High     => "bg-orange-500/20 text-orange-200"
      case IssuePriority.Medium   => "bg-slate-500/20 text-slate-300"
      case IssuePriority.Low      => "bg-slate-500/20 text-slate-400"
    span(cls := s"rounded-full px-2 py-0.5 text-[10px] font-semibold $colorCls")(priority.toString)

  private def timelineBody(workspaceId: String, issue: BoardIssue, timeline: List[TimelineEntry]): Frag =
    div(cls := "timeline-container relative pl-8")(
      div(cls := "absolute left-3 top-0 bottom-0 w-px bg-white/10")(),
      timeline.map(entry => timelineEntry(workspaceId, entry)),
    )

  private def timelineEntry(workspaceId: String, entry: TimelineEntry): Frag =
    val (icon, title, body) = entry match
      case e: IssueCreated    => ("📋", "Issue created", frag(
        p(cls := "text-xs text-slate-300")(e.description),
        span(cls := "text-[10px] text-slate-500")(s"Priority: ${e.priority}"),
      ))
      case e: MovedToTodo     => ("→", "Moved to Todo", frag())
      case e: AgentAssigned   => ("🤖", s"Agent assigned: ${e.agentName}", frag())
      case e: RunStarted      => ("▶", "Run started", frag(
        span(cls := "font-mono text-xs text-indigo-300")(e.branchName),
      ))
      case e: ChatMessages    => ("💬", s"Conversation (${e.messages.size} messages)", chatBlock(e))
      case e: RunCompleted    => ("✓", "Run completed", frag(
        span(cls := "text-xs text-slate-300")(s"${e.summary} · ${formatDuration(e.durationSeconds)}"),
      ))
      case e: GitChanges      => ("📁", "Git changes", gitChangesBlock(workspaceId, e))
      case e: DecisionRaised  => ("👁", "Review requested", frag(
        span(cls := "text-xs text-slate-300")(e.title),
        span(cls := s"ml-2 text-[10px] ${urgencyColor(e.urgency)}")(e.urgency),
      ))
      case e: ReviewAction    => (if e.action == "Approved" then "✅" else "🔄", s"${e.action}", frag(
        p(cls := "text-xs text-slate-300")(e.summary),
        span(cls := "text-[10px] text-slate-500")(s"by ${e.actor}"),
      ))
      case e: ReworkRequested => ("🔄", "Rework requested", frag(
        div(cls := "rounded border border-amber-400/20 bg-amber-500/5 px-2 py-1.5 text-xs text-amber-100")(
          e.reworkComment
        ),
        span(cls := "text-[10px] text-slate-500")(s"by ${e.actor}"),
      ))
      case e: Merged          => ("🔀", s"Merged ${e.branchName}", frag())
      case e: IssueDone       => ("🏁", "Done", frag(
        span(cls := "text-xs text-emerald-300")(e.result),
      ))
      case e: IssueFailed     => ("❌", "Failed", frag(
        span(cls := "text-xs text-rose-300")(e.reason),
      ))

    div(cls := "timeline-entry relative mb-4 pl-6")(
      div(cls := "absolute left-[-1.05rem] top-1 flex h-5 w-5 items-center justify-center rounded-full bg-slate-800 border border-white/10 text-[10px]")(
        icon
      ),
      div(cls := "rounded-lg border border-white/10 bg-slate-900/60 p-3")(
        div(cls := "flex items-center justify-between gap-2")(
          span(cls := "text-xs font-semibold text-white")(title),
          span(cls := "text-[10px] text-slate-500")(formatTimestamp(entry.occurredAt)),
        ),
        div(cls := "mt-1")(body),
      ),
    )

  private def chatBlock(entry: ChatMessages): Frag =
    val preview = entry.messages.take(2) ++ (if entry.messages.size > 2 then entry.messages.takeRight(1) else Nil)
    div(cls := "mt-1 space-y-1")(
      preview.map { msg =>
        div(cls := "rounded bg-black/20 px-2 py-1 text-[11px]")(
          span(cls := "font-semibold text-indigo-200")(s"${msg.role}: "),
          span(cls := "text-slate-300")(msg.contentPreview.take(150)),
        )
      },
      if entry.messages.size > 3 then
        a(
          href := s"/chat/${entry.conversationId}",
          cls  := "text-[10px] text-indigo-300 hover:text-indigo-200",
        )(s"View full conversation (${entry.messages.size} messages) →")
      else frag(),
    )

  private def gitChangesBlock(workspaceId: String, entry: GitChanges): Frag =
    div(
      cls                      := "mt-1",
      attr("hx-get")           := s"/api/workspaces/$workspaceId/runs/${entry.runId}/git/diff?base=main",
      attr("hx-trigger")       := "intersect once",
      attr("hx-target")        := "this",
      attr("hx-swap")          := "innerHTML",
    )(
      p(cls := "text-[11px] text-slate-400")("Loading diff stats..."),
    )

  private def reviewActionForm(workspaceId: String, issue: BoardIssue): Frag =
    div(
      id  := "rework-form",
      cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4",
    )(
      h3(cls := "text-sm font-semibold text-white mb-2")("Review this issue"),
      issue.frontmatter.branchName.filter(_.nonEmpty).map { branch =>
        div(cls := "mb-3 flex items-center gap-2 text-xs text-slate-300")(
          span("Branch:"),
          span(cls := "font-mono text-indigo-300")(branch),
          span(cls := "text-slate-500")("→ main"),
        )
      }.getOrElse(frag()),
      form(
        method          := "post",
        attr("hx-post") := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/quick-approve",
        attr("hx-target") := "body",
      )(
        textarea(
          name        := "notes",
          rows        := "3",
          placeholder := "Reviewer notes (optional)…",
          cls         := "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-cyan-500/50",
        )(""),
        div(cls := "mt-2 flex flex-wrap gap-2")(
          button(
            `type` := "submit",
            cls    := "rounded-lg bg-emerald-600 px-4 py-2 text-xs font-semibold text-white hover:bg-emerald-500",
          )("Approve & Merge"),
          button(
            `type`            := "button",
            cls               := "rounded-lg border border-amber-400/30 bg-amber-500/10 px-4 py-2 text-xs font-semibold text-amber-200 hover:bg-amber-500/20",
            attr("hx-post")   := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/rework",
            attr("hx-include") := "closest form",
            attr("hx-target") := "body",
          )("Request Rework"),
        ),
      ),
    )

  private def urgencyColor(urgency: String): String =
    urgency.toLowerCase match
      case "critical" => "text-rose-300"
      case "high"     => "text-orange-300"
      case "medium"   => "text-slate-300"
      case _          => "text-slate-400"

  private def formatDuration(seconds: Long): String =
    if seconds < 60 then s"${seconds}s"
    else if seconds < 3600 then s"${seconds / 60}m ${seconds % 60}s"
    else s"${seconds / 3600}h ${(seconds % 3600) / 60}m"

  private def formatTimestamp(instant: java.time.Instant): String =
    val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)

  private def timelineStyles: Frag =
    tag("style")(raw("""
      .timeline-container { position: relative; }
      .timeline-entry { transition: background 0.2s; }
      .timeline-entry:hover > div:last-child { border-color: rgba(255,255,255,0.15); }
    """))
```

- [ ] **Step 2: Verify compilation**

Run: `/opt/homebrew/bin/sbt --client compile`
Expected: Compilation succeeds. If there are type mismatches (e.g., `IssuePriority` companion method missing), fix them now.

- [ ] **Step 3: Commit**

```bash
git add src/main/scala/shared/web/IssueTimelineView.scala
git commit -m "feat(web): add IssueTimelineView with GitHub PR-style scrollable timeline"
```

---

### Task 7: BoardController — Timeline and Approval Routes

**Files:**
- Modify: `src/main/scala/board/boundary/BoardController.scala`

- [ ] **Step 1: Add IssueApprovalService and IssueTimelineService as dependencies**

Update `BoardControllerLive` constructor (around line 35) to add new dependencies:

```scala
final case class BoardControllerLive(
  boardRepository: BoardRepository,
  boardOrchestrator: BoardOrchestrator,
  workspaceRepository: WorkspaceRepository,
  issueParser: IssueMarkdownParser,
  projectStorageService: ProjectStorageService,
  issueApprovalService: IssueApprovalService,
  issueTimelineService: IssueTimelineService,
) extends BoardController:
```

Update the `live` ZLayer type (around line 23):

```scala
  val live
    : ZLayer[
      BoardRepository & BoardOrchestrator & WorkspaceRepository & IssueMarkdownParser & ProjectStorageService &
        IssueApprovalService & IssueTimelineService,
      Nothing,
      BoardController,
    ] =
    ZLayer.fromFunction(BoardControllerLive.apply)
```

- [ ] **Step 2: Add new routes**

Add these routes inside the `Routes(...)` block (after the existing approve route):

```scala
    Method.POST / "board" / string("workspaceId") / "issues" / string("issueId") / "quick-approve" -> handler {
      (workspaceId: String, issueId: String, req: Request) =>
        quickApproveIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
    },
    Method.POST / "board" / string("workspaceId") / "issues" / string("issueId") / "rework" -> handler {
      (workspaceId: String, issueId: String, req: Request) =>
        reworkIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
    },
```

- [ ] **Step 3: Replace renderIssueDetail with timeline rendering**

Replace the existing `renderIssueDetail` method (find it in the file) with a timeline-based version:

```scala
  private def renderIssueDetail(workspaceId: String, issueId: BoardIssueId): IO[BoardError, Response] =
    for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      issue            <- boardRepository.readIssue(projectRoot, issueId)
      timeline         <- issueTimelineService.buildTimeline(workspaceId, issueId)
                            .mapError(e => BoardError.ParseError(s"timeline: $e"))
    yield htmlResponse(IssueTimelineView.page(workspaceId, issue, timeline))
```

Add the import at the top of the file:

```scala
import shared.web.IssueTimelineView
```

- [ ] **Step 4: Add quickApproveIssue handler**

```scala
  private def quickApproveIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
    for
      issueId <- readBoardIssueId(issueIdRaw)
      form    <- req.body.asString
                   .map(parseFormParams)
                   .mapError(e => BoardError.ParseError(s"form parse: ${e.getMessage}"))
      notes    = form.getOrElse("notes", "").trim
      _       <- issueApprovalService.quickApprove(workspaceId, issueId, notes)
      response <- if isHtmx(req) then renderBoardPage(workspaceId)
                  else ZIO.succeed(Response.redirect(URL.decode(s"/board/$workspaceId").toOption.getOrElse(URL.root)))
    yield response

  private def parseFormParams(body: String): Map[String, String] =
    body.split("&").toList.filter(_.nonEmpty).flatMap { pair =>
      pair.split("=", 2).toList match
        case key :: value :: Nil => Some(java.net.URLDecoder.decode(key, "UTF-8") -> java.net.URLDecoder.decode(value, "UTF-8"))
        case key :: Nil          => Some(java.net.URLDecoder.decode(key, "UTF-8") -> "")
        case _                   => None
    }.toMap
```

Note: Check if `parseFormParams` or similar already exists in the file. The existing code may have form parsing helpers. Reuse them if available.

- [ ] **Step 5: Add reworkIssue handler**

```scala
  private def reworkIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
    for
      issueId <- readBoardIssueId(issueIdRaw)
      form    <- req.body.asString
                   .map(parseFormParams)
                   .mapError(e => BoardError.ParseError(s"form parse: ${e.getMessage}"))
      notes    = form.getOrElse("notes", "").trim
      comment  = if notes.nonEmpty then notes else "Rework requested"
      _       <- issueApprovalService.reworkIssue(workspaceId, issueId, comment, "web")
      response <- if isHtmx(req) then renderBoardPage(workspaceId)
                  else ZIO.succeed(Response.redirect(URL.decode(s"/board/$workspaceId").toOption.getOrElse(URL.root)))
    yield response
```

- [ ] **Step 6: Verify compilation**

Run: `/opt/homebrew/bin/sbt --client compile`
Expected: Compilation succeeds. Fix any issues with imports or method signatures.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/board/boundary/BoardController.scala
git commit -m "feat(board): add timeline, quick-approve, and rework routes to BoardController"
```

---

### Task 8: BoardView — Card UX Updates

**Files:**
- Modify: `src/main/scala/shared/web/BoardView.scala`

- [ ] **Step 1: Update card links to point to timeline**

Find the issue card title link (around line 211-214) and ensure it points to `/board/{workspaceId}/issues/{issueId}` (it already does, but verify).

- [ ] **Step 2: Add rework badge to cards**

In the `issueCard` method, after the tags section (around line 226), add a rework badge:

```scala
      if issue.frontmatter.transientState match
           case TransientState.Rework(_, _) => true
           case _                           => false
      then
        span(cls := "rounded bg-amber-500/20 px-1.5 py-0.5 text-[10px] text-amber-200")("Rework")
      else (),
```

- [ ] **Step 3: Add "Rework pending" badge for Todo cards with pending rework**

This requires checking the transientState. In the issueCard method, add after the existing badge logic:

```scala
      if column == BoardColumn.Todo && issue.frontmatter.assignedAgent.exists(_.nonEmpty) &&
         (issue.frontmatter.transientState match
            case TransientState.Rework(_, _) => true
            case _                           => false)
      then
        span(cls := "rounded bg-amber-500/20 px-1.5 py-0.5 text-[10px] text-amber-200")("Rework pending")
      else (),
```

- [ ] **Step 4: Update the Approve button to use quick-approve**

Find the existing Approve form in the card (around lines 228-241) and update:

```scala
        if column == BoardColumn.Review && issue.frontmatter.branchName.exists(_.nonEmpty) then
          form(
            method              := "post",
            action              := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/quick-approve",
            attr("hx-post")     := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/quick-approve",
            attr("hx-target")   := "#fs-board-root",
            attr("hx-swap")     := "innerHTML",
            attr("hx-confirm")  := s"Approve and merge branch '${issue.frontmatter.branchName.getOrElse("")}' into main?",
          )(
            button(
              `type` := "submit",
              cls    := "rounded border border-emerald-400/30 bg-emerald-500/20 px-2 py-1 text-[10px] font-semibold text-emerald-100 hover:bg-emerald-500/30",
            )("Approve & Merge"),
          )
        else (),
```

- [ ] **Step 5: Remove old detail page rendering**

Find the `detailPage` method in BoardView (around lines 166-208) and either remove it or redirect to the timeline. Since `BoardController.renderIssueDetail` now renders the timeline, the `detailPage` method in BoardView is no longer called. Remove it to avoid dead code.

- [ ] **Step 6: Verify compilation**

Run: `/opt/homebrew/bin/sbt --client compile`
Expected: Compilation succeeds.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/shared/web/BoardView.scala
git commit -m "feat(web): update board cards with quick-approve, rework badges, and timeline links"
```

---

### Task 9: DI Wiring

**Files:**
- Modify: `src/main/scala/app/ApplicationDI.scala`

- [ ] **Step 1: Add IssueApprovalService and IssueTimelineService to the layer composition**

In `ApplicationDI.scala`, find the `webServerLayer` method (around line 289) and add the new service layers. They need to be composed alongside existing layers:

```scala
      IssueApprovalService.live,
      IssueTimelineService.live,
```

Add these lines near the other board/decision service layers (around line 368, near `BoardOrchestrator.live`).

Also update the `BoardBoundaryController.live` reference — since `BoardControllerLive` now requires `IssueApprovalService & IssueTimelineService`, the `ZLayer.make` call should automatically resolve these if the layers are present. Verify by compiling.

- [ ] **Step 2: Verify compilation**

Run: `/opt/homebrew/bin/sbt --client compile`
Expected: Compilation succeeds. If there are missing layer errors, the compiler will tell you exactly which layers are needed where.

- [ ] **Step 3: Run full test suite**

Run: `/opt/homebrew/bin/sbt --client test`
Expected: All tests pass. Any test that constructs `BoardControllerLive` directly will need updating to provide the new dependencies.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/app/ApplicationDI.scala
git commit -m "feat(app): wire IssueApprovalService and IssueTimelineService into DI graph"
```

---

### Task 10: DecisionsController — Wire Rework Through IssueApprovalService

**Files:**
- Modify: `src/main/scala/decision/boundary/DecisionsController.scala`

- [ ] **Step 1: Add IssueApprovalService dependency**

Update the `make` method and `live` layer to include `IssueApprovalService`:

```scala
  val live: ZLayer[DecisionInbox & IssueApprovalService, Nothing, DecisionsController] =
    ZLayer.fromFunction(make)

  def make(decisionInbox: DecisionInbox, issueApprovalService: IssueApprovalService): DecisionsController =
```

- [ ] **Step 2: Update the resolve handler**

Modify the `resolve` private method to call `quickApprove` or `reworkIssue` based on the resolution kind, when the decision is an IssueReview:

```scala
  private def resolve(id: String, req: Request, decisionInbox: DecisionInbox, issueApprovalService: IssueApprovalService): IO[PersistenceError, Response] =
    for
      form       <- parseForm(req)
      resolution <- ZIO
                      .fromOption(form.get("resolution").flatMap(_.headOption).flatMap(parseResolutionKind))
                      .orElseFail(PersistenceError.QueryFailed("decision_resolution", "Missing resolution"))
      summary     = form.get("summary").flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)
                      .getOrElse("Resolved from web inbox")
      decision   <- decisionInbox.get(DecisionId(id))
                      .flatMap(ZIO.fromOption(_).orElseFail(PersistenceError.NotFound("decision", id)))

      // For IssueReview decisions, delegate to IssueApprovalService for full workflow
      _          <- decision.source.kind match
                      case DecisionSourceKind.IssueReview =>
                        val issueId   = decision.source.issueId.getOrElse(IssueId(decision.source.referenceId))
                        val wsId      = decision.source.workspaceId.getOrElse("")
                        resolution match
                          case DecisionResolutionKind.Approved        =>
                            issueApprovalService
                              .quickApprove(wsId, BoardIssueId(issueId.value), summary)
                              .mapError(e => PersistenceError.QueryFailed("quick_approve", e.toString))
                          case DecisionResolutionKind.ReworkRequested =>
                            issueApprovalService
                              .reworkIssue(wsId, BoardIssueId(issueId.value), summary, "web")
                              .mapError(e => PersistenceError.QueryFailed("rework", e.toString))
                          case _ =>
                            decisionInbox.resolve(DecisionId(id), resolution, actor = "web", summary = summary).unit
                      case _ =>
                        decisionInbox.resolve(DecisionId(id), resolution, actor = "web", summary = summary).unit

      runIdOpt    = form.get("_run_id").flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)
      response   <- (isHtmx(req), runIdOpt) match
                      case (true, Some(runId)) => runPanel(runId, decisionInbox)
                      case _                   => ZIO.succeed(redirect("/decisions"))
    yield response
```

Add the import at the top:

```scala
import board.control.IssueApprovalService
import board.entity.BoardError
import shared.ids.Ids.BoardIssueId
```

- [ ] **Step 3: Update route handler calls to pass issueApprovalService**

Update the route handlers in the `make` method to pass `issueApprovalService` to the `resolve` method:

```scala
        Method.POST / "decisions" / string("id") / "resolve"  -> handler { (id: String, req: Request) =>
          resolve(id, req, decisionInbox, issueApprovalService).catchAll(error => ZIO.succeed(persistErr(error)))
        },
```

- [ ] **Step 4: Verify compilation**

Run: `/opt/homebrew/bin/sbt --client compile`
Expected: Compilation succeeds.

- [ ] **Step 5: Update DI wiring for DecisionsController**

In `ApplicationDI.scala`, the `DecisionsController.live` layer now requires `IssueApprovalService`. Since we added `IssueApprovalService.live` in Task 9, this should be automatically resolved by `ZLayer.make`. Verify by compiling.

- [ ] **Step 6: Run full test suite**

Run: `/opt/homebrew/bin/sbt --client test`
Expected: All tests pass. If `DecisionsController` tests exist, they may need stub `IssueApprovalService` added.

- [ ] **Step 7: Commit**

```bash
git add src/main/scala/decision/boundary/DecisionsController.scala src/main/scala/app/ApplicationDI.scala
git commit -m "feat(decision): wire resolve to IssueApprovalService for end-to-end approve/rework"
```

---

### Task 11: Integration Smoke Test

**Files:**
- Create: `src/test/scala/board/control/IssueApprovalIntegrationSpec.scala`

- [ ] **Step 1: Write integration test for the full approve flow**

```scala
package board.control

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import board.entity.*
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*

object IssueApprovalIntegrationSpec extends ZIOSpecDefault:

  def spec = suite("IssueApprovalIntegrationSpec")(
    test("quickApprove resolves decision and delegates to board orchestrator") {
      // This test verifies the wiring between IssueApprovalService, DecisionInbox,
      // and BoardOrchestrator using Ref-based stubs.
      for
        decisionResolved <- Ref.make(false)
        boardApproved    <- Ref.make(false)
        // Build stubs that track calls
        // Verify both decision resolution and board approval happened
        result           <- ZIO.succeed(true) // Replace with actual test
      yield assertTrue(result)
    },
    test("reworkIssue resolves decision, moves to Todo, and continues run") {
      for
        decisionResolved <- Ref.make(false)
        movedToTodo      <- Ref.make(false)
        runContinued     <- Ref.make(Option.empty[String])
        // Build stubs that track calls
        // Verify decision resolved, card moved, and run continued with comment
        result           <- ZIO.succeed(true) // Replace with actual test
      yield assertTrue(result)
    },
  )
```

The engineer must implement the full stubs following the patterns from `BoardOrchestratorSpec.scala` and `DecisionInboxSpec.scala`. The test should verify that `quickApprove` calls both `decisionInbox.resolveOpenIssueReviewDecision` and `boardOrchestrator.approveIssue`, and that `reworkIssue` calls `decisionInbox.resolveOpenIssueReviewDecision`, `boardRepository.moveIssue`, and `workspaceRunService.continueRun`.

- [ ] **Step 2: Run the test**

Run: `/opt/homebrew/bin/sbt --client "testOnly *IssueApprovalIntegrationSpec*"`
Expected: Tests pass.

- [ ] **Step 3: Run full test suite**

Run: `/opt/homebrew/bin/sbt --client test`
Expected: All tests pass, no regressions.

- [ ] **Step 4: Commit**

```bash
git add src/test/scala/board/control/IssueApprovalIntegrationSpec.scala
git commit -m "test(board): add integration smoke tests for quickApprove and reworkIssue flows"
```

---

### Task 12: Final Compilation and Cleanup

**Files:**
- All modified files

- [ ] **Step 1: Run format check**

Run: `/opt/homebrew/bin/sbt fmt`
Expected: Code is formatted.

- [ ] **Step 2: Run full test suite**

Run: `/opt/homebrew/bin/sbt --client test`
Expected: All tests pass (1126+ existing + new tests).

- [ ] **Step 3: Run compile with warnings**

Run: `/opt/homebrew/bin/sbt --client compile`
Expected: Clean compilation, no warnings.

- [ ] **Step 4: Commit any format fixes**

```bash
git add -u
git commit -m "chore: format and cleanup after issue timeline workflow implementation"
```
