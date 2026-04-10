package governance.boundary

import governance.entity.*
import scalatags.Text.all.*
import shared.web.SettingsShell

object GovernanceView:

  def page(activePolicies: List[GovernancePolicy], archivedPolicies: List[GovernancePolicy]): String =
    SettingsShell.page("governance", "Settings — Governance")(
      div(cls := "space-y-6")(
        p(cls := "text-sm text-slate-300 mb-4")(
          "Active policies, lifecycle transition rules, gate requirements, and archive."
        ),
        if activePolicies.isEmpty then
          div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center")(
            p(cls := "text-slate-300")("No governance policies are defined yet.")
          )
        else
          div(cls := "space-y-4")(activePolicies.map(policyCard)*)
        ,
        if archivedPolicies.nonEmpty then
          div(cls := "space-y-3")(
            div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-3")(
              h2(cls := "text-lg font-semibold text-white")("Archived Policies")
            ),
            div(cls := "space-y-3")(archivedPolicies.map(archivedCard)*),
          )
        else frag(),
      )
    )

  private def policyCard(policy: GovernancePolicy): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5 space-y-5")(
      div(cls := "flex flex-wrap items-start justify-between gap-4")(
        div(
          h2(cls := "text-lg font-semibold text-white")(policy.name),
          p(cls := "mt-1 text-xs text-slate-500")(
            s"${policy.id.value} · v${policy.version} · project ${policy.projectId.value}"
          ),
        ),
        div(cls := "flex flex-wrap items-center gap-2")(
          if policy.isDefault then chip("Default") else frag(),
          statBadge(s"${policy.transitionRules.size} rules"),
          statBadge(s"${policy.daemonTriggers.size} triggers"),
          statBadge(s"${policy.escalationRules.size} escalations"),
        ),
      ),
      transitionDiagram(policy),
      if policy.transitionRules.nonEmpty then
        div(cls := "space-y-2")(
          div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")("Transition Rules"),
          div(cls := "space-y-2")(policy.transitionRules.map(transitionRuleRow)*),
        )
      else frag(),
    )

  private def transitionDiagram(policy: GovernancePolicy): Frag =
    val stages = List(
      GovernanceLifecycleStage.Backlog,
      GovernanceLifecycleStage.Todo,
      GovernanceLifecycleStage.InProgress,
      GovernanceLifecycleStage.HumanReview,
      GovernanceLifecycleStage.Rework,
      GovernanceLifecycleStage.Merging,
      GovernanceLifecycleStage.Done,
    )

    div(cls := "rounded-lg border border-white/10 bg-black/20 px-4 py-3")(
      div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400 mb-3")("Lifecycle Stages"),
      div(cls := "flex flex-wrap items-center gap-1")(
        stages.zipWithIndex.flatMap {
          case (stage, idx) =>
            val isLast = idx == stages.size - 1
            List[Frag](
              stageNode(stage, policy),
              if isLast then frag() else span(cls := "text-slate-500 text-xs")("→"),
            )
        }*
      ),
    )

  private def stageNode(
    stage: GovernanceLifecycleStage,
    policy: GovernancePolicy,
  ): Frag =
    val outgoing     = policy.transitionRules.filter(_.transition.from == stage)
    val hasGate      = outgoing.exists(r => r.requiredGates.nonEmpty)
    val hasApproval  = outgoing.exists(_.requireHumanApproval)
    val stageLabel   = stage.toString
    val (bg, border) =
      if stage == GovernanceLifecycleStage.Done then
        ("bg-emerald-500/10", "border-emerald-400/30")
      else if hasApproval then
        ("bg-amber-500/10", "border-amber-400/30")
      else if hasGate then
        ("bg-cyan-500/10", "border-cyan-400/30")
      else
        ("bg-slate-800/60", "border-white/15")
    div(cls := "flex flex-col items-center gap-1")(
      span(
        cls   := s"rounded border $border $bg px-2 py-1 text-xs font-semibold text-slate-100",
        title := gateTooltip(outgoing),
      )(stageLabel),
      if hasGate || hasApproval then
        span(cls := "text-[9px] text-slate-500")(gateShortLabel(outgoing))
      else frag(),
    )

  private def gateTooltip(rules: List[GovernanceTransitionRule]): String =
    rules.flatMap(_.requiredGates).distinct.map(_.toString).mkString(", ")

  private def gateShortLabel(rules: List[GovernanceTransitionRule]): String =
    val gates    = rules.flatMap(_.requiredGates).distinct
    val approval = rules.exists(_.requireHumanApproval)
    if gates.nonEmpty then s"${gates.size} gate(s)"
    else if approval then "approval"
    else ""

  private def transitionRuleRow(rule: GovernanceTransitionRule): Frag =
    div(cls := "rounded-md border border-white/10 bg-slate-950/60 px-3 py-2")(
      div(cls := "flex flex-wrap items-center justify-between gap-2")(
        div(
          span(cls := "text-xs font-semibold text-slate-200")(
            s"${rule.transition.from} → ${rule.transition.to}"
          ),
          span(cls := "ml-2 text-xs text-slate-500")(s"(${rule.transition.action})"),
        ),
        div(cls := "flex flex-wrap gap-1")(
          frag(
            rule.requiredGates.map(g =>
              span(cls := "rounded border border-cyan-400/30 bg-cyan-500/10 px-2 py-0.5 text-[10px] text-cyan-200")(
                g.toString
              )
            )*
          ),
          if rule.requireHumanApproval then
            span(
              cls := "rounded border border-amber-400/30 bg-amber-500/10 px-2 py-0.5 text-[10px] text-amber-200"
            )("HumanApproval")
          else frag(),
        ),
      ),
      if rule.allowedIssueTypes.nonEmpty then
        p(cls := "mt-1 text-[10px] text-slate-500")(
          s"Issue types: ${rule.allowedIssueTypes.mkString(", ")}"
        )
      else frag(),
      if rule.blockedTags.nonEmpty then
        p(cls := "mt-0.5 text-[10px] text-rose-400/80")(
          s"Blocked tags: ${rule.blockedTags.mkString(", ")}"
        )
      else frag(),
    )

  private def archivedCard(policy: GovernancePolicy): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-950/60 px-5 py-3 opacity-60")(
      div(cls := "flex flex-wrap items-center justify-between gap-2")(
        div(
          h3(cls := "text-sm font-semibold text-slate-300")(policy.name),
          p(cls := "text-xs text-slate-600")(
            s"${policy.id.value} · v${policy.version} · project ${policy.projectId.value}"
          ),
        ),
        div(cls := "flex flex-wrap items-center gap-2")(
          span(
            cls := "rounded-full border border-slate-400/30 bg-slate-500/10 px-3 py-1 text-xs font-semibold text-slate-400"
          )("Archived"),
          policy.archivedAt.map(t => chip(t.toString)).getOrElse(frag()),
        ),
      )
    )

  private def chip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-xs text-slate-200")(value)

  private def statBadge(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-2 py-0.5 text-xs text-slate-300")(value)
