package shared.web

import java.time.Instant
import java.time.format.DateTimeFormatter

import board.entity.*
import board.entity.TimelineEntry.*
import scalatags.Text.all.*

object IssueTimelineView:

  def page(
    workspaceId: String,
    issue: BoardIssue,
    timeline: List[TimelineEntry],
  ): String =
    Layout.page(
      s"Issue · ${issue.frontmatter.title}",
      s"/board/$workspaceId/issues/${issue.frontmatter.id.value}",
    )(
      div(cls := "space-y-5")(
        header(workspaceId, issue),
        timelineBody(workspaceId, timeline.sortBy(_.occurredAt)),
        if issue.column == BoardColumn.Review then reviewActionForm(workspaceId, issue) else frag(),
      )
    )

  private def header(workspaceId: String, issue: BoardIssue): Frag =
    div(
      cls := "sticky top-12 z-20 overflow-hidden rounded-2xl border border-white/10 bg-slate-950/95 shadow-[0_18px_50px_rgba(15,23,42,0.45)] backdrop-blur"
    )(
      div(
        cls := "border-b border-white/10 bg-[radial-gradient(circle_at_top_left,rgba(56,189,248,0.18),transparent_38%),radial-gradient(circle_at_top_right,rgba(99,102,241,0.16),transparent_34%)] px-5 py-4"
      )(
        div(cls := "flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between")(
          div(cls := "min-w-0 flex-1 space-y-2")(
            div(cls := "flex flex-wrap items-center gap-2")(
              span(
                cls := "rounded-full border border-cyan-400/30 bg-cyan-400/10 px-2 py-0.5 font-mono text-[10px] uppercase tracking-[0.18em] text-cyan-200"
              )(
                issue.frontmatter.id.value
              ),
              statusBadge(issue.column),
              priorityBadge(issue.frontmatter.priority),
              issue.frontmatter.assignedAgent.filter(_.nonEmpty).map(agentBadge).getOrElse(frag()),
            ),
            h1(cls := "text-xl font-semibold tracking-tight text-white sm:text-2xl")(issue.frontmatter.title),
            p(cls := "max-w-3xl text-sm leading-6 text-slate-300")(issue.body),
            div(cls := "flex flex-wrap items-center gap-3 text-xs text-slate-400")(
              issue.frontmatter.branchName.filter(_.nonEmpty).map { branch =>
                span(
                  cls := "inline-flex items-center gap-2 rounded-full border border-indigo-400/20 bg-indigo-500/10 px-2.5 py-1"
                )(
                  span(cls := "h-1.5 w-1.5 rounded-full bg-indigo-300")(),
                  span(cls := "font-mono text-indigo-200")(branch),
                )
              }.getOrElse(
                span(
                  cls := "inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-2.5 py-1"
                )(
                  span(cls := "text-slate-500")("No branch attached")
                )
              ),
              span(cls := "inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-2.5 py-1")(
                span("Created"),
                span(cls := "text-slate-200")(formatTimestamp(issue.frontmatter.createdAt)),
              ),
            ),
          ),
          div(cls := "flex flex-wrap items-start gap-2 lg:max-w-[18rem] lg:justify-end")(
            a(
              href := s"/board/$workspaceId",
              cls  := "rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-xs font-medium text-slate-200 hover:bg-white/10",
            )("Back to board"),
            if issue.column == BoardColumn.Review then
              frag(
                button(
                  `type`             := "button",
                  cls                := "rounded-lg bg-emerald-600 px-3 py-2 text-xs font-semibold text-white hover:bg-emerald-500",
                  attr("hx-post")    := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/quick-approve",
                  attr("hx-target")  := "body",
                  attr("hx-confirm") := approvalConfirm(issue),
                  attr("hx-swap")    := "outerHTML",
                )("Approve & Merge"),
                button(
                  `type`  := "button",
                  cls     := "rounded-lg border border-amber-400/25 bg-amber-400/10 px-3 py-2 text-xs font-semibold text-amber-100 hover:bg-amber-400/20",
                  onclick := "document.getElementById('issue-review-form')?.scrollIntoView({behavior:'smooth', block:'start'})",
                )("Request Rework"),
              )
            else frag(),
          ),
        )
      ),
      if issue.frontmatter.tags.nonEmpty then
        div(cls := "flex flex-wrap gap-2 px-5 py-3")(
          issue.frontmatter.tags.map(tag =>
            span(cls := "rounded-full border border-white/10 bg-white/5 px-2.5 py-1 text-[11px] text-slate-300")(tag)
          )
        )
      else frag(),
    )

  private def timelineBody(workspaceId: String, timeline: List[TimelineEntry]): Frag =
    div(cls := "overflow-hidden rounded-2xl border border-white/10 bg-slate-950/70")(
      div(cls := "max-h-[calc(100vh-16rem)] overflow-y-auto px-5 py-5 sm:px-6")(
        div(cls := "relative pl-10")(
          div(
            cls := "absolute bottom-0 left-[13px] top-0 w-px bg-gradient-to-b from-cyan-400/40 via-indigo-400/20 to-white/10"
          )(),
          if timeline.isEmpty then
            div(
              cls := "rounded-xl border border-dashed border-white/10 bg-white/[0.03] px-4 py-8 text-sm text-slate-400"
            )(
              "No timeline activity yet."
            )
          else
            timeline.map(entry => timelineEntry(workspaceId, entry)),
        )
      )
    )

  private def timelineEntry(workspaceId: String, entry: TimelineEntry): Frag =
    val (eyebrow, titleText, bodyContent, accentCls) = entry match
      case e: IssueCreated    =>
        (
          "Opened",
          e.title,
          frag(
            p(cls := "text-sm leading-6 text-slate-300")(e.description),
            if e.tags.nonEmpty then
              div(cls := "mt-3 flex flex-wrap gap-2")(
                e.tags.map(tag =>
                  span(
                    cls := "rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[11px] text-slate-400"
                  )(tag)
                )
              )
            else frag(),
          ),
          "border-cyan-400/40 bg-cyan-400/10 text-cyan-200",
        )
      case e: MovedToTodo     =>
        (
          "Workflow",
          "Moved back to Todo",
          mutedText(s"Issue ${e.issueId.value} is queued for another pass."),
          "border-slate-500/30 bg-slate-500/10 text-slate-200",
        )
      case e: AgentAssigned   =>
        (
          "Dispatch",
          s"Assigned to ${e.agentName}",
          mutedText("The issue is ready for the selected agent."),
          "border-indigo-400/35 bg-indigo-400/10 text-indigo-200",
        )
      case e: RunStarted      =>
        (
          "Run",
          "Agent run started",
          div(cls := "space-y-2 text-sm text-slate-300")(
            metadataRow("Run", e.runId),
            metadataRow("Branch", e.branchName, mono = true),
            a(
              href := s"/chat/${e.conversationId}",
              cls  := "inline-flex text-xs font-medium text-cyan-300 hover:text-cyan-200",
            )(
              s"Open conversation ${e.conversationId}"
            ),
          ),
          "border-cyan-400/35 bg-cyan-400/10 text-cyan-200",
        )
      case e: ChatMessages    =>
        (
          "Conversation",
          s"${e.messages.size} chat message${if e.messages.size == 1 then "" else "s"}",
          chatBlock(e),
          "border-fuchsia-400/35 bg-fuchsia-400/10 text-fuchsia-200",
        )
      case e: RunCompleted    =>
        (
          "Run",
          "Run completed",
          div(cls := "space-y-1 text-sm text-slate-300")(
            p(e.summary),
            span(cls := "text-xs text-slate-500")(s"Duration: ${formatDuration(e.durationSeconds)}"),
          ),
          "border-emerald-400/35 bg-emerald-400/10 text-emerald-200",
        )
      case e: GitChanges      =>
        (
          "Diff",
          "Git changes",
          gitChangesBlock(workspaceId, e),
          "border-violet-400/35 bg-violet-400/10 text-violet-200",
        )
      case e: DecisionRaised  =>
        (
          "Review",
          e.title,
          div(cls := "flex flex-wrap items-center gap-2")(
            span(cls := "text-sm text-slate-300")("Human review requested."),
            urgencyBadge(e.urgency),
          ),
          "border-amber-400/35 bg-amber-400/10 text-amber-200",
        )
      case e: ReviewAction    =>
        (
          "Review",
          e.action,
          div(cls := "space-y-2")(
            p(cls := "text-sm leading-6 text-slate-300")(e.summary),
            span(cls := "text-xs text-slate-500")(s"Actor: ${e.actor}"),
          ),
          "border-emerald-400/35 bg-emerald-400/10 text-emerald-200",
        )
      case e: ReworkRequested =>
        (
          "Rework",
          "Changes requested",
          div(cls := "space-y-2")(
            div(
              cls := "rounded-xl border border-amber-400/20 bg-amber-400/5 px-3 py-2 text-sm leading-6 text-amber-50"
            )(e.reworkComment),
            span(cls := "text-xs text-slate-500")(s"Actor: ${e.actor}"),
          ),
          "border-amber-400/35 bg-amber-400/10 text-amber-200",
        )
      case e: Merged          =>
        (
          "Merge",
          s"Merged ${e.branchName}",
          mutedText("Branch merged into the workspace default branch."),
          "border-emerald-400/35 bg-emerald-400/10 text-emerald-200",
        )
      case e: IssueDone       =>
        ("Done", "Issue completed", mutedText(e.result), "border-emerald-400/35 bg-emerald-400/10 text-emerald-200")
      case e: IssueFailed     =>
        ("Failure", "Issue failed", mutedText(e.reason), "border-rose-400/35 bg-rose-400/10 text-rose-200")

    div(cls := "relative mb-5")(
      div(
        cls := s"absolute left-[-34px] top-5 flex h-7 w-7 items-center justify-center rounded-full border $accentCls shadow-[0_0_0_6px_rgba(2,6,23,1)]"
      )(
        span(cls := "text-[10px] font-semibold")("●")
      ),
      tag("article")(
        cls := "rounded-2xl border border-white/10 bg-[linear-gradient(180deg,rgba(255,255,255,0.05),rgba(255,255,255,0.02))] p-4 shadow-[0_18px_40px_rgba(2,6,23,0.28)]"
      )(
        div(cls := "flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between")(
          div(cls := "space-y-1")(
            p(cls := "text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-500")(eyebrow),
            h3(cls := "text-sm font-semibold text-white")(titleText),
          ),
          tag("time")(cls := "shrink-0 text-[11px] text-slate-500")(formatTimestamp(entry.occurredAt)),
        ),
        div(cls := "mt-3")(bodyContent),
      ),
    )

  private def chatBlock(entry: ChatMessages): Frag =
    tag("details")(cls := "group overflow-hidden rounded-xl border border-white/10 bg-black/20")(
      tag("summary")(
        cls := "flex cursor-pointer list-none items-center justify-between gap-3 px-3 py-2 text-sm text-slate-200"
      )(
        span(s"Conversation ${entry.conversationId}"),
        span(cls := "text-xs text-cyan-300 group-open:hidden")("Expand"),
        span(cls := "hidden text-xs text-cyan-300 group-open:inline")("Collapse"),
      ),
      div(cls := "space-y-2 border-t border-white/10 px-3 py-3")(
        entry.messages.map { message =>
          div(cls := "rounded-lg border border-white/5 bg-white/[0.03] px-3 py-2")(
            div(cls := "mb-1 flex items-center justify-between gap-3")(
              span(cls := "text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400")(message.role),
              span(cls := "text-[10px] text-slate-500")(formatTimestamp(message.timestamp)),
            ),
            p(cls := "text-sm leading-6 text-slate-300")(message.fullContent),
          )
        },
        a(
          href := s"/chat/${entry.conversationId}",
          cls  := "inline-flex pt-1 text-xs font-medium text-cyan-300 hover:text-cyan-200",
        )(s"Open full conversation ${entry.conversationId}"),
      ),
    )

  private def gitChangesBlock(workspaceId: String, entry: GitChanges): Frag =
    div(
      cls                := "rounded-xl border border-dashed border-white/10 bg-black/20 px-3 py-3 text-sm text-slate-400",
      attr("hx-get")     := s"/api/workspaces/$workspaceId/runs/${entry.runId}/git/diff?base=main",
      attr("hx-trigger") := "intersect once",
      attr("hx-target")  := "this",
      attr("hx-swap")    := "innerHTML",
    )(
      div(cls := "mb-1 flex items-center justify-between gap-2")(
        span(cls := "font-mono text-xs text-violet-200")(entry.branchName),
        span(cls := "text-[10px] uppercase tracking-[0.18em] text-slate-500")("Lazy diff"),
      ),
      p("Loading diff stats..."),
    )

  private def reviewActionForm(workspaceId: String, issue: BoardIssue): Frag =
    div(
      id  := "issue-review-form",
      cls := "rounded-2xl border border-white/10 bg-slate-950/80 p-5 shadow-[0_18px_40px_rgba(2,6,23,0.28)]",
    )(
      div(cls := "mb-4 flex flex-col gap-1")(
        h2(cls := "text-sm font-semibold text-white")("Review action"),
        p(
          cls := "text-sm text-slate-400"
        )("Approve and merge the branch or request another agent pass with reviewer notes."),
      ),
      issue.frontmatter.branchName.filter(_.nonEmpty).map { branch =>
        div(
          cls := "mb-4 inline-flex items-center gap-2 rounded-full border border-indigo-400/20 bg-indigo-500/10 px-3 py-1.5 text-xs text-indigo-100"
        )(
          span("Branch"),
          span(cls := "font-mono")(branch),
        )
      }.getOrElse(frag()),
      div(cls := "grid gap-4 lg:grid-cols-2")(
        form(
          method            := "post",
          attr("hx-post")   := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/quick-approve",
          attr("hx-target") := "body",
          attr("hx-swap")   := "outerHTML",
          cls               := "space-y-3 rounded-xl border border-emerald-400/15 bg-emerald-400/5 p-4",
        )(
          h3(cls := "text-sm font-semibold text-emerald-100")("Approve & Merge"),
          textarea(
            name        := "notes",
            rows        := 5,
            placeholder := "Optional approval notes",
            cls         := "w-full rounded-xl border border-white/10 bg-slate-950/70 px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-emerald-400",
          )(),
          button(
            `type`             := "submit",
            cls                := "rounded-lg bg-emerald-600 px-3 py-2 text-xs font-semibold text-white hover:bg-emerald-500",
            attr("hx-confirm") := approvalConfirm(issue),
          )("Approve & Merge"),
        ),
        form(
          method            := "post",
          attr("hx-post")   := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/rework",
          attr("hx-target") := "body",
          attr("hx-swap")   := "outerHTML",
          cls               := "space-y-3 rounded-xl border border-amber-400/15 bg-amber-400/5 p-4",
        )(
          h3(cls := "text-sm font-semibold text-amber-100")("Request Rework"),
          textarea(
            name        := "comment",
            rows        := 5,
            required    := true,
            placeholder := "Explain what should change in the next run",
            cls         := "w-full rounded-xl border border-white/10 bg-slate-950/70 px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-amber-400",
          )(),
          button(
            `type` := "submit",
            cls    := "rounded-lg border border-amber-400/30 bg-amber-500/20 px-3 py-2 text-xs font-semibold text-amber-50 hover:bg-amber-500/30",
          )("Request Rework"),
        ),
      ),
    )

  private def statusBadge(column: BoardColumn): Frag =
    val colorCls = column match
      case BoardColumn.Backlog    => "border-slate-400/25 bg-slate-400/10 text-slate-200"
      case BoardColumn.Todo       => "border-sky-400/25 bg-sky-400/10 text-sky-200"
      case BoardColumn.InProgress => "border-amber-400/25 bg-amber-400/10 text-amber-200"
      case BoardColumn.Review     => "border-violet-400/25 bg-violet-400/10 text-violet-200"
      case BoardColumn.Done       => "border-emerald-400/25 bg-emerald-400/10 text-emerald-200"
      case BoardColumn.Archive    => "border-slate-400/25 bg-slate-400/10 text-slate-300"
    span(
      cls := s"rounded-full border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.16em] $colorCls"
    )(humanizeColumn(column))

  private def priorityBadge(priority: IssuePriority): Frag =
    val colorCls = priority match
      case IssuePriority.Critical => "border-rose-400/25 bg-rose-400/10 text-rose-200"
      case IssuePriority.High     => "border-orange-400/25 bg-orange-400/10 text-orange-200"
      case IssuePriority.Medium   => "border-slate-400/25 bg-slate-400/10 text-slate-200"
      case IssuePriority.Low      => "border-slate-400/20 bg-slate-400/5 text-slate-400"
    span(
      cls := s"rounded-full border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.16em] $colorCls"
    )(priority.toString)

  private def agentBadge(agent: String): Frag =
    span(
      cls := "rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-300"
    )(
      s"Agent $agent"
    )

  private def urgencyBadge(urgency: String): Frag =
    span(
      cls := s"rounded-full border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.16em] ${urgencyClass(urgency)}"
    )(urgency)

  private def urgencyClass(urgency: String): String =
    urgency.trim.toLowerCase match
      case "critical" => "border-rose-400/25 bg-rose-400/10 text-rose-200"
      case "high"     => "border-orange-400/25 bg-orange-400/10 text-orange-200"
      case "medium"   => "border-amber-400/25 bg-amber-400/10 text-amber-200"
      case _          => "border-slate-400/25 bg-slate-400/10 text-slate-300"

  private def humanizeColumn(column: BoardColumn): String =
    column match
      case BoardColumn.Backlog    => "Backlog"
      case BoardColumn.Todo       => "Todo"
      case BoardColumn.InProgress => "In Progress"
      case BoardColumn.Review     => "Review"
      case BoardColumn.Done       => "Done"
      case BoardColumn.Archive    => "Archive"

  private def mutedText(value: String): Frag =
    p(cls := "text-sm leading-6 text-slate-300")(value)

  private def metadataRow(label: String, value: String, mono: Boolean = false): Frag =
    div(cls := "flex flex-wrap items-center gap-2")(
      span(cls := "text-xs uppercase tracking-[0.16em] text-slate-500")(label),
      span(cls := (if mono then "font-mono text-xs text-slate-200" else "text-sm text-slate-300"))(value),
    )

  private def formatTimestamp(instant: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(instant)

  private def formatDuration(durationSeconds: Long): String =
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    if minutes > 0 then s"${minutes}m ${seconds}s" else s"${seconds}s"

  private def approvalConfirm(issue: BoardIssue): String =
    val branch = issue.frontmatter.branchName.getOrElse("this branch")
    s"Approve and merge '$branch' into main?"
