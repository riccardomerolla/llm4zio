package web.views

import models.AgentInfo
import scalatags.Text.all.*

object AgentsView:

  def list(agents: List[AgentInfo]): String =
    Layout.page("Agents", "/agents")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Agents"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Built-in migration agents and their capabilities"
          ),
        ),
        div(cls := "grid grid-cols-1 gap-4 lg:grid-cols-2")(
          agents.map(agentCard)
        ),
      )
    )

  private def agentCard(agent: AgentInfo): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
      div(cls := "flex items-start justify-between gap-3")(
        div(
          h2(cls := "text-lg font-semibold text-slate-100")(agent.displayName),
          p(cls := "mt-1 text-sm text-slate-300")(agent.description),
        ),
        aiBadge(agent.usesAI),
      ),
      div(cls := "mt-4 flex flex-wrap gap-2")(
        agent.tags.map(tagBadge)
      ),
      if agent.usesAI then
        div(cls := "mt-4")(
          a(
            href := s"/agents/${agent.name}/config",
            cls  := "inline-flex rounded-md border border-indigo-400/30 bg-indigo-500/20 px-3 py-1.5 text-sm font-semibold text-indigo-200 hover:bg-indigo-500/30",
          )("Configure")
        )
      else (),
    )

  private def aiBadge(usesAI: Boolean): Frag =
    val badgeCls =
      if usesAI then
        "rounded-full border border-emerald-400/30 bg-emerald-500/20 px-2 py-0.5 text-xs font-semibold text-emerald-200"
      else "rounded-full border border-slate-500/30 bg-slate-500/20 px-2 py-0.5 text-xs font-semibold text-slate-200"
    span(cls := badgeCls)(
      if usesAI then "Uses AI" else "No AI"
    )

  private def tagBadge(tag: String): Frag =
    span(cls := s"rounded-full border px-2 py-0.5 text-xs font-semibold ${tagBadgeClass(tag)}")(tag)

  private def tagBadgeClass(tag: String): String =
    val palette = Vector(
      "border-rose-400/30 bg-rose-500/20 text-rose-200",
      "border-amber-400/30 bg-amber-500/20 text-amber-200",
      "border-emerald-400/30 bg-emerald-500/20 text-emerald-200",
      "border-cyan-400/30 bg-cyan-500/20 text-cyan-200",
      "border-indigo-400/30 bg-indigo-500/20 text-indigo-200",
      "border-fuchsia-400/30 bg-fuchsia-500/20 text-fuchsia-200",
    )
    val idx     = math.abs(tag.toLowerCase.hashCode) % palette.size
    palette(idx)
