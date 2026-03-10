package shared.web

import java.time.Instant

import zio.test.*

import config.entity.{ AgentInfo, AgentType }
import issues.entity.api.{ AgentIssueView, IssuePriority, IssueStatus, IssueTemplate, TemplateVariable }

object IssuesViewSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("IssuesViewSpec")(
    test("markdownFragment renders markdown tables as HTML table") {
      val markdown =
        """| name | age |
          || --- | --- |
          || Alice | 30 |
          || Bob | 35 |
          |""".stripMargin

      val rendered = IssuesView.markdownFragment(markdown).render
      assertTrue(
        rendered.contains("<table"),
        rendered.contains("<thead"),
        rendered.contains("<tbody"),
        rendered.contains("Alice"),
      )
    },
    test("markdownFragment renders inline markdown and breaks inside table cells") {
      val markdown =
        """| Aspect | Details |
          || --- | --- |
          || **Primary** | Line one<br>Line **two** |
          |""".stripMargin

      val rendered = IssuesView.markdownFragment(markdown).render
      assertTrue(
        rendered.contains("<strong>Primary</strong>"),
        rendered.contains("Line one"),
        rendered.contains("Line <strong>two</strong>"),
        rendered.contains("<br"),
      )
    },
    test("new form renders workspace selector") {
      val html = IssuesView.newForm(
        defaultRunId = None,
        workspaces = List("ws-1" -> "Main Workspace", "ws-2" -> "Legacy Workspace"),
        templates = List(
          IssueTemplate(
            id = "bug-fix",
            name = "Bug Fix",
            description = "Fix bugs",
            issueType = "bug",
            priority = IssuePriority.High,
            tags = List("bug"),
            titleTemplate = "Fix {{component}}",
            descriptionTemplate = "Fix details",
            variables = List(TemplateVariable("component", "Component")),
            isBuiltin = true,
          )
        ),
      )
      assertTrue(
        html.contains("Linked Workspace"),
        html.contains("No workspace linked"),
        html.contains("Main Workspace (ws-1)"),
        html.contains("Template"),
        html.contains("Bug Fix (built-in)"),
        html.contains("issue-create-template-data"),
      )
    },
    test("list renders folder import controls and no legacy markdown import button") {
      val now  = Instant.parse("2026-03-02T10:00:00Z")
      val html = IssuesView.list(
        runId = None,
        issues = List(
          AgentIssueView(
            id = Some("1"),
            title = "Sample issue",
            description = "Sample",
            issueType = "task",
            createdAt = now,
            updatedAt = now,
          )
        ),
        statusFilter = None,
        query = None,
        tagFilter = None,
      )
      assertTrue(
        html.contains("Import Preview"),
        html.contains("data-import-folder=\"list\""),
        html.contains("/path/to/issues-folder"),
        !html.contains(">Import markdown<"),
      )
    },
    test("detail renders workspace badge and simplified assign when workspace linked") {
      val now   = Instant.parse("2026-03-02T10:00:00Z")
      val issue = AgentIssueView(
        id = Some("123"),
        title = "Linked issue",
        description = "Task",
        issueType = "task",
        priority = IssuePriority.Medium,
        status = IssueStatus.Open,
        workspaceId = Some("ws-1"),
        createdAt = now,
        updatedAt = now,
      )
      val html  = IssuesView.detail(
        issue = issue,
        assignments = Nil,
        availableAgents = List(
          AgentInfo(
            name = "agent-1",
            handle = "agent-1",
            displayName = "Agent One",
            description = "desc",
            agentType = AgentType.BuiltIn,
            usesAI = true,
            tags = List("code"),
          )
        ),
        workspaces = List("ws-1" -> "Main Workspace"),
      )
      assertTrue(
        html.contains("Workspace:"),
        html.contains("Main Workspace"),
        html.contains("name=\"workspaceId\" value=\"ws-1\""),
        !html.contains("No workspace linked"),
      )
    },
    test("board renders kanban columns and board root metadata") {
      val now   = Instant.parse("2026-03-02T10:00:00Z")
      val issue = AgentIssueView(
        id = Some("10"),
        title = "Board issue",
        description = "Task",
        issueType = "task",
        priority = IssuePriority.High,
        status = IssueStatus.InProgress,
        assignedAgent = Some("agent-1"),
        workspaceId = Some("ws-1"),
        createdAt = now,
        updatedAt = now,
      )
      val html  = IssuesView.board(
        issues = List(issue),
        workspaces = List("ws-1" -> "Main Workspace"),
        workspaceFilter = None,
        agentFilter = None,
        priorityFilter = None,
        tagFilter = None,
        query = None,
      )
      assertTrue(
        html.contains("Issue Board"),
        html.contains("Board"),
        html.contains("List"),
        html.contains("issues-select-all-board"),
        html.contains("Open"),
        html.contains("Assigned"),
        html.contains("In Progress"),
        html.contains("Completed"),
        html.contains("Failed"),
        html.contains("issues-board-root"),
        html.contains("data-drop-status=\"in_progress\""),
        html.contains("issues-bulk-toolbar-board"),
      )
    },
    test("detail renders external tracker link when issue has external ref") {
      val now   = Instant.parse("2026-03-02T10:00:00Z")
      val issue = AgentIssueView(
        id = Some("ext-1"),
        title = "External linked issue",
        description = "Task",
        issueType = "task",
        priority = IssuePriority.Medium,
        status = IssueStatus.Open,
        externalRef = Some("GH:owner/repo#42"),
        externalUrl = Some("https://github.com/owner/repo/issues/42"),
        createdAt = now,
        updatedAt = now,
      )
      val html  = IssuesView.detail(
        issue = issue,
        assignments = Nil,
        availableAgents = Nil,
        workspaces = Nil,
      )
      assertTrue(
        html.contains("External"),
        html.contains("GH:owner/repo#42"),
        html.contains("https://github.com/owner/repo/issues/42"),
      )
    },
  )
