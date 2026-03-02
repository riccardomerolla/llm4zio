package shared.web

import java.time.Instant

import zio.test.*

import _root_.config.entity.{ AgentInfo, AgentType }
import workspace.entity.{ RunMode, RunStatus, Workspace, WorkspaceRun }

object WorkspacesViewSpec extends ZIOSpecDefault:
  private val sampleAgents: List[AgentInfo] = List(
    AgentInfo(
      name = "code-agent",
      handle = "code-agent",
      displayName = "Code Agent",
      description = "Coding assistant",
      agentType = AgentType.BuiltIn,
      usesAI = true,
      tags = List("code"),
    )
  )

  private val sampleWs = Workspace(
    id = "ws-1",
    name = "my-api",
    localPath = "/home/user/my-api",
    defaultAgent = Some("code-agent"),
    description = Some("main API repo"),
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "claude",
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  private val dockerWs  = sampleWs.copy(
    id = "ws-docker",
    runMode = RunMode.Docker(image = "gemini:latest", network = Some("none")),
  )
  private val sampleRun = WorkspaceRun(
    id = "run-1",
    workspaceId = "ws-1",
    parentRunId = None,
    issueRef = "#42",
    agentName = "gemini-cli",
    prompt = "fix the bug",
    conversationId = "1",
    worktreePath = "/tmp/wt",
    branchName = "agent/42-run1",
    status = RunStatus.Completed,
    attachedUsers = Set.empty,
    controllerUserId = None,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  def spec: Spec[TestEnvironment, Any] = suite("WorkspacesViewSpec")(
    test("page renders workspace name and path") {
      val html = WorkspacesView.page(List(sampleWs), sampleAgents)
      assertTrue(
        html.contains("my-api"),
        html.contains("/home/user/my-api"),
        html.contains("claude"), // default cliTool
      )
    },
    test("page renders empty state when no workspaces") {
      val html = WorkspacesView.page(List.empty, List.empty)
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
    test("workspace card with RunMode.Host renders 'Host'") {
      val html = WorkspacesView.page(List(sampleWs), sampleAgents)
      assertTrue(html.contains("Host"))
    },
    test("workspace card with RunMode.Docker renders 'Docker' and image name") {
      val html = WorkspacesView.page(List(dockerWs), sampleAgents)
      assertTrue(html.contains("Docker") && html.contains("gemini:latest"))
    },
  )
