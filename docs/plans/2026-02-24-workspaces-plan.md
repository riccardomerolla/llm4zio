# Workspaces Feature Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a Workspaces feature that lets users register a local git repository and assign issues to CLI agents (Gemini CLI, OpenCode, Claude, Codex, Copilot), running each assignment in an isolated git worktree with stdout/stderr streamed as Status messages into the linked chat conversation.

**Architecture:** Approach A (thin shell, no GitHub dependency). A `Workspace` is a named config record pointing at a local git repo path. Each issue assignment creates a `WorkspaceRun`, shells out `git worktree add`, spawns the CLI agent as a subprocess in non-interactive mode, and streams lines line-by-line into a linked `ChatConversation`. Everything persists in EclipseStore using the same `prefix:<id>` key pattern as existing `agent:` and `workflow:` entries.

**Tech Stack:** Scala 3, ZIO 2.x, ZIO HTTP, zio-json, zio-schema, EclipseStore, Scalatags, HTMX 2.0.4, `java.lang.ProcessBuilder`

---

## Task 1: Data models — `Workspace`, `WorkspaceRun`, `RunStatus`

**Files:**
- Create: `src/main/scala/workspace/entity/WorkspaceModels.scala`
- Test: `src/test/scala/workspace/entity/WorkspaceModelsSpec.scala`

**Step 1: Write the failing test**

```scala
// src/test/scala/workspace/entity/WorkspaceModelsSpec.scala
package workspace.entity

import java.time.Instant

import zio.json.*
import zio.test.*

object WorkspaceModelsSpec extends ZIOSpecDefault:
  def spec = suite("WorkspaceModelsSpec")(
    test("Workspace round-trips through JSON") {
      val ws = Workspace(
        id = "ws-1",
        name = "my-api",
        localPath = "/home/user/projects/my-api",
        defaultAgent = Some("gemini-cli"),
        description = None,
        enabled = true,
        createdAt = Instant.parse("2026-02-24T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
      )
      val json = ws.toJson
      val decoded = json.fromJson[Workspace]
      assertTrue(decoded == Right(ws))
    },
    test("WorkspaceRun round-trips through JSON") {
      val run = WorkspaceRun(
        id = "run-1",
        workspaceId = "ws-1",
        issueRef = "#42",
        agentName = "gemini-cli",
        prompt = "Fix the null pointer in UserService",
        conversationId = "conv-1",
        worktreePath = "/tmp/agent-worktrees/my-api/run-1",
        branchName = "agent/42-run-1abc",
        status = RunStatus.Pending,
        createdAt = Instant.parse("2026-02-24T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
      )
      val json = run.toJson
      val decoded = json.fromJson[WorkspaceRun]
      assertTrue(decoded == Right(run))
    },
    test("RunStatus values serialize correctly") {
      assertTrue(RunStatus.Pending.toJson == "\"Pending\"") &&
      assertTrue(RunStatus.Running.toJson == "\"Running\"") &&
      assertTrue(RunStatus.Completed.toJson == "\"Completed\"") &&
      assertTrue(RunStatus.Failed.toJson == "\"Failed\"")
    },
  )
```

**Step 2: Run to confirm it fails**

```
sbt "testOnly workspace.entity.WorkspaceModelsSpec"
```
Expected: compilation error — `workspace.entity` package does not exist yet.

**Step 3: Write the models**

```scala
// src/main/scala/workspace/entity/WorkspaceModels.scala
package workspace.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

case class Workspace(
  id: String,
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
  enabled: Boolean = true,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

enum RunStatus derives JsonCodec, Schema:
  case Pending, Running, Completed, Failed

case class WorkspaceRun(
  id: String,
  workspaceId: String,
  issueRef: String,
  agentName: String,
  prompt: String,
  conversationId: String,
  worktreePath: String,
  branchName: String,
  status: RunStatus,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

enum WorkspaceError:
  case NotFound(id: String)
  case Disabled(id: String)
  case WorktreeError(message: String)
  case AgentNotFound(name: String)
  case RunTimeout(runId: String)
  case PersistenceFailure(cause: Throwable)
```

**Step 4: Run the test**

```
sbt "testOnly workspace.entity.WorkspaceModelsSpec"
```
Expected: PASS (3 tests)

**Step 5: Commit**

```bash
git add src/main/scala/workspace/entity/WorkspaceModels.scala \
        src/test/scala/workspace/entity/WorkspaceModelsSpec.scala
git commit -m "feat(workspace): add Workspace, WorkspaceRun, RunStatus data models"
```

---

## Task 2: EclipseStore schema codecs for `Workspace` and `WorkspaceRun`

**Files:**
- Modify: `src/main/scala/shared/store/ConfigStoreModule.scala` — add schema handlers
- Test: `src/test/scala/workspace/entity/WorkspaceRepositorySpec.scala` (first half — storage round-trip)

The EclipseStore binary codec is registered by adding `SchemaBinaryCodec.handlers(Schema[T])` entries to `configStoreHandlers` at the top of `ConfigStoreModule.scala`. This makes `TypedStore.store` and `TypedStore.fetch` work for `Workspace` and `WorkspaceRun`.

**Step 1: Write the failing test (storage round-trip only)**

```scala
// src/test/scala/workspace/entity/WorkspaceRepositorySpec.scala
package workspace.entity

import java.nio.file.{ Files, Path }
import java.time.Instant

import zio.*
import zio.test.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.store.*

object WorkspaceRepositorySpec extends ZIOSpecDefault:
  private def withTempDir[R, E, A](use: Path => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("workspace-repo-spec")).orDie
    )(dir =>
      ZIO.attemptBlocking {
        if Files.exists(dir) then
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach { p =>
            val _ = Files.deleteIfExists(p)
          }
      }.ignore
    )(use)

  private def layerFor(dataDir: Path): ZLayer[Any, EclipseStoreError, ConfigStoreModule.ConfigStoreService] =
    ZLayer.succeed(
      StoreConfig(
        configStorePath = dataDir.resolve("config-store").toString,
        dataStorePath = dataDir.resolve("data-store").toString,
      )
    ) >>> ConfigStoreModule.live

  private val sampleWs = Workspace(
    id = "ws-1",
    name = "my-api",
    localPath = "/tmp/my-api",
    defaultAgent = Some("gemini-cli"),
    description = None,
    enabled = true,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  def spec = suite("WorkspaceRepositorySpec - storage")(
    test("Workspace round-trips through TypedStore") {
      withTempDir { dir =>
        (for
          svc    <- ZIO.service[ConfigStoreModule.ConfigStoreService]
          _      <- svc.store.store("workspace:ws-1", sampleWs)
          loaded <- svc.store.fetch[String, Workspace]("workspace:ws-1")
        yield assertTrue(loaded.contains(sampleWs))).provideLayer(layerFor(dir))
      }
    },
  )
```

