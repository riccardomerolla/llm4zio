package shared.web

import java.time.Instant

import zio.test.*

import workspace.entity.{ RunStatus, Workspace, WorkspaceRun }

object WorkspacesViewSpec extends ZIOSpecDefault:
  private val sampleWs  = Workspace(
    id = "ws-1",
    name = "my-api",
    localPath = "/home/user/my-api",
    defaultAgent = Some("gemini-cli"),
    description = Some("main API repo"),
    enabled = true,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )
  private val sampleRun = WorkspaceRun(
    id = "run-1",
    workspaceId = "ws-1",
    issueRef = "#42",
    agentName = "gemini-cli",
    prompt = "fix the bug",
    conversationId = "1",
    worktreePath = "/tmp/wt",
    branchName = "agent/42-run1",
    status = RunStatus.Completed,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  def spec: Spec[TestEnvironment, Any] = suite("WorkspacesViewSpec")(
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
