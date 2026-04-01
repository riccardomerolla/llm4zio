package workspace.entity

import java.time.Instant

import zio.Scope
import zio.json.*
import zio.test.*

import shared.ids.Ids.ProjectId

object WorkspaceModelsSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("WorkspaceModelsSpec")(
    test("RunMode.Host round-trips through JSON") {
      val mode: RunMode = RunMode.Host
      assertTrue(mode.toJson.fromJson[RunMode] == Right(mode))
    },
    test("RunMode.Docker round-trips through JSON with all optional fields") {
      val mode: RunMode = RunMode.Docker(
        image = "ghcr.io/opencode-ai/opencode:latest",
        extraArgs = List("--env", "FOO=bar"),
        mountWorktree = true,
        network = Some("none"),
      )
      assertTrue(mode.toJson.fromJson[RunMode] == Right(mode))
    },
    test("RunMode.Docker round-trips through JSON with defaults") {
      val mode: RunMode = RunMode.Docker(image = "gemini:latest")
      assertTrue(mode.toJson.fromJson[RunMode] == Right(mode))
    },
    test("RunMode.Cloud round-trips through JSON with optional fields") {
      val mode: RunMode = RunMode.Cloud(
        provider = "aws-fargate",
        image = "ghcr.io/riccardomerolla/llm4zio-agent:latest",
        region = Some("eu-west-1"),
        network = Some("none"),
      )
      assertTrue(mode.toJson.fromJson[RunMode] == Right(mode))
    },
    test("Workspace round-trips through JSON") {
      val ws      = Workspace(
        id = "ws-1",
        projectId = ProjectId("test-project"),
        name = "my-api",
        localPath = "/home/user/projects/my-api",
        defaultAgent = Some("gemini"),
        description = None,
        enabled = true,
        runMode = RunMode.Host,
        cliTool = "gemini",
        createdAt = Instant.parse("2026-02-24T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
        defaultBranch = "main",
      )
      val json    = ws.toJson
      val decoded = json.fromJson[Workspace]
      assertTrue(decoded == Right(ws))
    },
    test("Workspace with RunMode.Docker round-trips through JSON") {
      val ws      = Workspace(
        id = "ws-docker",
        projectId = ProjectId("test-project"),
        name = "sandboxed-api",
        localPath = "/home/user/projects/sandboxed-api",
        defaultAgent = Some("opencode"),
        description = None,
        enabled = true,
        runMode = RunMode.Docker(image = "ghcr.io/opencode-ai/opencode:latest", network = Some("none")),
        cliTool = "opencode",
        createdAt = Instant.parse("2026-02-24T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
        defaultBranch = "main",
      )
      val json    = ws.toJson
      val decoded = json.fromJson[Workspace]
      assertTrue(decoded == Right(ws))
    },
    test("Workspace with RunMode.Cloud round-trips through JSON") {
      val ws      = Workspace(
        id = "ws-cloud",
        projectId = ProjectId("test-project"),
        name = "remote-api",
        localPath = "/home/user/projects/remote-api",
        defaultAgent = Some("codex"),
        description = Some("cloud execution"),
        enabled = true,
        runMode = RunMode.Cloud(
          provider = "aws-fargate",
          image = "ghcr.io/riccardomerolla/llm4zio-agent:latest",
          region = Some("eu-west-1"),
          network = Some("none"),
        ),
        cliTool = "codex",
        createdAt = Instant.parse("2026-02-24T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
        defaultBranch = "release",
      )
      val json    = ws.toJson
      val decoded = json.fromJson[Workspace]
      assertTrue(decoded == Right(ws))
    },
    test("WorkspaceRun round-trips through JSON") {
      val run     = WorkspaceRun(
        id = "run-1",
        workspaceId = "ws-1",
        parentRunId = Some("run-0"),
        issueRef = "#42",
        agentName = "gemini-cli",
        prompt = "Fix the null pointer in UserService",
        conversationId = "conv-1",
        worktreePath = "/tmp/agent-worktrees/my-api/run-1",
        branchName = "agent/42-run-1abc",
        status = RunStatus.Pending,
        attachedUsers = Set.empty,
        controllerUserId = None,
        createdAt = Instant.parse("2026-02-24T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
      )
      val json    = run.toJson
      val decoded = json.fromJson[WorkspaceRun]
      assertTrue(decoded == Right(run))
    },
    test("Workspace defaults to main branch when rebuilt from legacy events") {
      val now       = Instant.parse("2026-03-30T08:00:00Z")
      val workspace = Workspace.fromEvents(
        List(
          WorkspaceEvent.Created(
            workspaceId = "ws-legacy",
            projectId = ProjectId("test-project"),
            name = "legacy",
            localPath = "/tmp/legacy",
            defaultAgent = None,
            description = None,
            cliTool = "codex",
            runMode = RunMode.Host,
            occurredAt = now,
          )
        )
      )
      assertTrue(workspace.exists(_.defaultBranch == "main"))
    },
    test("Workspace applies explicit default branch change event") {
      val now       = Instant.parse("2026-03-30T08:00:00Z")
      val workspace = Workspace.fromEvents(
        List(
          WorkspaceEvent.Created(
            workspaceId = "ws-branch",
            projectId = ProjectId("test-project"),
            name = "branchy",
            localPath = "/tmp/branchy",
            defaultAgent = None,
            description = None,
            cliTool = "codex",
            runMode = RunMode.Host,
            occurredAt = now,
          ),
          WorkspaceEvent.DefaultBranchChanged(
            workspaceId = "ws-branch",
            defaultBranch = "develop",
            occurredAt = now.plusSeconds(1),
          ),
        )
      )
      assertTrue(workspace.exists(_.defaultBranch == "develop"))
    },
    test("RunStatus values round-trip through JSON") {
      val statuses: List[RunStatus] = List(
        RunStatus.Pending,
        RunStatus.Running(RunSessionMode.Autonomous),
        RunStatus.Running(RunSessionMode.Interactive),
        RunStatus.Running(RunSessionMode.Paused),
        RunStatus.Completed,
        RunStatus.Failed,
      )
      assertTrue(statuses.forall(s => s.toJson.fromJson[RunStatus] == Right(s)))
    },
    test("WorkspaceRun events apply interactive session transitions") {
      val now    = Instant.parse("2026-03-02T08:00:00Z")
      val events = List[WorkspaceRunEvent](
        WorkspaceRunEvent.Assigned(
          runId = "run-evt",
          workspaceId = "ws-1",
          parentRunId = None,
          issueRef = "#1",
          agentName = "claude",
          prompt = "fix",
          conversationId = "conv-1",
          worktreePath = "/tmp/wt",
          branchName = "agent/1",
          occurredAt = now,
        ),
        WorkspaceRunEvent.StatusChanged("run-evt", RunStatus.Running(RunSessionMode.Autonomous), now.plusSeconds(1)),
        WorkspaceRunEvent.UserAttached("run-evt", "alice", now.plusSeconds(2)),
        WorkspaceRunEvent.RunInterrupted("run-evt", "alice", now.plusSeconds(3)),
        WorkspaceRunEvent.RunResumed("run-evt", "alice", "continue", now.plusSeconds(4)),
        WorkspaceRunEvent.UserDetached("run-evt", "alice", now.plusSeconds(5)),
        WorkspaceRunEvent.CleanupRecorded(
          "run-evt",
          worktreeRemoved = true,
          branchDeleted = true,
          "cleanup done",
          now.plusSeconds(6),
        ),
      )
      val run    = WorkspaceRun.fromEvents(events)
      assertTrue(
        run.exists(_.status == RunStatus.Running(RunSessionMode.Autonomous)),
        run.exists(_.attachedUsers.isEmpty),
        run.exists(_.controllerUserId.isEmpty),
        run.exists(_.updatedAt == now.plusSeconds(6)),
      )
    },
  )