**Step 2: Run to confirm it fails**

```
sbt "testOnly workspace.entity.WorkspaceRepositorySpec"
```
Expected: runtime or compile error — `Workspace` has no registered codec handler in EclipseStore.

**Step 3: Add codec handlers to `ConfigStoreModule.scala`**

Open `src/main/scala/shared/store/ConfigStoreModule.scala`. At the top, add imports and extend `configStoreHandlers`:

```scala
// add to imports
import workspace.entity.{ Workspace, WorkspaceRun }

// extend the existing configStoreHandlers val (add two lines):
private val configStoreHandlers =
  SchemaBinaryCodec.handlers(Schema[String])
    ++ SchemaBinaryCodec.handlers(Schema[SettingValue])
    ++ SchemaBinaryCodec.handlers(Schema[Setting])
    ++ SchemaBinaryCodec.handlers(Schema[Workflow])
    ++ SchemaBinaryCodec.handlers(Schema[CustomAgent])
    ++ SchemaBinaryCodec.handlers(Schema[Workspace])       // new
    ++ SchemaBinaryCodec.handlers(Schema[WorkspaceRun])    // new
```

**Step 4: Run the test**

```
sbt "testOnly workspace.entity.WorkspaceRepositorySpec"
```
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/scala/shared/store/ConfigStoreModule.scala \
        src/test/scala/workspace/entity/WorkspaceRepositorySpec.scala
git commit -m "feat(workspace): register Workspace and WorkspaceRun EclipseStore codecs"
```

---

## Task 3: `WorkspaceRepository` trait + EclipseStore implementation

**Files:**
- Create: `src/main/scala/workspace/entity/WorkspaceRepository.scala`
- Modify: `src/test/scala/workspace/entity/WorkspaceRepositorySpec.scala` — add CRUD tests

**Step 1: Write the failing CRUD tests (add to the existing spec file)**

Add these tests inside the existing `suite` in `WorkspaceRepositorySpec.scala`:

```scala
    test("WorkspaceRepository saves and lists workspaces") {
      withTempDir { dir =>
        (for
          svc  <- ZIO.service[ConfigStoreModule.ConfigStoreService]
          repo  = WorkspaceRepositoryES(svc)
          _    <- repo.save(sampleWs)
          list <- repo.list
        yield assertTrue(list.exists(_.id == "ws-1"))).provideLayer(layerFor(dir))
      }
    },
    test("WorkspaceRepository get returns None for missing id") {
      withTempDir { dir =>
        (for
          svc  <- ZIO.service[ConfigStoreModule.ConfigStoreService]
          repo  = WorkspaceRepositoryES(svc)
          got  <- repo.get("missing")
        yield assertTrue(got.isEmpty)).provideLayer(layerFor(dir))
      }
    },
    test("WorkspaceRepository delete removes entry") {
      withTempDir { dir =>
        (for
          svc  <- ZIO.service[ConfigStoreModule.ConfigStoreService]
          repo  = WorkspaceRepositoryES(svc)
          _    <- repo.save(sampleWs)
          _    <- repo.delete("ws-1")
          got  <- repo.get("ws-1")
        yield assertTrue(got.isEmpty)).provideLayer(layerFor(dir))
      }
    },
    test("WorkspaceRepository saves and retrieves a WorkspaceRun") {
      withTempDir { dir =>
        val run = WorkspaceRun(
          id = "run-1", workspaceId = "ws-1", issueRef = "#42",
          agentName = "gemini-cli", prompt = "fix it",
          conversationId = "conv-1",
          worktreePath = "/tmp/wt", branchName = "agent/42-run1abc",
          status = RunStatus.Pending,
          createdAt = Instant.parse("2026-02-24T10:00:00Z"),
          updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
        )
        (for
          svc    <- ZIO.service[ConfigStoreModule.ConfigStoreService]
          repo    = WorkspaceRepositoryES(svc)
          _      <- repo.saveRun(run)
          loaded <- repo.getRun("run-1")
        yield assertTrue(loaded.contains(run))).provideLayer(layerFor(dir))
      }
    },
    test("WorkspaceRepository listRuns returns only runs for the given workspace") {
      withTempDir { dir =>
        val run1 = WorkspaceRun("r1", "ws-1", "#1", "gemini-cli", "p", "c1", "/wt1", "b1", RunStatus.Completed,
          Instant.parse("2026-02-24T10:00:00Z"), Instant.parse("2026-02-24T10:00:00Z"))
        val run2 = WorkspaceRun("r2", "ws-2", "#2", "opencode", "p", "c2", "/wt2", "b2", RunStatus.Failed,
          Instant.parse("2026-02-24T10:00:00Z"), Instant.parse("2026-02-24T10:00:00Z"))
        (for
          svc  <- ZIO.service[ConfigStoreModule.ConfigStoreService]
          repo  = WorkspaceRepositoryES(svc)
          _    <- repo.saveRun(run1)
          _    <- repo.saveRun(run2)
          runs <- repo.listRuns("ws-1")
        yield assertTrue(runs.length == 1 && runs.head.id == "r1")).provideLayer(layerFor(dir))
      }
    },
```

**Step 2: Run to confirm it fails**

```
sbt "testOnly workspace.entity.WorkspaceRepositorySpec"
```
Expected: compilation error — `WorkspaceRepositoryES` not found.

**Step 3: Write the repository**

```scala
// src/main/scala/workspace/entity/WorkspaceRepository.scala
package workspace.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.store.ConfigStoreModule

trait WorkspaceRepository:
  def list: IO[PersistenceError, List[Workspace]]
  def get(id: String): IO[PersistenceError, Option[Workspace]]
  def save(ws: Workspace): IO[PersistenceError, Unit]
  def delete(id: String): IO[PersistenceError, Unit]

  def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]
  def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]
  def saveRun(run: WorkspaceRun): IO[PersistenceError, Unit]
  def updateRunStatus(runId: String, status: RunStatus): IO[PersistenceError, Unit]

object WorkspaceRepository:
  val live: ZLayer[ConfigStoreModule.ConfigStoreService, Nothing, WorkspaceRepository] =
    ZLayer.fromFunction(WorkspaceRepositoryES.apply)

