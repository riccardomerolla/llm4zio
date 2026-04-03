package shared.web

import java.time.Instant

import zio.test.*

import workspace.entity.{ RunStatus, WorkspaceRun }

object WorkspacesViewSpec extends ZIOSpecDefault:

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
    test("new workspace form renders default branch field") {
      val html = WorkspacesView.newWorkspaceForm
      assertTrue(
        html.contains("""name="defaultBranch""""),
        html.contains("""value="main""""),
      )
    },
    test("runsDashboardPage renders dashboard table and quick actions") {
      val runningRun = sampleRun.copy(status = RunStatus.Running(workspace.entity.RunSessionMode.Autonomous))
      val html       = WorkspacesView.runsDashboardPage(
        runs = List(runningRun),
        workspaceNameById = Map("ws-1" -> "my-api"),
        workspaceFilter = None,
        agentFilter = None,
        statusFilter = None,
        scopeFilter = "all",
        sortBy = "created",
        dateFrom = None,
        dateTo = None,
        limit = 50,
      )
      assertTrue(
        html.contains("Run Status Dashboard"),
        html.contains("Workspace"),
        html.contains("Duration"),
        html.contains("Last Activity"),
        html.contains("View Changes"),
      )
    },
  )
