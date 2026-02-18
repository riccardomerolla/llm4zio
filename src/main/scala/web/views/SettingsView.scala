package web.views

import scalatags.Text.all.*

object SettingsView:

  private val inputCls =
    "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3"

  private val selectCls =
    "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3"

  private val labelCls = "block text-sm font-medium text-white mb-2"

  private val sectionCls = "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6"

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
          div(cls := "flex gap-4 pt-2")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500",
            )("Save Settings")
          ),
        ),
        div(cls := "mt-8 pt-6 border-t border-white/10")(
          div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4")(
            p(cls := "text-sm text-gray-300")(
              "💡 For advanced configuration with validation, diff, history, and hot reload, use the ",
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

  private def aiProviderSection(s: Map[String, String], errors: Map[String, String] = Map.empty): Frag =
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

  private def gatewaySection(s: Map[String, String], errors: Map[String, String] = Map.empty): Frag =
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

  private def telegramSection(s: Map[String, String], errors: Map[String, String] = Map.empty): Frag =
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

  // ---------------------------------------------------------------------------
  // Field helpers
  // ---------------------------------------------------------------------------

  private def textField(
    fieldName: String,
    labelText: String,
    s: Map[String, String],
    placeholder: String = "",
    error: Option[String] = None,
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
    min: String = "",
    max: String = "",
    step: String = "1",
    placeholder: String = "",
    error: Option[String] = None,
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
    error: Option[String] = None,
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
    placeholder: String = "",
    error: Option[String] = None,
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
      span(cls := "text-emerald-400 text-sm font-medium")("✓ Connection successful"),
      span(cls := "text-emerald-300 text-xs")("("),
      span(cls := "text-emerald-300 text-xs font-mono")(model),
      span(cls := "text-emerald-300 text-xs")(s", ${latencyMs}ms)"),
    ).toString

  def testConnectionError(error: String): String =
    div(cls := "inline-flex items-center gap-2 rounded-full bg-red-500/20 border border-red-500/50 px-4 py-2")(
      span(cls := "text-red-400 text-sm font-medium")("✗ Connection failed"),
      span(cls := "text-red-300 text-xs")("–"),
      span(cls := "text-red-300 text-xs")(error),
    ).toString