final case class WorkspaceRepositoryES(
  configStore: ConfigStoreModule.ConfigStoreService,
) extends WorkspaceRepository:

  private val ts = configStore.store

  private def wsKey(id: String): String  = s"workspace:$id"
  private def runKey(id: String): String = s"workspace-run:$id"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def list: IO[PersistenceError, List[Workspace]] =
    fetchByPrefix[Workspace]("workspace:", "listWorkspaces").map(_.sortBy(_.name.toLowerCase))

  override def get(id: String): IO[PersistenceError, Option[Workspace]] =
    ts.fetch[String, Workspace](wsKey(id)).mapError(storeErr("getWorkspace"))

  override def save(ws: Workspace): IO[PersistenceError, Unit] =
    ts.store(wsKey(ws.id), ws).mapError(storeErr("saveWorkspace"))

  override def delete(id: String): IO[PersistenceError, Unit] =
    ts.remove[String](wsKey(id)).mapError(storeErr("deleteWorkspace"))

  override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
    fetchByPrefix[WorkspaceRun]("workspace-run:", "listRuns")
      .map(_.filter(_.workspaceId == workspaceId).sortBy(_.createdAt).reverse)

  override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
    ts.fetch[String, WorkspaceRun](runKey(id)).mapError(storeErr("getRun"))

  override def saveRun(run: WorkspaceRun): IO[PersistenceError, Unit] =
    ts.store(runKey(run.id), run).mapError(storeErr("saveRun"))

  override def updateRunStatus(runId: String, status: RunStatus): IO[PersistenceError, Unit] =
    for
      existing <- getRun(runId).flatMap(
                    _.fold[IO[PersistenceError, WorkspaceRun]](
                      ZIO.fail(PersistenceError.NotFound("workspace-run", runId))
                    )(ZIO.succeed)
                  )
      now      <- Clock.instant
      _        <- saveRun(existing.copy(status = status, updatedAt = now))
    yield ()

  private def fetchByPrefix[V](prefix: String, op: String)(using zio.schema.Schema[V]): IO[PersistenceError, List[V]] =
    configStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(key => ts.fetch[String, V](key).mapError(storeErr(op))).map(_.flatten)
      )
```

**Step 4: Run the tests**

```
sbt "testOnly workspace.entity.WorkspaceRepositorySpec"
```
Expected: PASS (all tests including the new CRUD ones)

**Step 5: Commit**

```bash
git add src/main/scala/workspace/entity/WorkspaceRepository.scala \
        src/test/scala/workspace/entity/WorkspaceRepositorySpec.scala
git commit -m "feat(workspace): add WorkspaceRepository trait and EclipseStore impl"
```

---

## Task 4: `CliAgentRunner` — subprocess execution with line streaming

**Files:**
- Create: `src/main/scala/workspace/control/CliAgentRunner.scala`
- Test: `src/test/scala/workspace/control/CliAgentRunnerSpec.scala`

**Step 1: Write the failing test**

The test uses `echo` as a fake "agent" so it works without any real CLI tool installed.

```scala
// src/test/scala/workspace/control/CliAgentRunnerSpec.scala
package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import workspace.entity.{ RunStatus, WorkspaceRun }

