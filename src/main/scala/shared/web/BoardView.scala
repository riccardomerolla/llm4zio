package shared.web

import board.entity.*
import scalatags.Text.all.*

object BoardView:

  private val columnsInOrder: List[BoardColumn] = List(
    BoardColumn.Backlog,
    BoardColumn.Todo,
    BoardColumn.InProgress,
    BoardColumn.Review,
    BoardColumn.Done,
    BoardColumn.Archive,
  )

  def page(workspaceId: String, workspaceName: String, workspacePath: String, board: Board): String =
    Layout.page(s"Board · $workspaceName", s"/board/$workspaceId")(
      div(cls := "space-y-4")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")(s"$workspaceName Board"),
              p(cls := "mt-1 text-sm text-slate-300")(s"Workspace: $workspaceId"),
            ),
            button(
              `type`                      := "button",
              attr("data-board-dispatch") := "true",
              attr("data-workspace-id")   := workspaceId,
              cls                         := "rounded-md border border-indigo-400/30 bg-indigo-500/20 px-3 py-2 text-sm font-semibold text-indigo-100 hover:bg-indigo-500/30",
            )("Dispatch Cycle"),
          )
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(
          form(
            attr("data-board-create") := "true",
            cls                       := "grid grid-cols-1 gap-2 md:grid-cols-[2fr_1fr_auto]",
          )(
            input(
              `type`      := "text",
              name        := "title",
              placeholder := "New issue title",
              cls         := "rounded border border-white/20 bg-slate-900 px-3 py-2 text-sm text-slate-100",
            ),
            input(
              `type`      := "text",
              name        := "body",
              placeholder := "Issue body",
              cls         := "rounded border border-white/20 bg-slate-900 px-3 py-2 text-sm text-slate-100",
            ),
            button(
              `type` := "submit",
              cls    := "rounded border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-100 hover:bg-emerald-500/30",
            )("Create"),
          )
        ),
        div(
          id                          := "fs-board-root",
          attr("data-workspace-id")   := workspaceId,
          attr("data-workspace-path") := workspacePath,
          attr("data-fragment-url")   := s"/board/$workspaceId/fragment",
          attr("hx-get")              := s"/board/$workspaceId/fragment",
          attr("hx-trigger")          := "load, every 10s",
          attr("hx-swap")             := "innerHTML",
        )(
          raw(columnsFragment(workspaceId, board))
        ),
      ),
      JsResources.inlineModuleScript("/static/client/components/board-fs.js"),
    )

  def columnsFragment(workspaceId: String, board: Board): String =
    div(cls := "flex gap-3 overflow-x-auto pb-2")(
      columnsInOrder.map { column =>
        val issues = board.columns.getOrElse(column, Nil)
        div(
          cls                       := "min-w-[18rem] flex-1 rounded-xl border border-white/10 bg-slate-900/70 p-3",
          attr("data-board-column") := column.folderName,
        )(
          div(cls := "mb-3 flex items-center justify-between")(
            h3(cls := "text-sm font-semibold text-slate-100")(humanizeColumn(column)),
            span(cls := "rounded-full bg-white/10 px-2 py-0.5 text-xs text-slate-300")(issues.size.toString),
          ),
          div(
            cls                      := "space-y-2 min-h-[3rem]",
            attr("data-column-drop") := column.folderName,
          )(
            if issues.isEmpty then
              p(cls := "rounded border border-dashed border-white/10 px-2 py-3 text-xs text-slate-500")("No issues")
            else issues.map(issueCard(workspaceId, _, column))
          ),
        )
      }
    ).render

  def detailPage(workspaceId: String, issue: BoardIssue, renderedIssueMarkdown: Frag): String =
    Layout.page(s"Board Issue · ${issue.frontmatter.id.value}", s"/board/$workspaceId")(
      div(cls := "space-y-4")(
        div(cls := "flex items-center gap-3")(
          a(
            href := s"/board/$workspaceId",
            cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
          )("← Back to board"),
          h1(cls := "text-xl font-bold text-white")(issue.frontmatter.title),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(
          div(cls := "flex flex-wrap items-center gap-2 text-xs")(
            badge(issue.column.folderName),
            badge(issue.frontmatter.priority.toString),
            issue.frontmatter.assignedAgent.filter(_.nonEmpty).map(a => badge(s"agent:$a")).getOrElse(()),
            issue.frontmatter.tags.map(tag => badge(tag)),
          )
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-6")(
          div(cls := "prose prose-invert prose-sm max-w-none text-slate-100")(renderedIssueMarkdown)
        ),
      )
    )

  private def issueCard(workspaceId: String, issue: BoardIssue, column: BoardColumn): Frag =
    div(
      cls                         := "rounded-lg border border-white/10 bg-slate-800/70 p-3",
      attr("draggable")           := "true",
      attr("data-board-issue-id") := issue.frontmatter.id.value,
      attr("data-board-from")     := column.folderName,
    )(
      div(cls := "mb-1 flex items-start justify-between gap-2")(
        a(
          href := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}",
          cls  := "text-sm font-semibold text-white hover:text-indigo-200",
        )(issue.frontmatter.title),
        span(cls := "rounded bg-white/10 px-1.5 py-0.5 text-[10px] text-slate-300")(issue.frontmatter.priority.toString),
      ),
      div(cls := "mb-2 text-[11px] text-slate-400")(
        issue.frontmatter.assignedAgent.filter(_.nonEmpty).getOrElse("unassigned")
      ),
      if issue.frontmatter.tags.nonEmpty then
        div(cls := "flex flex-wrap gap-1")(
          issue.frontmatter.tags.take(4).map(tag =>
            span(cls := "rounded bg-indigo-500/20 px-2 py-0.5 text-[10px] text-indigo-100")(tag)
          )
        )
      else (),
      div(cls := "mt-2 flex items-center justify-end gap-2")(
        button(
          `type`                    := "button",
          cls                       := "rounded border border-white/20 px-2 py-1 text-[10px] text-slate-200 hover:bg-white/10",
          attr("data-board-delete") := issue.frontmatter.id.value,
        )("Delete")
      ),
    )

  private def badge(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-slate-200")(value)

  private def humanizeColumn(column: BoardColumn): String =
    column match
      case BoardColumn.Backlog    => "Backlog"
      case BoardColumn.Todo       => "Todo"
      case BoardColumn.InProgress => "In Progress"
      case BoardColumn.Review     => "Review"
      case BoardColumn.Done       => "Done"
      case BoardColumn.Archive    => "Archive"
