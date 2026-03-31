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
        // Page header
        Components.pageHeader(
          "Board",
          workspaceName,
          button(
            `type`                      := "button",
            attr("data-board-dispatch") := "true",
            attr("data-workspace-id")   := workspaceId,
            cls                         := "rounded-md border border-white/10 px-3 py-1.5 text-sm text-gray-300 hover:bg-white/5",
          )("Dispatch Cycle"),
          button(
            `type`                     := "button",
            attr("data-new-issue-btn") := "true",
            cls                        := "rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-indigo-500",
          )("New Issue"),
        ),
        // Compact status line
        statusLine(board),
        // New issue form (hidden by default, toggled by New Issue button)
        div(
          id  := "new-issue-panel",
          cls := "hidden rounded-xl border border-white/10 bg-slate-900/70 p-4",
        )(
          form(
            attr("data-board-create") := "true",
            cls                       := "flex flex-wrap gap-2",
          )(
            input(
              `type`      := "text",
              name        := "title",
              placeholder := "Issue title",
              cls         := "flex-1 min-w-[14rem] rounded border border-white/20 bg-slate-900 px-3 py-1.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-500",
            ),
            input(
              `type`      := "text",
              name        := "body",
              placeholder := "Description (optional)",
              cls         := "flex-[2] min-w-[14rem] rounded border border-white/20 bg-slate-900 px-3 py-1.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-500",
            ),
            button(
              `type` := "submit",
              cls    := "rounded bg-emerald-600 px-4 py-1.5 text-sm font-semibold text-white hover:bg-emerald-500",
            )("Create"),
          )
        ),
        // Filter bar (search is live; extended filters collapsed behind Filters button)
        tag("ab-filter-bar")(
          input(
            attr("slot") := "search",
            `type`       := "search",
            name         := "q",
            placeholder  := "Search issues…",
            id           := "board-search",
            cls          := "rounded-md border border-white/10 bg-slate-900/70 px-3 py-1.5 text-sm text-slate-300 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-500",
          ),
          div(
            attr("slot") := "filters",
            cls          := "flex flex-wrap gap-3",
          )(
            select(
              name := "priority",
              cls  := "rounded border border-white/10 bg-slate-900 px-2 py-1.5 text-sm text-slate-300",
            )(
              option(value := "")("All priorities"),
              option(value := "critical")("Critical"),
              option(value := "high")("High"),
              option(value := "medium")("Medium"),
              option(value := "low")("Low"),
            ),
            select(
              name := "column",
              cls  := "rounded border border-white/10 bg-slate-900 px-2 py-1.5 text-sm text-slate-300",
            )(
              (option(value := "")("All columns") +:
                columnsInOrder.map(col => option(value := col.folderName)(humanizeColumn(col))))*
            ),
          ),
        ),
        // Kanban board (HTMX-refreshed)
        tag("ab-board-fs")(
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
      JsResources.inlineModuleScript("/static/client/components/design-system/ab-filter-bar.js"),
      JsResources.inlineModuleScript("/static/client/components/ab-board-column.js"),
      JsResources.inlineModuleScript("/static/client/components/ab-board-layout.js"),
      JsResources.inlineModuleScript("/static/client/components/ab-board-fs.js"),
      // Toggle new-issue panel
      script(raw("""
        document.querySelector('[data-new-issue-btn]')?.addEventListener('click', () => {
          const panel = document.getElementById('new-issue-panel');
          if (panel) { panel.classList.toggle('hidden'); panel.querySelector('input')?.focus(); }
        });
      """)),
    )

  private def statusLine(board: Board): Frag =
    val counts = columnsInOrder.map(col => col -> board.columns.getOrElse(col, Nil).size)
    div(
      cls := "flex flex-wrap items-center gap-x-4 gap-y-1 rounded-xl border border-white/10 bg-slate-900/70 px-4 py-2 text-sm"
    )(
      counts.map { (col, n) =>
        span(cls := "flex items-center gap-1.5")(
          span(cls := "text-gray-400")(humanizeColumn(col)),
          span(
            cls := "min-w-[1.25rem] rounded-full bg-white/10 px-1.5 py-0.5 text-center text-xs font-semibold text-white"
          )(n.toString),
        )
      }*
    )

  def columnsFragment(workspaceId: String, board: Board): String =
    tag("ab-board-layout")(attr("default-expanded") := "todo,in-progress")(
      columnsInOrder.map { column =>
        val issues = board.columns.getOrElse(column, Nil)
        tag("ab-board-column")(
          attr("status")           := column.folderName,
          attr("label")            := humanizeColumn(column),
          attr("count")            := issues.size.toString,
          attr("color")            := columnDotColor(column),
          attr("data-drop-status") := column.folderName,
        )(
          div(cls := "mb-3 flex items-center justify-between", attr("data-column-header") := "")(
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
        if issue.column == BoardColumn.Review && issue.frontmatter.branchName.exists(_.nonEmpty) then
          div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-4")(
            form(
              method := "post",
              action := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/approve",
            )(
              button(
                `type` := "submit",
                cls    := "rounded border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-100 hover:bg-emerald-500/30",
              )("Approve & Merge")
            )
          )
        else (),
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
        if column == BoardColumn.Review && issue.frontmatter.branchName.exists(_.nonEmpty) then
          form(
            method            := "post",
            action            := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/approve",
            attr("hx-post")   := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/approve",
            attr("hx-target") := "#fs-board-root",
            attr("hx-swap")   := "innerHTML",
          )(
            button(
              `type` := "submit",
              cls    := "rounded border border-emerald-400/30 bg-emerald-500/20 px-2 py-1 text-[10px] font-semibold text-emerald-100 hover:bg-emerald-500/30",
            )("Approve")
          )
        else (),
        button(
          `type`                    := "button",
          cls                       := "rounded border border-white/20 px-2 py-1 text-[10px] text-slate-200 hover:bg-white/10",
          attr("data-board-delete") := issue.frontmatter.id.value,
        )("Delete"),
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

  private def columnDotColor(column: BoardColumn): String =
    column match
      case BoardColumn.Backlog    => "bg-slate-400"
      case BoardColumn.Todo       => "bg-blue-400"
      case BoardColumn.InProgress => "bg-amber-400"
      case BoardColumn.Review     => "bg-purple-400"
      case BoardColumn.Done       => "bg-emerald-400"
      case BoardColumn.Archive    => "bg-gray-400"