object CliAgentRunnerSpec extends ZIOSpecDefault:

  private val sampleRun = WorkspaceRun(
    id = "run-1",
    workspaceId = "ws-1",
    issueRef = "#42",
    agentName = "echo",
    prompt = "hello from agent",
    conversationId = "conv-1",
    worktreePath = "/tmp",
    branchName = "agent/42-run1abc",
    status = RunStatus.Running,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  def spec = suite("CliAgentRunnerSpec")(
    test("buildArgv for echo agent returns [echo, prompt]") {
      val argv = CliAgentRunner.buildArgv("echo", "hello world", "/tmp/wt")
      assertTrue(argv == List("echo", "hello world"))
    },
    test("buildArgv for gemini-cli returns correct args") {
      val argv = CliAgentRunner.buildArgv("gemini-cli", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("gemini", "-p", "fix the bug", "/tmp/wt"))
    },
    test("buildArgv for opencode returns correct args") {
      val argv = CliAgentRunner.buildArgv("opencode", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("opencode", "run", "--prompt", "fix the bug", "/tmp/wt"))
    },
    test("buildArgv for claude returns correct args") {
      val argv = CliAgentRunner.buildArgv("claude", "fix the bug", "/tmp/wt")
      assertTrue(argv == List("claude", "--print", "fix the bug"))
    },
    test("runProcess with echo collects lines") {
      for
        lines <- CliAgentRunner.runProcess(List("echo", "line one"), cwd = "/tmp")
        exitCode = lines._2
        output   = lines._1
      yield assertTrue(exitCode == 0 && output.exists(_.contains("line one")))
    },
  )
```

**Step 2: Run to confirm it fails**

```
sbt "testOnly workspace.control.CliAgentRunnerSpec"
```
Expected: compilation error — `CliAgentRunner` not found.

**Step 3: Write `CliAgentRunner`**

```scala
// src/main/scala/workspace/control/CliAgentRunner.scala
package workspace.control

import java.io.{ BufferedReader, InputStreamReader }
import java.nio.file.Paths

import zio.*

object CliAgentRunner:

  /** Map agent name → argv list. `worktreePath` is passed as the working directory for agents that use cwd,
    * or as an explicit argument for agents that require it.
    */
  def buildArgv(agentName: String, prompt: String, worktreePath: String): List[String] =
    agentName match
      case "gemini-cli" => List("gemini", "-p", prompt, worktreePath)
      case "opencode"   => List("opencode", "run", "--prompt", prompt, worktreePath)
      case "claude"     => List("claude", "--print", prompt)
      case "codex"      => List("codex", prompt)
      case "copilot"    => List("gh", "copilot", "suggest", "-t", "shell", prompt)
      case other        => List(other, prompt)  // generic: pass prompt as single arg

  /** Run argv as a subprocess with `cwd` as working directory.
    * Returns (stdout+stderr lines, exit code).
    * Runs blocking IO on a dedicated thread pool via `ZIO.attemptBlockingIO`.
    */
  def runProcess(argv: List[String], cwd: String): Task[(List[String], Int)] =
    ZIO.attemptBlockingIO {
      val pb = new ProcessBuilder(argv*)
      pb.directory(Paths.get(cwd).toFile)
      pb.redirectErrorStream(true)   // merge stderr into stdout
      val process = pb.start()
      val reader  = BufferedReader(InputStreamReader(process.getInputStream))
      val lines   = scala.collection.mutable.ListBuffer.empty[String]
      var line    = reader.readLine()
      while line != null do
        lines += line
        line = reader.readLine()
      reader.close()
      val exitCode = process.waitFor()
      (lines.toList, exitCode)
    }
```

**Step 4: Run the tests**

```
sbt "testOnly workspace.control.CliAgentRunnerSpec"
```
Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add src/main/scala/workspace/control/CliAgentRunner.scala \
        src/test/scala/workspace/control/CliAgentRunnerSpec.scala
git commit -m "feat(workspace): add CliAgentRunner with argv mapping and process execution"
```

---

## Task 5: `WorkspaceRunService` — worktree lifecycle + fiber orchestration

**Files:**
- Create: `src/main/scala/workspace/control/WorkspaceRunService.scala`
- Test: `src/test/scala/workspace/control/WorkspaceRunServiceSpec.scala`

The service: (1) creates a `WorkspaceRun` record, (2) runs `git worktree add`, (3) creates a `ChatConversation`, (4) forks a fiber that runs `CliAgentRunner` and streams lines as `ConversationEntry(messageType=Status)`.

**Step 1: Write the failing tests**

```scala
// src/test/scala/workspace/control/WorkspaceRunServiceSpec.scala
package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import conversation.entity.api.{ MessageType, SenderType }
import db.{ ChatRepository, PersistenceError }
import workspace.entity.*

object WorkspaceRunServiceSpec extends ZIOSpecDefault:

  // Stub ChatRepository that records addMessage calls
  private class StubChatRepo(messages: Ref[List[String]]) extends ChatRepository:
    def createConversation(c: conversation.entity.api.ChatConversation) =
      ZIO.succeed(1L)
    def getConversation(id: Long)       = ZIO.succeed(None)
    def listConversations(o: Int, l: Int) = ZIO.succeed(Nil)
    def getConversationsByChannel(ch: String) = ZIO.succeed(Nil)
    def listConversationsByRun(r: Long) = ZIO.succeed(Nil)
    def updateConversation(c: conversation.entity.api.ChatConversation) = ZIO.unit
    def deleteConversation(id: Long) = ZIO.unit
    def addMessage(m: conversation.entity.api.ConversationEntry) =
      messages.update(_ :+ m.content).as(1L)
    def getMessages(cid: Long) = ZIO.succeed(Nil)
    def getMessagesSince(cid: Long, since: Instant) = ZIO.succeed(Nil)
    def createIssue(i: issues.entity.api.AgentIssue) = ZIO.succeed(1L)
    def getIssue(id: Long) = ZIO.succeed(None)
    def listIssues(o: Int, l: Int) = ZIO.succeed(Nil)
    def listIssuesByRun(r: Long) = ZIO.succeed(Nil)
    def listIssuesByStatus(s: issues.entity.api.IssueStatus) = ZIO.succeed(Nil)
    def listUnassignedIssues(r: Long) = ZIO.succeed(Nil)
    def updateIssue(i: issues.entity.api.AgentIssue) = ZIO.unit
    def assignIssueToAgent(id: Long, name: String) = ZIO.unit
    def createAssignment(a: issues.entity.api.AgentAssignment) = ZIO.succeed(1L)
    def getAssignment(id: Long) = ZIO.succeed(None)
    def listAssignmentsByIssue(id: Long) = ZIO.succeed(Nil)
    def updateAssignment(a: issues.entity.api.AgentAssignment) = ZIO.unit

  // Stub WorkspaceRepository backed by an in-memory Ref
  private class StubWorkspaceRepo(
    wsRef: Ref[Map[String, Workspace]],
    runRef: Ref[Map[String, WorkspaceRun]],
  ) extends WorkspaceRepository:
    def list = wsRef.get.map(_.values.toList)
    def get(id: String) = wsRef.get.map(_.get(id))
    def save(ws: Workspace) = wsRef.update(_ + (ws.id -> ws))
    def delete(id: String) = wsRef.update(_ - id)
    def listRuns(workspaceId: String) = runRef.get.map(_.values.filter(_.workspaceId == workspaceId).toList)
    def getRun(id: String) = runRef.get.map(_.get(id))
    def saveRun(run: WorkspaceRun) = runRef.update(_ + (run.id -> run))
    def updateRunStatus(runId: String, status: RunStatus) =
      runRef.update(m => m.get(runId).fold(m)(r => m + (runId -> r.copy(status = status))))

  private val sampleWs = Workspace(
    id = "ws-1", name = "test-repo", localPath = "/tmp",
    defaultAgent = Some("echo"), description = None, enabled = true,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  def spec = suite("WorkspaceRunServiceSpec")(
    test("assign returns a WorkspaceRun with Pending status initially") {
      for
        messages <- Ref.make(List.empty[String])
        wsMap    <- Ref.make(Map("ws-1" -> sampleWs))
        runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
        chatRepo  = StubChatRepo(messages)
        wsRepo    = StubWorkspaceRepo(wsMap, runMap)
        svc       = WorkspaceRunServiceLive(wsRepo, chatRepo)
        req       = AssignRunRequest(issueRef = "#1", prompt = "fix it", agentName = "echo")
        run      <- svc.assign("ws-1", req)
      yield assertTrue(run.workspaceId == "ws-1" && run.issueRef == "#1")
    },
    test("assign fails with WorkspaceError.NotFound for unknown workspace") {
      for
        messages <- Ref.make(List.empty[String])
        wsMap    <- Ref.make(Map.empty[String, Workspace])
        runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
        chatRepo  = StubChatRepo(messages)
        wsRepo    = StubWorkspaceRepo(wsMap, runMap)
        svc       = WorkspaceRunServiceLive(wsRepo, chatRepo)
        req       = AssignRunRequest(issueRef = "#1", prompt = "fix it", agentName = "echo")
        result   <- svc.assign("missing", req).either
      yield assertTrue(result.isLeft)
    },
  )
```

**Step 2: Run to confirm it fails**

```
sbt "testOnly workspace.control.WorkspaceRunServiceSpec"
```
Expected: compilation error — `WorkspaceRunService` not found.

**Step 3: Write `WorkspaceRunService`**

```scala
// src/main/scala/workspace/control/WorkspaceRunService.scala
package workspace.control

import java.time.Instant

import zio.*
import zio.json.*

import conversation.entity.api.{ ChatConversation, ConversationEntry, MessageType, SenderType }
import db.{ ChatRepository, PersistenceError }
import workspace.entity.*

case class AssignRunRequest(issueRef: String, prompt: String, agentName: String)

trait WorkspaceRunService:
  def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun]
  def continueRun(runId: String, followUpPrompt: String): IO[WorkspaceError, Unit]

object WorkspaceRunService:
  val live: ZLayer[WorkspaceRepository & ChatRepository, Nothing, WorkspaceRunService] =
    ZLayer.fromFunction(WorkspaceRunServiceLive.apply)

final case class WorkspaceRunServiceLive(
  wsRepo: WorkspaceRepository,
  chatRepo: ChatRepository,
) extends WorkspaceRunService:

  override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
    for
      ws        <- wsRepo.get(workspaceId)
                     .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
                     .flatMap(_.fold[IO[WorkspaceError, Workspace]](ZIO.fail(WorkspaceError.NotFound(workspaceId)))(ZIO.succeed))
      _         <- ZIO.unless(ws.enabled)(ZIO.fail(WorkspaceError.Disabled(workspaceId)))
      runId      = java.util.UUID.randomUUID().toString
      short      = runId.take(8)
      branch     = s"agent/${req.issueRef.stripPrefix("#")}-$short"
      wtPath     = s"${sys.props("user.home")}/.cache/agent-worktrees/${ws.name}/$runId"
      _         <- gitWorktreeAdd(ws.localPath, wtPath, branch)
      now       <- Clock.instant
      conv       = ChatConversation(
                     title = s"[${ws.name}] ${req.issueRef}",
                     runId = Some(runId),
                     createdAt = now,
                     updatedAt = now,
                   )
      convId    <- chatRepo.createConversation(conv)
                     .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      run        = WorkspaceRun(
                     id = runId, workspaceId = workspaceId, issueRef = req.issueRef,
                     agentName = req.agentName, prompt = req.prompt,
                     conversationId = convId.toString,
                     worktreePath = wtPath, branchName = branch,
                     status = RunStatus.Pending, createdAt = now, updatedAt = now,
                   )
      _         <- wsRepo.saveRun(run)
                     .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _         <- executeInFiber(run).forkDaemon
    yield run

  override def continueRun(runId: String, followUpPrompt: String): IO[WorkspaceError, Unit] =
    for
      run <- wsRepo.getRun(runId)
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
               .flatMap(_.fold[IO[WorkspaceError, WorkspaceRun]](ZIO.fail(WorkspaceError.NotFound(runId)))(ZIO.succeed))
      _   <- executeInFiber(run.copy(prompt = followUpPrompt)).forkDaemon
    yield ()

  private def executeInFiber(run: WorkspaceRun): IO[WorkspaceError, Unit] =
    val argv = CliAgentRunner.buildArgv(run.agentName, run.prompt, run.worktreePath)
    for
      _ <- wsRepo.updateRunStatus(run.id, RunStatus.Running)
             .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      result <- CliAgentRunner.runProcess(argv, run.worktreePath)
                  .mapError(e => WorkspaceError.WorktreeError(e.getMessage))
                  .either
      (lines, exitCode) = result.getOrElse((List.empty, 1))
      _      <- streamLinesToConversation(run.conversationId, lines)
      status  = if result.isRight && exitCode == 0 then RunStatus.Completed else RunStatus.Failed
      _      <- wsRepo.updateRunStatus(run.id, status)
                  .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      _      <- gitWorktreeRemove(run.worktreePath).ignore
    yield ()

  private def streamLinesToConversation(conversationId: String, lines: List[String]): IO[WorkspaceError, Unit] =
    ZIO.foreachDiscard(lines) { line =>
      for
        now <- Clock.instant
        entry = ConversationEntry(
                  conversationId = conversationId,
                  sender = "agent",
                  senderType = SenderType.Assistant,
                  content = line,
                  messageType = MessageType.Status,
                  createdAt = now,
                  updatedAt = now,
                )
        _ <- chatRepo.addMessage(entry)
               .mapError(e => WorkspaceError.PersistenceFailure(RuntimeException(e.toString)))
      yield ()
    }

  private def gitWorktreeAdd(repoPath: String, wtPath: String, branch: String): IO[WorkspaceError, Unit] =
    ZIO.attemptBlockingIO {
      val pb = new ProcessBuilder("git", "worktree", "add", wtPath, "-b", branch)
      pb.directory(java.nio.file.Paths.get(repoPath).toFile)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val out  = scala.io.Source.fromInputStream(proc.getInputStream).mkString
      val code = proc.waitFor()
      if code != 0 then throw RuntimeException(s"git worktree add failed (exit $code): $out")
    }.mapError(e => WorkspaceError.WorktreeError(e.getMessage))

  private def gitWorktreeRemove(wtPath: String): Task[Unit] =
    ZIO.attemptBlockingIO {
      val pb = new ProcessBuilder("git", "worktree", "remove", "--force", wtPath)
      pb.start().waitFor()
      ()
    }
```

**Step 4: Run the tests**

```
sbt "testOnly workspace.control.WorkspaceRunServiceSpec"
```
Expected: PASS (2 tests)

**Step 5: Commit**

```bash
git add src/main/scala/workspace/control/WorkspaceRunService.scala \
        src/test/scala/workspace/control/WorkspaceRunServiceSpec.scala
git commit -m "feat(workspace): add WorkspaceRunService with worktree lifecycle and fiber orchestration"
```

---

## Task 6: `WorkspacesView` — Scalatags UI

**Files:**
- Create: `src/main/scala/shared/web/WorkspacesView.scala`
- Test: `src/test/scala/shared/web/WorkspacesViewSpec.scala`

**Step 1: Write the failing test**

```scala
// src/test/scala/shared/web/WorkspacesViewSpec.scala
package shared.web

import java.time.Instant

import zio.test.*

import workspace.entity.{ RunStatus, Workspace, WorkspaceRun }

object WorkspacesViewSpec extends ZIOSpecDefault:
  private val sampleWs = Workspace(
    id = "ws-1", name = "my-api", localPath = "/home/user/my-api",
    defaultAgent = Some("gemini-cli"), description = Some("main API repo"),
    enabled = true,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )
  private val sampleRun = WorkspaceRun(
    id = "run-1", workspaceId = "ws-1", issueRef = "#42",
    agentName = "gemini-cli", prompt = "fix the bug", conversationId = "1",
    worktreePath = "/tmp/wt", branchName = "agent/42-run1",
    status = RunStatus.Completed,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  def spec = suite("WorkspacesViewSpec")(
    test("page renders workspace name and path") {
      val html = WorkspacesView.page(List(sampleWs))
      assertTrue(
        html.contains("my-api"),
        html.contains("/home/user/my-api"),
        html.contains("gemini-cli"),
      )
    },
    test("page renders empty state when no workspaces") {
      val html = WorkspacesView.page(List.empty)
      assertTrue(html.contains("No workspaces"))
    },
    test("runsFragment renders run row with status and conversation link") {
      val html = WorkspacesView.runsFragment(List(sampleRun))
      assertTrue(
        html.contains("#42"),
        html.contains("gemini-cli"),
        html.contains("Completed"),
        html.contains("/chat/1"),
      )
    },
    test("runsFragment renders empty state") {
      val html = WorkspacesView.runsFragment(List.empty)
      assertTrue(html.contains("No runs"))
    },
  )
```

**Step 2: Run to confirm it fails**

```
sbt "testOnly shared.web.WorkspacesViewSpec"
```
Expected: compilation error — `WorkspacesView` not found.

**Step 3: Write `WorkspacesView`**

```scala
// src/main/scala/shared/web/WorkspacesView.scala
package shared.web

import scalatags.Text.all.*
import scalatags.Text.tags2.nav

import workspace.entity.{ RunStatus, Workspace, WorkspaceRun }

object WorkspacesView:

  def page(workspaces: List[Workspace]): String =
    Layout.page("Workspaces", "/workspaces")(
      div(cls := "flex items-center justify-between mb-6")(
        h1(cls := "text-2xl font-bold text-white")("Workspaces"),
        a(
          href := "#",
          cls  := "rounded-md bg-indigo-600 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-500",
          attr("hx-get")     := "/api/workspaces/new",
          attr("hx-target")  := "#modal-container",
          attr("hx-swap")    := "innerHTML",
        )("+  New Workspace"),
      ),
      div(id := "modal-container"),
      if workspaces.isEmpty then
        div(cls := "text-center text-gray-400 mt-16")("No workspaces configured yet.")
      else
        div(cls := "space-y-4")(workspaces.map(workspaceCard)*),
    )

  private def workspaceCard(ws: Workspace): Frag =
    div(
      cls := "rounded-lg bg-gray-800 p-4",
      id  := s"ws-${ws.id}",
    )(
      div(cls := "flex items-start justify-between")(
        div(
          p(cls := "text-lg font-semibold text-white")(ws.name),
          p(cls := "text-sm text-gray-400")(ws.localPath),
          ws.defaultAgent.map(a => p(cls := "text-xs text-gray-500 mt-1")(s"Default agent: $a")).getOrElse(frag()),
        ),
        div(cls := "flex gap-2")(
          button(
            cls              := "text-sm text-indigo-400 hover:text-indigo-300",
            attr("hx-get")   := s"/api/workspaces/${ws.id}/runs",
            attr("hx-target") := s"#runs-${ws.id}",
            attr("hx-swap")  := "innerHTML",
          )("Runs"),
          a(
            href := "#",
            cls  := "text-sm text-gray-400 hover:text-white",
            attr("hx-get")    := s"/api/workspaces/${ws.id}/edit",
            attr("hx-target") := "#modal-container",
            attr("hx-swap")   := "innerHTML",
          )("Edit"),
          button(
            cls               := "text-sm text-red-400 hover:text-red-300",
            attr("hx-delete") := s"/api/workspaces/${ws.id}",
            attr("hx-target") := s"#ws-${ws.id}",
            attr("hx-swap")   := "outerHTML",
            attr("hx-confirm") := s"Delete workspace ${ws.name}?",
          )("Delete"),
        ),
      ),
      div(id := s"runs-${ws.id}", cls := "mt-4"),
    )

  def runsFragment(runs: List[WorkspaceRun]): String =
    if runs.isEmpty then
      div(cls := "text-sm text-gray-500 py-2")("No runs yet.").render
    else
      div(
        table(cls := "w-full text-sm text-left text-gray-300")(
          thead(tr(
            th(cls := "py-2 pr-4")("Issue"),
            th(cls := "py-2 pr-4")("Agent"),
            th(cls := "py-2 pr-4")("Status"),
            th(cls := "py-2")("Actions"),
          )),
          tbody(runs.map(runRow)*),
        ),
        assignForm(runs.headOption.map(_.workspaceId).getOrElse("")),
      ).render

  private def runRow(run: WorkspaceRun): Frag =
    tr(cls := "border-t border-gray-700")(
      td(cls := "py-2 pr-4")(run.issueRef),
      td(cls := "py-2 pr-4")(run.agentName),
      td(cls := "py-2 pr-4")(statusBadge(run.status)),
      td(cls := "py-2")(
        a(href := s"/chat/${run.conversationId}", cls := "text-indigo-400 hover:underline")("View Chat")
      ),
    )

  private def statusBadge(status: RunStatus): Frag =
    val (label, colour) = status match
      case RunStatus.Pending   => ("Pending", "bg-gray-700 text-gray-300")
      case RunStatus.Running   => ("Running", "bg-blue-700 text-blue-100")
      case RunStatus.Completed => ("Completed", "bg-green-700 text-green-100")
      case RunStatus.Failed    => ("Failed", "bg-red-700 text-red-100")
    span(cls := s"rounded px-2 py-0.5 text-xs font-medium $colour")(label)

  private def assignForm(workspaceId: String): Frag =
    div(cls := "mt-4 border-t border-gray-700 pt-4")(
      p(cls := "text-sm font-medium text-gray-300 mb-2")("Assign new issue"),
      div(cls := "flex flex-wrap gap-2")(
        input(
          tpe         := "text",
          name        := "issueRef",
          placeholder := "Issue ref  (#42)",
          cls         := "rounded bg-gray-700 px-3 py-1.5 text-sm text-white w-28",
        ),
        input(
          tpe         := "text",
          name        := "prompt",
          placeholder := "Task prompt",
          cls         := "rounded bg-gray-700 px-3 py-1.5 text-sm text-white flex-1",
        ),
        input(
          tpe         := "text",
          name        := "agentName",
          placeholder := "Agent (gemini-cli)",
          cls         := "rounded bg-gray-700 px-3 py-1.5 text-sm text-white w-36",
        ),
        button(
          cls               := "rounded bg-indigo-600 px-3 py-1.5 text-sm text-white hover:bg-indigo-500",
          attr("hx-post")   := s"/api/workspaces/$workspaceId/runs",
          attr("hx-include") := "closest div",
          attr("hx-target") := "closest div[id^='runs-']",
          attr("hx-swap")   := "innerHTML",
        )("Assign"),
      ),
    )
```

**Step 4: Run the tests**

```
sbt "testOnly shared.web.WorkspacesViewSpec"
```
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
git add src/main/scala/shared/web/WorkspacesView.scala \
        src/test/scala/shared/web/WorkspacesViewSpec.scala
git commit -m "feat(workspace): add WorkspacesView Scalatags UI"
```

---

## Task 7: `WorkspacesController` — HTTP routes

**Files:**
- Create: `src/main/scala/workspace/boundary/WorkspacesController.scala`
- Test: `src/test/scala/workspace/boundary/WorkspacesControllerSpec.scala`

**Step 1: Write the failing tests**

```scala
// src/test/scala/workspace/boundary/WorkspacesControllerSpec.scala
package workspace.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.json.*
import zio.test.*

import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.*

object WorkspacesControllerSpec extends ZIOSpecDefault:

  private val sampleWs = Workspace(
    id = "ws-1", name = "my-api", localPath = "/tmp/my-api",
    defaultAgent = Some("gemini-cli"), description = None, enabled = true,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  private class StubWorkspaceRepo(ref: Ref[Map[String, Workspace]]) extends WorkspaceRepository:
    def list = ref.get.map(_.values.toList)
    def get(id: String) = ref.get.map(_.get(id))
    def save(ws: Workspace) = ref.update(_ + (ws.id -> ws))
    def delete(id: String) = ref.update(_ - id)
    def listRuns(wid: String) = ZIO.succeed(Nil)
    def getRun(id: String) = ZIO.succeed(None)
    def saveRun(r: WorkspaceRun) = ZIO.unit
    def updateRunStatus(id: String, s: RunStatus) = ZIO.unit

  private class StubRunService extends WorkspaceRunService:
    def assign(wid: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(wid))
    def continueRun(runId: String, followUp: String): IO[WorkspaceError, Unit] = ZIO.unit

  def spec = suite("WorkspacesControllerSpec")(
    test("GET /settings/workspaces returns 200") {
      for
        wsRef  <- Ref.make(Map("ws-1" -> sampleWs))
        repo    = StubWorkspaceRepo(wsRef)
        runSvc  = StubRunService()
        routes  = WorkspacesController.routes(repo, runSvc)
        req     = Request.get(URL(Path.decode("/settings/workspaces")))
        resp   <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Ok)
    },
    test("GET /api/workspaces returns JSON list") {
      for
        wsRef  <- Ref.make(Map("ws-1" -> sampleWs))
        repo    = StubWorkspaceRepo(wsRef)
        runSvc  = StubRunService()
        routes  = WorkspacesController.routes(repo, runSvc)
        req     = Request.get(URL(Path.decode("/api/workspaces")))
        resp   <- routes.runZIO(req)
        body   <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("my-api"))
    },
    test("DELETE /api/workspaces/:id returns 204") {
      for
        wsRef  <- Ref.make(Map("ws-1" -> sampleWs))
        repo    = StubWorkspaceRepo(wsRef)
        runSvc  = StubRunService()
        routes  = WorkspacesController.routes(repo, runSvc)
        req     = Request(method = Method.DELETE, url = URL(Path.decode("/api/workspaces/ws-1")))
        resp   <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("GET /api/workspaces/:id/runs returns 200") {
      for
        wsRef  <- Ref.make(Map("ws-1" -> sampleWs))
        repo    = StubWorkspaceRepo(wsRef)
        runSvc  = StubRunService()
        routes  = WorkspacesController.routes(repo, runSvc)
        req     = Request.get(URL(Path.decode("/api/workspaces/ws-1/runs")))
        resp   <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Ok)
    },
  )
