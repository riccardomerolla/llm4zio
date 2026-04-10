package decision.boundary

import decision.entity.*
import scalatags.Text.all.*

object DecisionsView:

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
