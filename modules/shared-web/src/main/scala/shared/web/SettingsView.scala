package shared.web

import zio.json.*

import gateway.boundary.{ ChannelCardData, ChannelView }
import issues.entity.api.IssueTemplate
import llm4zio.tools.{ Tool, ToolSandbox }
import scalatags.Text.all.*

object SettingsView:

  private val inputCls =
    "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3"

  private val selectCls =
    "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3"

  private val labelCls = "block text-sm font-medium text-white mb-2"

  private val sectionCls = "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6"

  def settingsShell(activeTab: String, pageTitle: String)(bodyContent: Frag*): String =
    SettingsShell.page(activeTab, pageTitle)(bodyContent*)

  def connectorsTab(
    settings: Map[String, String],
    flash: Option[String] = None,
    errors: Map[String, String] = Map.empty,
  ): String =
    settingsShell("connectors", "Settings — Connectors")(
      flash.map { msg =>
        div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      if errors.nonEmpty then
        div(cls := "mb-6 rounded-md bg-red-500/10 border border-red-500/30 p-4")(
          p(cls := "text-sm font-semibold text-red-400")("Validation Errors"),
          ul(cls := "text-xs text-red-300 mt-2 space-y-1")(
            errors.map { case (key, msg) => li(s"$key: $msg") }.toSeq*
          ),
        )
      else (),
      defaultConnectorsSection(settings, errors),
      envVarsScript,
    )

  // ---------------------------------------------------------------------------
  // Default connectors section — two side-by-side cards
  // ---------------------------------------------------------------------------

  private def defaultConnectorsSection(s: Map[String, String], errors: Map[String, String]): Frag =
    div(cls := "grid grid-cols-1 lg:grid-cols-2 gap-6 mb-8")(
      apiDefaultCard(s, errors),
      cliDefaultCard(s, errors),
    )

  def apiDefaultCard(s: Map[String, String], errors: Map[String, String] = Map.empty): Frag =
    div(id := "api-card", cls := sectionCls)(
      tag("form")(
        attr("hx-post")   := "/settings/connectors/api",
        attr("hx-target") := "#api-card",
        attr("hx-swap")   := "outerHTML",
        cls               := "space-y-4",
      )(
        h2(cls := "text-lg font-semibold text-white mb-4")("API Default"),
        div(
          label(cls := labelCls, `for` := "connector.default.api.provider")("Provider"),
          tag("select")(
            name := "connector.default.api.provider",
            id   := "connector.default.api.provider",
            cls  := selectCls,
          )(
            connectorOption("gemini-api", "Gemini API", s.get("connector.default.api.provider")),
            connectorOption("openai", "OpenAI", s.get("connector.default.api.provider")),
            connectorOption("anthropic", "Anthropic", s.get("connector.default.api.provider")),
            connectorOption("lm-studio", "LM Studio (Local)", s.get("connector.default.api.provider")),
            connectorOption("ollama", "Ollama (Local)", s.get("connector.default.api.provider")),
          ),
          showError(errors.get("connector.default.api.provider")),
        ),
        textField(
          "connector.default.api.model",
          "Model",
          s,
          placeholder = "gemini-2.5-flash",
          error = errors.get("connector.default.api.model"),
        ),
        textField(
          "connector.default.api.baseUrl",
          "Base URL",
          s,
          placeholder = "Optional: http://localhost:1234",
          error = errors.get("connector.default.api.baseUrl"),
        ),
        div(
          label(cls := labelCls, `for` := "connector.default.api.apiKey")("API Key"),
          input(
            `type`      := "password",
            name        := "connector.default.api.apiKey",
            id          := "connector.default.api.apiKey",
            value       := s.getOrElse("connector.default.api.apiKey", ""),
            placeholder := "Enter API key (optional)",
            cls         := inputCls,
          ),
          showError(errors.get("connector.default.api.apiKey")),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "connector.default.api.timeout",
            "Timeout (s)",
            s,
            default = "300",
            min = "10",
            max = "900",
            error = errors.get("connector.default.api.timeout"),
          ),
          numberField(
            "connector.default.api.maxRetries",
            "Max Retries",
            s,
            default = "3",
            min = "0",
            max = "10",
            error = errors.get("connector.default.api.maxRetries"),
          ),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "connector.default.api.requestsPerMinute",
            "Requests/min",
            s,
            default = "60",
            min = "1",
            max = "600",
            error = errors.get("connector.default.api.requestsPerMinute"),
          ),
          numberField(
            "connector.default.api.burstSize",
            "Burst Size",
            s,
            default = "10",
            min = "1",
            max = "100",
            error = errors.get("connector.default.api.burstSize"),
          ),
        ),
        numberField(
          "connector.default.api.acquireTimeout",
          "Acquire Timeout (s)",
          s,
          default = "30",
          min = "1",
          max = "300",
          error = errors.get("connector.default.api.acquireTimeout"),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "connector.default.api.temperature",
            "Temperature",
            s,
            default = "",
            min = "0",
            max = "2",
            step = "0.1",
            placeholder = "Optional (0.0 - 2.0)",
            error = errors.get("connector.default.api.temperature"),
          ),
          numberField(
            "connector.default.api.maxTokens",
            "Max Tokens",
            s,
            default = "",
            min = "1",
            max = "1048576",
            placeholder = "Optional",
            error = errors.get("connector.default.api.maxTokens"),
          ),
        ),
        textField(
          "connector.default.api.fallbackChain",
          "Fallback Chain",
          s,
          placeholder = "openai:gpt-4o-mini, anthropic:claude-3-5-haiku-latest",
          error = errors.get("connector.default.api.fallbackChain"),
        ),
        p(cls := "text-xs text-gray-400 -mt-2")(
          "Comma-separated fallback models. Use connector:model format."
        ),
        div(cls := "flex gap-3 pt-4 border-t border-white/10")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
          )("Save"),
          button(
            `type`             := "button",
            cls                := "rounded-md bg-emerald-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-emerald-500",
            attr("hx-post")    := "/api/settings/test-ai",
            attr("hx-include") := "[name^='connector.default.api.']",
            attr("hx-target")  := "#api-test-result",
            attr("hx-swap")    := "innerHTML",
          )("Test Connection"),
        ),
        div(id := "api-test-result", cls := "mt-3")(),
      )
    )

  def cliDefaultCard(s: Map[String, String], errors: Map[String, String] = Map.empty): Frag =
    div(id := "cli-card", cls := sectionCls)(
      tag("form")(
        attr("hx-post")   := "/settings/connectors/cli",
        attr("hx-target") := "#cli-card",
        attr("hx-swap")   := "outerHTML",
        cls               := "space-y-4",
      )(
        h2(cls := "text-lg font-semibold text-white mb-4")("CLI Default"),
        div(
          label(cls := labelCls, `for` := "connector.default.cli.connector")("Connector"),
          tag("select")(
            name := "connector.default.cli.connector",
            id   := "connector.default.cli.connector",
            cls  := selectCls,
          )(
            connectorOption("claude-cli", "Claude CLI", s.get("connector.default.cli.connector")),
            connectorOption("gemini-cli", "Gemini CLI", s.get("connector.default.cli.connector")),
            connectorOption("opencode", "OpenCode", s.get("connector.default.cli.connector")),
            connectorOption("codex", "Codex", s.get("connector.default.cli.connector")),
            connectorOption("copilot", "Copilot", s.get("connector.default.cli.connector")),
          ),
          showError(errors.get("connector.default.cli.connector")),
        ),
        textField(
          "connector.default.cli.model",
          "Model",
          s,
          placeholder = "Optional model override",
          error = errors.get("connector.default.cli.model"),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "connector.default.cli.timeout",
            "Timeout (s)",
            s,
            default = "300",
            min = "10",
            max = "900",
            error = errors.get("connector.default.cli.timeout"),
          ),
          numberField(
            "connector.default.cli.maxRetries",
            "Max Retries",
            s,
            default = "3",
            min = "0",
            max = "10",
            error = errors.get("connector.default.cli.maxRetries"),
          ),
        ),
        numberField(
          "connector.default.cli.turnLimit",
          "Turn Limit",
          s,
          default = "25",
          min = "1",
          max = "200",
          error = errors.get("connector.default.cli.turnLimit"),
        ),
        div(
          label(cls := labelCls, `for` := "connector.default.cli.sandbox")("Sandbox"),
          tag("select")(
            name := "connector.default.cli.sandbox",
            id   := "connector.default.cli.sandbox",
            cls  := selectCls,
          )(
            connectorOption("none", "None", s.get("connector.default.cli.sandbox")),
            connectorOption("docker", "Docker", s.get("connector.default.cli.sandbox")),
            connectorOption("podman", "Podman", s.get("connector.default.cli.sandbox")),
            connectorOption("seatbeltmacos", "Seatbelt (macOS)", s.get("connector.default.cli.sandbox")),
            connectorOption("runsc", "gVisor (runsc)", s.get("connector.default.cli.sandbox")),
            connectorOption("lxc", "LXC", s.get("connector.default.cli.sandbox")),
          ),
          showError(errors.get("connector.default.cli.sandbox")),
        ),
        div(
          label(cls := labelCls, `for` := "connector.default.cli.flags")("Flags"),
          textarea(
            name                := "connector.default.cli.flags",
            id                  := "connector.default.cli.flags",
            rows                := 3,
            cls                 := "block w-full rounded-md bg-white/5 border-0 py-2 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
            attr("placeholder") := "Additional CLI flags, one per line",
          )(s.getOrElse("connector.default.cli.flags", "")),
          showError(errors.get("connector.default.cli.flags")),
        ),
        envVarsEditor("connector.default.cli.envVars", s.getOrElse("connector.default.cli.envVars", "")),
        div(cls := "flex gap-3 pt-4 border-t border-white/10")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
          )("Save")
        ),
      )
    )

  // ---------------------------------------------------------------------------
  // Connector option helper
  // ---------------------------------------------------------------------------

  private def connectorOption(value: String, labelText: String, current: Option[String]): Frag =
    val isSelected = current.contains(value)
    tag("option")(
      attr("value") := value,
      if isSelected then attr("selected") := "selected" else (),
    )(labelText)

  // ---------------------------------------------------------------------------
  // Env vars editor
  // ---------------------------------------------------------------------------

  private def envVarsEditor(fieldPrefix: String, rawEnvVars: String): Frag =
    val pairs = rawEnvVars.split(",").toList.map(_.trim).filter(_.contains("=")).zipWithIndex.map {
      case (kv, idx) =>
        val parts = kv.split("=", 2)
        (idx, parts(0).trim, if parts.length > 1 then parts(1).trim else "")
    }
    div(cls := "space-y-2")(
      label(cls := labelCls)("Environment Variables"),
      div(id := s"env-vars-$fieldPrefix", cls := "space-y-2")(
        if pairs.nonEmpty then
          pairs.map { case (idx, k, v) => envVarRow(fieldPrefix, idx, k, v) }
        else
          Seq(envVarRow(fieldPrefix, 0, "", ""))
      ),
      button(
        `type`          := "button",
        cls             := "mt-2 rounded-md bg-white/10 px-3 py-1.5 text-xs font-semibold text-gray-300 ring-1 ring-white/10 hover:bg-white/20",
        attr("onclick") := s"addEnvVarRow('$fieldPrefix')",
      )("+ Add Variable"),
    )

  private def envVarRow(fieldPrefix: String, index: Int, key: String, value: String): Frag =
    div(cls := "flex items-center gap-2", attr("data-env-row") := "")(
      input(
        `type`              := "text",
        name                := s"${fieldPrefix}.key.$index",
        attr("placeholder") := "KEY",
        cls                 := "flex-1 " + inputCls,
        attr("value")       := key,
      ),
      span(cls := "text-gray-400")("="),
      input(
        `type`              := "text",
        name                := s"${fieldPrefix}.value.$index",
        attr("placeholder") := "value",
        cls                 := "flex-1 " + inputCls,
        attr("value")       := value,
      ),
      button(
        `type`          := "button",
        cls             := "text-red-400 hover:text-red-300 text-sm px-2",
        attr("onclick") := "this.parentElement.remove()",
      )("x"),
    )

  // ---------------------------------------------------------------------------
  // Env vars inline JS
  // ---------------------------------------------------------------------------

  private def envVarsScript: Frag =
    tag("script")(raw(
      """
      |function addEnvVarRow(fieldPrefix) {
      |  var container = document.getElementById('env-vars-' + fieldPrefix);
      |  if (!container) return;
      |  var rows = container.querySelectorAll('[data-env-row]');
      |  var idx = rows.length;
      |  var row = document.createElement('div');
      |  row.className = 'flex items-center gap-2';
      |  row.setAttribute('data-env-row', '');
      |  var keyInput = document.createElement('input');
      |  keyInput.type = 'text';
      |  keyInput.name = fieldPrefix + '.key.' + idx;
      |  keyInput.placeholder = 'KEY';
      |  keyInput.className = 'flex-1 block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3';
      |  var eq = document.createElement('span');
      |  eq.className = 'text-gray-400';
      |  eq.textContent = '=';
      |  var valInput = document.createElement('input');
      |  valInput.type = 'text';
      |  valInput.name = fieldPrefix + '.value.' + idx;
      |  valInput.placeholder = 'value';
      |  valInput.className = 'flex-1 block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3';
      |  var removeBtn = document.createElement('button');
      |  removeBtn.type = 'button';
      |  removeBtn.className = 'text-red-400 hover:text-red-300 text-sm px-2';
      |  removeBtn.textContent = 'x';
      |  removeBtn.addEventListener('click', function() { row.remove(); });
      |  row.appendChild(keyInput);
      |  row.appendChild(eq);
      |  row.appendChild(valInput);
      |  row.appendChild(removeBtn);
      |  container.appendChild(row);
      |}
      """.stripMargin
    ))

  // ---------------------------------------------------------------------------
  // Other tabs (unchanged)
  // ---------------------------------------------------------------------------

  def channelsTab(
    cards: List[ChannelCardData],
    nowMs: Long,
    flash: Option[String] = None,
  ): String =
    settingsShell("channels", "Settings — Channels")(
      flash.map { msg =>
        div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      div(cls := "mb-5 rounded-lg border border-indigo-400/20 bg-indigo-500/10 p-4 text-sm text-indigo-100")(
        p("Telegram settings are managed only in "),
        a(
          href := "/settings/gateway",
          cls  := "font-semibold underline decoration-indigo-300/50 hover:text-white",
        )("/settings/gateway"),
        p(cls := "mt-2 text-indigo-200")(
          "For Discord: create a bot in the Discord Developer Portal, enable MESSAGE CONTENT intent, invite the bot to your server, then save Bot Token and optional Guild/Channel IDs below."
        ),
      ),
      div(cls := "flex items-center justify-between mb-4")(
        p(cls := "text-sm text-gray-400")("Live channel status and configuration. Auto-refresh every 10 seconds."),
        a(
          href := "/api/channels/status",
          cls  := "inline-flex items-center rounded-md bg-white/5 px-3 py-1.5 text-xs font-semibold text-gray-300 ring-1 ring-white/10 hover:bg-white/10",
        )("Status API \u2197"),
      ),
      div(cls := "mb-6 rounded-lg bg-white/5 ring-1 ring-white/10 p-4")(
        p(cls := "text-xs font-semibold uppercase tracking-wide text-gray-400 mb-3")("Add Channel"),
        tag("form")(
          cls                 := "flex items-center gap-2",
          attr("hx-post")     := "/api/channels/add",
          attr("hx-target")   := "#channels-cards",
          attr("hx-swap")     := "innerHTML",
          attr("hx-encoding") := "application/x-www-form-urlencoded",
        )(
          select(name := "name", cls := "rounded-md bg-gray-900 px-3 py-2 text-sm text-white ring-1 ring-white/10")(
            option(value := "discord")("Discord"),
            option(value := "slack")("Slack"),
            option(value := "websocket")("WebSocket"),
          ),
          input(
            `type`              := "password",
            name                := "botToken",
            attr("placeholder") := "Bot / App token",
            cls                 := "flex-1 rounded-md bg-gray-900 px-3 py-2 text-sm text-white ring-1 ring-white/10",
          ),
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-600 px-3 py-2 text-xs font-semibold text-white hover:bg-indigo-500",
          )("Add"),
        ),
      ),
      div(
        id                   := "channels-cards",
        attr("hx-get")       := "/settings/channels/cards",
        attr("hx-trigger")   := "every 10s",
        attr("hx-swap")      := "innerHTML",
        attr("hx-indicator") := "#channels-refresh-indicator",
      )(ChannelView.cardsFragment(cards, nowMs)),
      div(id := "channels-refresh-indicator", cls := "htmx-indicator text-xs text-gray-500 mt-3")("Refreshing..."),
    )

  def gatewayTab(
    settings: Map[String, String],
    flash: Option[String] = None,
    errors: Map[String, String] = Map.empty,
  ): String =
    settingsShell("gateway", "Settings — Gateway")(
      flash.map { msg =>
        div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      if errors.nonEmpty then
        div(cls := "mb-6 rounded-md bg-red-500/10 border border-red-500/30 p-4")(
          p(cls := "text-sm font-semibold text-red-400")("Validation Errors"),
          ul(cls := "text-xs text-red-300 mt-2 space-y-1")(
            errors.map { case (key, msg) => li(s"$key: $msg") }.toSeq*
          ),
        )
      else (),
      tag("form")(method := "post", action := "/settings/gateway", cls := "space-y-6 max-w-2xl")(
        gatewaySection(settings, errors),
        telegramSection(settings, errors),
        memorySection(settings, errors),
        div(cls := "flex gap-4 pt-2")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
          )("Save Gateway Settings")
        ),
      ),
      div(cls := "mt-10 rounded-lg border border-red-500/30 bg-red-950/30 p-5 max-w-2xl")(
        h3(cls := "text-lg font-semibold text-red-200")("Reset Operational Data"),
        p(cls := "mt-2 text-sm text-red-100/90")(
          "Deletes all tasks, conversations, activity logs, and memory. Configuration is preserved."
        ),
        button(
          `type`             := "button",
          cls                := "mt-4 rounded-md border border-red-400/40 bg-red-500/20 px-4 py-2 text-sm font-semibold text-red-100 hover:bg-red-500/30",
          attr("hx-post")    := "/api/store/reset-data",
          attr("hx-confirm") := "This will permanently delete all tasks and conversations. Are you sure?",
          attr("hx-swap")    := "none",
        )("Reset Data Store"),
      ),
      tag("script")(
        raw("""
          |document.addEventListener('DOMContentLoaded', function() {
          |  const modeSelect = document.getElementById('telegram.mode');
          |  const webhookGroup = document.getElementById('telegram-webhook-group');
          |  const pollingGroup = document.getElementById('telegram-polling-group');
          |
          |  function updateFieldVisibility() {
          |    const mode = modeSelect.value;
          |    if (mode === 'Webhook') {
          |      webhookGroup.style.display = 'block';
          |      pollingGroup.style.display = 'none';
          |    } else if (mode === 'Polling') {
          |      webhookGroup.style.display = 'none';
          |      pollingGroup.style.display = 'block';
          |    }
          |  }
          |
          |  if (modeSelect) {
          |    updateFieldVisibility();
          |    modeSelect.addEventListener('change', updateFieldVisibility);
          |  }
          |});
        """.stripMargin)
      ),
    )

  def issueTemplatesTab(
    templates: List[IssueTemplate],
    flash: Option[String] = None,
  ): String =
    val builtin = templates.filter(_.isBuiltin).sortBy(_.name.toLowerCase)
    val custom  = templates.filterNot(_.isBuiltin).sortBy(_.name.toLowerCase)
    settingsShell("issues-templates", "Settings — Issue Templates")(
      flash.map { msg =>
        div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      div(cls := "mb-6 rounded-lg border border-white/10 bg-slate-900/70 p-5")(
        h2(cls := "text-lg font-semibold text-white")("Issue Templates"),
        p(cls := "mt-2 text-sm text-slate-300")(
          "Create reusable issue scaffolds with variables. Built-in templates are read-only; custom templates are editable."
        ),
      ),
      div(cls := "grid grid-cols-1 gap-4 lg:grid-cols-2")(
        div(cls := "rounded-lg border border-white/10 bg-slate-900/60 p-4")(
          h3(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Built-in Templates"),
          ul(cls := "mt-3 space-y-2 text-sm text-slate-300")(
            if builtin.isEmpty then li("No built-in templates available.")
            else
              builtin.map(t =>
                li(cls := "rounded border border-white/10 bg-slate-800/70 p-2")(
                  p(cls := "font-medium text-slate-100")(t.name),
                  p(cls := "mt-1 text-xs text-slate-400")(t.description),
                )
              )
          ),
        ),
        div(cls := "rounded-lg border border-white/10 bg-slate-900/60 p-4")(
          h3(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Custom Templates"),
          p(
            cls := "mt-2 text-xs text-slate-400"
          )("Use the editor below to create, update, or delete custom templates."),
          div(id := "issue-template-manager", cls := "mt-3", attr("data-api-base") := "/api/issue-templates")(
            div(id := "issue-template-manager-list", cls     := "space-y-2"),
            div(id := "issue-template-manager-feedback", cls := "mt-3 text-sm text-slate-300"),
          ),
        ),
      ),
      div(cls := "mt-4 rounded-lg border border-white/10 bg-slate-900/60 p-4")(
        h3(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Template Editor"),
        form(id := "issue-template-manager-form", cls := "mt-3 space-y-3")(
          input(`type` := "hidden", id := "issue-template-id", name       := "id"),
          input(`type` := "hidden", id := "issue-template-is-edit", value := "false"),
          div(cls := "grid grid-cols-1 gap-3 md:grid-cols-2")(
            textInput("issue-template-name", "Name", "Bug Fix (Custom)"),
            textInput("issue-template-issue-type", "Issue Type", "task"),
            textInput("issue-template-tags", "Tags (comma separated)", "bug,triage"),
            selectInput(
              inputId = "issue-template-priority",
              labelText = "Priority",
              values = List("Low", "Medium", "High", "Critical"),
            ),
          ),
          textInput("issue-template-description", "Description", "Template description"),
          textAreaInput("issue-template-title-template", "Title Template", "Fix {{component}} bug in {{area}}", 3),
          textAreaInput(
            "issue-template-description-template",
            "Description Template",
            "# Goal\nFix {{component}} in {{area}}.\n\n## Acceptance Criteria\n- [ ] Add regression tests",
            8,
          ),
          textAreaInput(
            "issue-template-variables",
            "Variables JSON",
            """[
              |  {"name":"component","label":"Component","required":true},
              |  {"name":"area","label":"Area","required":true}
              |]""".stripMargin,
            8,
          ),
          div(cls := "flex items-center gap-3 pt-2")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
            )("Save Template"),
            button(
              `type` := "button",
              id     := "issue-template-reset",
              cls    := "rounded-md border border-white/20 px-4 py-2 text-sm font-semibold text-slate-200 hover:bg-white/10",
            )("Reset"),
          ),
        ),
      ),
      script(id := "issue-template-manager-data", `type` := "application/json")(raw(custom.toJson)),
      JsResources.inlineModuleScript("/static/client/components/issue-templates-manager.js"),
    )

  private def textInput(inputId: String, labelText: String, placeholderText: String): Frag =
    div(
      label(cls := labelCls, `for` := inputId)(labelText),
      input(
        id                  := inputId,
        `type`              := "text",
        cls                 := inputCls,
        attr("placeholder") := placeholderText,
      ),
    )

  private def textAreaInput(inputId: String, labelText: String, placeholderText: String, rowsCount: Int): Frag =
    div(
      label(cls := labelCls, `for` := inputId)(labelText),
      textarea(
        id                  := inputId,
        rows                := rowsCount,
        cls                 := "block w-full rounded-md bg-white/5 border-0 py-2 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
        attr("placeholder") := placeholderText,
      ),
    )

  private def selectInput(inputId: String, labelText: String, values: List[String]): Frag =
    div(
      label(cls := labelCls, `for` := inputId)(labelText),
      select(id := inputId, cls := selectCls)(
        values.map(v => option(value := v)(v))
      ),
    )

  def systemTab: String =
    settingsShell("system", "Settings — System")(
      div(cls := "mb-4")(
        p(cls := "text-sm text-gray-400")("Real-time gateway, agent, channel, and resource telemetry.")
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
        tag("ab-health-dashboard")(
          attr("ws-url") := "/ws/console"
        )()
      ),
      JsResources.inlineModuleScript("/static/client/components/ab-health-dashboard.js"),
    )

  def page(
    settings: Map[String, String],
    flash: Option[String] = None,
    errors: Map[String, String] = Map.empty,
  ): String =
    Layout.page("Settings", "/settings")(
      div(cls := "max-w-2xl")(
        h1(cls := "text-2xl font-bold text-white mb-6")("Settings"),
        flash.map { msg =>
          div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
            p(cls := "text-sm text-green-400")(msg)
          )
        },
        if errors.nonEmpty then
          div(cls := "mb-6 rounded-md bg-red-500/10 border border-red-500/30 p-4")(
            p(cls := "text-sm font-semibold text-red-400")("Validation Errors"),
            ul(cls := "text-xs text-red-300 mt-2 space-y-1")(
              errors.map { case (key, msg) => li(s"$key: $msg") }.toSeq*
            ),
          )
        else (),
        tag("form")(method := "post", action := "/settings", cls := "space-y-6")(
          aiProviderSection(settings, errors),
          gatewaySection(settings, errors),
          telegramSection(settings, errors),
          memorySection(settings, errors),
          div(cls := "flex gap-4 pt-2")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500",
            )("Save Settings")
          ),
        ),
        div(cls := "mt-8 rounded-lg border border-red-500/30 bg-red-950/30 p-5")(
          h3(cls := "text-lg font-semibold text-red-200")("Reset Operational Data"),
          p(cls := "mt-2 text-sm text-red-100/90")(
            "Deletes all tasks, conversations, activity logs, and memory. Configuration is preserved."
          ),
          button(
            `type`             := "button",
            cls                := "mt-4 rounded-md border border-red-400/40 bg-red-500/20 px-4 py-2 text-sm font-semibold text-red-100 hover:bg-red-500/30",
            attr("hx-post")    := "/api/store/reset-data",
            attr("hx-confirm") := "This will permanently delete all tasks and conversations. Are you sure?",
            attr("hx-swap")    := "none",
          )("Reset Data Store"),
        ),
        div(cls := "mt-8 pt-6 border-t border-white/10")(
          div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
            p(cls := "text-sm text-gray-300")(
              "\uD83D\uDCA1 For advanced configuration with validation, diff, history, and hot reload, use the ",
              a(href := "/config", cls := "text-indigo-400 hover:text-indigo-300 underline")("Config Editor"),
              ".",
            )
          )
        ),
      ),
      tag("script")(
        raw("""
          |document.addEventListener('DOMContentLoaded', function() {
          |  const modeSelect = document.getElementById('telegram.mode');
          |  const webhookGroup = document.getElementById('telegram-webhook-group');
          |  const pollingGroup = document.getElementById('telegram-polling-group');
          |
          |  function updateFieldVisibility() {
          |    const mode = modeSelect.value;
          |    if (mode === 'Webhook') {
          |      webhookGroup.style.display = 'block';
          |      pollingGroup.style.display = 'none';
          |    } else if (mode === 'Polling') {
          |      webhookGroup.style.display = 'none';
          |      pollingGroup.style.display = 'block';
          |    }
          |  }
          |
          |  if (modeSelect) {
          |    updateFieldVisibility();
          |    modeSelect.addEventListener('change', updateFieldVisibility);
          |  }
          |});
        """.stripMargin)
      ),
    )

  private def aiProviderSection(s: Map[String, String], errors: Map[String, String]): Frag =
    tag("section")(cls := sectionCls)(
      h2(cls := "text-lg font-semibold text-white mb-4")("AI Provider"),
      div(cls := "space-y-4")(
        div(
          label(cls := labelCls, `for` := "ai.provider")("Provider"),
          tag("select")(name := "ai.provider", id := "ai.provider", cls := selectCls)(
            providerOption("GeminiCli", "Gemini CLI", s.get("ai.provider")),
            providerOption("GeminiApi", "Gemini API", s.get("ai.provider")),
            providerOption("OpenAi", "OpenAI", s.get("ai.provider")),
            providerOption("Anthropic", "Anthropic", s.get("ai.provider")),
            providerOption("LmStudio", "LM Studio (Local)", s.get("ai.provider")),
            providerOption("Ollama", "Ollama (Local)", s.get("ai.provider")),
          ),
          p(cls := "text-xs text-gray-400 mt-1")(
            "LM Studio and Ollama run locally. Cloud providers require API keys."
          ),
          showError(errors.get("ai.provider")),
        ),
        textField(
          "ai.model",
          "Model",
          s,
          placeholder = "gemini-2.5-flash (or llama3 for local)",
          error = errors.get("ai.model"),
        ),
        textField(
          "ai.baseUrl",
          "Base URL",
          s,
          placeholder = "Optional: http://localhost:1234 (LM Studio), http://localhost:11434 (Ollama)",
          error = errors.get("ai.baseUrl"),
        ),
        div(
          label(cls := labelCls, `for` := "ai.apiKey")("API Key"),
          input(
            `type`      := "password",
            name        := "ai.apiKey",
            id          := "ai.apiKey",
            value       := s.getOrElse("ai.apiKey", ""),
            placeholder := "Enter API key (optional)",
            cls         := inputCls,
          ),
          showError(errors.get("ai.apiKey")),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "ai.timeout",
            "Timeout (seconds)",
            s,
            default = "300",
            min = "10",
            max = "900",
            error = errors.get("ai.timeout"),
          ),
          numberField(
            "ai.maxRetries",
            "Max Retries",
            s,
            default = "3",
            min = "0",
            max = "10",
            error = errors.get("ai.maxRetries"),
          ),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "ai.requestsPerMinute",
            "Requests/min",
            s,
            default = "60",
            min = "1",
            max = "600",
            error = errors.get("ai.requestsPerMinute"),
          ),
          numberField(
            "ai.burstSize",
            "Burst Size",
            s,
            default = "10",
            min = "1",
            max = "100",
            error = errors.get("ai.burstSize"),
          ),
        ),
        numberField(
          "ai.acquireTimeout",
          "Acquire Timeout (seconds)",
          s,
          default = "30",
          min = "1",
          max = "300",
          error = errors.get("ai.acquireTimeout"),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "ai.temperature",
            "Temperature",
            s,
            default = "",
            min = "0",
            max = "2",
            step = "0.1",
            placeholder = "Optional (0.0 - 2.0)",
            error = errors.get("ai.temperature"),
          ),
          numberField(
            "ai.maxTokens",
            "Max Tokens",
            s,
            default = "",
            min = "1",
            max = "1048576",
            placeholder = "Optional",
            error = errors.get("ai.maxTokens"),
          ),
        ),
        textField(
          "ai.fallbackChain",
          "Fallback Chain",
          s,
          placeholder = "OpenAi:gpt-4o-mini, Anthropic:claude-3-5-haiku-latest",
          error = errors.get("ai.fallbackChain"),
        ),
        p(cls := "text-xs text-gray-400 -mt-2")(
          "Comma-separated fallback models. Use provider:model or model (uses current provider)."
        ),
        div(cls := "flex gap-3 pt-4 border-t border-white/10")(
          button(
            `type`             := "button",
            cls                := "rounded-md bg-emerald-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-emerald-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed",
            attr("hx-post")    := "/api/settings/test-ai",
            attr("hx-include") := "[name^='ai.']",
            attr("hx-target")  := "#ai-test-result",
            attr("hx-swap")    := "innerHTML",
          )("Test Connection")
        ),
        div(id := "ai-test-result", cls := "mt-3")(),
      ),
    )

  private def gatewaySection(s: Map[String, String], errors: Map[String, String]): Frag =
    tag("section")(cls := sectionCls)(
      h2(cls := "text-lg font-semibold text-white mb-4")("Gateway"),
      div(cls := "space-y-4")(
        textField("gateway.name", "Gateway Name", s, placeholder = "My Gateway", error = errors.get("gateway.name"))
      ),
      div(cls := "space-y-3 mt-4")(
        checkboxField(
          "gateway.dryRun",
          "Dry Run Mode",
          s,
          default = false,
          error = errors.get("gateway.dryRun"),
        ),
        checkboxField(
          "gateway.verbose",
          "Verbose Logging",
          s,
          default = false,
          error = errors.get("gateway.verbose"),
        ),
      ),
    )

  private def telegramSection(s: Map[String, String], errors: Map[String, String]): Frag =
    tag("section")(cls := sectionCls)(
      h2(cls := "text-lg font-semibold text-white mb-4")("Telegram"),
      div(cls := "space-y-4")(
        checkboxField(
          "telegram.enabled",
          "Enable Telegram Bot",
          s,
          default = false,
          error = errors.get("telegram.enabled"),
        ),
        passwordField(
          "telegram.botToken",
          "Bot Token",
          s,
          placeholder = "Telegram bot token from @BotFather",
          error = errors.get("telegram.botToken"),
        ),
        div(
          label(cls := labelCls, `for` := "telegram.mode")("Mode"),
          tag("select")(name := "telegram.mode", id := "telegram.mode", cls := selectCls)(
            modeOption("Webhook", "Webhook", s.get("telegram.mode")),
            modeOption("Polling", "Polling", s.get("telegram.mode")),
          ),
          p(cls := "text-xs text-gray-400 mt-1")("Webhook: Push updates; Polling: Pull updates"),
          showError(errors.get("telegram.mode")),
        ),
      ),
      div(id := "telegram-webhook-group", cls := "space-y-4 mt-4 pt-4 border-t border-white/10")(
        p(cls := "text-sm font-medium text-gray-300")("Webhook Configuration"),
        textField(
          "telegram.webhookUrl",
          "Webhook URL",
          s,
          placeholder = "https://your-domain.com/telegram/webhook",
          error = errors.get("telegram.webhookUrl"),
        ),
        passwordField(
          "telegram.secretToken",
          "Secret Token",
          s,
          placeholder = "Optional: secret token for webhook validation",
          error = errors.get("telegram.secretToken"),
        ),
      ),
      div(id := "telegram-polling-group", cls := "space-y-4 mt-4 pt-4 border-t border-white/10")(
        p(cls := "text-sm font-medium text-gray-300")("Polling Configuration"),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "telegram.polling.interval",
            "Poll Interval (seconds)",
            s,
            default = "1",
            min = "1",
            max = "60",
            error = errors.get("telegram.polling.interval"),
          ),
          numberField(
            "telegram.polling.batchSize",
            "Batch Size",
            s,
            default = "100",
            min = "1",
            max = "1000",
            error = errors.get("telegram.polling.batchSize"),
          ),
        ),
        numberField(
          "telegram.polling.timeout",
          "Timeout (seconds)",
          s,
          default = "30",
          min = "1",
          max = "120",
          error = errors.get("telegram.polling.timeout"),
        ),
      ),
    )

  private def memorySection(s: Map[String, String], errors: Map[String, String]): Frag =
    tag("section")(cls := sectionCls)(
      h2(cls := "text-lg font-semibold text-white mb-4")("Memory"),
      div(cls := "space-y-4")(
        checkboxField(
          "memory.enabled",
          "Enable Memory",
          s,
          default = true,
          error = errors.get("memory.enabled"),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField(
            "memory.maxContextMemories",
            "Max Context Memories",
            s,
            default = "5",
            min = "1",
            max = "25",
            error = errors.get("memory.maxContextMemories"),
          ),
          numberField(
            "memory.summarizationThreshold",
            "Summarization Threshold",
            s,
            default = "20",
            min = "1",
            max = "200",
            error = errors.get("memory.summarizationThreshold"),
          ),
        ),
        numberField(
          "memory.retentionDays",
          "Retention Days",
          s,
          default = "90",
          min = "1",
          max = "3650",
          error = errors.get("memory.retentionDays"),
        ),
      ),
    )

  // ---------------------------------------------------------------------------
  // Field helpers
  // ---------------------------------------------------------------------------

  private def textField(
    fieldName: String,
    labelText: String,
    s: Map[String, String],
    placeholder: String,
    error: Option[String],
  ): Frag =
    div(
      label(cls := labelCls, `for` := fieldName)(labelText),
      input(
        `type`              := "text",
        name                := fieldName,
        id                  := fieldName,
        value               := s.getOrElse(fieldName, ""),
        attr("placeholder") := placeholder,
        cls                 := inputCls,
      ),
      showError(error),
    )

  private def numberField(
    fieldName: String,
    labelText: String,
    s: Map[String, String],
    default: String,
    min: String,
    max: String,
    step: String = "1",
    placeholder: String = "",
    error: Option[String],
  ): Frag =
    div(
      label(cls := labelCls, `for` := fieldName)(labelText),
      input(
        `type`              := "number",
        name                := fieldName,
        id                  := fieldName,
        value               := s.getOrElse(fieldName, default),
        attr("min")         := min,
        attr("max")         := max,
        attr("step")        := step,
        attr("placeholder") := (if placeholder.nonEmpty then placeholder else default),
        cls                 := inputCls,
      ),
      showError(error),
    )

  private def checkboxField(
    fieldName: String,
    labelText: String,
    s: Map[String, String],
    default: Boolean,
    error: Option[String],
  ): Frag =
    val checked = s.get(fieldName).map(_ == "true").getOrElse(default)
    div(
      div(cls := "flex items-center gap-3")(
        input(
          `type` := "checkbox",
          name   := fieldName,
          id     := fieldName,
          cls    := "h-4 w-4 rounded border-white/10 bg-white/5 text-indigo-600 focus:ring-indigo-600",
          if checked then attr("checked") := "checked" else (),
        ),
        label(cls := "text-sm text-gray-400", `for` := fieldName)(labelText),
      ),
      showError(error),
    )

  private def providerOption(value: String, labelText: String, current: Option[String]): Frag =
    val isSelected = current.contains(value) || (current.isEmpty && value == "GeminiCli")
    tag("option")(
      attr("value") := value,
      if isSelected then attr("selected") := "selected" else (),
    )(labelText)

  private def modeOption(value: String, labelText: String, current: Option[String]): Frag =
    val isSelected = current.contains(value) || (current.isEmpty && value == "Webhook")
    tag("option")(
      attr("value") := value,
      if isSelected then attr("selected") := "selected" else (),
    )(labelText)

  private def passwordField(
    fieldName: String,
    labelText: String,
    s: Map[String, String],
    placeholder: String,
    error: Option[String],
  ): Frag =
    div(
      label(cls := labelCls, `for` := fieldName)(labelText),
      input(
        `type`              := "password",
        name                := fieldName,
        id                  := fieldName,
        value               := s.getOrElse(fieldName, ""),
        attr("placeholder") := placeholder,
        cls                 := inputCls,
      ),
      showError(error),
    )

  private def showError(error: Option[String]): Frag =
    error.map { msg =>
      p(cls := "text-xs text-red-400 mt-1")(msg)
    }.getOrElse(())

  def testConnectionSuccess(model: String, latencyMs: Long): String =
    div(cls := "inline-flex items-center gap-2 rounded-full bg-emerald-500/20 border border-emerald-500/50 px-4 py-2")(
      span(cls := "text-emerald-400 text-sm font-medium")("\u2713 Connection successful"),
      span(cls := "text-emerald-300 text-xs")("("),
      span(cls := "text-emerald-300 text-xs font-mono")(model),
      span(cls := "text-emerald-300 text-xs")(s", ${latencyMs}ms)"),
    ).toString

  def testConnectionError(error: String): String =
    div(cls := "inline-flex items-center gap-2 rounded-full bg-red-500/20 border border-red-500/50 px-4 py-2")(
      span(cls := "text-red-400 text-sm font-medium")("\u2717 Connection failed"),
      span(cls := "text-red-300 text-xs")("\u2013"),
      span(cls := "text-red-300 text-xs")(error),
    ).toString

  def toolsFragment(tools: List[Tool]): String =
    if tools.isEmpty then
      div(cls := "text-sm text-gray-400 py-4")("No tools registered").render
    else
      div(cls := "space-y-3")(
        tools.map { tool =>
          div(cls := "rounded-lg border border-white/10 bg-slate-900/70 p-4")(
            div(cls := "flex items-start justify-between gap-3")(
              div(cls := "min-w-0")(
                span(cls := "font-mono text-sm font-semibold text-indigo-300")(tool.name),
                p(cls := "text-xs text-slate-300 mt-1")(tool.description),
              ),
              sandboxBadge(tool.sandbox),
            ),
            if tool.tags.nonEmpty then
              div(cls := "mt-2 flex flex-wrap gap-1")(
                tool.tags.toList.sorted.map { tagLabel =>
                  span(
                    cls := "inline-flex items-center rounded-md bg-white/5 ring-1 ring-white/10 px-2 py-0.5 text-[11px] text-gray-400"
                  )(
                    tagLabel
                  )
                }
              )
            else frag(),
          )
        }
      ).render

  private def sandboxBadge(sandbox: ToolSandbox): Frag =
    val (label, cls_) = sandbox match
      case ToolSandbox.WorkspaceReadOnly  =>
        ("WorkspaceReadOnly", "bg-emerald-500/10 text-emerald-300 ring-emerald-400/30")
      case ToolSandbox.WorkspaceReadWrite => ("WorkspaceReadWrite", "bg-amber-500/10 text-amber-300 ring-amber-400/30")
      case ToolSandbox.Unrestricted       => ("Unrestricted", "bg-purple-500/10 text-purple-300 ring-purple-400/30")
    span(cls := s"inline-flex items-center rounded-md px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset $cls_")(
      label
    )
