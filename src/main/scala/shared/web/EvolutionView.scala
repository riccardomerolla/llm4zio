package shared.web

import evolution.entity.{ EvolutionProposal, EvolutionProposalStatus }
import scalatags.Text.all.*

object EvolutionView:

  def page(proposals: List[EvolutionProposal]): String =
    val grouped = proposals.groupBy(_.status).withDefaultValue(Nil)
    Layout.page("Evolution", "/evolution")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Evolution"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Platform change proposals across governance, workflows, and daemon automation."
          ),
        ),
        div(cls := "grid gap-4 md:grid-cols-4")(
          statusTile("Proposed", grouped(EvolutionProposalStatus.Proposed).size),
          statusTile("Approved", grouped(EvolutionProposalStatus.Approved).size),
          statusTile("Applied", grouped(EvolutionProposalStatus.Applied).size),
          statusTile("Rolled Back", grouped(EvolutionProposalStatus.RolledBack).size),
        ),
        if proposals.isEmpty then
          div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center")(
            p(cls := "text-slate-300")("No evolution proposals exist yet.")
          )
        else
          div(cls := "space-y-4")(proposals.sortBy(proposal => -proposal.updatedAt.toEpochMilli).map(proposalCard)*),
      )
    )

  private def statusTile(label: String, count: Int): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 px-4 py-3")(
      div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(label),
      div(cls := "mt-2 text-2xl font-semibold text-white")(count.toString),
    )

  private def proposalCard(proposal: EvolutionProposal): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      div(cls := "flex flex-wrap items-start justify-between gap-4")(
        div(
          h2(cls := "text-lg font-semibold text-white")(proposal.title),
          p(cls := "mt-1 text-xs text-slate-500")(s"${proposal.id.value} · project ${proposal.projectId.value}"),
        ),
        span(cls := statusBadgeClass(proposal.status))(proposal.status.toString),
      ),
      p(cls := "mt-3 text-sm text-slate-300")(proposal.rationale),
      div(cls := "mt-4 flex flex-wrap gap-2 text-xs text-slate-400")(
        chip(s"Target ${targetLabel(proposal)}"),
        chip(s"Proposed by ${proposal.proposer.actor}"),
        chip(s"Updated ${proposal.updatedAt.toString}"),
      ),
    )

  private def targetLabel(proposal: EvolutionProposal): String =
    proposal.target match
      case _: evolution.entity.EvolutionTarget.GovernancePolicyTarget   => "Governance Policy"
      case _: evolution.entity.EvolutionTarget.WorkflowDefinitionTarget => "Workflow"
      case _: evolution.entity.EvolutionTarget.DaemonAgentSpecTarget    => "Daemon"

  private def chip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1")(value)

  private def statusBadgeClass(status: EvolutionProposalStatus): String =
    val tone = status match
      case EvolutionProposalStatus.Proposed   => "border-sky-400/30 bg-sky-500/10 text-sky-200"
      case EvolutionProposalStatus.Approved   => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case EvolutionProposalStatus.Applied    => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case EvolutionProposalStatus.RolledBack => "border-rose-400/30 bg-rose-500/10 text-rose-200"
    s"rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-wide $tone"