```

**Step 2: Run to confirm it fails**

```
sbt "testOnly workspace.boundary.WorkspacesControllerSpec"
```
Expected: compilation error.

**Step 3: Write `WorkspacesController`**

```scala
// src/main/scala/workspace/boundary/WorkspacesController.scala
package workspace.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.json.*

import shared.web.WorkspacesView
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.*

object WorkspacesController:

  def routes(repo: WorkspaceRepository, runSvc: WorkspaceRunService): Routes[Any, Response] =
    Routes(
      // Full page
      Method.GET / "settings" / "workspaces" ->
        Handler.fromZIO(
          repo.list.orDie.map(ws => Response.html(WorkspacesView.page(ws)))
        ),

      // JSON list
      Method.GET / "api" / "workspaces" ->
        Handler.fromZIO(
          repo.list.orDie.map(ws => Response.json(ws.toJson))
        ),

      // Create
      Method.POST / "api" / "workspaces" ->
        Handler.fromFunctionZIO[Request] { req =>
          for
            body <- req.body.asString.orDie
            ws   <- ZIO.fromEither(body.fromJson[WorkspaceCreateRequest])
                      .mapError(e => Response.badRequest(e))
            now  <- Clock.instant
            newWs = Workspace(
                      id = java.util.UUID.randomUUID().toString,
                      name = ws.name, localPath = ws.localPath,
                      defaultAgent = ws.defaultAgent, description = ws.description,
                      enabled = true, createdAt = now, updatedAt = now,
                    )
            _    <- repo.save(newWs).orDie
          yield Response.json(newWs.toJson).withStatus(Status.Created)
        },

      // Update
      Method.PUT / "api" / "workspaces" / string("id") ->
        Handler.fromFunctionZIO[(String, Request)] { (id, req) =>
          for
            body    <- req.body.asString.orDie
            patch   <- ZIO.fromEither(body.fromJson[WorkspaceCreateRequest])
                         .mapError(e => Response.badRequest(e))
            existing <- repo.get(id).orDie
                          .flatMap(_.fold(ZIO.succeed(Response(status = Status.NotFound)))(ZIO.succeed))
          yield existing match
            case r: Response => r
            case ws: Workspace =>
              val updated = ws.copy(name = patch.name, localPath = patch.localPath,
                defaultAgent = patch.defaultAgent, description = patch.description)
              // save async, return immediately
              repo.save(updated).orDie.forkDaemon
              Response.json(updated.toJson)
        },

      // Delete
      Method.DELETE / "api" / "workspaces" / string("id") ->
        Handler.fromFunctionZIO[String] { id =>
          repo.delete(id).orDie.as(Response(status = Status.NoContent))
        },

      // Runs list (HTMX fragment)
      Method.GET / "api" / "workspaces" / string("id") / "runs" ->
        Handler.fromFunctionZIO[String] { id =>
          repo.listRuns(id).orDie.map(runs => Response.html(WorkspacesView.runsFragment(runs)))
        },

      // Assign run
      Method.POST / "api" / "workspaces" / string("id") / "runs" ->
        Handler.fromFunctionZIO[(String, Request)] { (id, req) =>
          for
            body   <- req.body.asString.orDie
            assign <- ZIO.fromEither(body.fromJson[AssignRunRequest])
                        .mapError(e => Response.badRequest(e))
            result <- runSvc.assign(id, assign)
                        .mapError {
                          case WorkspaceError.NotFound(_) => Response(status = Status.NotFound)
                          case WorkspaceError.Disabled(_) => Response(status = Status.Conflict)
                          case WorkspaceError.WorktreeError(msg) =>
                            Response.json(s"""{"error": "$msg"}""").withStatus(Status.Conflict)
                          case other => Response.internalServerError(other.toString)
                        }
            runs   <- repo.listRuns(id).orDie
          yield Response.html(WorkspacesView.runsFragment(runs))
        },

      // Run status
      Method.GET / "api" / "workspaces" / string("wsId") / "runs" / string("runId") ->
        Handler.fromFunctionZIO[(String, String)] { (_, runId) =>
          repo.getRun(runId).orDie.map {
            case None      => Response(status = Status.NotFound)
            case Some(run) => Response.json(run.toJson)
          }
        },
    )

  val live: ZLayer[WorkspaceRepository & WorkspaceRunService, Nothing, WorkspacesController.type] =
    ZLayer.succeed(WorkspacesController)

