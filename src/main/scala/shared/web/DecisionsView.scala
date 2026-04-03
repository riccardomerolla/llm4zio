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
    pendingCount: Int = 0,
  ): String =
    Layout.page("Decisions", "/decisions", pendingDecisions = Some(pendingCount))(
      tag("style")(raw(
        "@keyframes decision-pulse{0%,100%{border-color:rgba(244,63,94,.45)}50%{border-color:rgba(244,63,94,.1)}}" +
          ".decision-critical{animation:decision-pulse 2s ease-in-out infinite}"
      )),
      div(cls := "space-y-6")(
        header(statusFilter, sourceFilter, urgencyFilter, query, pendingCount),
        decisionsList(decisions),
      ),
      keyboardNavScript,
    )

  def cardsFragment(decisions: List[Decision]): String =
    decisionsList(decisions).render

  def sidePanelFragment(decisions: List[Decision], runId: String): String =
    div(cls := "space-y-3 p-1", attr("data-decision-panel") := runId)(
      if decisions.isEmpty then
        div(cls := "rounded-lg border border-dashed border-white/10 bg-slate-900/40 p-6 text-center")(
          p(cls := "text-xs text-gray-400")("No pending decisions for this run."),
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

  private def decisionsList(decisions: List[Decision]): Frag =
    div(
      id                 := "decisions-list",
      attr("hx-get")     := "/decisions/fragment",
      attr("hx-trigger") := "every 5s [!document.querySelector('#decisions-list textarea:focus')]",
      attr("hx-target")  := "#decisions-list",
      attr("hx-swap")    := "outerHTML",
      attr("hx-include") := "#decisions-filter-form",
    )(
      if decisions.isEmpty then
        div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center")(
          p(cls := "text-slate-300")("No decisions match the current filters.")
        )
      else
        div(cls := "space-y-4")(decisions.map(card)*)
    )

  private def header(
    statusFilter: Option[String],
    sourceFilter: Option[String],
    urgencyFilter: Option[String],
    query: Option[String],
    pendingCount: Int,
  ): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-3")(
      form(
        id     := "decisions-filter-form",
        action := "/decisions",
        method := "get",
        cls    := "flex flex-wrap items-center gap-3",
      )(
        h1(cls := "shrink-0 text-lg font-bold text-white mr-2")("Decision Inbox"),
        if pendingCount > 0 then
          span(
            cls := "shrink-0 inline-flex items-center rounded-full border border-amber-400/30 bg-amber-500/10 px-3 py-1 text-xs font-semibold text-amber-200 mr-1"
          )(s"$pendingCount pending")
        else frag(),
        inlineSelect("status", statusFilter, List("pending", "resolved", "escalated", "expired"), "Status"),
        inlineSelect(
          "source",
          sourceFilter,
          List("issue_review", "governance", "agent_escalation", "manual"),
          "Source",
        ),
        inlineSelect("urgency", urgencyFilter, List("low", "medium", "high", "critical"), "Urgency"),
        input(
          id          := "query",
          name        := "query",
          value       := query.getOrElse(""),
          placeholder := "Search issue, title, context…",
          cls         := "min-w-0 flex-1 rounded border border-white/10 bg-black/20 px-3 py-1.5 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        button(
          `type` := "submit",
          cls    := "shrink-0 rounded bg-cyan-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-cyan-500",
        )("Apply"),
      )
    )

  private def inlineSelect(name: String, selected: Option[String], values: List[String], placeholder: String): Frag =
    select(
      id           := name,
      attr("name") := name,
      cls          := "shrink-0 rounded border border-white/10 bg-black/20 px-3 py-1.5 text-sm text-slate-100",
    )(
      option(value := "", if selected.isEmpty then attr("selected") := "selected" else ())(placeholder),
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
    )

  private def card(decision: Decision): Frag =
    val criticalCls =
      if decision.urgency == DecisionUrgency.Critical then " decision-critical border-rose-500/45"
      else " border-white/10"
    div(
      cls                   := s"rounded-xl border bg-slate-900/60 p-5$criticalCls",
      attr("data-decision") := decision.id.value,
      attr("tabindex")      := "0",
    )(
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
      div(cls := "mt-4 space-y-2")(
        form(action := s"/decisions/${decision.id.value}/resolve", method := "post")(
          textarea(
            name        := "summary",
            rows        := "2",
            placeholder := "Reviewer notes (optional)…",
            cls         := "w-full rounded border border-white/10 bg-black/20 px-2 py-1.5 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-cyan-500/50",
          )(""),
          div(cls := "mt-2 flex flex-wrap gap-2")(
            button(
              `type`              := "submit",
              name                := "resolution",
              value               := DecisionResolutionKind.Approved.toString,
              attr("data-action") := "approve",
              cls                 := "rounded border border-emerald-400/30 bg-emerald-500/10 px-3 py-2 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/20",
            )("Approve"),
            button(
              `type`              := "submit",
              name                := "resolution",
              value               := DecisionResolutionKind.ReworkRequested.toString,
              attr("data-action") := "rework",
              cls                 := "rounded border border-amber-400/30 bg-amber-500/10 px-3 py-2 text-xs font-semibold text-amber-200 hover:bg-amber-500/20",
            )("Request rework"),
          ),
        ),
        escalateForm(decision.id.value),
      )

  private def escalateForm(decisionId: String): Frag =
    form(action := s"/decisions/$decisionId/escalate", method := "post")(
      button(
        `type`              := "submit",
        attr("data-action") := "escalate",
        cls                 := "rounded border border-rose-400/30 bg-rose-500/10 px-3 py-2 text-xs font-semibold text-rose-200 hover:bg-rose-500/20",
      )("Escalate")
    )

  private def sidePanelActionBar(decision: Decision, runId: String): Frag =
    if decision.status != DecisionStatus.Pending then frag()
    else
      div(cls := "mt-2 space-y-2")(
        form(
          action          := s"/decisions/${decision.id.value}/resolve",
          method          := "post",
          attr("hx-post") := s"/decisions/${decision.id.value}/resolve",
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
        ),
      )

  private def keyboardNavScript: Frag =
    script(raw(
      """(function () {
        |  var focusIdx = -1;
        |  function getCards() { return Array.from(document.querySelectorAll('[data-decision]')); }
        |  function applyFocus(cards) {
        |    cards.forEach(function (c, i) {
        |      c.classList.toggle('ring-2', i === focusIdx);
        |      c.classList.toggle('ring-white/30', i === focusIdx);
        |    });
        |    if (focusIdx >= 0 && cards[focusIdx]) {
        |      cards[focusIdx].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        |    }
        |  }
        |  document.addEventListener('keydown', function (e) {
        |    var tag = e.target.tagName;
        |    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
        |    var cards = getCards();
        |    if (!cards.length) return;
        |    if (e.key === 'j') {
        |      focusIdx = Math.min(focusIdx + 1, cards.length - 1);
        |      applyFocus(cards);
        |    } else if (e.key === 'k') {
        |      focusIdx = Math.max(focusIdx - 1, 0);
        |      applyFocus(cards);
        |    } else if (e.key === 'a' && focusIdx >= 0 && cards[focusIdx]) {
        |      var btn = cards[focusIdx].querySelector('[data-action="approve"]');
        |      if (btn) btn.click();
        |    } else if (e.key === 'r' && focusIdx >= 0 && cards[focusIdx]) {
        |      var btn = cards[focusIdx].querySelector('[data-action="rework"]');
        |      if (btn) btn.click();
        |    } else if (e.key === 'e' && focusIdx >= 0 && cards[focusIdx]) {
        |      var btn = cards[focusIdx].querySelector('[data-action="escalate"]');
        |      if (btn) btn.click();
        |    }
        |  });
        |})();
        |""".stripMargin
    ))

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
