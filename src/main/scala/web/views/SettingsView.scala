package web.views

import scalatags.Text.all.*

object SettingsView:

  private val inputCls =
    "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3"

  private val selectCls =
    "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3"

  private val labelCls = "block text-sm font-medium text-white mb-2"

  private val sectionCls = "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6"

  def page(settings: Map[String, String], flash: Option[String] = None): String =
    Layout.page("Settings", "/settings")(
      div(cls := "max-w-2xl")(
        h1(cls := "text-2xl font-bold text-white mb-6")("Settings"),
        flash.map { msg =>
          div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
            p(cls := "text-sm text-green-400")(msg)
          )
        },
        tag("form")(method := "post", action := "/settings", cls := "space-y-6")(
          aiProviderSection(settings),
          processingSection(settings),
          discoverySection(settings),
          featuresSection(settings),
          div(cls := "flex gap-4 pt-2")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500",
            )("Save Settings")
          ),
        ),
      )
    )

  private def aiProviderSection(s: Map[String, String]): Frag =
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
          ),
        ),
        textField("ai.model", "Model", s, placeholder = "gemini-2.5-flash"),
        textField("ai.baseUrl", "Base URL", s, placeholder = "https://api.example.com (optional)"),
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
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField("ai.timeout", "Timeout (seconds)", s, default = "90", min = "1", max = "600"),
          numberField("ai.maxRetries", "Max Retries", s, default = "3", min = "0", max = "10"),
        ),
        div(cls := "grid grid-cols-2 gap-4")(
          numberField("ai.requestsPerMinute", "Requests/min", s, default = "60", min = "1", max = "600"),
          numberField("ai.burstSize", "Burst Size", s, default = "10", min = "1", max = "100"),
        ),
        numberField("ai.acquireTimeout", "Acquire Timeout (seconds)", s, default = "30", min = "1", max = "300"),
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
          ),
          numberField(
            "ai.maxTokens",
            "Max Tokens",
            s,
            default = "",
            min = "1",
            max = "1048576",
            placeholder = "Optional",
          ),
        ),
      ),
    )

  private def processingSection(s: Map[String, String]): Frag =
    tag("section")(cls := sectionCls)(
      h2(cls := "text-lg font-semibold text-white mb-4")("Processing"),
      div(cls := "grid grid-cols-2 gap-4")(
        numberField("processing.parallelism", "Parallelism", s, default = "4", min = "1", max = "64"),
        numberField("processing.batchSize", "Batch Size", s, default = "10", min = "1", max = "100"),
      ),
    )

  private def discoverySection(s: Map[String, String]): Frag =
    tag("section")(cls := sectionCls)(
      h2(cls := "text-lg font-semibold text-white mb-4")("Discovery"),
      div(cls := "space-y-4")(
        numberField("discovery.maxDepth", "Max Depth", s, default = "25", min = "1", max = "100"),
        div(
          label(cls := labelCls, `for` := "discovery.excludePatterns")("Exclude Patterns"),
          p(cls := "text-xs text-gray-500 mb-2")("One glob pattern per line"),
          tag("textarea")(
            name := "discovery.excludePatterns",
            id   := "discovery.excludePatterns",
            rows := "6",
            cls  := inputCls,
          )(
            s.getOrElse(
              "discovery.excludePatterns",
              "**/.git/**\n**/target/**\n**/node_modules/**\n**/.idea/**\n**/.vscode/**\n**/backup/**\n**/*.bak\n**/*.tmp\n**/*~",
            )
          ),
        ),
      ),
    )

  private def featuresSection(s: Map[String, String]): Frag =
    tag("section")(cls := sectionCls)(
      h2(cls := "text-lg font-semibold text-white mb-4")("Features"),
      div(cls := "space-y-3")(
        checkboxField(
          "features.enableCheckpointing",
          "Enable Checkpointing",
          s,
          default = true,
        ),
        checkboxField(
          "features.verbose",
          "Verbose Logging",
          s,
          default = false,
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
    )

  private def checkboxField(
    fieldName: String,
    labelText: String,
    s: Map[String, String],
    default: Boolean,
  ): Frag =
    val checked = s.get(fieldName).map(_ == "true").getOrElse(default)
    div(cls := "flex items-center gap-3")(
      input(
        `type` := "checkbox",
        name   := fieldName,
        id     := fieldName,
        cls    := "h-4 w-4 rounded border-white/10 bg-white/5 text-indigo-600 focus:ring-indigo-600",
        if checked then attr("checked") := "checked" else (),
      ),
      label(cls := "text-sm text-gray-400", `for` := fieldName)(labelText),
    )

  private def providerOption(value: String, labelText: String, current: Option[String]): Frag =
    val isSelected = current.contains(value) || (current.isEmpty && value == "GeminiCli")
    tag("option")(
      attr("value") := value,
      if isSelected then attr("selected") := "selected" else (),
    )(labelText)
