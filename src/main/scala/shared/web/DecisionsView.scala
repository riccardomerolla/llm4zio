package shared.web

import decision.entity.*
import scalatags.Text.all.*

object DecisionsView:

  def page(
    decisions: List[Decision],
    statusFilter: Option[String],
    sourceFilter: Option[String],
    urgencyFilter: Option[String],
    query: Option[String],
  ): String =
    Layout.page("Decisions", "/decisions")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Decision Inbox"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Review queued human decisions, resolve issue reviews, and monitor overdue escalations."
          ),
        ),
        filterBar(statusFilter, sourceFilter, urgencyFilter, query),
        if decisions.isEmpty then
          div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center")(
            p(cls := "text-slate-300")("No decisions match the current filters.")
          )
        else
          div(cls := "space-y-4")(decisions.map(card)*),
      )
    )

  private def filterBar(
    statusFilter: Option[String],
    sourceFilter: Option[String],
    urgencyFilter: Option[String],
    query: Option[String],
  ): Frag =
    form(
      action := "/decisions",
      method := "get",
      cls    := "grid gap-3 rounded-xl border border-white/10 bg-slate-900/60 p-4 lg:grid-cols-4",
    )(
      selectField("status", statusFilter, List("pending", "resolved", "escalated", "expired")),
      selectField("source", sourceFilter, List("issue_review", "governance", "agent_escalation", "manual")),
      selectField("urgency", urgencyFilter, List("low", "medium", "high", "critical")),
      div(cls := "space-y-2")(
        label(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400", `for` := "query")("Search"),
        input(
          id          := "query",
          name        := "query",
          value       := query.getOrElse(""),
          placeholder := "issue, title, context",
          cls         := "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
        ),
        div(cls := "flex justify-end")(
          button(
            `type` := "submit",
            cls    := "rounded bg-cyan-600 px-3 py-2 text-xs font-semibold text-white hover:bg-cyan-500",
          )(
            "Apply"
          )
        ),
      ),
    )

  private def selectField(name: String, selected: Option[String], values: List[String]): Frag =
    div(cls := "space-y-2")(
      label(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400", `for` := name)(name.capitalize),
      select(
        id           := name,
        attr("name") := name,
        cls          := "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
      )(
        option(value := "", if selected.isEmpty then attr("selected") := "selected" else ())("Any"),
        frag(
          values.map { rawValue =>
            option(
              attr("value") := rawValue,
              if selected.contains(rawValue) then attr("selected") := "selected" else (),
            )(
              rawValue.split("_").map(_.capitalize).mkString(" ")
            )
          }
        ),
      ),
    )

  private def card(decision: Decision): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      div(cls := "flex flex-wrap items-start justify-between gap-4")(
        div(
          h2(cls := "text-lg font-semibold text-white")(decision.title),
          p(cls := "mt-1 text-sm text-slate-300")(decision.context),
          p(
            cls := "mt-2 text-xs text-slate-500"
          )(s"${decision.id.value} · ${decision.source.kind.toString} · ${decision.action.toString}"),
        ),
        div(cls := "flex flex-wrap items-center gap-2")(statusBadge(decision.status), urgencyBadge(decision.urgency)),
      ),
      div(cls := "mt-4 flex flex-wrap gap-2 text-xs text-slate-300")(
        chip(s"Ref ${decision.source.referenceId}"),
        decision.source.workspaceId.map(id => chip(s"Workspace $id")).getOrElse(frag()),
        decision.deadlineAt.map(ts => chip(s"Deadline ${ts.toString}")).getOrElse(chip("No deadline")),
      ),
      decision.resolution.map { resolution =>
        div(cls := "mt-4 rounded-lg border border-emerald-400/20 bg-emerald-500/5 px-3 py-3 text-sm text-emerald-100")(
          p(cls := "font-semibold")(resolution.kind.toString),
          p(cls := "mt-1 text-xs text-emerald-200/80")(s"${resolution.actor} · ${resolution.respondedAt}"),
          p(cls := "mt-2 text-sm text-emerald-50")(resolution.summary),
        )
      }.getOrElse(actionBar(decision)),
    )

  private def actionBar(decision: Decision): Frag =
    if decision.status != DecisionStatus.Pending then frag()
    else
      div(cls := "mt-4 flex flex-wrap gap-2")(
        resolutionForm(decision.id.value, DecisionResolutionKind.Approved, "Approve", "emerald"),
        resolutionForm(decision.id.value, DecisionResolutionKind.ReworkRequested, "Request rework", "amber"),
        escalateForm(decision.id.value),
      )

  private def resolutionForm(
    decisionId: String,
    resolutionKind: DecisionResolutionKind,
    labelText: String,
    tone: String,
  ): Frag =
    form(action := s"/decisions/$decisionId/resolve", method := "post")(
      input(`type` := "hidden", name := "resolution", value := resolutionKind.toString),
      button(
        `type` := "submit",
        cls    := s"rounded border border-$tone-400/30 bg-$tone-500/10 px-3 py-2 text-xs font-semibold text-$tone-200 hover:bg-$tone-500/20",
      )(labelText),
    )

  private def escalateForm(decisionId: String): Frag =
    form(action := s"/decisions/$decisionId/escalate", method := "post")(
      button(
        `type` := "submit",
        cls    := "rounded border border-rose-400/30 bg-rose-500/10 px-3 py-2 text-xs font-semibold text-rose-200 hover:bg-rose-500/20",
      )("Escalate")
    )

  private def chip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-xs text-slate-200")(value)

  private def statusBadge(status: DecisionStatus): Frag =
    val tone = status match
      case DecisionStatus.Pending   => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case DecisionStatus.Resolved  => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case DecisionStatus.Escalated => "border-rose-400/30 bg-rose-500/10 text-rose-200"
      case DecisionStatus.Expired   => "border-slate-400/30 bg-slate-500/10 text-slate-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $tone")(status.toString)

  private def urgencyBadge(urgency: DecisionUrgency): Frag =
    val tone = urgency match
      case DecisionUrgency.Critical => "border-rose-400/30 bg-rose-500/10 text-rose-200"
      case DecisionUrgency.High     => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case DecisionUrgency.Medium   => "border-cyan-400/30 bg-cyan-500/10 text-cyan-200"
      case DecisionUrgency.Low      => "border-slate-400/30 bg-slate-500/10 text-slate-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $tone")(urgency.toString)
