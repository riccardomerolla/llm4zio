# Fix Type Boundary Violations + Rename API DTOs

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rename `AgentIssue`/`AgentAssignment` API DTOs to `*View` suffix, remove duplicate issue/assignment methods from `ChatRepository`, and route all callers through the event-sourced `IssueRepository`.

**Architecture:** The codebase has two parallel issue storage paths: `ChatRepository` (legacy KV-based, uses API DTOs at its interface) and `IssueRepositoryES` (event-sourced, uses domain types). We consolidate all callers onto the correct `IssueRepository` path and rename the API DTOs so their layer is unambiguous. Conversation/message methods stay in `ChatRepository` unchanged.

**Tech Stack:** Scala 3, ZIO 2, ZIO Test, EclipseStore KV store, scalafmt 3.9.10

---

## Overview of changes

| # | What | Files |
|---|------|-------|
| 1 | Rename `AgentIssue` → `AgentIssueView`, `AgentAssignment` → `AgentAssignmentView` in API model file | `IssueApiModels.scala` |
| 2 | Update `models/ChatModels.scala` aliases | `ChatModels.scala` |
| 3 | Remove issue/assignment methods from `ChatRepository` + `ChatRepositoryLive` | both `db/` files |
| 4 | Rewrite `IssueController` to use `IssueRepository` | `IssueController.scala` |
| 5 | Rewrite `IssueAssignmentOrchestrator` to use `IssueRepository` | `IssueAssignmentOrchestrator.scala` |
| 6 | Rewrite `WorkspaceRunService` to use `IssueRepository` | `WorkspaceRunService.scala` |
| 7 | Update view files (`WorkspacesView`, `WorkspacesController`) | 2 files |
| 8 | Update `ChatRepositoryESSpec` (remove issue tests) | `ChatRepositoryESSpec.scala` |
| 9 | Update `WorkspaceRunServiceSpec` | `WorkspaceRunServiceSpec.scala` |
| 10 | Compile and fix any remaining references | - |

---

## Task 1: Rename API DTOs in `IssueApiModels.scala`

**Files:**
- Modify: `src/main/scala/issues/entity/api/IssueApiModels.scala`

**Step 1: Rename `AgentIssue` → `AgentIssueView` and `AgentAssignment` → `AgentAssignmentView`**

Replace the full file content:

```scala
package issues.entity.api

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

enum IssuePriority derives JsonCodec, Schema:
  case Low, Medium, High, Critical

enum IssueStatus derives JsonCodec, Schema:
  case Open, Assigned, InProgress, Completed, Failed, Skipped

case class AgentIssueView(
  id: Option[String] = None,
  runId: Option[String] = None,
  conversationId: Option[String] = None,
  title: String,
  description: String,
  issueType: String,
  tags: Option[String] = None,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  priority: IssuePriority = IssuePriority.Medium,
  status: IssueStatus = IssueStatus.Open,
  assignedAgent: Option[String] = None,
  assignedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  errorMessage: Option[String] = None,
  resultData: Option[String] = None,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

case class AgentAssignmentView(
  id: Option[String] = None,
  issueId: String,
  agentName: String,
  status: String = "pending",
  assignedAt: Instant,
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  executionLog: Option[String] = None,
  result: Option[String] = None,
) derives JsonCodec

case class AgentIssueCreateRequest(
  runId: Option[String] = None,
  title: String,
  description: String,
  issueType: String,
  tags: Option[String] = None,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  priority: IssuePriority = IssuePriority.Medium,
  conversationId: Option[String] = None,
) derives JsonCodec

case class AssignIssueRequest(
  agentName: String
) derives JsonCodec
```