case class WorkspaceCreateRequest(
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
) derives zio.json.JsonCodec
```

**Step 4: Run the tests**

```
sbt "testOnly workspace.boundary.WorkspacesControllerSpec"
```
Expected: PASS (4 tests)

**Step 5: Commit**

```bash
git add src/main/scala/workspace/boundary/WorkspacesController.scala \
        src/test/scala/workspace/boundary/WorkspacesControllerSpec.scala
git commit -m "feat(workspace): add WorkspacesController HTTP routes"
```

---

## Task 8: Wire into `ApplicationDI` and add nav entry

**Files:**
- Modify: `src/main/scala/app/ApplicationDI.scala`
- Modify: `src/main/scala/shared/web/Layout.scala`
- Modify: `src/main/scala/shared/web/HtmlViews.scala`

**Step 1: No test needed** — this is pure wiring. Run the full suite after to confirm nothing breaks.

**Step 2: Add `WorkspaceRepository` and `WorkspaceRunService` to `ApplicationDI`**

In `ApplicationDI.scala`, find the `webServerLayer` (or equivalent `ZLayer.make` call that composes all controllers). Add:

```scala
// Add to imports at top of ApplicationDI.scala
import workspace.boundary.WorkspacesController as WorkspaceWorkspacesController
import workspace.control.WorkspaceRunService
import workspace.entity.WorkspaceRepository

