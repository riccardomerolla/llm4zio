package decision.boundary

import decision.entity.*
import scalatags.Text.all.*
import shared.web.Layout

object DecisionsView:

  /** Phase 3 R9: the web-side supervisor inbox mirror. Renders pending +
    * escalated decisions with QuickOption buttons that POST to
    * `/decisions/{id}/resolve` with a `key=` form field — the same
    * resolution model as Telegram's inline buttons, just in HTML.
    *
    * For policy-locked corp environments where Telegram is blocked, this
    * is the supervisor's working surface.
    */
  def inboxPage(decisions: List[Decision], flash: Option[String] = None): String =
    Layout.page("Supervisor Inbox", "/decisions/inbox")(
      div(cls := "mx-auto max-w-5xl space-y-6 p-6")(
        h1(cls := "text-2xl font-bold text-white")("Supervisor Inbox"),
        p(cls := "text-sm text-slate-400")(
          s"${decisions.size} decision(s) awaiting your attention. " +
            "Tap a quick-reply button to resolve. Channel-agnostic — Telegram " +
            "and this inbox stay in sync."
        ),
        flash.map(message =>
          div(cls := "rounded-lg border border-emerald-400/30 bg-emerald-500/10 px-4 py-2 text-sm text-emerald-200")(
            message
          )
        ).getOrElse(frag()),
        if decisions.isEmpty then
          div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/40 p-12 text-center")(
            p(cls := "text-sm text-slate-400")("No decisions need attention right now.")
          )
        else
          div(cls := "space-y-4")(decisions.map(inboxCard)),
      )
    )

  private def inboxCard(decision: Decision): Frag =
    val borderTone =
      if decision.urgency == DecisionUrgency.Critical then "border-rose-500/45"
      else if decision.urgency == DecisionUrgency.High then "border-amber-400/35"
      else "border-white/10"
    val statusTone =
      decision.status match
        case DecisionStatus.Escalated => "text-rose-300"
        case DecisionStatus.Pending   => "text-slate-300"
        case _                        => "text-emerald-300"
    div(cls := s"rounded-xl border bg-slate-900/60 p-5 $borderTone")(
      div(cls := "flex flex-wrap items-start justify-between gap-3")(
        div(
          h2(cls := "text-lg font-semibold text-white")(decision.title),
          p(cls := s"mt-1 text-xs uppercase tracking-wide $statusTone")(
            s"${decision.status} · ${decision.urgency}"
          ),
        ),
        urgencyBadge(decision.urgency),
      ),
      p(cls := "mt-3 text-sm text-slate-200 whitespace-pre-wrap")(decision.context),
      decision.source.issueId.map(id =>
        p(cls := "mt-2 text-xs text-indigo-300")(s"Issue: ${id.value}")
      ).getOrElse(frag()),
      div(cls := "mt-4 flex flex-wrap gap-2")(
        decision.renderableQuickOptions.map(option => quickReplyButton(decision, option))
      ),
    )

  private def quickReplyButton(decision: Decision, option: QuickOption): Frag =
    val tone = option.resolution match
      case DecisionResolutionKind.Approved        => "border-emerald-400/40 bg-emerald-500/10 text-emerald-200 hover:bg-emerald-500/20"
      case DecisionResolutionKind.ReworkRequested => "border-amber-400/40 bg-amber-500/10 text-amber-200 hover:bg-amber-500/20"
      case DecisionResolutionKind.Acknowledged    => "border-cyan-400/40 bg-cyan-500/10 text-cyan-200 hover:bg-cyan-500/20"
      case DecisionResolutionKind.Escalated       => "border-rose-400/40 bg-rose-500/10 text-rose-200 hover:bg-rose-500/20"
      case DecisionResolutionKind.Expired         => "border-slate-400/40 bg-slate-500/10 text-slate-200"
    form(
      cls    := "inline-block",
      action := s"/decisions/${decision.id.value}/resolve",
      method := "post",
    )(
      input(`type` := "hidden", name := "key", value := option.key),
      input(`type` := "hidden", name := "actor", value := "web-supervisor"),
      button(
        `type` := "submit",
        cls    := s"rounded-md border px-3 py-1.5 text-sm font-semibold $tone",
      )(option.label),
    )

  def sidePanelFragment(decisions: List[Decision], runId: String): String =
    div(cls := "space-y-3 p-1", attr("data-decision-panel") := runId)(
      if decisions.isEmpty then
        div(cls := "rounded-lg border border-dashed border-white/10 bg-slate-900/40 p-6 text-center")(
          p(cls := "text-xs text-gray-400")("No pending decisions for this run.")
        )
      else
        frag(
          p(cls := "text-xs text-gray-400 mb-2")(s"${decisions.size} decision(s) pending review"),
          decisions.map(d => sidePanelCard(d, runId)),
        )
    ).render

  private def sidePanelCard(decision: Decision, runId: String): Frag =
    val criticalCls =
      if decision.urgency == DecisionUrgency.Critical then " border-rose-500/45" else " border-white/10"
    div(cls := s"rounded-lg border bg-slate-900/60 p-3$criticalCls")(
      div(cls := "flex items-start justify-between gap-2")(
        h3(cls := "text-xs font-semibold text-white leading-snug")(decision.title),
        div(cls := "flex-shrink-0")(urgencyBadge(decision.urgency)),
      ),
      p(cls := "mt-1 text-[11px] text-slate-400 line-clamp-3")(decision.context),
      decision.source.issueId.map(issueId =>
        p(cls := "mt-1 text-[11px] text-indigo-300")(s"Issue: ${issueId.value}")
      ).getOrElse(frag()),
      decision.resolution.map { resolution =>
        div(cls := "mt-2 rounded border border-emerald-400/20 bg-emerald-500/5 px-2 py-2 text-[11px] text-emerald-100")(
          span(cls := "font-semibold")(resolution.kind.toString),
          span(cls := "ml-2 text-emerald-200/80")(s"by ${resolution.actor}"),
        )
      }.getOrElse(sidePanelActionBar(decision, runId)),
    )

  private def sidePanelActionBar(decision: Decision, runId: String): Frag =
    if decision.status != DecisionStatus.Pending then frag()
    else
      div(cls := "mt-2 space-y-2")(
        form(
          action            := s"/decisions/${decision.id.value}/resolve",
          method            := "post",
          attr("hx-post")   := s"/decisions/${decision.id.value}/resolve",
          attr("hx-target") := "closest [data-decision-panel]",
          attr("hx-swap")   := "outerHTML",
        )(
          input(`type` := "hidden", name := "_run_id", value := runId),
          textarea(
            name        := "summary",
            rows        := "2",
            placeholder := "Reviewer notes (optional)…",
            cls         := "w-full rounded border border-white/10 bg-black/20 px-2 py-1 text-[11px] text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-cyan-500/50",
          )(""),
          div(cls := "mt-1.5 flex flex-wrap gap-1.5")(
            button(
              `type`              := "submit",
              name                := "resolution",
              value               := DecisionResolutionKind.Approved.toString,
              attr("data-action") := "approve",
              cls                 := "rounded border border-emerald-400/30 bg-emerald-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-emerald-200 hover:bg-emerald-500/20",
            )("Approve"),
            button(
              `type`              := "submit",
              name                := "resolution",
              value               := DecisionResolutionKind.ReworkRequested.toString,
              attr("data-action") := "rework",
              cls                 := "rounded border border-amber-400/30 bg-amber-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-amber-200 hover:bg-amber-500/20",
            )("Rework"),
            button(
              `type`            := "button",
              cls               := "rounded border border-rose-400/30 bg-rose-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-rose-200 hover:bg-rose-500/20",
              attr("hx-post")   := s"/decisions/${decision.id.value}/escalate",
              attr("hx-target") := "closest [data-decision-panel]",
              attr("hx-swap")   := "outerHTML",
            )("Escalate"),
          ),
        )
      )

  private def urgencyBadge(urgency: DecisionUrgency): Frag =
    val tone = urgency match
      case DecisionUrgency.Critical => "border-rose-400/30 bg-rose-500/10 text-rose-200"
      case DecisionUrgency.High     => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case DecisionUrgency.Medium   => "border-cyan-400/30 bg-cyan-500/10 text-cyan-200"
      case DecisionUrgency.Low      => "border-slate-400/30 bg-slate-500/10 text-slate-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $tone")(urgency.toString)
