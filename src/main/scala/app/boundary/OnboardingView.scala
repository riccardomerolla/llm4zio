package app.boundary

import scalatags.Text.all.*
import shared.web.Layout

/** Phase 5 (big-review R10): 60-second first-run wizard.
  *
  * Single page, single POST. Saves LLM config + Telegram bot config + creates
  * the first project + first workspace. The 5-role typed roster is already
  * seeded into AgentRepository on every boot (BuiltInAgentSynchronizer +
  * DefaultRoster), so this wizard doesn't need a roster step — it just
  * confirms the cast.
  */
object OnboardingView:

  final case class Defaults(
    aiProvider: String,
    aiModel: String,
    telegramEnabled: Boolean,
    telegramBotToken: String,
    telegramSupervisorChatId: String,
    projectName: String,
    workspaceName: String,
    workspaceLocalPath: String,
  )

  object Defaults:
    val empty: Defaults = Defaults(
      aiProvider = "Anthropic",
      aiModel = "claude-sonnet-4-6",
      telegramEnabled = false,
      telegramBotToken = "",
      telegramSupervisorChatId = "",
      projectName = "My Project",
      workspaceName = "main",
      workspaceLocalPath = "",
    )

  def page(defaults: Defaults, error: Option[String] = None, flash: Option[String] = None): String =
    Layout.page("Welcome", "/onboarding")(
      div(cls := "mx-auto max-w-3xl space-y-8 p-6")(
        h1(cls := "text-3xl font-bold text-white")("Welcome to your agentic software house"),
        p(cls := "text-sm text-slate-300")(
          "Four steps, sixty seconds. We'll configure your LLM, connect Telegram for HITL, " +
            "confirm your default cast of five employees (Pat / Alex / Ben / Dana / Rex), and " +
            "register your first workspace. You can change everything later in Settings."
        ),
        error.map(message =>
          div(cls := "rounded-lg border border-rose-500/40 bg-rose-500/10 px-4 py-2 text-sm text-rose-200")(
            message
          )
        ).getOrElse(frag()),
        flash.map(message =>
          div(cls := "rounded-lg border border-emerald-500/40 bg-emerald-500/10 px-4 py-2 text-sm text-emerald-200")(
            message
          )
        ).getOrElse(frag()),
        form(action := "/onboarding", method := "post", cls := "space-y-6")(
          stepLlm(defaults),
          stepTelegram(defaults),
          stepRoster,
          stepWorkspace(defaults),
          div(cls := "flex justify-end pt-4")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-emerald-500 px-6 py-2.5 text-sm font-semibold text-white hover:bg-emerald-400",
            )("Finish setup →"),
          ),
        ),
      )
    )

  private def stepLlm(d: Defaults): Frag =
    stepCard(
      number = 1,
      title = "Connect your LLM",
      subtitle = "Which model your employees use by default. Drives cost calculation in the dashboard.",
    )(
      labelledField("Provider")(
        select(
          attr("name") := "aiProvider",
          cls          := fieldCls,
        )(
          providerOption("Anthropic", d.aiProvider),
          providerOption("OpenAI", d.aiProvider),
          providerOption("Gemini", d.aiProvider),
          providerOption("LmStudio", d.aiProvider),
          providerOption("Ollama", d.aiProvider),
          providerOption("Mock", d.aiProvider),
        )
      ),
      labelledField("Model")(
        input(
          attr("type")  := "text",
          attr("name")  := "aiModel",
          attr("value") := d.aiModel,
          placeholder   := "e.g. claude-sonnet-4-6",
          cls           := fieldCls,
        )
      ),
      labelledField("API key")(
        input(
          attr("type")  := "password",
          attr("name")  := "aiApiKey",
          placeholder   := "sk-…  (leave blank for local providers)",
          cls           := fieldCls,
        )
      ),
    )

  private def stepTelegram(d: Defaults): Frag =
    stepCard(
      number = 2,
      title = "Connect Telegram",
      subtitle = "Where decision escalations land. Skip if you're behind a corporate policy — " +
        "/decisions/inbox mirrors the same flow in the browser.",
    )(
      labelledField("Enabled")(
        input(
          attr("type")    := "checkbox",
          attr("name")    := "telegramEnabled",
          attr("value")   := "true",
          if d.telegramEnabled then attr("checked") := "checked" else (),
          cls             := "h-4 w-4 rounded border-white/20 bg-black/30 text-emerald-500",
        )
      ),
      labelledField("Bot token")(
        div(cls := "space-y-2")(
          div(cls := "flex gap-2")(
            input(
              attr("type")  := "text",
              attr("name")  := "telegramBotToken",
              attr("id")    := "telegramBotToken",
              attr("value") := d.telegramBotToken,
              placeholder   := "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
              cls           := s"$fieldCls flex-1",
            ),
            button(
              attr("type")           := "button",
              attr("hx-post")        := "/onboarding/test-telegram",
              attr("hx-include")     := "#telegramBotToken",
              attr("hx-target")      := "#telegramTestResult",
              attr("hx-swap")        := "innerHTML",
              attr("hx-indicator")   := "#telegramTestSpinner",
              cls                    := "shrink-0 rounded-md border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/20",
            )("Test"),
          ),
          div(id := "telegramTestSpinner", cls := "htmx-indicator text-xs text-slate-400")("Talking to api.telegram.org…"),
          div(id := "telegramTestResult"),
        )
      ),
      labelledField("Supervisor chat ID")(
        input(
          attr("type")  := "text",
          attr("name")  := "telegramSupervisorChatId",
          attr("value") := d.telegramSupervisorChatId,
          placeholder   := "Your numeric Telegram user/chat ID",
          cls           := fieldCls,
        )
      ),
    )

  private def stepRoster: Frag =
    stepCard(
      number = 3,
      title = "Your default roster",
      subtitle = "Five typed employees — already seeded into the agent registry. " +
        "Rename or add custom employees later in Settings.",
    )(
      div(cls := "grid gap-3 sm:grid-cols-2 lg:grid-cols-3")(
        rosterCard("Pat", "PM / Triage", "purple"),
        rosterCard("Alex", "Frontend Engineer", "pink"),
        rosterCard("Ben", "Backend Engineer", "blue"),
        rosterCard("Dana", "QA", "emerald"),
        rosterCard("Rex", "Reviewer", "amber"),
      )
    )

  private def stepWorkspace(d: Defaults): Frag =
    stepCard(
      number = 4,
      title = "Your first workspace",
      subtitle = "A workspace is a local git repository the agents work in. " +
        "We'll create a project and link the workspace to it.",
    )(
      labelledField("Project name")(
        input(
          attr("type")  := "text",
          attr("name")  := "projectName",
          attr("value") := d.projectName,
          placeholder   := "e.g. llm4zio",
          attr("required") := "required",
          cls           := fieldCls,
        )
      ),
      labelledField("Workspace name")(
        input(
          attr("type")  := "text",
          attr("name")  := "workspaceName",
          attr("value") := d.workspaceName,
          placeholder   := "e.g. main",
          attr("required") := "required",
          cls           := fieldCls,
        )
      ),
      labelledField("Local git path")(
        input(
          attr("type")  := "text",
          attr("name")  := "workspaceLocalPath",
          attr("value") := d.workspaceLocalPath,
          placeholder   := "/Users/you/code/your-repo",
          attr("required") := "required",
          cls           := fieldCls,
        )
      ),
    )

  private val fieldCls =
    "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500"

  private def stepCard(number: Int, title: String, subtitle: String)(content: Frag*): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      div(cls := "mb-4 flex items-start gap-3")(
        span(
          cls := "inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-emerald-500/15 text-sm font-semibold text-emerald-300 ring-1 ring-emerald-400/30"
        )(number.toString),
        div(
          h2(cls := "text-lg font-semibold text-white")(title),
          p(cls := "mt-1 text-xs text-slate-400")(subtitle),
        ),
      ),
      div(cls := "space-y-3")(content*),
    )

  private def labelledField(text: String)(field: Frag): Frag =
    div(cls := "space-y-1")(
      label(cls := "block text-xs font-medium text-slate-300")(text),
      field,
    )

  private def providerOption(value: String, current: String): Frag =
    option(
      attr("value") := value,
      if current.equalsIgnoreCase(value) then attr("selected") := "selected" else (),
    )(value)

  private def rosterCard(name: String, role: String, tone: String): Frag =
    val (border, text) = tone match
      case "purple"  => ("border-purple-400/30 bg-purple-500/10", "text-purple-200")
      case "pink"    => ("border-pink-400/30 bg-pink-500/10", "text-pink-200")
      case "blue"    => ("border-blue-400/30 bg-blue-500/10", "text-blue-200")
      case "emerald" => ("border-emerald-400/30 bg-emerald-500/10", "text-emerald-200")
      case "amber"   => ("border-amber-400/30 bg-amber-500/10", "text-amber-200")
      case _         => ("border-gray-400/30 bg-gray-500/10", "text-gray-200")
    div(cls := s"rounded-lg border $border p-3")(
      p(cls := s"text-base font-semibold $text")(name),
      p(cls := "text-xs text-slate-300")(role),
    )