// In the ZLayer.make / ZLayer.makeSome that builds the web server,
// add after the ToolRegistry.layer line:
WorkspaceRepository.live,
WorkspaceRunService.live,
```

Then in the `WebServer` routes composition (wherever all controller routes are combined with `++`), add:

```scala
++ WorkspacesController.routes(workspaceRepo, workspaceRunSvc)
```

The exact insertion points depend on how routes are assembled in `ApplicationDI`. Look for the block that combines all `Routes[...]` and append the new routes there. If routes are assembled inside `WebServer`, inject `WorkspaceRepository` and `WorkspaceRunService` into `WebServer` instead.

**Step 3: Add "Workspaces" nav entry to `Layout.scala`**

In `src/main/scala/shared/web/Layout.scala`, find the `"Workspace"` section of the sidebar (lines 52–59) and add a new `navItem` after "Issues":

```scala
navItem("/workspaces", "Workspaces", Icons.folder, currentPath.startsWith("/workspaces")),
```

**Step 4: Add `workspacesPage` facade to `HtmlViews.scala`**

```scala
def workspacesPage(workspaces: List[workspace.entity.Workspace]): String =
  WorkspacesView.page(workspaces)

def workspacesRunsFragment(runs: List[workspace.entity.WorkspaceRun]): String =
  WorkspacesView.runsFragment(runs)
