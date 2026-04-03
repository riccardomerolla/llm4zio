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
      ),
      JsResources.inlineModuleScript("/static/client/components/ab-git-summary.js"),
      JsResources.inlineModuleScript("/static/client/components/ab-confirm-modal.js"),
      confirmModalScript,
    )

  private def header(workspaceId: String, issue: BoardIssue): Frag =
    div(
      cls := "sticky top-10 z-20 flex items-center gap-3 rounded-xl border border-white/10 bg-slate-950/95 px-4 py-2.5 shadow-lg backdrop-blur",
    )(
      a(
        href := s"/board",
        cls  := "flex-shrink-0 rounded-md border border-white/10 bg-white/5 px-2 py-1 text-[11px] text-slate-300 hover:bg-white/10",
      )("← Board"),
      span(
        cls := "rounded-full border border-cyan-400/30 bg-cyan-400/10 px-2 py-0.5 font-mono text-[10px] uppercase tracking-[0.18em] text-cyan-200"
      )(issue.frontmatter.id.value),
      statusBadge(issue.column),
      priorityBadge(issue.frontmatter.priority),
      h1(cls := "min-w-0 flex-1 truncate text-sm font-semibold text-white")(issue.frontmatter.title),
      issue.frontmatter.branchName.filter(_.nonEmpty).map { branch =>
        span(
          cls := "hidden sm:inline-flex items-center gap-1.5 rounded-full border border-indigo-400/20 bg-indigo-500/10 px-2 py-0.5 text-[10px] font-mono text-indigo-200"
        )(
          span(cls := "h-1 w-1 rounded-full bg-indigo-300")(),
          branch,
        )
      }.getOrElse(frag()),
      if issue.column == BoardColumn.Review then
        div(cls := "flex items-center gap-1.5 flex-shrink-0")(
          button(
            `type`                    := "button",
            cls                       := "rounded-md bg-emerald-600 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-emerald-500",
            attr("data-confirm-action") := "approve",
            attr("data-confirm-url")  := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/quick-approve",
            attr("data-confirm-msg")  := approvalConfirm(issue),
          )("Approve & Merge"),
          button(
            `type`  := "button",
            cls     := "rounded-md border border-amber-400/25 bg-amber-400/10 px-2.5 py-1 text-[11px] font-semibold text-amber-100 hover:bg-amber-400/20",
            onclick := "document.getElementById('issue-review-form')?.scrollIntoView({behavior:'smooth', block:'start'})",
          )("Rework"),
        )
      else frag(),
    )

  private def timelineBody(workspaceId: String, timeline: List[TimelineEntry]): Frag =
    div(cls := "overflow-hidden rounded-2xl border border-white/10 bg-slate-950/70")(
      div(cls := "max-h-[calc(100vh-16rem)] overflow-y-auto px-5 py-5 sm:px-6")(
        div(cls := "relative pl-10")(
          div(
            cls := "absolute bottom-0 left-5 top-0 w-px bg-gradient-to-b from-cyan-400/40 via-indigo-400/20 to-white/10"
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
            p(cls := "whitespace-pre-wrap text-sm leading-6 text-slate-300")(message.fullContent),
          )
        },
        a(
          href := s"/chat/${entry.conversationId}",
          cls  := "inline-flex pt-1 text-xs font-medium text-cyan-300 hover:text-cyan-200",
        )(s"Open full conversation ${entry.conversationId}"),
      ),
    )

  private def gitChangesBlock(workspaceId: String, entry: GitChanges): Frag =
    val basePath = s"/api/workspaces/$workspaceId/runs/${entry.runId}/git"
    div(
      tag("ab-git-summary")(
        attr("status-url") := s"$basePath/status",
        attr("diff-url")   := s"$basePath/diff?base=main",
      ),
      div(cls := "mt-2 flex items-center gap-2 text-xs text-slate-500")(
        span(cls := "font-mono text-violet-200")(entry.branchName),
      ),
    )

  private def reviewActionForm(workspaceId: String, issue: BoardIssue): Frag =
    val issueUrl = s"/board/$workspaceId/issues/${issue.frontmatter.id.value}"
    div(
      id  := "issue-review-form",
      cls := "overflow-hidden rounded-2xl border border-white/10 bg-slate-950/80",
    )(
      div(cls := "p-4")(
        textarea(
          id          := "review-comment",
          name        := "comment",
          rows        := 3,
          placeholder := "Leave a comment...",
          cls         := "w-full rounded-lg border border-white/10 bg-slate-950/70 px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-400/50 resize-y",
        )(),
        div(cls := "mt-3 flex items-center justify-end gap-2")(
          button(
            `type`                      := "button",
            cls                         := "rounded-lg bg-emerald-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-emerald-500 transition-colors",
            attr("data-confirm-action") := "approve",
            attr("data-confirm-url")    := s"$issueUrl/quick-approve",
            attr("data-confirm-msg")    := approvalConfirm(issue),
            attr("data-include")        := "#review-comment",
          )("Approve & Merge"),
          button(
            `type`                      := "button",
            cls                         := "rounded-lg border border-amber-400/30 bg-amber-500/20 px-3.5 py-1.5 text-xs font-semibold text-amber-50 hover:bg-amber-500/30 transition-colors",
            attr("data-confirm-action") := "rework",
            attr("data-confirm-url")    := s"$issueUrl/rework",
            attr("data-confirm-msg")    := "Request rework on this issue? The agent will run again with your notes.",
            attr("data-include")        := "#review-comment",
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

  private val confirmModalScript: Frag =
    script(raw("""
      (function(){
        var modal = document.createElement('ab-confirm-modal');
        document.body.appendChild(modal);

        document.addEventListener('click', async function(e){
          var btn = e.target.closest('[data-confirm-action]');
          if(!btn) return;
          e.preventDefault();
          e.stopPropagation();

          var action  = btn.getAttribute('data-confirm-action');
          var url     = btn.getAttribute('data-confirm-url');
          var msg     = btn.getAttribute('data-confirm-msg') || 'Are you sure?';
          var incSel  = btn.getAttribute('data-include');

          // For rework, require comment first
          if(action === 'rework'){
            var ta = document.getElementById('review-comment');
            if(ta && !ta.value.trim()){
              ta.focus();
              ta.classList.add('ring-1','ring-amber-400');
              setTimeout(function(){ ta.classList.remove('ring-1','ring-amber-400'); }, 2000);
              return;
            }
          }

          var heading = action === 'approve' ? 'Approve & Merge' : 'Request Rework';
          var variant = action === 'rework' ? 'danger' : 'default';
          var confirmText = action === 'approve' ? 'Approve' : 'Request Rework';

          var ok = await modal.confirm({
            heading: heading,
            message: msg,
            confirmText: confirmText,
            cancelText: 'Cancel',
            variant: variant,
          });
          if(!ok) return;

          // Build form body from included element
          var body = '';
          if(incSel){
            var el = document.querySelector(incSel);
            if(el && el.name){
              body = encodeURIComponent(el.name) + '=' + encodeURIComponent(el.value || '');
            }
          }

          // Fire HTMX-style POST via fetch, then follow HX-Redirect
          var resp = await fetch(url, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/x-www-form-urlencoded',
              'HX-Request': 'true',
            },
            body: body,
          });
          var redirect = resp.headers.get('HX-Redirect');
          if(redirect){
            window.location.href = redirect;
          } else if(resp.ok){
            window.location.href = '/board';
          }
        });
      })();
    """))

