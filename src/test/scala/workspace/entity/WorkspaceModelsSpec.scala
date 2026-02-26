package workspace.entity

import java.time.Instant

import zio.Scope
import zio.json.*
import zio.test.*

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
    test("Workspace round-trips through JSON") {
      val ws      = Workspace(
        id = "ws-1",
        name = "my-api",
        localPath = "/home/user/projects/my-api",
        defaultAgent = Some("gemini-cli"),
        description = None,
        enabled = true,
        createdAt = Instant.parse("2026-02-24T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
      )
      val json    = ws.toJson
      val decoded = json.fromJson[Workspace]
      assertTrue(decoded == Right(ws))
    },
    test("Workspace with RunMode.Docker round-trips through JSON") {
      val ws      = Workspace(
        id = "ws-docker",
        name = "sandboxed-api",
        localPath = "/home/user/projects/sandboxed-api",
        defaultAgent = Some("opencode"),
        description = None,
        enabled = true,
        runMode = RunMode.Docker(image = "ghcr.io/opencode-ai/opencode:latest", network = Some("none")),
        createdAt = Instant.parse("2026-02-24T10:00:00Z"),
        updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
      )
      val json    = ws.toJson
      val decoded = json.fromJson[Workspace]
      assertTrue(decoded == Right(ws))
    },
    test("WorkspaceRun round-trips through JSON") {
      val run     = WorkspaceRun(
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
      val json    = run.toJson
      val decoded = json.fromJson[WorkspaceRun]
      assertTrue(decoded == Right(run))
    },
    test("RunStatus values round-trip through JSON") {
      val statuses: List[RunStatus] = List(RunStatus.Pending, RunStatus.Running, RunStatus.Completed, RunStatus.Failed)
      assertTrue(statuses.forall(s => s.toJson.fromJson[RunStatus] == Right(s)))
    },
  )