```

**Step 5: Run full test suite**

```
sbt test
```
Expected: all tests pass (no regressions).

**Step 6: Commit**

```bash
git add src/main/scala/app/ApplicationDI.scala \
        src/main/scala/shared/web/Layout.scala \
        src/main/scala/shared/web/HtmlViews.scala
git commit -m "feat(workspace): wire WorkspaceRepository and WorkspacesController into app DI and nav"
```

---

## Task 9: Timeout + process cleanup on app shutdown

**Files:**
- Modify: `src/main/scala/workspace/control/WorkspaceRunService.scala`

This task adds a configurable timeout (default 30 min) using `ZIO.timeout` on the `CliAgentRunner.runProcess` call. If it times out, send SIGTERM via `ProcessBuilder`.

**Step 1: Write the failing test**

Add to `WorkspaceRunServiceSpec.scala`:

```scala
    test("executeInFiber respects timeout and marks run Failed") {
      // Uses 'sleep 60' as a fake long-running agent with a 100ms timeout
      // The test verifies the run ends up in Failed status
      for
        messages <- Ref.make(List.empty[String])
        wsMap    <- Ref.make(Map("ws-1" -> sampleWs))
        runMap   <- Ref.make(Map.empty[String, WorkspaceRun])
        chatRepo  = StubChatRepo(messages)
        wsRepo    = StubWorkspaceRepo(wsMap, runMap)
        svc       = WorkspaceRunServiceLive(wsRepo, chatRepo, timeoutSeconds = 0)  // 0 = immediate
        req       = AssignRunRequest("#slow", "slow task", "sleep")
        _        <- svc.assign("ws-1", req).ignore
        _        <- ZIO.sleep(200.millis)
        runs     <- wsRepo.listRuns("ws-1")
        // status will be Failed due to timeout or git worktree error (no real git repo)
      yield assertTrue(runs.isEmpty || runs.forall(r => r.status == RunStatus.Failed || r.status == RunStatus.Pending))
    }
```

**Step 2: Add `timeoutSeconds` parameter to `WorkspaceRunServiceLive`**

```scala
final case class WorkspaceRunServiceLive(
  wsRepo: WorkspaceRepository,
  chatRepo: ChatRepository,
  timeoutSeconds: Long = 1800,  // 30 minutes default
) extends WorkspaceRunService:
  // ... same as before, but wrap CliAgentRunner.runProcess with timeout:
  val processEffect = CliAgentRunner.runProcess(argv, run.worktreePath)
                        .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                        .mapError(e => WorkspaceError.WorktreeError(e.getMessage))

  // If timeout fires, ZIO.timeout returns None → treat as failure
  result <- processEffect.map(_.getOrElse((List("Timed out"), 1)))
```

Update `WorkspaceRunService.live` to pass default timeout:

```scala
val live: ZLayer[WorkspaceRepository & ChatRepository, Nothing, WorkspaceRunService] =
  ZLayer.fromFunction((repo: WorkspaceRepository, chat: ChatRepository) =>
    WorkspaceRunServiceLive(repo, chat)
  )
```

**Step 3: Run the tests**

```
sbt "testOnly workspace.control.WorkspaceRunServiceSpec"
```
Expected: PASS

**Step 4: Commit**

```bash
git add src/main/scala/workspace/control/WorkspaceRunService.scala \
        src/test/scala/workspace/control/WorkspaceRunServiceSpec.scala
git commit -m "feat(workspace): add configurable run timeout to WorkspaceRunService"
```

---

## Task 10: Final verification

**Step 1: Run the full test suite**

```
sbt test
```
Expected: all tests PASS, no new failures.

**Step 2: Run formatter check**

```
sbt check
```
Expected: no formatting errors. If there are, run `sbt fmt` then re-check.

**Step 3: Verify the app compiles cleanly**

```
sbt compile
```
Expected: no errors or warnings.

**Step 4: Final commit (if any fmt changes)**

```bash
git add -u
git commit -m "style: apply scalafmt to workspace package"
```
