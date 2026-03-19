package shared.web

import zio.json.*

import _root_.config.entity.{ AgentChannelBinding, AgentInfo, AgentType }
import agent.entity.Agent
import agent.entity.api.{ AgentActiveRun, AgentMetricsHistoryPoint, AgentMetricsSummary, AgentRunHistoryItem }
import scalatags.Text.all.*

object AgentsView:

  final case class AgentCard(
    info: AgentInfo,
    registryAgent: Option[Agent],
    metrics: AgentMetricsSummary,
    activeRuns: List[AgentActiveRun],
    bindings: List[AgentChannelBinding],
  )

  def list(cards: List[AgentCard], flash: Option[String] = None): String =
    Layout.page("Agents", "/agents")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")("Agents"),
              p(cls := "mt-1 text-sm text-slate-300")(
                "Built-in, custom config, and registry agents"
              ),
            ),
            a(
              href := "/agents/new",
              cls  := "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-500/30",
            )("Create Agent"),
          )
        ),
        flash.map { msg =>
          div(cls := "rounded-md border border-emerald-500/30 bg-emerald-500/10 p-4")(
            p(cls := "text-sm text-emerald-300")(msg)
          )
        },
        if cards.isEmpty then
          div(cls := "rounded-xl border border-white/10 bg-slate-900/60 px-4 py-8 text-sm text-slate-400")(
            "No agents configured yet."
          )
        else
          div(cls := "grid grid-cols-1 gap-4 lg:grid-cols-2")(
            cards.sortBy(_.info.displayName.toLowerCase).map(agentCard)
          ),
      )
    )

  private def agentCard(card: AgentCard): Frag =
    val agent   = card.info
    val metrics = card.metrics
    div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
      div(cls := "flex items-start justify-between gap-3")(
        div(
          h2(cls := "text-lg font-semibold text-slate-100")(agent.displayName),
          p(cls := "mt-1 text-sm text-slate-300")(agent.description),
          p(cls := "mt-1 font-mono text-xs text-indigo-300")(s"@${effectiveHandle(agent)}"),
          card.registryAgent.map(reg => p(cls := "mt-1 font-mono text-[11px] text-slate-500")(s"id: ${reg.id.value}")),
        ),
        div(cls := "flex flex-col items-end gap-2")(
          typeBadge(agent.agentType),
          aiBadge(agent.usesAI),
          statusBadge(card.registryAgent),
        ),
      ),
      div(cls := "mt-4 grid grid-cols-2 gap-2 text-xs text-slate-300 md:grid-cols-3")(
        metricCard("Runs", metrics.totalRuns.toString),
        metricCard("Success", f"${metrics.successRate * 100}%.1f%%"),
        metricCard("Avg", formatSeconds(metrics.averageDurationSeconds)),
      ),
      if agent.tags.nonEmpty then
        div(cls := "mt-4 flex flex-wrap gap-2")(
          agent.tags.map(tagBadge)
        )
      else (),
      if card.activeRuns.nonEmpty then
        div(cls := "mt-4 rounded-lg border border-amber-400/20 bg-amber-500/10 p-3")(
          p(cls := "text-xs font-semibold uppercase tracking-wide text-amber-200")("Active Runs"),
          ul(cls := "mt-2 space-y-1 text-xs text-amber-100")(
            card.activeRuns.take(3).map { run =>
              li(
                span(cls := "font-semibold")(run.runId),
                span(cls := "ml-2")(run.status),
                span(cls := "ml-2 text-amber-200/70")(run.issueRef),
              )
            }
          ),
          card.registryAgent match
            case Some(registryAgent) =>
              div(cls := "mt-3 flex flex-wrap gap-2")(
                monitorActionButton(registryAgent.id.value, "pause", "Pause", "amber"),
                monitorActionButton(registryAgent.id.value, "resume", "Resume", "emerald"),
                monitorActionButton(registryAgent.id.value, "abort", "Abort", "rose"),
              )
            case None                => (),
        )
      else (),
      div(cls := "mt-4 flex flex-wrap gap-2")(
        if agent.usesAI then
          a(
            href := s"/agents/${agent.name}/config",
            cls  := "inline-flex rounded-md border border-indigo-400/30 bg-indigo-500/20 px-3 py-1.5 text-sm font-semibold text-indigo-200 hover:bg-indigo-500/30",
          )("Configure AI")
        else (),
        card.registryAgent.map { reg =>
          a(
            href := s"/agents/${reg.id.value}",
            cls  := "inline-flex rounded-md border border-slate-400/30 bg-slate-600/20 px-3 py-1.5 text-sm font-semibold text-slate-200 hover:bg-slate-500/30",
          )("Details")
        },
        if agent.agentType == AgentType.Custom then
          List(
            a(
              href := s"/agents/${agent.name}/edit",
              cls  := "inline-flex rounded-md border border-cyan-400/30 bg-cyan-500/20 px-3 py-1.5 text-sm font-semibold text-cyan-200 hover:bg-cyan-500/30",
            )("Edit"),
            form(method := "post", action := s"/agents/${agent.name}/delete")(
              button(
                `type` := "submit",
                cls    := "inline-flex rounded-md border border-rose-400/30 bg-rose-500/10 px-3 py-1.5 text-sm font-semibold text-rose-200 hover:bg-rose-500/20",
              )("Delete")
            ),
          )
        else List.empty[Frag],
      ),
      div(cls := "mt-4 border-t border-white/10 pt-3")(
        p(cls := "mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400")("Channel Bindings"),
        if card.bindings.isEmpty then p(cls := "text-xs text-slate-500")("No bindings")
        else
          ul(cls := "mb-2 space-y-1 text-xs text-slate-300")(
            card.bindings.map { binding =>
              val channel = binding.channelName.trim
              val account = binding.accountId.map(_.trim).filter(_.nonEmpty)
              val label   =
                if channel.nonEmpty then s"$channel${account.map(id => s" ($id)").getOrElse("")}"
                else s"(missing channel)${account.map(id => s" ($id)").getOrElse("")}"
              li(cls := "flex items-center justify-between gap-2")(
                span(cls := "flex-1 break-all text-slate-200")(label),
                form(method := "post", action := s"/agents/${agent.name}/bindings/remove")(
                  input(`type` := "hidden", name := "channelName", value := binding.channelName),
                  input(
                    `type`     := "hidden",
                    name       := "accountId",
                    value      := binding.accountId.getOrElse(""),
                  ),
                  button(
                    `type` := "submit",
                    cls    := "rounded border border-rose-400/30 bg-rose-500/10 px-2 py-0.5 text-[10px] font-semibold text-rose-200",
                  )("Unbind"),
                ),
              )
            }
          )
        ,
        form(method := "post", action := s"/agents/${agent.name}/bindings", cls := "mt-2 flex gap-2")(
          input(
            `type`      := "text",
            name        := "channelName",
            placeholder := "channel (e.g. telegram)",
            cls         := "w-full rounded-md border border-white/10 bg-slate-800/80 px-2 py-1 text-xs text-slate-100",
          ),
          input(
            `type`      := "text",
            name        := "accountId",
            placeholder := "accountId (optional)",
            cls         := "w-full rounded-md border border-white/10 bg-slate-800/80 px-2 py-1 text-xs text-slate-100",
          ),
          button(
            `type` := "submit",
            cls    := "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-2 py-1 text-xs font-semibold text-emerald-200",
          )("Bind"),
        ),
      ),
    )

  private def monitorActionButton(agentId: String, actionName: String, label: String, palette: String): Frag =
    val buttonCls = palette match
      case "amber"   =>
        "rounded-md border border-amber-400/30 bg-amber-500/20 px-3 py-1 text-xs font-semibold text-amber-100"
      case "emerald" =>
        "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-1 text-xs font-semibold text-emerald-100"
      case _         =>
        "rounded-md border border-rose-400/30 bg-rose-500/20 px-3 py-1 text-xs font-semibold text-rose-100"
    form(method := "post", action := s"/agents/$agentId/$actionName")(
      button(`type` := "submit", cls := buttonCls)(label)
    )

  private def statusBadge(registryAgent: Option[Agent]): Frag =
    registryAgent match
      case Some(agent) if agent.enabled => Components.badge("Enabled", "success")
      case Some(_)                      => Components.badge("Disabled", "gray")
      case None                         => Components.badge("Config Only", "gray")

  private def metricCard(labelText: String, value: String): Frag =
    div(cls := "rounded border border-white/10 bg-slate-800/70 px-2 py-2")(
      p(cls := "text-[10px] uppercase tracking-wide text-slate-400")(labelText),
      p(cls := "mt-1 text-sm font-semibold text-slate-100")(value),
    )

  def detail(
    agent: Agent,
    metrics: AgentMetricsSummary,
    runs: List[AgentRunHistoryItem],
    activeRuns: List[AgentActiveRun],
    history: List[AgentMetricsHistoryPoint],
    flash: Option[String] = None,
  ): String =
    Layout.page(s"Agent ${agent.name}", "/agents")(
      div(cls := "space-y-4")(
        a(
          href := "/agents",
          cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
        )("Back to agents"),
        flash.map(msg =>
          div(cls := "rounded-md bg-emerald-500/10 border border-emerald-400/30 p-3 text-sm text-emerald-200")(msg)
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
          h1(cls := "text-xl font-bold text-white")(agent.name),
          p(cls := "mt-1 text-sm text-slate-300")(agent.description),
          div(cls := "mt-3 flex flex-wrap gap-2 text-xs")(
            span(cls := "rounded bg-indigo-500/20 px-2 py-1 text-indigo-200")(s"cli: ${agent.cliTool}"),
            span(cls := "rounded bg-slate-700/40 px-2 py-1 text-slate-200")(s"timeout: ${agent.timeout}"),
            span(
              cls := "rounded bg-slate-700/40 px-2 py-1 text-slate-200"
            )(s"maxConcurrentRuns: ${agent.maxConcurrentRuns}"),
            agent.dockerMemoryLimit.map(limit =>
              span(cls := "rounded bg-slate-700/40 px-2 py-1 text-slate-200")(s"docker memory: $limit")
            ),
            agent.dockerCpuLimit.map(limit =>
              span(cls := "rounded bg-slate-700/40 px-2 py-1 text-slate-200")(s"docker cpus: $limit")
            ),
          ),
          div(cls := "mt-3 text-xs text-slate-300")(
            strong("Capabilities: "),
            if agent.capabilities.isEmpty then "none" else agent.capabilities.mkString(", "),
          ),
          div(cls := "mt-4 flex gap-2")(
            a(
              href := s"/agents/${agent.id.value}/edit",
              cls  := "rounded-md border border-indigo-300/30 px-3 py-1.5 text-xs font-semibold text-indigo-200 hover:bg-indigo-500/20",
            )("Edit"),
            tag("form")(method := "post", action := s"/agents/${agent.id.value}/disable")(
              button(
                `type` := "submit",
                cls    := "rounded-md border border-amber-300/30 px-3 py-1.5 text-xs font-semibold text-amber-200 hover:bg-amber-500/20",
              )(if agent.enabled then "Disable" else "Enable")
            ),
          ),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
          h2(cls := "text-sm font-semibold text-slate-100")("Metrics"),
          div(cls := "mt-2 grid grid-cols-2 gap-2 text-xs text-slate-300 md:grid-cols-5")(
            metricCard("Total", metrics.totalRuns.toString),
            metricCard("Completed", metrics.completedRuns.toString),
            metricCard("Failed", metrics.failedRuns.toString),
            metricCard("Active", metrics.activeRuns.toString),
            metricCard("Success", f"${metrics.successRate * 100}%.1f%%"),
          ),
          div(cls := "mt-2 grid grid-cols-1 gap-2 text-xs text-slate-300 md:grid-cols-4")(
            metricCard("Runs (7d)", metrics.totalRuns7d.toString),
            metricCard("Runs (30d)", metrics.totalRuns30d.toString),
            metricCard("Avg Duration", formatSeconds(metrics.averageDurationSeconds)),
            metricCard("Issues Resolved", metrics.issuesResolvedCount.toString),
          ),
        ),
        div(
          cls                                := "rounded-xl border border-white/10 bg-slate-900/60 p-4",
          attr("data-agent-metrics-history") := "true",
          attr("data-history-points")        := history.toJson,
        )(
          h2(cls := "text-sm font-semibold text-slate-100")("30d Trend"),
          div(cls := "mt-3 grid grid-cols-1 gap-3 md:grid-cols-2")(
            div(
              p(cls := "text-xs text-slate-400")("Success Rate"),
              div(cls := "mt-1 rounded border border-white/10 bg-slate-800/70 p-2")(
                tag("svg")(
                  cls                         := "w-full h-16 text-emerald-300",
                  attr("viewBox")             := "0 0 100 40",
                  attr("preserveAspectRatio") := "none",
                  attr("data-sparkline")      := "success-rate",
                )
              ),
            ),
            div(
              p(cls := "text-xs text-slate-400")("Run Count"),
              div(cls := "mt-1 rounded border border-white/10 bg-slate-800/70 p-2")(
                tag("svg")(
                  cls                         := "w-full h-16 text-indigo-300",
                  attr("viewBox")             := "0 0 100 40",
                  attr("preserveAspectRatio") := "none",
                  attr("data-sparkline")      := "run-count",
                )
              ),
            ),
          ),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
          h2(cls := "text-sm font-semibold text-slate-100")("Active Runs"),
          if activeRuns.isEmpty then
            p(cls := "mt-2 text-xs text-slate-400")("No active runs.")
          else
            div(cls := "mt-2 space-y-2")(
              activeRuns.map { run =>
                val target =
                  if run.conversationId.trim.nonEmpty then s"/chat/${run.conversationId}"
                  else s"/runs?agent=${agent.name}"
                a(
                  href := target,
                  cls  := "block rounded border border-white/10 bg-slate-800/70 px-3 py-2 text-xs text-slate-300 hover:bg-slate-800",
                )(
                  span(cls := "font-semibold text-slate-100")(run.runId),
                  span(cls := "ml-2")(s"workspace:${run.workspaceId}"),
                  span(cls := "ml-2")(run.status),
                  span(cls := "ml-2 text-slate-400")(run.issueRef),
                )
              }
            ),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
          h2(cls := "text-sm font-semibold text-slate-100")("Run History"),
          if runs.isEmpty then
            p(cls := "mt-2 text-xs text-slate-400")("No runs found for this agent.")
          else
            div(cls := "mt-2 space-y-2")(
              runs.map { run =>
                div(cls := "rounded border border-white/10 bg-slate-800/70 px-3 py-2 text-xs text-slate-300")(
                  span(cls := "font-semibold text-slate-100")(run.runId),
                  span(cls := "ml-2")(s"workspace:${run.workspaceId}"),
                  span(cls := "ml-2")(run.status),
                  span(cls := "ml-2 text-slate-500")(run.updatedAt.toString),
                )
              }
            ),
        ),
        JsResources.inlineModuleScript("/static/client/components/agent-metrics.js"),
      )
    )

  def newAgentForm(
    values: Map[String, String] = Map.empty,
    flash: Option[String] = None,
  ): String =
    Layout.page("Create Agent", "/agents")(
      div(cls := "mx-auto max-w-4xl space-y-5")(
        a(href := "/agents", cls := "text-sm font-medium text-indigo-300 hover:text-indigo-200")("Back to agents"),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Create Agent"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Single flow for config-level custom agents and registry-backed agents."
          ),
        ),
        flash.map { msg =>
          div(cls := "rounded-md border border-amber-500/30 bg-amber-500/10 p-4")(
            p(cls := "text-sm text-amber-200")(msg)
          )
        },
        form(method := "post", action := "/agents", cls := "space-y-5")(
          div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5 space-y-4")(
            div(
              label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := "agentSource")("Agent Type"),
              select(
                id   := "agentSource",
                name := "agentSource",
                cls  := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100",
              )(
                option(
                  value := "custom",
                  if values.getOrElse("agentSource", "custom") == "custom" then selected := "selected" else (),
                )("Custom Config Agent"),
                option(
                  value := "registry",
                  if values.get("agentSource").contains("registry") then selected := "selected" else (),
                )("Registry Agent"),
              ),
            ),
            div(cls := "grid grid-cols-1 gap-4 md:grid-cols-2")(
              textInputField(
                fieldName = "name",
                labelText = "Name",
                value = values.getOrElse("name", ""),
                placeholder = "e.g. billingRefactorAgent",
                required = true,
              ),
              textInputField(
                fieldName = "displayName",
                labelText = "Display Name",
                value = values.getOrElse("displayName", ""),
                placeholder = "e.g. Billing Refactor Agent",
                required = true,
              ),
              textInputField(
                fieldName = "description",
                labelText = "Description",
                value = values.getOrElse("description", ""),
                placeholder = "One-line purpose",
                required = true,
              ),
              textInputField(
                fieldName = "tags",
                labelText = "Tags (comma separated)",
                value = values.getOrElse("tags", ""),
                placeholder = "analysis,refactor,billing",
              ),
            ),
            div(
              label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := "systemPrompt")("System Prompt"),
              textarea(
                id          := "systemPrompt",
                name        := "systemPrompt",
                rows        := 10,
                cls         := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
                placeholder := "You are a specialized migration agent. Follow these rules...",
                required,
              )(values.getOrElse("systemPrompt", "")),
            ),
          ),
          div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
            h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Registry Fields"),
            p(cls := "mt-1 text-xs text-slate-400")(
              "Used only when Agent Type is Registry Agent."
            ),
            div(cls := "mt-3 grid grid-cols-1 gap-4 md:grid-cols-2")(
              textInputField(
                fieldName = "cliTool",
                labelText = "CLI Tool",
                value = values.getOrElse("cliTool", "gemini"),
                placeholder = "gemini / claude / codex",
              ),
              textInputField(
                fieldName = "capabilities",
                labelText = "Capabilities",
                value = values.getOrElse("capabilities", values.getOrElse("tags", "")),
                placeholder = "analysis,review",
              ),
              textInputField(
                fieldName = "defaultModel",
                labelText = "Default Model",
                value = values.getOrElse("defaultModel", ""),
                placeholder = "optional",
              ),
              textInputField(
                fieldName = "maxConcurrentRuns",
                labelText = "Max Concurrent Runs",
                value = values.getOrElse("maxConcurrentRuns", "1"),
                placeholder = "1",
              ),
              textInputField(
                fieldName = "timeout",
                labelText = "Timeout (ISO-8601)",
                value = values.getOrElse("timeout", "PT30M"),
                placeholder = "PT30M",
              ),
              textInputField(
                fieldName = "dockerMemoryLimit",
                labelText = "Docker Memory Limit",
                value = values.getOrElse("dockerMemoryLimit", ""),
                placeholder = "optional, e.g. 2g",
              ),
              textInputField(
                fieldName = "dockerCpuLimit",
                labelText = "Docker CPU Limit",
                value = values.getOrElse("dockerCpuLimit", ""),
                placeholder = "optional, e.g. 1.5",
              ),
            ),
            div(cls := "mt-4")(
              label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := "envVars")(
                "Env Vars (KEY=VALUE per line)"
              ),
              textarea(
                id   := "envVars",
                name := "envVars",
                rows := 5,
                cls  := "w-full rounded-md border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100",
              )(values.getOrElse("envVars", "")),
            ),
            div(cls := "mt-3")(
              label(cls := "inline-flex items-center gap-2 text-sm text-slate-200")(
                input(
                  `type` := "checkbox",
                  name   := "enabled",
                  if values.getOrElse("enabled", "true") == "true" then checked else (),
                ),
                span("Enabled"),
              )
            ),
          ),
          div(cls := "flex items-center gap-3")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
            )("Create Agent"),
            a(href := "/agents", cls := "text-sm font-medium text-slate-300 hover:text-white")("Cancel"),
          ),
        ),
      )
    )

  def editCustomAgentForm(
    name: String,
    values: Map[String, String],
    flash: Option[String] = None,
  ): String =
    customAgentFormPage(
      title = s"Edit Custom Agent: $name",
      formAction = s"/agents/$name/edit",
      values = values,
      flash = flash,
      editingName = Some(name),
    )

  def registryForm(
    title: String,
    formAction: String,
    values: Map[String, String],
  ): String =
    Layout.page(title, "/agents")(
      div(cls := "max-w-3xl space-y-4")(
        a(
          href := "/agents",
          cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
        )("Back to agents"),
        h1(cls := "text-2xl font-bold text-white")(title),
        tag("form")(
          method := "post",
          action := formAction,
          cls    := "space-y-4 rounded-xl border border-white/10 bg-slate-900/70 p-5",
        )(
          textField("name", "Name", values.getOrElse("name", "")),
          textField("description", "Description", values.getOrElse("description", "")),
          textField("cliTool", "CLI Tool", values.getOrElse("cliTool", "gemini")),
          capabilityEditor(
            fieldName = "capabilities",
            labelText = "Capabilities",
            valueText = values.getOrElse("capabilities", ""),
          ),
          textField("defaultModel", "Default Model", values.getOrElse("defaultModel", "")),
          textAreaField("systemPrompt", "System Prompt", values.getOrElse("systemPrompt", "")),
          textField("maxConcurrentRuns", "Max Concurrent Runs", values.getOrElse("maxConcurrentRuns", "1")),
          textField("timeout", "Timeout ISO-8601 (e.g. PT30M)", values.getOrElse("timeout", "PT30M")),
          textField(
            "dockerMemoryLimit",
            "Docker Memory Limit (optional, e.g. 2g)",
            values.getOrElse("dockerMemoryLimit", ""),
          ),
          textField(
            "dockerCpuLimit",
            "Docker CPU Limit (optional, e.g. 1.5)",
            values.getOrElse("dockerCpuLimit", ""),
          ),
          textAreaField("envVars", "Env Vars (KEY=VALUE per line)", values.getOrElse("envVars", "")),
          div(
            label(cls := "inline-flex items-center gap-2 text-sm text-slate-200")(
              input(
                `type` := "checkbox",
                name   := "enabled",
                if values.getOrElse("enabled", "true") == "true" then checked else (),
              ),
              span("Enabled"),
            )
          ),
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
          )("Save"),
        ),
        JsResources.inlineModuleScript("/static/client/components/capability-tag-editor.js"),
      )
    )

  private def customAgentFormPage(
    title: String,
    formAction: String,
    values: Map[String, String],
    flash: Option[String],
    editingName: Option[String],
  ): String =
    Layout.page(title, "/agents")(
      div(cls := "mx-auto max-w-4xl space-y-5")(
        a(href := "/agents", cls := "text-sm font-medium text-indigo-300 hover:text-indigo-200")("Back to agents"),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")(title),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Define a reusable custom agent for issue assignments."
          ),
        ),
        flash.map { msg =>
          div(cls := "rounded-md border border-amber-500/30 bg-amber-500/10 p-4")(
            p(cls := "text-sm text-amber-200")(msg)
          )
        },
        form(method := "post", action := formAction, cls := "space-y-5")(
          div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
            div(cls := "grid grid-cols-1 gap-4 md:grid-cols-2")(
              textInputField(
                fieldName = "name",
                labelText = "Name",
                value = values.getOrElse("name", ""),
                placeholder = "e.g. billingRefactorAgent",
                readOnly = editingName.isDefined,
                required = true,
              ),
              textInputField(
                fieldName = "displayName",
                labelText = "Display Name",
                value = values.getOrElse("displayName", ""),
                placeholder = "e.g. Billing Refactor Agent",
                required = true,
              ),
              textInputField(
                fieldName = "description",
                labelText = "Description",
                value = values.getOrElse("description", ""),
                placeholder = "One-line purpose",
              ),
              textInputField(
                fieldName = "tags",
                labelText = "Tags (comma separated)",
                value = values.getOrElse("tags", ""),
                placeholder = "analysis,refactor,billing",
              ),
            ),
            div(cls := "mt-4")(
              label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := "systemPrompt")("System Prompt"),
              textarea(
                id          := "systemPrompt",
                name        := "systemPrompt",
                rows        := 14,
                cls         := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
                placeholder := "You are a specialized migration agent. Follow these rules...",
                required,
              )(values.getOrElse("systemPrompt", "")),
            ),
          ),
          div(cls := "flex items-center gap-3")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
            )(
              if editingName.isDefined then "Save Changes" else "Create Agent"
            ),
            a(href := "/agents", cls := "text-sm font-medium text-slate-300 hover:text-white")("Cancel"),
          ),
        ),
      )
    )

  private def textInputField(
    fieldName: String,
    labelText: String,
    value: String,
    placeholder: String,
    readOnly: Boolean = false,
    required: Boolean = false,
  ): Frag =
    div(
      label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := fieldName)(labelText),
      input(
        `type`                   := "text",
        id                       := fieldName,
        name                     := fieldName,
        scalatags.Text.all.value := value,
        attr("placeholder")      := placeholder,
        cls                      := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
        if readOnly then readonly := "readonly" else (),
        if required then scalatags.Text.all.required else (),
      ),
    )

  def agentConfigPage(
    agent: AgentInfo,
    overrideSettings: Map[String, String],
    globalSettings: Map[String, String],
    flash: Option[String],
  ): String =
    Layout.page(s"${agent.displayName} Config", "/agents")(
      div(cls := "max-w-3xl space-y-6")(
        div(
          a(
            href := "/agents",
            cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
          )("Back to agents")
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")(s"${agent.displayName} Configuration"),
          p(cls := "mt-1 text-sm text-slate-300")(agent.description),
        ),
        flash.map { msg =>
          div(cls := "rounded-md border border-emerald-500/30 bg-emerald-500/10 p-4")(
            p(cls := "text-sm text-emerald-300")(msg)
          )
        },
        tag("form")(method := "post", action := s"/agents/${agent.name}/config", cls := "space-y-6")(
          aiSection(agent, overrideSettings, globalSettings),
          div(cls := "flex items-center gap-3")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
            )("Save Overrides"),
            span(cls := "text-xs text-slate-400")("Leave fields empty to use global defaults"),
          ),
        ),
        tag("form")(method := "post", action := s"/agents/${agent.name}/config/reset")(
          button(
            `type` := "submit",
            cls    := "rounded-md border border-rose-400/30 bg-rose-500/10 px-4 py-2 text-sm font-semibold text-rose-200 hover:bg-rose-500/20",
          )("Reset to Global Defaults")
        ),
      )
    )

  private def aiSection(agent: AgentInfo, overrideSettings: Map[String, String], globalSettings: Map[String, String])
    : Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-6")(
      h2(cls := "text-lg font-semibold text-white")("AI Provider Overrides"),
      p(cls := "mt-1 text-xs text-slate-400")(
        s"Overrides apply only to ${agent.displayName}. Global defaults are shown as placeholders."
      ),
      div(cls := "mt-5 space-y-4")(
        providerField(overrideSettings.get("ai.provider"), globalSettings.get("ai.provider")),
        textField("ai.model", "Model", overrideSettings, globalSettings),
        textField("ai.baseUrl", "Base URL", overrideSettings, globalSettings),
        passwordField("ai.apiKey", "API Key", overrideSettings, globalSettings),
        numberGrid(
          numberField("ai.timeout", "Timeout (seconds)", overrideSettings, globalSettings, step = "1"),
          numberField("ai.maxRetries", "Max Retries", overrideSettings, globalSettings, step = "1"),
        ),
        numberGrid(
          numberField("ai.requestsPerMinute", "Requests/min", overrideSettings, globalSettings, step = "1"),
          numberField("ai.burstSize", "Burst Size", overrideSettings, globalSettings, step = "1"),
        ),
        numberField("ai.acquireTimeout", "Acquire Timeout (seconds)", overrideSettings, globalSettings, step = "1"),
        numberGrid(
          numberField("ai.temperature", "Temperature", overrideSettings, globalSettings, step = "0.1"),
          numberField("ai.maxTokens", "Max Tokens", overrideSettings, globalSettings, step = "1"),
        ),
        textField("ai.fallbackChain", "Fallback Chain", overrideSettings, globalSettings),
      ),
    )

  private def providerField(selectedValue: Option[String], global: Option[String]): Frag =
    val currentProvider = selectedValue.filter(_.nonEmpty)
    val globalLabel     = global.filter(_.nonEmpty).map(v => s"Use global ($v)").getOrElse("Use global")
    div(
      label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := "ai.provider")("Provider"),
      select(
        name := "ai.provider",
        id   := "ai.provider",
        cls  := "block w-full rounded-md border-0 bg-white/5 px-3 py-1.5 text-white ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-indigo-500 sm:text-sm/6",
      )(
        option(
          value := "",
          if currentProvider.isEmpty then selected := "selected" else (),
        )(globalLabel),
        providerOption("GeminiCli", "Gemini CLI", currentProvider),
        providerOption("GeminiApi", "Gemini API", currentProvider),
        providerOption("OpenAi", "OpenAI", currentProvider),
        providerOption("Anthropic", "Anthropic", currentProvider),
        providerOption("LmStudio", "LM Studio (Local)", currentProvider),
        providerOption("Ollama", "Ollama (Local)", currentProvider),
      ),
    )

  private def providerOption(optionValue: String, labelText: String, current: Option[String]): Frag =
    option(
      scalatags.Text.all.value := optionValue,
      if current.contains(optionValue) then selected := "selected" else (),
    )(labelText)

  private def textField(
    key: String,
    labelText: String,
    values: Map[String, String],
    global: Map[String, String],
  ): Frag =
    inputField("text", key, labelText, values, global, "w-full")

  private def passwordField(
    key: String,
    labelText: String,
    values: Map[String, String],
    global: Map[String, String],
  ): Frag =
    inputField("password", key, labelText, values, global, "w-full")

  private def numberField(
    key: String,
    labelText: String,
    values: Map[String, String],
    global: Map[String, String],
    step: String,
  ): Frag =
    inputField("number", key, labelText, values, global, "w-full", step = Some(step))

  private def numberGrid(left: Frag, right: Frag): Frag =
    div(cls := "grid grid-cols-1 gap-4 md:grid-cols-2")(left, right)

  private def inputField(
    inputType: String,
    key: String,
    labelText: String,
    values: Map[String, String],
    global: Map[String, String],
    widthCls: String,
    step: Option[String] = None,
  ): Frag =
    div(
      label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := key)(labelText),
      input(
        `type`              := inputType,
        name                := key,
        id                  := key,
        value               := values.getOrElse(key, ""),
        attr("placeholder") := global.getOrElse(key, ""),
        cls                 := s"$widthCls rounded-md border-0 bg-white/5 px-3 py-1.5 text-white ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-indigo-500 sm:text-sm/6",
        step.map(s => attr("step") := s),
      ),
    )

  private def effectiveHandle(agent: AgentInfo): String =
    Option(agent.handle).map(_.trim).filter(_.nonEmpty).getOrElse(agent.name)

  private def typeBadge(agentType: AgentType): Frag =
    agentType match
      case AgentType.BuiltIn => Components.badge("Built-in", "info")
      case AgentType.Custom  => Components.badge("Custom", "purple")

  private def aiBadge(usesAI: Boolean): Frag =
    if usesAI then Components.badge("Uses AI", "success")
    else Components.badge("No AI", "gray")

  private def tagBadge(tag: String): Frag =
    val variants = Vector("error", "amber", "success", "info", "indigo", "purple")
    val variant  = variants(math.abs(tag.toLowerCase.hashCode) % variants.size)
    Components.badge(tag, variant)

  private def formatSeconds(seconds: Long): String =
    if seconds <= 0 then "0s"
    else if seconds < 60 then s"${seconds}s"
    else
      val minutes = seconds / 60
      val rem     = seconds % 60
      s"${minutes}m ${rem}s"

  private def textField(nameValue: String, labelText: String, valueText: String): Frag =
    div(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200", `for` := nameValue)(labelText),
      input(
        `type` := "text",
        id     := nameValue,
        name   := nameValue,
        value  := valueText,
        cls    := "w-full rounded-md border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100",
      ),
    )

  private def textAreaField(nameValue: String, labelText: String, valueText: String): Frag =
    div(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200", `for` := nameValue)(labelText),
      textarea(
        id   := nameValue,
        name := nameValue,
        rows := 5,
        cls  := "w-full rounded-md border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100",
      )(valueText),
    )

  private def capabilityEditor(fieldName: String, labelText: String, valueText: String): Frag =
    div(
      label(cls := "mb-1 block text-sm font-semibold text-slate-200", `for` := s"${fieldName}EditorInput")(labelText),
      div(
        cls                            := "rounded-md border border-white/15 bg-slate-800/80 px-3 py-2",
        attr("data-capability-editor") := "true",
      )(
        input(`type`                    := "hidden", id                                               := fieldName, name := fieldName, value := valueText),
        div(cls                         := "mb-2 flex flex-wrap gap-2", attr("data-capability-chips") := "true"),
        input(
          `type`                        := "text",
          id                            := s"${fieldName}EditorInput",
          cls                           := "w-full rounded border border-white/10 bg-slate-900/80 px-2 py-1.5 text-sm text-slate-100",
          attr("data-capability-input") := "true",
          attr("placeholder")           := "Type capability and press Enter",
        ),
      ),
    )
