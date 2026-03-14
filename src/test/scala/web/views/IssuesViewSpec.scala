package shared.web

import java.time.Instant

import zio.test.*

import config.entity.{ AgentInfo, AgentType }
import issues.entity.api.*

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
        html.contains("Acceptance Criteria"),
        html.contains("Proof Of Work Requirements"),
        html.contains("Prompt Template"),
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
        issueRuns = Nil,
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
        analysisDocs = Nil,
        mergeHistory = Nil,
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
        acceptanceCriteria = Some("Tests pass"),
        kaizenSkill = Some("scala-zio-refactor"),
        proofOfWorkRequirements = List("tests pass"),
        createdAt = now,
        updatedAt = now,
      )
      val html  = IssuesView.detail(
        issue = issue,
        issueRuns = Nil,
        availableAgents = Nil,
        analysisDocs = Nil,
        mergeHistory = Nil,
        workspaces = Nil,
      )
      assertTrue(
        html.contains("External"),
        html.contains("GH:owner/repo#42"),
        html.contains("https://github.com/owner/repo/issues/42"),
        html.contains("Acceptance Criteria"),
        html.contains("Kaizen Skill"),
      )
    },
    test("detail renders analysis context tab and vscode deep links") {
      val now  = Instant.parse("2026-03-02T10:00:00Z")
      val html = IssuesView.detail(
        issue = AgentIssueView(
          id = Some("analysis-issue"),
          title = "Review me",
          description = "Task",
          issueType = "task",
          status = IssueStatus.HumanReview,
          createdAt = now,
          updatedAt = now,
        ),
        issueRuns = Nil,
        availableAgents = Nil,
        analysisDocs = List(
          AnalysisContextDocView(
            title = "Architecture",
            content = "## Risks\n\n- Tight coupling",
            filePath = ".llm4zio/analysis/architecture.md",
            vscodeUrl = Some("vscode://file/Users/riccardo/repo/.llm4zio/analysis/architecture.md"),
          )
        ),
        mergeHistory = Nil,
        workspaces = Nil,
      )
      assertTrue(
        html.contains("Analysis Context"),
        html.contains("Open in VSCode"),
        html.contains("vscode://file/Users/riccardo/repo/.llm4zio/analysis/architecture.md"),
        html.contains(".llm4zio/analysis/architecture.md"),
        html.contains("Tight coupling"),
      )
    },
    test("detail renders approve action for HumanReview issues") {
      val now  = Instant.parse("2026-03-02T10:00:00Z")
      val html = IssuesView.detail(
        issue = AgentIssueView(
          id = Some("review-issue"),
          title = "Approve this",
          description = "Task",
          issueType = "task",
          status = IssueStatus.HumanReview,
          createdAt = now,
          updatedAt = now,
        ),
        issueRuns = Nil,
        availableAgents = Nil,
        analysisDocs = Nil,
        mergeHistory = Nil,
        workspaces = Nil,
      )
      assertTrue(
        html.contains("Approve"),
        html.contains("action=\"/issues/review-issue/approve\""),
        html.contains("name=\"approvedBy\" value=\"detail\""),
      )
    },
    test("detail renders merge conflict section with retry merge affordance") {
      val now  = Instant.parse("2026-03-02T10:00:00Z")
      val html = IssuesView.detail(
        issue = AgentIssueView(
          id = Some("merge-issue"),
          title = "Resolve merge",
          description = "Task",
          issueType = "task",
          status = IssueStatus.Rework,
          mergeConflictFiles = List("src/Main.scala", "README.md"),
          createdAt = now,
          updatedAt = now,
        ),
        issueRuns = Nil,
        availableAgents = Nil,
        analysisDocs = Nil,
        mergeHistory = Nil,
        workspaces = Nil,
      )
      assertTrue(
        html.contains("Merge Conflict"),
        html.contains("src/Main.scala"),
        html.contains("README.md"),
        html.contains("Retry Merge"),
        html.contains("Resolve Manually"),
        html.contains("name=\"status\" value=\"merging\""),
      )
    },
    test("board renders merge conflict badge on affected issue card") {
      val now   = Instant.parse("2026-03-02T10:00:00Z")
      val issue = AgentIssueView(
        id = Some("merge-10"),
        title = "Conflict issue",
        description = "Task",
        issueType = "task",
        status = IssueStatus.Rework,
        mergeConflictFiles = List("src/Main.scala"),
        createdAt = now,
        updatedAt = now,
      )
      val html  = IssuesView.board(
        issues = List(issue),
        workspaces = Nil,
        workspaceFilter = None,
        agentFilter = None,
        priorityFilter = None,
        tagFilter = None,
        query = None,
      )
      assertTrue(
        html.contains("Conflict"),
        html.contains("Merge conflict affecting 1 file(s)"),
      )
    },
    test("detail renders merge history timeline") {
      val now  = Instant.parse("2026-03-02T10:00:00Z")
      val html = IssuesView.detail(
        issue = AgentIssueView(
          id = Some("merge-history-1"),
          title = "Merged issue",
          description = "Task",
          issueType = "task",
          status = IssueStatus.Done,
          createdAt = now,
          updatedAt = now,
        ),
        issueRuns = Nil,
        availableAgents = Nil,
        analysisDocs = Nil,
        mergeHistory = List(
          MergeHistoryEntryView(
            eventType = "attempted",
            happenedAt = now.minusSeconds(120),
            sourceBranch = Some("agent/merge-history-1"),
            targetBranch = Some("main"),
          ),
          MergeHistoryEntryView(
            eventType = "ci",
            happenedAt = now.minusSeconds(90),
            ciPassed = Some(false),
            details = Some("tests failed"),
          ),
          MergeHistoryEntryView(
            eventType = "failed",
            happenedAt = now.minusSeconds(60),
            conflictFiles = List("src/Main.scala"),
          ),
          MergeHistoryEntryView(
            eventType = "succeeded",
            happenedAt = now.minusSeconds(30),
            commitSha = Some("1234567890abcdef"),
            filesChanged = Some(3),
            insertions = Some(24),
            deletions = Some(7),
          ),
        ),
        workspaces = Nil,
      )
      assertTrue(
        html.contains("Merge History"),
        html.contains("Merge Attempted"),
        html.contains("CI Failed"),
        html.contains("Merge Failed"),
        html.contains("Merge Succeeded"),
        html.contains("agent/merge-history-1"),
        html.contains("src/Main.scala"),
        html.contains("1234567890ab"),
        html.contains("3 files changed, +24 / -7"),
      )
    },
    test("board renders approve action on HumanReview cards") {
      val now   = Instant.parse("2026-03-02T10:00:00Z")
      val issue = AgentIssueView(
        id = Some("review-10"),
        title = "Needs approval",
        description = "Task",
        issueType = "task",
        status = IssueStatus.HumanReview,
        createdAt = now,
        updatedAt = now,
      )
      val html  = IssuesView.board(
        issues = List(issue),
        workspaces = Nil,
        workspaceFilter = None,
        agentFilter = None,
        priorityFilter = None,
        tagFilter = None,
        query = None,
      )
      assertTrue(
        html.contains("Approve"),
        html.contains("action=\"/issues/review-10/approve\""),
        html.contains("name=\"approvedBy\" value=\"board\""),
      )
    },
  )
