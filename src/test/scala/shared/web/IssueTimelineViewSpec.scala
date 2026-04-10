package shared.web

import java.time.Instant

import zio.test.*

import board.boundary.IssueTimelineView
import board.entity.*
import board.entity.TimelineEntry.*
import shared.ids.Ids.BoardIssueId

object IssueTimelineViewSpec extends ZIOSpecDefault:

  private val now     = Instant.parse("2026-04-03T10:00:00Z")
  private val issueId = BoardIssueId("issue-42")

  private val reviewIssue = BoardIssue(
    frontmatter = IssueFrontmatter(
      id = issueId,
      title = "Polish timeline workflow",
      priority = IssuePriority.High,
      assignedAgent = Some("code-agent"),
      requiredCapabilities = Nil,
      blockedBy = Nil,
      tags = List("timeline", "workflow"),
      acceptanceCriteria = Nil,
      estimate = None,
      proofOfWork = Nil,
      transientState = TransientState.None,
      branchName = Some("agent/timeline-42"),
      failureReason = None,
      completedAt = None,
      createdAt = now,
    ),
    body = "Create a GitHub-style review timeline for board issues.",
    column = BoardColumn.Review,
    directoryPath = s"/tmp/project/.board/review/${issueId.value}",
  )

  private val doneIssue = reviewIssue.copy(column = BoardColumn.Done)

  private val timeline = List(
    IssueCreated(
      issueId,
      reviewIssue.frontmatter.title,
      reviewIssue.body,
      IssuePriority.High,
      reviewIssue.frontmatter.tags,
      now,
    ),
    AgentAssigned(issueId, "code-agent", now.plusSeconds(30)),
    RunStarted("run-42", "agent/timeline-42", "77", now.plusSeconds(60)),
    ChatMessages(
      "run-42",
      "77",
      List(
        ChatMessageSummary(
          "user",
          "Please refine the timeline layout",
          "Please refine the timeline layout",
          now.plusSeconds(61),
        ),
        ChatMessageSummary(
          "assistant",
          "Done, adding sticky header.",
          "Done, adding sticky header.",
          now.plusSeconds(62),
        ),
      ),
      now.plusSeconds(61),
    ),
    GitChanges("run-42", "ws-1", "agent/timeline-42", now.plusSeconds(70)),
    DecisionRaised("decision-42", "Review timeline polish", "High", now.plusSeconds(120)),
    ReworkRequested("Tighten the sticky header spacing.", "reviewer", now.plusSeconds(150)),
    ReviewAction("decision-42", "Approved", "reviewer", "Looks good", now.plusSeconds(180)),
    Merged("agent/timeline-42", now.plusSeconds(181)),
    IssueDone("Merged successfully", now.plusSeconds(182)),
    AnalysisDocAttached(
      title = "Code Review",
      analysisType = "Code Review",
      content = "## Summary\n\nCode looks clean.\n\n- No major issues found.",
      filePath = ".llm4zio/analysis/code-review.md",
      vscodeUrl = Some("vscode://file/tmp/project/.llm4zio/analysis/code-review.md"),
      occurredAt = now.plusSeconds(190),
    ),
  )

  def spec: Spec[Any, Nothing] =
    suite("IssueTimelineViewSpec")(
      test("page renders sticky header, timeline entries, and review actions for review issues") {
        val html = IssueTimelineView.page("ws-1", reviewIssue, timeline)
        assertTrue(
          html.contains("sticky top-10"),
          html.contains("Polish timeline workflow"),
          html.contains("agent/timeline-42"),
          html.contains("Approve &amp; Merge") || html.contains("Approve & Merge"),
          html.contains("Request Rework"),
          html.contains("""/board/ws-1/issues/issue-42/quick-approve"""),
          html.contains("""/board/ws-1/issues/issue-42/rework"""),
          html.contains("Open full conversation"),
          html.contains("""diff-url="/api/workspaces/ws-1/runs/run-42/git/diff?base=main""""),
          html.contains("Tighten the sticky header spacing."),
          html.contains("Merged successfully"),
        )
      },
      test("chat messages render as a collapsible details block") {
        val html = IssueTimelineView.page("ws-1", reviewIssue, timeline)
        assertTrue(
          html.contains("<details"),
          html.contains("Conversation 77"),
          html.contains("Please refine the timeline layout"),
          html.contains("Done, adding sticky header."),
          html.contains("Expand"),
          html.contains("Collapse"),
        )
      },
      test("analysis doc renders as expandable details block with VSCode link") {
        val html = IssueTimelineView.page("ws-1", reviewIssue, timeline)
        assertTrue(
          html.contains("Code Review"),
          html.contains("<details"),
          html.contains("code-review.md"),
          html.contains("Open in VSCode"),
          html.contains("vscode://file/tmp/project/.llm4zio/analysis/code-review.md"),
          html.contains("Expand"),
          html.contains("Collapse"),
          html.contains("Code looks clean."),
          html.contains("No major issues found."),
        )
      },
      test("review action form is hidden when the issue is not in review") {
        val html = IssueTimelineView.page("ws-1", doneIssue, timeline)
        assertTrue(
          !html.contains("""/board/ws-1/issues/issue-42/quick-approve"""),
          !html.contains("""/board/ws-1/issues/issue-42/rework"""),
          !html.contains("Review action"),
        )
      },
    )
