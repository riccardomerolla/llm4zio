package web.views

import models.{ AgentInfo, AgentType }
import scalatags.Text.all.*

object AgentsView:

  def list(agents: List[AgentInfo], flash: Option[String] = None): String =
    Layout.page("Agents", "/agents")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")("Agents"),
              p(cls := "mt-1 text-sm text-slate-300")(
                "Built-in and custom migration agents"
              ),
            ),
            a(
              href := "/agents/new",
              cls  := "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-500/30",
            )("Create Custom Agent"),
          )
        ),
        flash.map { msg =>
          div(cls := "rounded-md border border-emerald-500/30 bg-emerald-500/10 p-4")(
            p(cls := "text-sm text-emerald-300")(msg)
          )
        },
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
        div(cls := "flex flex-col items-end gap-2")(
          typeBadge(agent.agentType),
          aiBadge(agent.usesAI),
        ),
      ),
      div(cls := "mt-4 flex flex-wrap gap-2")(
        agent.tags.map(tagBadge)
      ),
      div(cls := "mt-4 flex flex-wrap gap-2")(
        if agent.usesAI then
          a(
            href := s"/agents/${agent.name}/config",
            cls  := "inline-flex rounded-md border border-indigo-400/30 bg-indigo-500/20 px-3 py-1.5 text-sm font-semibold text-indigo-200 hover:bg-indigo-500/30",
          )("Configure AI")
        else (),
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
    )

  private def typeBadge(agentType: AgentType): Frag =
    val badgeCls = agentType match
      case AgentType.BuiltIn =>
        "rounded-full border border-sky-400/30 bg-sky-500/20 px-2 py-0.5 text-xs font-semibold text-sky-200"
      case AgentType.Custom  =>
        "rounded-full border border-violet-400/30 bg-violet-500/20 px-2 py-0.5 text-xs font-semibold text-violet-200"
    span(cls := badgeCls)(
      agentType match
        case AgentType.BuiltIn => "Built-in"
        case AgentType.Custom  => "Custom"
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

  def newCustomAgentForm(
    values: Map[String, String] = Map.empty,
    flash: Option[String] = None,
  ): String =
    customAgentFormPage(
      title = "Create Custom Agent",
      formAction = "/agents",
      values = values,
      flash = flash,
      editingName = None,
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

  private def customAgentFormPage(
    title: String,
    formAction: String,
    values: Map[String, String],
    flash: Option[String],
    editingName: Option[String],
  ): String =
    Layout.page(title, "/agents")(
      div(cls := "mx-auto max-w-4xl space-y-5")(
        a(href := "/agents", cls := "text-sm font-medium text-indigo-300 hover:text-indigo-200")("← Back to Agents"),
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
          )("← Back to Agents")
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
