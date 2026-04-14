package board.boundary

import java.time.Instant
import java.time.format.DateTimeFormatter

import board.entity.*
import board.entity.TimelineEntry.*
import scalatags.Text.all.*
import shared.web.*
import shared.web.IssuesMarkdownSupport.markdownFragment

object IssueTimelineView:

  def page(
    workspaceId: String,
    issue: BoardIssue,
    context: IssueContext,
  ): String =
    Layout.page(
      s"Issue · ${issue.frontmatter.title}",
      s"/board/$workspaceId/issues/${issue.frontmatter.id.value}",
    )(
      div(cls := "space-y-4")(
        header(workspaceId, issue),
        linkedPlanPanels(context.linkedPlans),
        linkedSpecPanels(context.linkedSpecs),
        timelineBody(workspaceId, context.timeline.sortBy(_.occurredAt)),
        if issue.column == BoardColumn.Review then
          frag(
            aiReviewPanel(workspaceId, issue),
            reviewActionForm(workspaceId, issue),
          )
        else frag(),
      ),
      JsResources.inlineModuleScript("/static/client/components/ab-git-summary.js"),
      JsResources.inlineModuleScript("/static/client/components/ab-confirm-modal.js"),
      confirmModalScript,
    )

  private def header(workspaceId: String, issue: BoardIssue): Frag =
    Components.pageHeader(
      title = issue.frontmatter.title,
      backHref = "/board",
      backText = "Board",
      sticky = true,
      badges = Seq(
        tag("ab-badge")(
          attr("text")    := issue.frontmatter.id.value,
          attr("variant") := "info",
        ),
        tag("ab-badge")(
          attr("text")    := humanizeColumn(issue.column),
          attr("variant") := columnBadgeVariant(issue.column),
        ),
        tag("ab-badge")(
          attr("text")    := issue.frontmatter.priority.toString,
          attr("variant") := priorityBadgeVariant(issue.frontmatter.priority),
        ),
      ),
      actions = Seq(
        issue.frontmatter.branchName.filter(_.nonEmpty).map { branch =>
          span(
            cls := "hidden sm:inline-flex items-center gap-1.5 rounded-full border border-indigo-400/20 bg-indigo-500/10 px-2 py-0.5 text-[10px] font-mono text-indigo-200"
          )(
            span(cls := "h-1 w-1 rounded-full bg-indigo-300")(),
            branch,
          )
        }.getOrElse(frag()),
        if issue.column == BoardColumn.Review then
          frag(
            button(
              `type`                      := "button",
              cls                         := "rounded-md bg-emerald-600 px-2.5 py-1 text-xs font-semibold text-white hover:bg-emerald-500",
              attr("data-confirm-action") := "approve",
              attr("data-confirm-url")    := s"/board/$workspaceId/issues/${issue.frontmatter.id.value}/quick-approve",
              attr("data-confirm-msg")    := approvalConfirm(issue),
            )("Approve & Merge"),
            button(
              `type`  := "button",
              cls     := "rounded-md border border-amber-400/25 bg-amber-400/10 px-2.5 py-1 text-xs font-semibold text-amber-100 hover:bg-amber-400/20",
              onclick := "document.getElementById('issue-review-form')?.scrollIntoView({behavior:'smooth', block:'start'})",
            )("Rework"),
          )
        else frag(),
      ),
    )

  // ── Linked Plan panels ─────────────────────────────────────────────────

  private def linkedPlanPanels(plans: List[LinkedPlan]): Frag =
    if plans.isEmpty then frag()
    else frag(plans.map(planPanel)*)

  private def planPanel(plan: LinkedPlan): Frag =
    tag("details")(attr("open") := "open", cls := "rounded-xl border border-indigo-400/20 bg-indigo-500/5")(
      tag("summary")(cls := "cursor-pointer px-5 py-3 flex items-center gap-3")(
        span(cls := "text-sm font-semibold text-white")("Plan"),
        statusBadge(plan.status, planStatusColor(plan.status)),
        plan.validationStatus
          .map(vs => span(cls := "text-[10px] text-slate-400")(s"Validation: $vs"))
          .getOrElse(frag()),
        span(cls := "ml-auto text-xs text-slate-500")(plan.id),
      ),
      div(cls := "px-5 pb-4 space-y-2")(
        p(cls := "text-sm text-slate-300 line-clamp-3")(plan.summary),
        div(cls := "flex flex-wrap gap-2 text-xs")(
          chip(s"${plan.taskCount} task(s)"),
          plan.specificationId.map(sid => chip(s"Spec: $sid")).getOrElse(frag()),
        ),
      ),
    )

  private def planStatusColor(status: String): String = status match
    case "Completed" => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
    case "Executing" => "border-cyan-400/30 bg-cyan-500/10 text-cyan-200"
    case "Validated" => "border-blue-400/30 bg-blue-500/10 text-blue-200"
    case "Abandoned" => "border-rose-400/30 bg-rose-500/10 text-rose-200"
    case _           => "border-slate-400/30 bg-slate-500/10 text-slate-200"

  // ── Linked Spec panels ────────────────────────────────────────────────

  private def linkedSpecPanels(specs: List[LinkedSpec]): Frag =
    if specs.isEmpty then frag()
    else frag(specs.map(specPanel)*)

  private def specPanel(spec: LinkedSpec): Frag =
    tag("details")(attr("open") := "open", cls := "rounded-xl border border-violet-400/20 bg-violet-500/5")(
      tag("summary")(cls := "cursor-pointer px-5 py-3 flex items-center gap-3")(
        span(cls := "text-sm font-semibold text-white")("Specification"),
        statusBadge(spec.status, specStatusColor(spec.status)),
        span(cls := "text-xs text-slate-400")(s"v${spec.version}"),
        span(cls := "ml-auto text-xs text-slate-500")(spec.id),
      ),
      div(cls := "px-5 pb-4 space-y-2")(
        h3(cls := "text-sm font-semibold text-white")(spec.title),
        p(cls := "text-xs text-slate-400")(s"by ${spec.author}"),
        p(cls := "text-sm text-slate-300 line-clamp-3")(spec.contentPreview),
        if spec.reviewCommentCount > 0 then
          span(cls := "text-xs text-slate-500")(s"${spec.reviewCommentCount} review comment(s)")
        else frag(),
      ),
    )

  private def specStatusColor(status: String): String = status match
    case "Approved"     => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
    case "InRefinement" => "border-amber-400/30 bg-amber-500/10 text-amber-200"
    case "Superseded"   => "border-slate-400/30 bg-slate-500/10 text-slate-200"
    case _              => "border-slate-400/30 bg-slate-500/10 text-slate-200"

  // ── Shared helpers ────────────────────────────────────────────────────

  private def statusBadge(text: String, colorCls: String): Frag =
    span(cls := s"rounded-full border px-2.5 py-0.5 text-[10px] font-semibold $colorCls")(text)

  private def chip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-2.5 py-0.5 text-xs text-slate-300")(value)

  // ── Timeline ──────────────────────────────────────────────────────────

  private def timelineBody(workspaceId: String, timeline: List[TimelineEntry]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/70 px-5 py-5 sm:px-6")(
      div(cls := "relative pl-10")(
        // Timeline spine
        div(
          cls := "absolute bottom-0 left-5 top-0 w-px bg-gradient-to-b from-indigo-400/30 via-white/10 to-transparent"
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

  private def timelineEntry(workspaceId: String, entry: TimelineEntry): Frag =
    val (eyebrow, titleText, bodyContent, dotColor) = entry match
      case e: IssueCreated           =>
        (
          "Opened",
          e.title,
          frag(
            p(cls := "text-sm leading-6 text-slate-300")(e.description),
            if e.tags.nonEmpty then
              div(cls := "mt-3 flex flex-wrap gap-2")(
                e.tags.map(t =>
                  tag("ab-badge")(attr("text") := t, attr("variant") := "gray")
                )
              )
            else frag(),
          ),
          "bg-cyan-400",
        )
      case e: MovedToTodo            =>
        (
          "Workflow",
          "Moved back to Todo",
          mutedText(s"Issue ${e.issueId.value} is queued for another pass."),
          "bg-slate-400",
        )
      case e: AgentAssigned          =>
        (
          "Dispatch",
          s"Assigned to ${e.agentName}",
          mutedText("The issue is ready for the selected agent."),
          "bg-indigo-400",
        )
      case e: RunStarted             =>
        (
          "Run",
          "Agent run started",
          div(cls := "space-y-2 text-sm text-slate-300")(
            metadataRow("Run", e.runId),
            Option.when(e.branchName.nonEmpty)(metadataRow("Branch", e.branchName, mono = true)),
            Option.when(e.conversationId.nonEmpty)(
              a(
                href := s"/chat/${e.conversationId}",
                cls  := "inline-flex text-xs font-medium text-indigo-300 hover:text-indigo-200",
              )(
                "Open conversation"
              )
            ),
          ),
          "bg-cyan-400",
        )
      case e: ChatMessages           =>
        (
          "Conversation",
          s"${e.messages.size} chat message${if e.messages.size == 1 then "" else "s"}",
          chatBlock(e),
          "bg-purple-400",
        )
      case e: RunCompleted           =>
        (
          "Run",
          "Run completed",
          div(cls := "space-y-1 text-sm text-slate-300")(
            p(e.summary),
            span(cls := "text-xs text-slate-500")(s"Duration: ${formatDuration(e.durationSeconds)}"),
          ),
          "bg-emerald-400",
        )
      case e: GitChanges             =>
        (
          "Diff",
          "Git changes",
          gitChangesBlock(workspaceId, e),
          "bg-violet-400",
        )
      case e: DecisionRaised         =>
        (
          "Review",
          e.title,
          div(cls := "flex flex-wrap items-center gap-2")(
            span(cls := "text-sm text-slate-300")("Human review requested."),
            tag("ab-badge")(attr("text") := e.urgency, attr("variant") := urgencyBadgeVariant(e.urgency)),
          ),
          "bg-amber-400",
        )
      case e: ReviewAction           =>
        (
          "Review",
          e.action,
          div(cls := "space-y-2")(
            p(cls := "text-sm leading-6 text-slate-300")(e.summary),
            span(cls := "text-xs text-slate-500")(s"Actor: ${e.actor}"),
          ),
          "bg-emerald-400",
        )
      case e: ReworkRequested        =>
        (
          "Rework",
          "Changes requested",
          div(cls := "space-y-2")(
            div(
              cls := "rounded-lg border border-amber-400/20 bg-amber-400/5 px-3 py-2 text-sm leading-6 text-amber-50"
            )(e.reworkComment),
            span(cls := "text-xs text-slate-500")(s"Actor: ${e.actor}"),
          ),
          "bg-amber-400",
        )
      case e: Merged                 =>
        (
          "Merge",
          s"Merged ${e.branchName}",
          mutedText("Branch merged into the workspace default branch."),
          "bg-emerald-400",
        )
      case e: IssueDone              =>
        ("Done", "Issue completed", mutedText(e.result), "bg-emerald-400")
      case e: IssueFailed            =>
        ("Failure", "Issue failed", mutedText(e.reason), "bg-red-400")
      case e: AnalysisDocAttached    =>
        ("Analysis", e.title, analysisDocBlock(e), "bg-teal-400")
      case e: A2ADialogueStarted     =>
        (
          "A2A",
          s"Dialogue started: ${e.topic}",
          div(cls := "space-y-2")(
            p(cls := "text-sm text-slate-300")(s"Participants: ${e.participantNames.mkString(", ")}"),
            a(
              href := s"#a2a-${e.conversationId}",
              cls  := "inline-flex text-xs font-medium text-indigo-300 hover:text-indigo-200",
            )("View conversation"),
          ),
          "bg-purple-400",
        )
      case e: A2ADialogueConcluded   =>
        val (label, color) = e.outcomeType match
          case "Approved"         => ("Approved", "bg-emerald-400")
          case "ChangesRequested" => ("Changes Requested", "bg-amber-400")
          case "Escalated"        => ("Escalated", "bg-red-400")
          case _                  => ("Completed", "bg-blue-400")
        (
          "A2A",
          s"Dialogue concluded: $label",
          mutedText(e.outcomeSummary),
          color,
        )
      case e: PlanningRecommendation =>
        (
          "Planning",
          s"${e.recommendations.size} issue${if e.recommendations.size == 1 then "" else "s"} recommended for Todo",
          div(cls := "space-y-2")(
            e.recommendations.sortBy(_.rank).map { rec =>
              div(cls := "flex items-center gap-3 rounded-lg border border-white/5 bg-white/[0.03] px-3 py-2")(
                span(cls := "text-xs font-bold text-indigo-300")(s"#${rec.rank}"),
                div(cls := "flex-1")(
                  span(cls := "text-sm text-white")(rec.title),
                  p(cls := "text-xs text-slate-400 line-clamp-1")(rec.reasoning),
                ),
                span(cls := "text-xs font-mono text-slate-500")(f"${rec.score}%.0f%%"),
              )
            }
          ),
          "bg-cyan-400",
        )
      case e: TriageCompleted        =>
        (
          "Triage",
          "AI triage completed",
          div(cls := "space-y-2")(
            div(cls := "flex flex-wrap gap-2")(
              e.suggestedLabels.map(l => tag("ab-badge")(attr("text") := s"[AI] $l", attr("variant") := "gray"))
            ),
            div(cls := "flex flex-wrap gap-2 text-xs")(
              chip(s"Priority: ${e.suggestedPriority}"),
              e.suggestedCapabilities.map(c => chip(c)),
            ),
            p(cls := "text-xs text-slate-400")(e.reasoning),
          ),
          "bg-teal-400",
        )

    div(cls := "relative mb-4")(
      // Timeline dot — centered on the spine line (left-5 = 20px from container)
      div(
        cls := s"absolute left-[-25px] top-4 h-2.5 w-2.5 rounded-full $dotColor ring-4 ring-slate-900"
      )(),
      // Entry card — standard card surface
      tag("article")(
        cls := "rounded-xl border border-white/10 bg-slate-800/70 p-4"
      )(
        div(cls := "flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between")(
          div(cls := "space-y-1")(
            p(cls := "text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-500")(eyebrow),
            h3(cls := "text-sm font-semibold text-white")(titleText),
          ),
          tag("time")(cls := "shrink-0 text-[11px] text-slate-500")(formatTimestamp(entry.occurredAt)),
        ),
        div(cls := "mt-3")(bodyContent),
      ),
    )

  private def chatBlock(entry: ChatMessages): Frag =
    tag("details")(cls := "group overflow-hidden rounded-lg border border-white/10 bg-black/20")(
      tag("summary")(
        cls := "flex cursor-pointer list-none items-center justify-between gap-3 px-3 py-2 text-sm text-slate-200"
      )(
        span(s"Conversation ${entry.conversationId}"),
        span(cls := "text-xs text-indigo-300 group-open:hidden")("Expand"),
        span(cls := "hidden text-xs text-indigo-300 group-open:inline")("Collapse"),
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
        Option.when(entry.conversationId.nonEmpty)(
          a(
            href := s"/chat/${entry.conversationId}",
            cls  := "inline-flex pt-1 text-xs font-medium text-indigo-300 hover:text-indigo-200",
          )("Open full conversation")
        ),
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
        span(cls := "font-mono text-violet-200")(entry.branchName)
      ),
    )

  private def analysisDocBlock(entry: AnalysisDocAttached): Frag =
    tag("details")(cls := "group overflow-hidden rounded-lg border border-white/10 bg-black/20")(
      tag("summary")(
        cls := "flex cursor-pointer list-none items-center justify-between gap-3 px-4 py-3 text-sm font-medium text-slate-100"
      )(
        span(entry.title),
        div(cls := "flex items-center gap-3")(
          entry.vscodeUrl.map(url =>
            a(
              href    := url,
              cls     := "text-xs font-medium text-indigo-300 hover:text-indigo-200",
              onclick := "event.stopPropagation();",
            )("Open in VSCode")
          ).getOrElse(frag()),
          span(cls := "text-xs text-teal-300 group-open:hidden")("Expand"),
          span(cls := "hidden text-xs text-teal-300 group-open:inline")("Collapse"),
        ),
      ),
      div(cls := "space-y-3 border-t border-white/10 px-4 py-4")(
        p(cls := "text-xs text-slate-500")(entry.filePath),
        div(cls := "prose prose-invert prose-sm max-w-none text-slate-100")(
          markdownFragment(safeContent(entry.content))
        ),
      ),
    )

  private def safeContent(value: String): String =
    try Option(value).getOrElse("")
    catch case _: Throwable => ""

  private def reviewActionForm(workspaceId: String, issue: BoardIssue): Frag =
    val issueUrl = s"/board/$workspaceId/issues/${issue.frontmatter.id.value}"
    div(
      id  := "issue-review-form",
      cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5",
    )(
      h3(cls := "mb-3 text-sm font-semibold text-white")("Review action"),
      textarea(
        id          := "review-comment",
        name        := "comment",
        rows        := 3,
        placeholder := "Leave a comment...",
        cls         := "w-full rounded-lg border border-white/10 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-400/50 resize-y",
      )(),
      div(cls := "mt-3 flex items-center justify-end gap-2")(
        button(
          `type`                      := "button",
          cls                         := "rounded-md bg-emerald-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-emerald-500 transition-colors",
          attr("data-confirm-action") := "approve",
          attr("data-confirm-url")    := s"$issueUrl/quick-approve",
          attr("data-confirm-msg")    := approvalConfirm(issue),
          attr("data-include")        := "#review-comment",
        )("Approve & Merge"),
        button(
          `type`                      := "button",
          cls                         := "rounded-md border border-amber-400/30 bg-amber-500/20 px-3.5 py-1.5 text-xs font-semibold text-amber-50 hover:bg-amber-500/30 transition-colors",
          attr("data-confirm-action") := "rework",
          attr("data-confirm-url")    := s"$issueUrl/rework",
          attr("data-confirm-msg")    := "Request rework on this issue? The agent will run again with your notes.",
          attr("data-include")        := "#review-comment",
        )("Request Rework"),
      ),
    )

  private def aiReviewPanel(workspaceId: String, issue: BoardIssue): Frag =
    val issueUrl = s"/board/$workspaceId/issues/${issue.frontmatter.id.value}"
    div(
      cls := "rounded-xl border border-indigo-400/20 bg-indigo-500/5 p-5"
    )(
      div(cls := "flex items-center justify-between")(
        div(cls := "space-y-1")(
          h3(cls := "text-sm font-semibold text-white")("AI Code Review"),
          p(cls := "text-xs text-slate-400")("Start an A2A dialogue between the review agent and the coding agent"),
        ),
        button(
          `type`            := "button",
          cls               := "rounded-md bg-indigo-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500 transition-colors",
          attr("hx-post")   := s"$issueUrl/start-ai-review",
          attr("hx-swap")   := "outerHTML",
          attr("hx-target") := "closest div",
        )("Start AI Review"),
      ),
      div(
        id  := s"a2a-panel-${issue.frontmatter.id.value}",
        cls := "mt-4",
      )(),
    )

  // ── Badge variant mappings ────────────────────────────────────────────────

  private def columnBadgeVariant(column: BoardColumn): String =
    column match
      case BoardColumn.Backlog    => "gray"
      case BoardColumn.Todo       => "info"
      case BoardColumn.InProgress => "warning"
      case BoardColumn.Review     => "purple"
      case BoardColumn.Done       => "success"
      case BoardColumn.Archive    => "gray"

  private def priorityBadgeVariant(priority: IssuePriority): String =
    priority match
      case IssuePriority.Critical => "error"
      case IssuePriority.High     => "warning"
      case IssuePriority.Medium   => "default"
      case IssuePriority.Low      => "gray"

  private def urgencyBadgeVariant(urgency: String): String =
    urgency.trim.toLowerCase match
      case "critical" => "error"
      case "high"     => "warning"
      case "medium"   => "amber"
      case _          => "gray"

  // ── Helpers ───────────────────────────────────────────────────────────────

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
          if(!resp.ok){
            var errText = await resp.text();
            modal.confirm({
              heading: 'Error',
              message: errText || 'Action failed. Please try again.',
              confirmText: 'OK',
              cancelText: 'Dismiss',
              variant: 'danger',
            });
            return;
          }
          var redirect = resp.headers.get('HX-Redirect');
          if(redirect){
            window.location.href = redirect;
          } else {
            window.location.href = '/board';
          }
        });
      })();
    """))
