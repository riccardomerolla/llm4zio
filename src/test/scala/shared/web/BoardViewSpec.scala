package shared.web

import java.time.Instant

import zio.test.*

import board.boundary.BoardView
import board.entity.*
import shared.ids.Ids.BoardIssueId

object BoardViewSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-04-03T10:00:00Z")

  private def issue(
    id: String,
    title: String,
    column: BoardColumn,
    transientState: TransientState = TransientState.None,
    assignedAgent: Option[String] = Some("codex"),
    branchName: Option[String] = None,
    tags: List[String] = Nil,
  ): BoardIssue =
    BoardIssue(
      frontmatter = IssueFrontmatter(
        id = BoardIssueId(id),
        title = title,
        priority = IssuePriority.High,
        assignedAgent = assignedAgent,
        requiredCapabilities = Nil,
        blockedBy = Nil,
        tags = tags,
        acceptanceCriteria = Nil,
        estimate = None,
        proofOfWork = Nil,
        transientState = transientState,
        branchName = branchName,
        failureReason = None,
        completedAt = None,
        createdAt = now,
      ),
      body = s"Body for $title",
      column = column,
      directoryPath = s"/tmp/project/.board/${column.folderName}/$id",
    )

  def spec: Spec[Any, Nothing] =
    suite("BoardViewSpec")(
      test("review cards link to timeline and quick-approve via HTMX") {
        val reviewIssue = issue(
          id = "issue-review-1",
          title = "Review timeline card",
          column = BoardColumn.Review,
          branchName = Some("agent/review-1"),
          tags = List("timeline"),
        )
        val board       = Board("/tmp/project", Map(BoardColumn.Review -> List(reviewIssue)))
        val html        = BoardView.columnsFragment("ws-1", board)

        assertTrue(
          html.contains("""href="/board/ws-1/issues/issue-review-1""""),
          html.contains("""action="/board/ws-1/issues/issue-review-1/quick-approve""""),
          html.contains("""hx-post="/board/ws-1/issues/issue-review-1/quick-approve""""),
          html.contains("Approve &amp; Merge") || html.contains("Approve & Merge"),
        )
      },
      test("reworked todo cards show both rework badges") {
        val todoIssue = issue(
          id = "issue-rework-1",
          title = "Resume after review",
          column = BoardColumn.Todo,
          transientState = TransientState.Rework("Tighten validation", now.minusSeconds(60)),
          assignedAgent = Some("codex"),
        )
        val board     = Board("/tmp/project", Map(BoardColumn.Todo -> List(todoIssue)))
        val html      = BoardView.columnsFragment("ws-1", board)

        assertTrue(
          html.contains("Rework"),
          html.contains("Rework pending"),
        )
      },
      test("cards without rework state do not render rework badges") {
        val plainIssue = issue(
          id = "issue-todo-1",
          title = "Plain todo card",
          column = BoardColumn.Todo,
          transientState = TransientState.None,
          assignedAgent = Some("codex"),
        )
        val board      = Board("/tmp/project", Map(BoardColumn.Todo -> List(plainIssue)))
        val html       = BoardView.columnsFragment("ws-1", board)

        assertTrue(
          !html.contains("Rework pending"),
          !html.contains("""bg-amber-500/20 px-1.5 py-0.5 text-[10px] text-amber-200">Rework<"""),
        )
      },
    )