**Step 2: Verify it compiles (catch-all — other files will break, that's expected)**

```bash
sbt "compile" 2>&1 | head -40
```

Expected: compile errors referencing `AgentIssue` and `AgentAssignment` — that's correct.

**Step 3: Commit**

```bash
git add src/main/scala/issues/entity/api/IssueApiModels.scala
git commit -m "refactor: rename AgentIssue/AgentAssignment DTOs to *View suffix"
```

---

## Task 2: Update `ChatModels.scala` aliases

**Files:**
- Modify: `src/main/scala/models/ChatModels.scala`

**Step 1: Update the two renamed type aliases**

Replace:
```scala
type AgentIssue = issues.entity.api.AgentIssue
val AgentIssue = issues.entity.api.AgentIssue

type AgentAssignment = issues.entity.api.AgentAssignment
val AgentAssignment = issues.entity.api.AgentAssignment
```

With:
```scala
type AgentIssueView = issues.entity.api.AgentIssueView
val AgentIssueView = issues.entity.api.AgentIssueView

type AgentAssignmentView = issues.entity.api.AgentAssignmentView
val AgentAssignmentView = issues.entity.api.AgentAssignmentView
```

**Step 2: Commit**

```bash
git add src/main/scala/models/ChatModels.scala
git commit -m "refactor: update ChatModels aliases for renamed View DTOs"
```

---

## Task 3: Remove issue/assignment methods from `ChatRepository` + `ChatRepositoryLive`

**Files:**
- Modify: `src/main/scala/db/ChatRepository.scala`
- Modify: `src/main/scala/db/ChatRepositoryLive.scala`

**Step 1: Remove from `ChatRepository.scala`**

Remove the import line:
```scala
import issues.entity.api.{ AgentAssignment, AgentIssue, IssueStatus }
```

Remove the `// Agent Issues` section (lines 26-35):
```scala
  // Agent Issues
  def createIssue(issue: AgentIssue): IO[PersistenceError, Long]
  def getIssue(id: Long): IO[PersistenceError, Option[AgentIssue]]
  def listIssues(offset: Int, limit: Int): IO[PersistenceError, List[AgentIssue]]
  def listIssuesByRun(runId: Long): IO[PersistenceError, List[AgentIssue]]
  def listIssuesByStatus(status: IssueStatus): IO[PersistenceError, List[AgentIssue]]
  def listUnassignedIssues(runId: Long): IO[PersistenceError, List[AgentIssue]]
  def updateIssue(issue: AgentIssue): IO[PersistenceError, Unit]
  def deleteIssue(id: Long): IO[PersistenceError, Unit]
  def assignIssueToAgent(issueId: Long, agentName: String): IO[PersistenceError, Unit]
```

Remove the `// Agent Assignments` section (lines 37-41):
```scala
  // Agent Assignments
  def createAssignment(assignment: AgentAssignment): IO[PersistenceError, Long]
  def getAssignment(id: Long): IO[PersistenceError, Option[AgentAssignment]]
  def listAssignmentsByIssue(issueId: Long): IO[PersistenceError, List[AgentAssignment]]
  def updateAssignment(assignment: AgentAssignment): IO[PersistenceError, Unit]
```

Remove the corresponding static helpers in the companion object (lines 184-221):
```scala
  def createIssue(issue: AgentIssue): ZIO[ChatRepository, PersistenceError, Long] = ...
  def getIssue(id: Long): ZIO[ChatRepository, PersistenceError, Option[AgentIssue]] = ...
  def listIssues(...): ZIO[ChatRepository, PersistenceError, List[AgentIssue]] = ...
  def listIssuesByRun(...): ZIO[ChatRepository, PersistenceError, List[AgentIssue]] = ...
  def listIssuesByStatus(...): ZIO[ChatRepository, PersistenceError, List[AgentIssue]] = ...
  def listUnassignedIssues(...): ZIO[ChatRepository, PersistenceError, List[AgentIssue]] = ...
  def updateIssue(...): ZIO[ChatRepository, PersistenceError, Unit] = ...
  def deleteIssue(...): ZIO[ChatRepository, PersistenceError, Unit] = ...
  def assignIssueToAgent(...): ZIO[ChatRepository, PersistenceError, Unit] = ...
  def createAssignment(...): ZIO[ChatRepository, PersistenceError, Long] = ...
  def getAssignment(...): ZIO[ChatRepository, PersistenceError, Option[AgentAssignment]] = ...
  def listAssignmentsByIssue(...): ZIO[ChatRepository, PersistenceError, List[AgentAssignment]] = ...
  def updateAssignment(...): ZIO[ChatRepository, PersistenceError, Unit] = ...
```

**Step 2: Remove from `ChatRepositoryLive.scala`**

Remove the import:
```scala
import issues.entity.api.{ AgentAssignment, AgentIssue, IssuePriority, IssueStatus }
```

Remove the implementations for `createIssue`, `getIssue`, `listIssues`, `listIssuesByRun`, `listIssuesByStatus`, `listUnassignedIssues`, `updateIssue`, `deleteIssue`, `assignIssueToAgent`, `createAssignment`, `getAssignment`, `listAssignmentsByIssue`, `updateAssignment` (lines 110-194).

Remove the private conversion helpers `toIssueRow`, `fromIssueRow`, `toAssignmentRow`, `fromAssignmentRow` (lines 367-440).

Keep all conversation/message/session methods intact.

**Step 3: Run `sbt compile` to see remaining failures**

```bash
sbt "compile" 2>&1 | grep "error:" | head -40
```

**Step 4: Commit**

```bash
git add src/main/scala/db/ChatRepository.scala src/main/scala/db/ChatRepositoryLive.scala
git commit -m "refactor: remove issue/assignment methods from ChatRepository (use IssueRepository)"
```

---

## Task 4: Rewrite `IssueController` to use `IssueRepository`

**Files:**
- Modify: `src/main/scala/issues/boundary/IssueController.scala`

**Context:** `IssueController` currently uses `chatRepository.createIssue/getIssue/listIssues/...`. These must be replaced with `IssueRepository.append(event)` / `IssueRepository.get(id)` / `IssueRepository.list(filter)`. The domain `AgentIssue` (from `issues.entity`) has a different shape than the old API DTO; we need a helper to convert it to `AgentIssueView` for HTTP responses.

**Domain `AgentIssue` fields:**
- `id: IssueId` (value class wrapping `String`)
- `runId: Option[TaskRunId]`
- `conversationId: Option[ConversationId]`
- `title`, `description`, `issueType`, `priority: String`
- `state: IssueState` (sealed trait: `Open`, `Assigned`, `InProgress`, `Completed`, `Failed`, `Skipped`)
- `tags: List[String]`
- `contextPath: String`, `sourceFolder: String`

**`IssueEvent.Created` fields (from `issues.entity.IssueEvent`):**
Read the event model to understand the correct fields. Look at `src/main/scala/issues/entity/IssueEvent.scala` before writing code.

**Step 1: Read `IssueEvent.scala`**

```bash
cat src/main/scala/issues/entity/IssueEvent.scala
```

Note the fields of `IssueEvent.Created` — likely: `issueId: IssueId`, `title`, `description`, `issueType`, `priority`, `occurredAt: Instant`.

**Step 2: Add `IssueRepository` to `IssueController` dependencies**

Update the `live` ZLayer in `IssueController`:

```scala
val live: ZLayer[ChatRepository & TaskRepository & IssueAssignmentOrchestrator & IssueRepository, Nothing, IssueController] =
  ZLayer.fromFunction(IssueControllerLive.apply)
```

Update `IssueControllerLive` constructor:

```scala
final case class IssueControllerLive(
  chatRepository: ChatRepository,
  taskRepository: TaskRepository,
  issueAssignmentOrchestrator: IssueAssignmentOrchestrator,
  issueRepository: IssueRepository,
) extends IssueController:
```

**Step 3: Add a `domainToView` converter**

Add this private method inside `IssueControllerLive`:

```scala
import issues.entity.api.{ AgentIssueView, IssueStatus, IssuePriority }
import issues.entity.{ AgentIssue as DomainIssue, IssueState }

private def domainToView(i: DomainIssue): AgentIssueView =
  val (status, assignedAgent, assignedAt, completedAt, errorMessage) = i.state match
    case IssueState.Open(_)                                    => (IssueStatus.Open, None, None, None, None)
    case IssueState.Assigned(agent, at)                        => (IssueStatus.Assigned, Some(agent.value), Some(at), None, None)
    case IssueState.InProgress(agent, at)                      => (IssueStatus.InProgress, Some(agent.value), Some(at), None, None)
    case IssueState.Completed(agent, at, _)                    => (IssueStatus.Completed, Some(agent.value), None, Some(at), None)
    case IssueState.Failed(agent, at, msg)                     => (IssueStatus.Failed, Some(agent.value), None, Some(at), Some(msg))
    case IssueState.Skipped(at, reason)                        => (IssueStatus.Skipped, None, None, Some(at), Some(reason))
  val priority = IssuePriority.values.find(_.toString.equalsIgnoreCase(i.priority)).getOrElse(IssuePriority.Medium)
  AgentIssueView(
    id = Some(i.id.value),
    runId = i.runId.map(_.value),
    conversationId = i.conversationId.map(_.value),
    title = i.title,
    description = i.description,
    issueType = i.issueType,
    tags = if i.tags.isEmpty then None else Some(i.tags.mkString(",")),
    contextPath = Option(i.contextPath).filter(_.nonEmpty),
    sourceFolder = Option(i.sourceFolder).filter(_.nonEmpty),
    priority = priority,
    status = status,
    assignedAgent = assignedAgent,
    assignedAt = assignedAt,
    completedAt = completedAt,
    errorMessage = errorMessage,
    createdAt = i.state match
      case IssueState.Open(at) => at
      case _                   => java.time.Instant.EPOCH,
    updatedAt = assignedAt.orElse(completedAt).getOrElse(java.time.Instant.EPOCH),
  )
```

**Step 4: Update import block**

```scala
import issues.entity.api.{ AgentIssueCreateRequest, AgentIssueView, AssignIssueRequest, IssuePriority, IssueStatus }
import issues.entity.{ AgentIssue as DomainIssue, IssueEvent, IssueFilter, IssueRepository, IssueState, IssueStateTag }
import shared.ids.Ids.IssueId
```

**Step 5: Rewrite `POST /issues` (form create)**

```scala
Method.POST / "issues" -> handler { (req: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      form    <- parseForm(req)
      title   <- required(form, "title")
      content <- required(form, "description")
      now     <- Clock.instant
      issueId  = IssueId.generate
      event    = IssueEvent.Created(
                   issueId = issueId,
                   title = title,
                   description = content,
                   issueType = form.get("issueType").map(_.trim).filter(_.nonEmpty).getOrElse("task"),
                   priority = form.get("priority").getOrElse("medium"),
                   occurredAt = now,
                 )
      _       <- issueRepository.append(event)
      redirect = form.get("runId").map(id => s"/issues?run_id=$id").getOrElse("/issues")
    yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", redirect)))
  }
},
```

**Step 6: Rewrite `POST /api/issues` (JSON create)**

```scala
Method.POST / "api" / "issues" -> handler { (req: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      body         <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
      issueRequest <- ZIO.fromEither(body.fromJson[AgentIssueCreateRequest])
                        .mapError(err => PersistenceError.QueryFailed("json_parse", err))
      now          <- Clock.instant
      issueId       = IssueId.generate
      event         = IssueEvent.Created(
                        issueId = issueId,
                        title = issueRequest.title,
                        description = issueRequest.description,
                        issueType = issueRequest.issueType,
                        priority = issueRequest.priority.toString,
                        occurredAt = now,
                      )
      _            <- issueRepository.append(event)
      created      <- issueRepository.get(issueId)
    yield Response.json(domainToView(created).toJson)
  }
},
```

**Step 7: Rewrite `GET /api/issues`**

```scala
Method.GET / "api" / "issues" -> handler { (req: Request) =>
  val runIdStr = req.queryParam("run_id").map(_.trim).filter(_.nonEmpty)
  ErrorHandlingMiddleware.fromPersistence {
    val filter = IssueFilter(
      runId = runIdStr.map(s => shared.ids.Ids.TaskRunId(s)),
    )
    issueRepository.list(filter).map(issues => Response.json(issues.map(domainToView).toJson))
  }
},
```

**Step 8: Rewrite `GET /api/issues/:id`**

```scala
Method.GET / "api" / "issues" / string("id") -> handler { (id: String, _: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    issueRepository.get(IssueId(id))
      .map(issue => Response.json(domainToView(issue).toJson))
  }
},
```

**Step 9: Rewrite `DELETE /api/issues/:id`**

`IssueRepository` is event-sourced and doesn't support delete. For now, remove the DELETE route or return `501 Not Implemented`:

```scala
Method.DELETE / "api" / "issues" / string("id") -> handler { (_: String, _: Request) =>
  ZIO.succeed(Response(status = Status.NotImplemented))
},
```

**Step 10: Update `GET /issues` (HTML list)**

```scala
Method.GET / "issues" -> handler { (req: Request) =>
  val runId        = req.queryParam("run_id").map(_.trim).filter(_.nonEmpty)
  val statusFilter = req.queryParam("status").map(_.trim).filter(_.nonEmpty)
  val query        = req.queryParam("q").map(_.trim).filter(_.nonEmpty)
  val tagFilter    = req.queryParam("tag").map(_.trim).filter(_.nonEmpty)

  ErrorHandlingMiddleware.fromPersistence {
    for
      issues   <- loadIssues(runId, statusFilter)
      filtered  = filterIssues(issues, query, tagFilter)
    yield html(HtmlViews.issuesView(runId, filtered, statusFilter, query, tagFilter))
  }
},
```

Update `loadIssues` to use `IssueRepository`:

```scala
private def loadIssues(runId: Option[String], statusFilter: Option[String]): IO[PersistenceError, List[AgentIssueView]] =
  val filter = IssueFilter(
    runId = runId.map(shared.ids.Ids.TaskRunId.apply),
    states = statusFilter.flatMap(parseIssueStateTag).map(Set(_)).getOrElse(Set.empty),
  )
  issueRepository.list(filter).map(_.map(domainToView))

private def parseIssueStateTag(raw: String): Option[IssueStateTag] =
  raw.trim.toLowerCase match
    case "open"        => Some(IssueStateTag.Open)
    case "assigned"    => Some(IssueStateTag.Assigned)
    case "in_progress" => Some(IssueStateTag.InProgress)
    case "completed"   => Some(IssueStateTag.Completed)
    case "failed"      => Some(IssueStateTag.Failed)
    case "skipped"     => Some(IssueStateTag.Skipped)
    case _             => None
```

Update `filterIssues` signature:

```scala
private def filterIssues(issues: List[AgentIssueView], query: Option[String], tag: Option[String]): List[AgentIssueView] = ...
```

**Step 11: Rewrite `GET /issues/:id` (HTML detail)**

The detail page needs assignments. Since `IssueAssignmentOrchestrator.assignIssue` now needs to be updated too (Task 5), keep using `chatRepository.listAssignmentsByIssue` for assignments temporarily. Fetch the domain issue from `issueRepository`, convert to view:

```scala
Method.GET / "issues" / string("id") -> handler { (id: String, _: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      issue          <- issueRepository.get(IssueId(id))
      assignments    <- chatRepository.listAssignmentsByIssue(???) // see note
      ...
```

**Note:** `listAssignmentsByIssue` is being removed from `ChatRepository`. For Task 4, the detail page can show an empty assignment list temporarily. Assignment viewing will be addressed in Task 5.

Simplified version:

```scala
Method.GET / "issues" / string("id") -> handler { (id: String, _: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      issue          <- issueRepository.get(IssueId(id))
      customAgents   <- taskRepository.listCustomAgents
      enabledCustom   = customAgents.filter(_.enabled)
      availableAgents = AgentRegistry.allAgents(enabledCustom).filter(_.usesAI)
    yield html(HtmlViews.issueDetail(domainToView(issue), List.empty, availableAgents))
  }
},
```

**Step 12: Rewrite `POST /issues/:id/assign`**

```scala
Method.POST / "issues" / string("id") / "assign" -> handler { (id: String, req: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      form      <- parseForm(req)
      agentName <- required(form, "agentName")
      updated   <- issueAssignmentOrchestrator.assignIssue(id, agentName)
      redirectTo = updated.conversationId.map(cid => s"/chat/$cid").getOrElse(s"/issues/$id")
    yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", redirectTo)))
  }
},
```

Note: `assignIssue` signature changes in Task 5 (takes `String` id instead of `Long`).

**Step 13: Rewrite `PATCH /api/issues/:id/assign`**

```scala
Method.PATCH / "api" / "issues" / string("id") / "assign" -> handler { (id: String, req: Request) =>
  ErrorHandlingMiddleware.fromPersistence {
    for
      body          <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
      assignRequest <- ZIO.fromEither(body.fromJson[AssignIssueRequest])
                        .mapError(err => PersistenceError.QueryFailed("json_parse", err))
      updated       <- issueAssignmentOrchestrator.assignIssue(id, assignRequest.agentName)
    yield Response.json(domainToView(updated).toJson)
  }
},
```

**Step 14: Update `POST /issues/import`**

The import creates issues — replace `chatRepository.createIssue(issue)` with `issueRepository.append(event)`:

```scala
private def parseMarkdownIssue(file: Path, markdown: String, now: Instant): IssueEvent.Created =
  val lines = markdown.linesIterator.toList
  val title = lines
    .find(_.trim.startsWith("#"))
    .map(_.replaceFirst("^#+\\s*", "").trim)
    .filter(_.nonEmpty)
    .getOrElse(file.getFileName.toString.stripSuffix(".md"))

  def metadata(key: String): Option[String] =
    lines.find(_.toLowerCase.startsWith(s"$key:"))
      .flatMap(_.split(":", 2).lift(1).map(_.trim).filter(_.nonEmpty))

  IssueEvent.Created(
    issueId = IssueId.generate,
    title = title,
    description = markdown,
    issueType = metadata("type").getOrElse("task"),
    priority = metadata("priority").getOrElse("medium"),
    occurredAt = now,
  )
```

Update `importIssuesFromConfiguredFolder` to append events:

```scala
issue = parseMarkdownIssue(file, markdown, now)
_ <- issueRepository.append(issue)
```

**Step 15: Compile**

```bash
sbt "compile" 2>&1 | grep "error:" | head -40
```

Fix any remaining type errors.

**Step 16: Commit**

```bash
git add src/main/scala/issues/boundary/IssueController.scala
git commit -m "refactor: IssueController uses IssueRepository (event-sourced) instead of ChatRepository"
```

---

## Task 5: Rewrite `IssueAssignmentOrchestrator` to use `IssueRepository`

**Files:**
- Modify: `src/main/scala/orchestration/control/IssueAssignmentOrchestrator.scala`

**Step 1: Change `assignIssue` signature from `Long` to `String` IssueId**

```scala
trait IssueAssignmentOrchestrator:
  def assignIssue(issueId: String, agentName: String): IO[PersistenceError, AgentIssueView]
```

**Step 2: Add `IssueRepository` to dependencies**

```scala
val live: ZLayer[
  ChatRepository & TaskRepository & LlmService & AgentConfigResolver & ActivityHub & IssueRepository,
  Nothing,
  IssueAssignmentOrchestrator,
] = ZLayer.scoped { ... }
```

And inject it:

```scala
issueRepository <- ZIO.service[IssueRepository]
service = IssueAssignmentOrchestratorLive(
  chatRepository,
  migrationRepository,
  llmService,
  configResolver,
  activityHub,
  queue,
  issueRepository,
)
```

Update `IssueAssignmentOrchestratorLive`:

```scala
final private case class IssueAssignmentOrchestratorLive(
  chatRepository: ChatRepository,
  migrationRepository: TaskRepository,
  llmService: LlmService,
  configResolver: AgentConfigResolver,
  activityHub: ActivityHub,
  queue: Queue[AssignmentTask],
  issueRepository: IssueRepository,
) extends IssueAssignmentOrchestrator:
```

**Step 3: Rewrite `assignIssue`**

```scala
override def assignIssue(issueId: String, agentName: String): IO[PersistenceError, AgentIssueView] =
  for
    issue <- issueRepository.get(IssueId(issueId))
    now   <- Clock.instant
    _     <- issueRepository.append(
               IssueEvent.Assigned(
                 issueId = IssueId(issueId),
                 agent = AgentId(agentName),
                 assignedAt = now,
               )
             )
    // Ensure a conversation exists
    convId <- ensureIssueConversation(issueId, issue, agentName)
    // Queue for background context delivery
    _      <- queue.offer(AssignmentTask(issueId, agentName, convId))
    _      <- activityHub.publish(
               ActivityEvent(
                 id = EventId.generate,
                 eventType = ActivityEventType.AgentAssigned,
                 source = "issue-assignment",
                 runId = issue.runId.map(r => TaskRunId(r.value)),
                 agentName = Some(agentName),
                 summary = s"Agent '$agentName' assigned to issue #$issueId: ${issue.title}",
                 createdAt = now,
               )
             )
    updated <- issueRepository.get(IssueId(issueId))
  yield domainToView(updated)
```

**Step 4: Update `AssignmentTask`**

```scala
final private case class AssignmentTask(
  issueId: String,
  agentName: String,
  conversationId: String,
)
```

**Step 5: Rewrite `ensureIssueConversation`**

```scala
private def ensureIssueConversation(
  issueId: String,
  issue: DomainIssue,
  agentName: String,
): IO[PersistenceError, String] =
  issue.conversationId match
    case Some(cid) => ZIO.succeed(cid.value)
    case None      =>
      for
        now    <- Clock.instant
        convId <- chatRepository.createConversation(
                    ChatConversation(
                      runId = issue.runId.map(_.value),
                      title = s"Issue #$issueId: ${issue.title}",
                      description = Some("Auto-generated conversation from issue assignment"),
                      createdAt = now,
                      updatedAt = now,
                      createdBy = Some("system"),
                    )
                  )
        _      <- issueRepository.append(
                    IssueEvent.ConversationLinked(
                      issueId = IssueId(issueId),
                      conversationId = ConversationId(convId.toString),
                      occurredAt = now,
                    )
                  )
      yield convId.toString
```

**Note:** `IssueEvent.ConversationLinked` may not exist yet. Check `IssueEvent.scala`. If it doesn't exist, add it — or store the conversation link via a different mechanism. If `IssueEvent` doesn't have a link event, the simplest approach is to use `IssueEvent.Assigned` to carry conversationId if it has a field for it, or add a new event. Read the actual `IssueEvent.scala` first.

**Step 6: Rewrite `processQueue` and `processTask`**

```scala
final private case class AssignmentTask(
  issueId: String,
  agentName: String,
  conversationId: String,
)

private def processTask(task: AssignmentTask): IO[PersistenceError, Unit] =
  (for
    issue <- issueRepository.get(IssueId(task.issueId))
    _     <- sendIssueContextToAgent(issue, task.agentName, task.conversationId)
  yield ()).catchAll { err =>
    ZIO.logError(s"Issue assignment ${task.issueId} failed: $err")
  }
```

**Step 7: Rewrite `sendIssueContextToAgent`**

```scala
private def sendIssueContextToAgent(
  issue: DomainIssue,
  agentName: String,
  conversationId: String,
): IO[PersistenceError, Unit] =
  for
    conversationKey <- ZIO.fromOption(conversationId.toLongOption)
                        .orElseFail(PersistenceError.QueryFailed("issue", s"Invalid conversation id: $conversationId"))
    runMetadata     <- issue.runId match
                        case Some(runId) =>
                          runId.value.toLongOption match
                            case Some(parsedId) => migrationRepository.getRun(parsedId)
                            case None           => ZIO.none
                        case None => ZIO.none
    customAgent     <- migrationRepository.getCustomAgentByName(agentName)
    prompt           = buildIssueAssignmentPrompt(issue, agentName, runMetadata, customAgent.map(_.systemPrompt))
    now             <- Clock.instant
    _               <- chatRepository.addMessage(
                         ConversationEntry(
                           conversationId = conversationId,
                           sender = "system",
                           senderType = SenderType.System,
                           content = prompt,
                           messageType = MessageType.Status,
                           createdAt = now,
                           updatedAt = now,
                         )
                       )
    llmResponse     <- llmService.execute(prompt).mapError(convertLlmError)
    now2            <- Clock.instant
    _               <- chatRepository.addMessage(
                         ConversationEntry(
                           conversationId = conversationId,
                           sender = "assistant",
                           senderType = SenderType.Assistant,
                           content = llmResponse.content,
                           messageType = MessageType.Text,
                           metadata = Some(llmResponse.metadata.toJson),
                           createdAt = now2,
                           updatedAt = now2,
                         )
                       )
    conv            <- chatRepository.getConversation(conversationKey)
                        .someOrFail(PersistenceError.NotFound("conversation", conversationKey))
    _               <- chatRepository.updateConversation(conv.copy(updatedAt = now2))
  yield ()
```

**Step 8: Rewrite `buildIssueAssignmentPrompt`**

```scala
private def buildIssueAssignmentPrompt(
  issue: DomainIssue,
  agentName: String,
  run: Option[db.TaskRunRow],
  customSystemPrompt: Option[String],
): String =
  val runContext    = run match
    case Some(value) =>
      s"""Run metadata:
         |- runId: ${value.id}
         |- sourceDir: ${value.sourceDir}
         |- outputDir: ${value.outputDir}
         |- status: ${value.status}
         |- currentPhase: ${value.currentPhase.getOrElse("n/a")}
         |""".stripMargin
    case None        => "Run metadata: not linked"
  val systemContext = customSystemPrompt.map(_.trim).filter(_.nonEmpty) match
    case Some(prompt) =>
      s"""Custom agent system prompt (highest priority):
         |$prompt
         |
         |""".stripMargin
    case None         => ""

  s"""${systemContext}Issue assignment for agent: $agentName
     |
     |Issue title: ${issue.title}
     |Issue type: ${issue.issueType}
     |Priority: ${issue.priority}
     |Tags: ${if issue.tags.isEmpty then "none" else issue.tags.mkString(", ")}
     |Context path: ${if issue.contextPath.isEmpty then "none" else issue.contextPath}
     |Source folder: ${if issue.sourceFolder.isEmpty then "none" else issue.sourceFolder}
     |
     |$runContext
     |
     |Markdown task:
     |${issue.description}
     |
     |Please execute this task and provide a concise implementation summary and next actions.
     |""".stripMargin
```

**Step 9: Add `domainToView` helper (same as Task 4)**

Copy the `domainToView` converter from Task 4 into `IssueAssignmentOrchestratorLive`.

**Step 10: Update imports**

```scala
import issues.entity.api.{ AgentIssueView, IssueStatus, IssuePriority }
import issues.entity.{ AgentIssue as DomainIssue, IssueEvent, IssueFilter, IssueRepository, IssueState }
import shared.ids.Ids.{ AgentId, ConversationId, EventId, IssueId, TaskRunId }
```

**Step 11: Compile**

```bash
sbt "compile" 2>&1 | grep "error:" | head -40
```

**Step 12: Commit**

```bash
git add src/main/scala/orchestration/control/IssueAssignmentOrchestrator.scala
git commit -m "refactor: IssueAssignmentOrchestrator uses IssueRepository (event-sourced)"
```

---

## Task 6: Update `WorkspaceRunService` to use `IssueRepository`

**Files:**
- Modify: `src/main/scala/workspace/control/WorkspaceRunService.scala`

**Step 1: Add `IssueRepository` to dependencies**

```scala
object WorkspaceRunService:
  val live: ZLayer[WorkspaceRepository & ChatRepository & IssueRepository, Nothing, WorkspaceRunService] =
    ZLayer.fromFunction((repo: WorkspaceRepository, chat: ChatRepository, issueRepo: IssueRepository) =>
      WorkspaceRunServiceLive(repo, chat, issueRepo)
    )
```

Update `WorkspaceRunServiceLive`:

```scala
final case class WorkspaceRunServiceLive(
  wsRepo: WorkspaceRepository,
  chatRepo: ChatRepository,
  issueRepo: IssueRepository,
  timeoutSeconds: Long = 1800,
  worktreeAdd: (String, String, String) => IO[WorkspaceError, Unit] = WorkspaceRunServiceLive.defaultWorktreeAdd,
  worktreeRemove: String => Task[Unit] = WorkspaceRunServiceLive.defaultWorktreeRemove,
  dockerCheck: IO[WorkspaceError, Unit] = DockerSupport.requireDocker,
) extends WorkspaceRunService:
```

**Step 2: Update `assign` to fetch issue from `IssueRepository`**

Replace:
```scala
issue  <- req.issueRef.stripPrefix("#").toLongOption match
            case None     => ZIO.succeed(None)
            case Some(id) =>
              chatRepo.getIssue(id)
                .mapError(...)
                .catchAllDefect(...)
```

With:
```scala
issue  <- req.issueRef.stripPrefix("#") match
            case ""  => ZIO.succeed(None)
            case id  =>
              issueRepo.get(IssueId(id))
                .map(Some(_))
                .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                .catchAll(_ => ZIO.succeed(None))
```

**Step 3: Update `buildPrompt` signature and body**

```scala
import issues.entity.AgentIssue as DomainIssue
import issues.entity.IssueRepository
import shared.ids.Ids.IssueId

private def buildPrompt(
  req: AssignRunRequest,
  issue: Option[DomainIssue],
  repoPath: String,
  worktreePath: String,
): String =
  issue match
    case None    =>
      s"""Issue: ${req.issueRef}
         |Task: ${req.prompt}
         |
         |Repository: $repoPath
         |Working directory: $worktreePath""".stripMargin
    case Some(i) =>
      s"""Issue ${req.issueRef}: ${i.title}${
        if i.description.nonEmpty then s"\nDescription:\n${i.description}" else ""
      }${
        if i.contextPath.nonEmpty then s"\nContext path: ${i.contextPath}" else ""
      }${
        if i.sourceFolder.nonEmpty then s"\nSource folder: ${i.sourceFolder}" else ""
      }
      Repository: $repoPath
      Working directory: $worktreePath"""
```

**Step 4: Update imports**

```scala
import issues.entity.{ AgentIssue as DomainIssue, IssueRepository }
import shared.ids.Ids.IssueId
```

Remove:
```scala
import issues.entity.api.AgentIssue
```

**Step 5: Compile**

```bash
sbt "compile" 2>&1 | grep "error:" | head -40
```

**Step 6: Update `ApplicationDI.scala` wiring**

Search for `WorkspaceRunService.live` in `src/main/scala/app/ApplicationDI.scala` and add `IssueRepository` to its layer:

```bash
grep -n "WorkspaceRunService" src/main/scala/app/ApplicationDI.scala
```

Add `IssueRepository` (which should already be in scope via `IssueRepositoryES.live`).

**Step 7: Commit**

```bash
git add src/main/scala/workspace/control/WorkspaceRunService.scala src/main/scala/app/ApplicationDI.scala
git commit -m "refactor: WorkspaceRunService fetches issues from IssueRepository"
```

---

## Task 7: Update view and controller files

**Files:**
- Modify: `src/main/scala/shared/web/WorkspacesView.scala`
- Modify: `src/main/scala/workspace/boundary/WorkspacesController.scala`

**Step 1: `WorkspacesView.scala` — update import and type**

Replace:
```scala
import issues.entity.api.AgentIssue
```
With:
```scala
import issues.entity.api.AgentIssueView
```

Replace all usages of `AgentIssue` with `AgentIssueView` in this file (the `issueSearchResults` method parameter).

**Step 2: `WorkspacesController.scala` — remove unused import**

Remove:
```scala
import issues.entity.api.IssueStatus
```

**Step 3: Compile**

```bash
sbt "compile" 2>&1 | grep "error:" | head -40
```

**Step 4: Commit**

```bash
git add src/main/scala/shared/web/WorkspacesView.scala src/main/scala/workspace/boundary/WorkspacesController.scala
git commit -m "refactor: update view files to use AgentIssueView"
```

---

## Task 8: Update `ChatRepositoryESSpec`

**Files:**
- Modify: `src/test/scala/db/ChatRepositoryESSpec.scala`

**Step 1: Remove issue-related imports**

Remove:
```scala
import issues.entity.api.{ AgentIssue, IssuePriority }
```

**Step 2: Remove the test `"restart persists chat messages and issues with string IDs"`**

This test (around line 324) calls `repo.createIssue(...)` and `repo.getIssue(...)` which no longer exist. Either:
- Delete the test entirely, or
- Replace with an equivalent test using `IssueRepository` (simpler: just delete it — the behavior is tested by `IssueRepositoryESSpec`)

**Step 3: Compile tests**

```bash
sbt "test:compile" 2>&1 | grep "error:" | head -40
```

**Step 4: Commit**

```bash
git add src/test/scala/db/ChatRepositoryESSpec.scala
git commit -m "test: remove issue CRUD tests from ChatRepositoryESSpec (covered by IssueRepositoryESSpec)"
```

---

## Task 9: Update `WorkspaceRunServiceSpec`

**Files:**
- Modify: `src/test/scala/workspace/control/WorkspaceRunServiceSpec.scala`

**Step 1: Inspect current stub**

The spec has a `StubChatRepo` that extends `ChatRepository`. Since `createIssue/getIssue` are removed, update the stub to remove those methods.

**Step 2: Add a `StubIssueRepo`**

```scala
final class StubIssueRepo(issues: Map[String, issues.entity.AgentIssue] = Map.empty) extends IssueRepository:
  def append(event: IssueEvent): IO[PersistenceError, Unit] = ZIO.unit
  def get(id: IssueId): IO[PersistenceError, issues.entity.AgentIssue] =
    ZIO.fromOption(issues.get(id.value)).orElseFail(PersistenceError.NotFound("issue", 0L))
  def list(filter: IssueFilter): IO[PersistenceError, List[issues.entity.AgentIssue]] =
    ZIO.succeed(issues.values.toList)
```

**Step 3: Update test layers**

Update tests that previously injected a stub issue into `chatRepo.getIssue` to instead inject it into `StubIssueRepo`.

**Step 4: Compile and run**

```bash
sbt "testOnly workspace.control.WorkspaceRunServiceSpec" 2>&1 | tail -20
```

Expected: all tests pass.

**Step 5: Commit**

```bash
git add src/test/scala/workspace/control/WorkspaceRunServiceSpec.scala
git commit -m "test: update WorkspaceRunServiceSpec to use StubIssueRepo"
```

---

## Task 10: Final compile, run all tests, clean up

**Step 1: Full compile**

```bash
sbt "compile; test:compile" 2>&1 | grep "error:" | head -40
```

Fix any remaining errors.

**Step 2: Run all tests**

```bash
sbt test 2>&1 | tail -30
```

**Step 3: Run scalafmt**

```bash
sbt scalafmtAll 2>&1 | tail -5
```

**Step 4: Final commit**

```bash
git add -p  # stage any remaining fixes
git commit -m "refactor: fix type boundary violations - API DTOs renamed to *View, issue ops via IssueRepository"
```

---

## Notes and Gotchas

### `IssueId.generate`
Check `src/main/scala/shared/ids/Ids.scala` for the actual `IssueId` definition and whether a `generate` method exists. If not, use:
```scala
IssueId(java.util.UUID.randomUUID().toString)
```

### `IssueEvent.ConversationLinked`
If this event doesn't exist in `IssueEvent.scala`, the simplest workaround is to not link the conversation via an event — instead, store the conversation ID separately or use `IssueEvent.Assigned` if it carries a conversation field. Read `IssueEvent.scala` before writing code.

### `AgentId` type
The `IssueState.Assigned` case uses `AgentId` — verify the `AgentId` wraps a `String` and use `AgentId(agentName)`.

### Long IDs in `IssueController`
The old controller used `Long` IDs for issues (from KV store). The new event-sourced repo uses `IssueId` (String/UUID). The `parseLongId` helper can be removed. Routes that were `GET /issues/:id` with Long parsing become `GET /issues/:id` accepting any String.

### `HtmlViews.issueDetail` / `HtmlViews.issuesView`
These views accept `AgentIssueView` (old `AgentIssue`). After rename, update all calls to pass `AgentIssueView`. The view files themselves (`IssuesView.scala`, etc.) need import updates too — scan for `import issues.entity.api.AgentIssue` and update to `AgentIssueView`.

### `IssueAssignmentOrchestrator.assignIssue` signature change
The trait changes from `Long` to `String` ID. Update the call sites: `IssueController.scala` (already shown above) and `WorkspacesController.scala` if it calls `assignIssue`.

### `ApplicationDI.scala` wiring
Search for `IssueAssignmentOrchestrator.live` and `WorkspaceRunService.live` and ensure `IssueRepository` (provided by `IssueRepositoryES.live`) is available in the layer graph.
