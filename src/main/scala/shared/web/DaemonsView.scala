package shared.web

import daemon.entity.*
import scalatags.Text.all.*

object DaemonsView:

  def page(statuses: List[DaemonAgentStatus]): String =
    Layout.page("Daemons", "/daemons")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Daemon Agents"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Continuous maintenance agents derived per project and governed through settings or governance policy."
          ),
        ),
        if statuses.isEmpty then
          div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center text-slate-300")(
            "No daemon agents are configured yet."
          )
        else
          div(cls := "space-y-4")(statuses.groupBy(_.spec.projectId.value).toList.sortBy(_._1).map(projectSection)),
      )
    )

  private def projectSection(entry: (String, List[DaemonAgentStatus])): Frag =
    val (projectId, statuses) = entry
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      div(cls := "mb-4 flex items-center justify-between gap-3")(
        div(
          h2(cls := "text-lg font-semibold text-white")(s"Project $projectId"),
          p(cls := "text-xs text-slate-400")(s"${statuses.size} maintenance daemon(s)"),
        )
      ),
      div(cls := "space-y-3")(statuses.sortBy(_.spec.name).map(card)),
    )

  private def card(status: DaemonAgentStatus): Frag =
    div(cls := "rounded-lg border border-white/10 bg-slate-950/60 p-4")(
      div(cls := "flex flex-wrap items-start justify-between gap-4")(
        div(
          h3(cls := "text-base font-semibold text-white")(status.spec.name),
          p(cls := "mt-1 text-sm text-slate-300")(status.spec.purpose),
          p(cls := "mt-2 text-xs text-slate-500")(status.spec.id.value),
        ),
        div(cls := "flex flex-wrap items-center gap-2")(
          badge(status.runtime.health),
          lifecycleBadge(status.runtime.lifecycle),
          if status.enabled then chip("Enabled") else chip("Disabled"),
          if status.spec.governed then chip("Governed") else chip("Built-in"),
        ),
      ),
      div(cls := "mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4")(
        stat("Trigger", renderTrigger(status.spec.trigger)),
        stat("Agent", status.spec.agentName),
        stat("Workspaces", status.spec.workspaceIds.mkString(", ")),
        stat("Issues Created", status.runtime.issuesCreated.toString),
      ),
      div(cls := "mt-3 grid gap-3 md:grid-cols-3")(
        stat("Last Started", renderInstant(status.runtime.startedAt)),
        stat("Last Completed", renderInstant(status.runtime.completedAt)),
        stat("Last Issue", renderInstant(status.runtime.lastIssueCreatedAt)),
      ),
      status.runtime.lastSummary.map { summary =>
        div(cls := "mt-3 rounded-md border border-emerald-400/20 bg-emerald-500/5 px-3 py-2 text-xs text-emerald-100")(
          summary
        )
      }.getOrElse(frag()),
      status.runtime.lastError.map { error =>
        div(cls := "mt-3 rounded-md border border-rose-400/20 bg-rose-500/5 px-3 py-2 text-xs text-rose-100")(
          error
        )
      }.getOrElse(frag()),
      div(cls := "mt-4 flex flex-wrap gap-2")(
        actionForm(status.spec.id.value, "start", "Start", "emerald"),
        actionForm(status.spec.id.value, "stop", "Stop", "slate"),
        actionForm(status.spec.id.value, "restart", "Restart", "cyan"),
        if status.enabled then actionForm(status.spec.id.value, "disable", "Disable", "amber")
        else actionForm(status.spec.id.value, "enable", "Enable", "amber"),
      ),
    )

  private def actionForm(id: String, routeAction: String, labelText: String, tone: String): Frag =
    form(action := s"/daemons/$id/$routeAction", method := "post")(
      button(
        `type` := "submit",
        cls    := s"rounded border border-$tone-400/30 bg-$tone-500/10 px-3 py-2 text-xs font-semibold text-$tone-200 hover:bg-$tone-500/20",
      )(labelText)
    )

  private def stat(labelText: String, value: String): Frag =
    div(cls := "rounded-md border border-white/10 bg-black/20 px-3 py-2")(
      div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(labelText),
      div(cls := "mt-1 text-sm text-slate-100")(if value.trim.nonEmpty then value else "None"),
    )

  private def renderTrigger(trigger: DaemonTriggerCondition): String =
    trigger match
      case DaemonTriggerCondition.Scheduled(interval)              => s"Scheduled every ${formatDuration(interval)}"
      case DaemonTriggerCondition.EventDriven(triggerId)           => s"Event-driven ($triggerId)"
      case DaemonTriggerCondition.Continuous(pollInterval)         => s"Continuous ${formatDuration(pollInterval)}"
      case DaemonTriggerCondition.ScheduledWithEvent(interval, id) => s"${formatDuration(interval)} + $id"

  private def renderInstant(value: Option[java.time.Instant]): String =
    value.map(_.toString).getOrElse("Never")

  private def formatDuration(value: zio.Duration): String =
    value.toString

  private def badge(health: DaemonHealth): Frag =
    val tone = health match
      case DaemonHealth.Healthy  => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case DaemonHealth.Running  => "border-cyan-400/30 bg-cyan-500/10 text-cyan-200"
      case DaemonHealth.Degraded => "border-rose-400/30 bg-rose-500/10 text-rose-200"
      case DaemonHealth.Paused   => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case DaemonHealth.Disabled => "border-slate-400/30 bg-slate-500/10 text-slate-200"
      case DaemonHealth.Idle     => "border-white/15 bg-white/5 text-slate-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $tone")(health.toString)

  private def lifecycleBadge(lifecycle: DaemonLifecycle): Frag =
    val tone = lifecycle match
      case DaemonLifecycle.Running => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case DaemonLifecycle.Stopped => "border-slate-400/30 bg-slate-500/10 text-slate-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $tone")(lifecycle.toString)

  private def chip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-xs text-slate-200")(value)
