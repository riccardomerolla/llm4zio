package shared.web

import scalatags.Text.all.*

object WorkspaceTemplatesView:

  final private case class QuestionPreview(
    number: String,
    label: String,
    field: String,
    answerLines: List[String],
    helper: String,
  )

  final private case class IssuePreview(
    title: String,
    summary: String,
    meta: String,
  )

  final private case class TemplateInfo(
    id: String,
    name: String,
    description: String,
    stackLabel: String,
    stackClasses: String,
    focusLabel: String,
    buildCommand: String,
    referenceLabel: String,
    prompt: String,
    questions: List[QuestionPreview],
    runModeFollowUps: List[String],
    scaffoldOutputs: List[String],
    issuePreviews: List[IssuePreview],
  )

  private val templates: List[TemplateInfo] = List(
    TemplateInfo(
      id = "scala3-zio",
      name = "Scala 3 + ZIO",
      description =
        "Event-driven backend scaffold for teams that want typed effects, ZLayer wiring, and a board backlog generated from concrete service capabilities.",
      stackLabel = "Scala 3 / ZIO 2.x",
      stackClasses = "border-rose-400/30 bg-rose-500/10 text-rose-200",
      focusLabel = "Best for APIs, daemons, and workflow services",
      buildCommand = "sbt compile",
      referenceLabel = "Open Scala 3 + ZIO reference",
      prompt =
        "Create a new Scala 3 + ZIO workspace called payments-api. Scaffold a service that ingests payment events, exposes settlement endpoints, validates incoming payloads, and seeds the board with the next implementation issues before I start coding.",
      questions = List(
        QuestionPreview(
          "1",
          "Name",
          "name",
          List("payments-api"),
          "Keep it lowercase, stable, and usable as both workspace name and repository slug.",
        ),
        QuestionPreview(
          "2",
          "Path",
          "path",
          List("~/workspaces/payments-api"),
          "Prefer an empty absolute path so the skill can scaffold, initialize git, and register in one pass.",
        ),
        QuestionPreview(
          "3",
          "Description",
          "description",
          List("Process payment events and expose settlement workflows for operations."),
          "This sentence becomes the workspace description and shapes the generated issue backlog.",
        ),
        QuestionPreview(
          "4",
          "Stack",
          "stack",
          List("scala3-zio"),
          "Locks the scaffold to the Scala 3 + ZIO reference and build conventions.",
        ),
        QuestionPreview(
          "5",
          "Features",
          "features",
          List(
            "Ingest payment webhooks with schema validation.",
            "Persist settlement jobs and retry failed deliveries.",
            "Expose status endpoints for support operations.",
            "Add ZIO Test coverage for the happy path and typed failures.",
          ),
          "This prompt is the source material for the next issue cards.",
        ),
        QuestionPreview(
          "6",
          "CLI Tool",
          "cliTool",
          List("codex", "Alternatives: claude, gemini, copilot, opencode"),
          "The page stays provider-neutral: choose the agent CLI that should execute runs inside the registered workspace.",
        ),
        QuestionPreview(
          "7",
          "Run Mode",
          "runMode",
          List("host"),
          "Host is the fastest default for local sbt workflows; switching modes adds a few follow-up questions.",
        ),
      ),
      runModeFollowUps = List(
        "Docker adds image, mount-worktree, and network prompts before registration.",
        "Cloud adds provider, image, and optional region so remote runners can be wired later without changing the backlog.",
      ),
      scaffoldOutputs = List(
        "Creates the project directory, initializes git, and writes the sbt build plus source/test skeleton.",
        "Adds the agent conventions file and stack-specific ignores.",
        "Registers the workspace through /api/workspaces with the selected CLI tool and run mode.",
        "Turns the feature prompt into setup, feature, testing, and documentation issue cards.",
      ),
      issuePreviews = List(
        IssuePreview(
          "Set up the ZIO service skeleton and dependency layers",
          "Establish modules, wiring, and a compile-safe build baseline.",
          "high · setup · M",
        ),
        IssuePreview(
          "Implement webhook ingestion with typed validation failures",
          "Capture invalid payload handling and domain event mapping.",
          "high · feature · M",
        ),
        IssuePreview(
          "Add settlement status endpoints and JSON contracts",
          "Expose operational visibility for retries and reconciliation.",
          "medium · feature · M",
        ),
        IssuePreview(
          "Cover ingest and retry flows with ZIO Test",
          "Protect the first end-to-end flow before more features land.",
          "medium · testing · S",
        ),
      ),
    ),
    TemplateInfo(
      id = "spring-boot",
      name = "Spring Boot",
      description =
        "Opinionated enterprise service scaffold with layered modules, validation, actuator readiness, and backlog cards that reflect operational features from day one.",
      stackLabel = "Java 21 / Spring Boot",
      stackClasses = "border-emerald-400/30 bg-emerald-500/10 text-emerald-200",
      focusLabel = "Best for REST platforms and integration-heavy backends",
      buildCommand = "./mvnw compile",
      referenceLabel = "Open Spring Boot reference",
      prompt =
        "Create a new Spring Boot workspace called order-ops. Scaffold an operations API for order intake, validation, fulfillment retries, and dashboard-ready monitoring. Generate the first issue cards from that scope before registering the workspace.",
      questions = List(
        QuestionPreview(
          "1",
          "Name",
          "name",
          List("order-ops"),
          "Use the final product or bounded-context name so the workspace and issue cards stay coherent.",
        ),
        QuestionPreview(
          "2",
          "Path",
          "path",
          List("~/workspaces/order-ops"),
          "An empty path keeps the scaffold deterministic and safe to register automatically.",
        ),
        QuestionPreview(
          "3",
          "Description",
          "description",
          List("Operate order intake, fulfillment retries, and support-facing status APIs."),
          "A strong description helps the generated board separate platform work from domain work.",
        ),
        QuestionPreview(
          "4",
          "Stack",
          "stack",
          List("spring-boot"),
          "Pins the generator to Maven wrapper, layered packaging, and Java 21 defaults.",
        ),
        QuestionPreview(
          "5",
          "Features",
          "features",
          List(
            "Validate incoming orders and reject malformed requests.",
            "Persist fulfillment attempts and schedule retries.",
            "Expose health and metrics endpoints for operators.",
            "Document external API contracts and integration assumptions.",
          ),
          "These become the backbone of the first backlog, not just project notes.",
        ),
        QuestionPreview(
          "6",
          "CLI Tool",
          "cliTool",
          List("codex", "Alternatives: claude, gemini, copilot, opencode"),
          "Provider selection is decoupled from the template, so the same scaffold works across agent ecosystems.",
        ),
        QuestionPreview(
          "7",
          "Run Mode",
          "runMode",
          List("docker"),
          "Docker is often a practical default for Java services that need a repeatable runtime or local dependencies.",
        ),
      ),
      runModeFollowUps = List(
        "Docker prompts for the runtime image and whether to mount the worktree for live editing.",
        "Cloud prompts for provider, deploy image, and region when the service should run outside the host from day one.",
      ),
      scaffoldOutputs = List(
        "Creates the Maven wrapper, layered package structure, and starter application module.",
        "Adds validation, testing, and actuator-ready placeholders so the first issues start from a production-minded baseline.",
        "Registers the workspace with the chosen execution mode and CLI tool.",
        "Builds the initial issue stack around setup, core APIs, observability, and test coverage.",
      ),
      issuePreviews = List(
        IssuePreview(
          "Bootstrap the Spring Boot service and Maven conventions",
          "Lay down the runtime, formatting, and test harness essentials.",
          "high · setup · S",
        ),
        IssuePreview(
          "Implement order intake validation and persistence",
          "Create the first domain slice with request validation and storage.",
          "high · feature · M",
        ),
        IssuePreview(
          "Add fulfillment retry orchestration and operational endpoints",
          "Make retries visible and actionable for support workflows.",
          "high · feature · M",
        ),
        IssuePreview(
          "Wire Actuator health checks and baseline integration tests",
          "Protect the service contract and readiness story early.",
          "medium · testing · S",
        ),
      ),
    ),
    TemplateInfo(
      id = "react-ts",
      name = "React + TypeScript",
      description =
        "Frontend workspace scaffold focused on fast product iteration, strong typing, component testing, and a backlog derived from user-facing capabilities instead of file lists.",
      stackLabel = "React 19 / TypeScript 5",
      stackClasses = "border-sky-400/30 bg-sky-500/10 text-sky-200",
      focusLabel = "Best for dashboards, portals, and SPA product surfaces",
      buildCommand = "npm install && npm run dev",
      referenceLabel = "Open React + TypeScript reference",
      prompt =
        "Create a new React + TypeScript workspace called customer-portal. Scaffold a portal with authentication, account overview, payment activity, and support messaging. Generate the starter issue cards from the user experience goals before registering the workspace.",
      questions = List(
        QuestionPreview(
          "1",
          "Name",
          "name",
          List("customer-portal"),
          "Choose the product-facing name the team will recognize in the board and workspace switcher.",
        ),
        QuestionPreview(
          "2",
          "Path",
          "path",
          List("~/workspaces/customer-portal"),
          "Use a fresh frontend directory so the generator can add Vite, testing, and linting in one shot.",
        ),
        QuestionPreview(
          "3",
          "Description",
          "description",
          List("Customer portal for account visibility, payment activity, and support interactions."),
          "This description becomes the short summary for the workspace and the generated issue set.",
        ),
        QuestionPreview(
          "4",
          "Stack",
          "stack",
          List("react-ts"),
          "Locks the scaffold to React, TypeScript strict mode, and the Vite reference.",
        ),
        QuestionPreview(
          "5",
          "Features",
          "features",
          List(
            "Authenticate returning customers and preserve session state.",
            "Show account status, recent invoices, and payment activity.",
            "Support guided actions like downloading statements and opening support requests.",
            "Add component and page tests for critical journeys.",
          ),
          "Feature phrasing should describe user journeys because those become the next issue cards.",
        ),
        QuestionPreview(
          "6",
          "CLI Tool",
          "cliTool",
          List("codex", "Alternatives: claude, gemini, copilot, opencode"),
          "Pick whichever agent runtime should operate on the workspace once the scaffold is registered.",
        ),
        QuestionPreview(
          "7",
          "Run Mode",
          "runMode",
          List("host"),
          "Host keeps local Vite feedback loops tight, while Docker stays available for reproducible review environments.",
        ),
      ),
      runModeFollowUps = List(
        "Docker asks for the frontend image and whether the worktree should be mounted for live reload.",
        "Cloud asks for provider, image, and region when the portal should be previewed in a managed runtime.",
      ),
      scaffoldOutputs = List(
        "Creates the Vite project shell, TypeScript configuration, and test tooling.",
        "Adds the current agent-instructions file plus frontend-oriented ignores and workspace metadata.",
        "Registers the workspace so agents can run from the board with the selected CLI tool.",
        "Seeds the backlog with UX, state management, testing, and docs issues derived from the feature prompt.",
      ),
      issuePreviews = List(
        IssuePreview(
          "Bootstrap the React app shell and developer workflow",
          "Set up routing, linting, test harnesses, and base layout.",
          "high · setup · S",
        ),
        IssuePreview(
          "Implement authentication and session handling flows",
          "Cover sign-in state, guards, and error handling.",
          "high · feature · M",
        ),
        IssuePreview(
          "Build account overview and payment activity surfaces",
          "Turn the main user journey into navigable screens and data states.",
          "high · feature · M",
        ),
        IssuePreview(
          "Add user-journey tests for portal entry and activity views",
          "Protect the first critical customer flows before expanding the UI.",
          "medium · testing · S",
        ),
      ),
    ),
  )

  private val defaultTemplateId = templates.headOption.map(_.id).getOrElse("scala3-zio")

  def page(): String =
    Layout.page("Workspace Templates", "/workspace-templates")(
      div(cls := "space-y-8")(
        Components.pageHeader(
          "Workspace Templates",
          "Choose a stack, shape the prompt, answer the seven wizard questions, and preview the scaffold plus next issue cards before creating the workspace.",
          a(
            href := "/workspaces",
            cls  := "rounded-md border border-white/15 px-3 py-1.5 text-sm text-slate-300 hover:bg-white/5",
          )("← Workspaces"),
        ),
        wizardFlowSummary,
        templateSelector,
        div(cls := "space-y-6")(templates.map(templatePanel)*),
        selectorScript,
      )
    )

  private def wizardFlowSummary: Frag =
    div(
      cls := "rounded-2xl border border-white/10 bg-[linear-gradient(135deg,rgba(15,23,42,0.96),rgba(30,41,59,0.86))] p-6 shadow-[0_18px_60px_rgba(15,23,42,0.45)]"
    )(
      div(cls := "flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between")(
        div(cls := "max-w-3xl")(
          p(cls := "text-[11px] font-semibold uppercase tracking-[0.28em] text-cyan-300")("Wizard-first flow"),
          h2(
            cls := "mt-2 text-2xl font-semibold text-white"
          )("Turn a template into a registered workspace and a usable backlog"),
          p(cls := "mt-3 max-w-2xl text-sm leading-6 text-slate-300")(
            "The page now mirrors the workspace-template skill itself: start from a template, craft the user prompt, answer the standard questions, then review scaffold and issue outputs before handing execution to an agent."
          ),
        ),
        div(cls := "rounded-xl border border-cyan-400/20 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-100")(
          span(cls := "font-semibold text-white")("Outcome: "),
          "one prompt, one scaffold, one registered workspace, and a first set of issue cards.",
        ),
      ),
      div(cls := "mt-6 grid grid-cols-1 gap-3 lg:grid-cols-4")(
        flowStep("1", "Select template", "Pick the stack and see the matching prompt, defaults, and scaffold plan."),
        flowStep("2", "Prompt first", "Capture the user intent that should drive scaffolding and backlog generation."),
        flowStep("3", "Answer 7 questions", "Name, path, description, stack, features, CLI tool, and run mode."),
        flowStep(
          "4",
          "Scaffold + plan",
          "Create the workspace and seed the board with the next actionable issue cards.",
        ),
      ),
    )

  private def flowStep(number: String, title: String, body: String): Frag =
    div(cls := "rounded-xl border border-white/10 bg-black/20 p-4")(
      div(cls := "flex items-center gap-3")(
        div(
          cls := "flex size-8 items-center justify-center rounded-full bg-cyan-400/15 text-sm font-semibold text-cyan-200"
        )(number),
        h3(cls := "text-sm font-semibold text-white")(title),
      ),
      p(cls := "mt-3 text-sm leading-6 text-slate-400")(body),
    )

  private def templateSelector: Frag =
    div(cls := "rounded-2xl border border-white/10 bg-white/[0.03] p-4 sm:p-5")(
      div(cls := "flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between")(
        div(
          p(cls := "text-sm font-semibold text-white")("Select a template"),
          p(cls := "mt-1 text-sm text-slate-400")(
            "Each template keeps the same seven questions, but changes the recommended answers, scaffold outputs, and backlog cards."
          ),
        ),
        p(cls := "text-xs uppercase tracking-[0.22em] text-slate-500")("3 curated starting points"),
      ),
      div(cls := "mt-4 grid grid-cols-1 gap-3 xl:grid-cols-3")(
        templates.map(templateSelectorButton)*
      ),
    )

  private def templateSelectorButton(template: TemplateInfo): Frag =
    button(
      `type`                       := "button",
      attr("data-template-button") := template.id,
      attr("aria-controls")        := s"workspace-template-panel-${template.id}",
      attr("aria-selected")        := (template.id == defaultTemplateId).toString,
      cls                          := selectorButtonClasses(template.id == defaultTemplateId),
    )(
      div(cls := "flex items-start justify-between gap-3")(
        div(cls := "min-w-0 text-left")(
          p(cls := "text-sm font-semibold text-white")(template.name),
          p(cls := "mt-1 text-sm leading-6 text-slate-400")(template.focusLabel),
        ),
        span(
          cls := s"rounded-full border px-2.5 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] ${template.stackClasses}"
        )(
          template.stackLabel
        ),
      ),
      p(cls := "mt-4 text-left text-sm leading-6 text-slate-300")(template.description),
    )

  private def selectorButtonClasses(selected: Boolean): String =
    val base =
      "group rounded-2xl border p-4 text-left transition duration-150 focus:outline-none focus:ring-2 focus:ring-cyan-400/70"
    if selected then s"$base border-cyan-400/40 bg-cyan-500/10 shadow-[0_0_0_1px_rgba(34,211,238,0.15)]"
    else s"$base border-white/10 bg-black/20 hover:border-white/20 hover:bg-white/[0.05]"

  private def templatePanel(template: TemplateInfo): Frag =
    div(
      id                          := s"workspace-template-panel-${template.id}",
      attr("data-template-panel") := template.id,
      attr("data-selected")       := (template.id == defaultTemplateId).toString,
      cls                         := (if template.id == defaultTemplateId then "space-y-6" else "hidden space-y-6"),
    )(
      div(
        cls := "rounded-[28px] border border-white/10 bg-[radial-gradient(circle_at_top_left,rgba(56,189,248,0.18),transparent_32%),linear-gradient(160deg,rgba(15,23,42,0.96),rgba(17,24,39,0.96))] p-5 sm:p-6 shadow-[0_24px_90px_rgba(2,6,23,0.38)]"
      )(
        div(cls := "flex flex-col gap-4 border-b border-white/10 pb-5 lg:flex-row lg:items-start lg:justify-between")(
          div(cls := "max-w-3xl")(
            p(cls := "text-[11px] font-semibold uppercase tracking-[0.28em] text-slate-400")("Selected template"),
            h2(cls := "mt-2 text-2xl font-semibold text-white")(template.name),
            p(cls := "mt-3 text-sm leading-6 text-slate-300")(template.description),
          ),
          div(cls := "flex flex-wrap items-center gap-2")(
            span(
              cls := s"rounded-full border px-3 py-1 text-[11px] font-semibold uppercase tracking-[0.18em] ${template.stackClasses}"
            )(
              template.stackLabel
            ),
            span(
              cls := "rounded-full border border-white/10 bg-white/5 px-3 py-1 text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-300"
            )(
              template.focusLabel
            ),
            a(
              href   := s"/static/skills/workspace-template/references/${template.id}.md",
              target := "_blank",
              cls    := "rounded-full border border-cyan-400/25 bg-cyan-500/10 px-3 py-1 text-[11px] font-semibold uppercase tracking-[0.18em] text-cyan-100 hover:bg-cyan-500/15",
            )(template.referenceLabel),
          ),
        ),
        div(cls := "mt-6 grid grid-cols-1 gap-6 xl:grid-cols-[1.2fr_0.8fr]")(
          div(cls := "space-y-6")(
            promptPanel(template),
            questionsPanel(template),
          ),
          div(cls := "space-y-6")(
            scaffoldPanel(template),
            issueCardsPanel(template),
          ),
        ),
      )
    )

  private def promptPanel(template: TemplateInfo): Frag =
    div(cls := "rounded-2xl border border-cyan-400/20 bg-cyan-500/[0.07] p-5")(
      div(cls := "flex items-center justify-between gap-3")(
        div(
          p(cls := "text-[11px] font-semibold uppercase tracking-[0.24em] text-cyan-300")("Prompt first"),
          h3(cls := "mt-2 text-lg font-semibold text-white")("User prompt to place above the wizard questions"),
        ),
        span(
          cls := "rounded-full border border-cyan-400/25 bg-black/20 px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-cyan-100"
        )(
          "Template-aware"
        ),
      ),
      p(cls := "mt-3 text-sm leading-6 text-slate-300")(
        "Use this prompt as the opening instruction for any supported agent. It frames the workspace goal before the skill starts asking the seven standard questions."
      ),
      div(
        cls := "mt-4 rounded-2xl border border-white/10 bg-slate-950/80 p-4 font-mono text-[13px] leading-7 text-emerald-200 shadow-inner"
      )(
        template.prompt
      ),
    )

  private def questionsPanel(template: TemplateInfo): Frag =
    div(cls := "rounded-2xl border border-white/10 bg-black/20 p-5")(
      div(cls := "flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between")(
        div(
          p(cls := "text-[11px] font-semibold uppercase tracking-[0.24em] text-slate-400")("Wizard questions"),
          h3(cls := "mt-2 text-lg font-semibold text-white")("Seven standard answers for this template"),
        ),
        span(cls := "text-xs text-slate-500")("name · path · description · stack · features · CLI tool · run mode"),
      ),
      div(cls := "mt-5 space-y-3")(
        template.questions.map(renderQuestion)*
      ),
      div(cls := "mt-5 rounded-2xl border border-amber-400/20 bg-amber-500/[0.06] p-4")(
        p(cls := "text-xs font-semibold uppercase tracking-[0.22em] text-amber-200")("Run-mode follow-ups"),
        ul(cls := "mt-3 space-y-2 text-sm leading-6 text-slate-300")(
          template.runModeFollowUps.map { item =>
            li(cls := "flex gap-3")(
              span(cls := "mt-1 text-amber-200")("•"),
              span(item),
            )
          }*
        ),
      ),
    )

  private def renderQuestion(question: QuestionPreview): Frag =
    div(cls := "rounded-2xl border border-white/10 bg-white/[0.04] p-4")(
      div(cls := "flex gap-4")(
        div(
          cls := "flex size-9 shrink-0 items-center justify-center rounded-full bg-white/10 text-sm font-semibold text-white"
        )(question.number),
        div(cls := "min-w-0 flex-1")(
          div(cls := "flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between")(
            div(
              p(cls := "text-sm font-semibold text-white")(question.label),
              p(cls := "text-xs uppercase tracking-[0.18em] text-slate-500")(question.field),
            ),
            span(
              cls := "rounded-full border border-white/10 bg-black/20 px-2.5 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-300"
            )(
              "Suggested answer"
            ),
          ),
          div(cls := "mt-3 rounded-xl border border-white/10 bg-slate-950/70 p-3")(
            question.answerLines match
              case answer :: Nil => p(cls := "font-mono text-[13px] text-emerald-200")(answer)
              case many          =>
                ul(cls := "space-y-2 font-mono text-[13px] text-emerald-200")(
                  many.map(item =>
                    li(cls := "flex gap-3")(
                      span(cls := "text-cyan-200")("•"),
                      span(item),
                    )
                  )*
                )
          ),
          p(cls := "mt-3 text-sm leading-6 text-slate-400")(question.helper),
        ),
      )
    )

  private def scaffoldPanel(template: TemplateInfo): Frag =
    div(cls := "rounded-2xl border border-white/10 bg-black/20 p-5")(
      p(cls := "text-[11px] font-semibold uppercase tracking-[0.24em] text-slate-400")("Scaffold preview"),
      h3(cls := "mt-2 text-lg font-semibold text-white")("What gets created before the workspace is registered"),
      p(cls := "mt-3 text-sm leading-6 text-slate-300")(
        "The scaffold stays tied to the selected reference and build tool, but the agent provider stays configurable through the CLI tool answer."
      ),
      div(cls := "mt-4 rounded-2xl border border-white/10 bg-slate-950/75 p-4")(
        p(cls := "text-xs font-semibold uppercase tracking-[0.2em] text-slate-500")("Build check"),
        p(cls := "mt-2 font-mono text-[13px] text-cyan-200")(template.buildCommand),
      ),
      ul(cls := "mt-5 space-y-3")(
        template.scaffoldOutputs.map { item =>
          li(
            cls := "flex gap-3 rounded-xl border border-white/10 bg-white/[0.03] p-3 text-sm leading-6 text-slate-300"
          )(
            span(cls := "mt-1 text-cyan-300")("◆"),
            span(item),
          )
        }*
      ),
    )

  private def issueCardsPanel(template: TemplateInfo): Frag =
    div(cls := "rounded-2xl border border-white/10 bg-black/20 p-5")(
      div(cls := "flex items-center justify-between gap-3")(
        div(
          p(cls := "text-[11px] font-semibold uppercase tracking-[0.24em] text-slate-400")("Next issue cards"),
          h3(cls := "mt-2 text-lg font-semibold text-white")("Backlog preview generated from the user prompt"),
        ),
        span(
          cls := "rounded-full border border-white/10 bg-white/[0.04] px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-300"
        )(
          s"${template.issuePreviews.size} starter cards"
        ),
      ),
      p(cls := "mt-3 text-sm leading-6 text-slate-300")(
        "These are the first cards the wizard should propose after scaffolding. They stay concrete, execution-ready, and tied to the selected feature prompt."
      ),
      div(cls := "mt-5 space-y-3")(
        template.issuePreviews.zipWithIndex.map {
          case (issue, index) =>
            div(cls := "rounded-2xl border border-white/10 bg-white/[0.04] p-4")(
              div(cls := "flex items-start justify-between gap-3")(
                div(
                  p(cls := "text-xs uppercase tracking-[0.22em] text-slate-500")(s"Issue ${index + 1}"),
                  h4(cls := "mt-1 text-sm font-semibold text-white")(issue.title),
                ),
                span(
                  cls := "rounded-full border border-cyan-400/20 bg-cyan-500/[0.08] px-2.5 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-cyan-100"
                )(
                  issue.meta
                ),
              ),
              p(cls := "mt-3 text-sm leading-6 text-slate-400")(issue.summary),
            )
        }*
      ),
    )

  private def selectorScript: Frag =
    script(raw(
      """(function () {
        |  function initWorkspaceTemplateWizard() {
        |    var buttons = Array.prototype.slice.call(document.querySelectorAll('[data-template-button]'));
        |    var panels = Array.prototype.slice.call(document.querySelectorAll('[data-template-panel]'));
        |    if (!buttons.length || !panels.length) return;
        |
        |    function buttonClass(selected) {
        |      var base = 'group rounded-2xl border p-4 text-left transition duration-150 focus:outline-none focus:ring-2 focus:ring-cyan-400/70';
        |      return selected
        |        ? base + ' border-cyan-400/40 bg-cyan-500/10 shadow-[0_0_0_1px_rgba(34,211,238,0.15)]'
        |        : base + ' border-white/10 bg-black/20 hover:border-white/20 hover:bg-white/[0.05]';
        |    }
        |
        |    function selectTemplate(templateId) {
        |      buttons.forEach(function (button) {
        |        var selected = button.getAttribute('data-template-button') === templateId;
        |        button.setAttribute('aria-selected', String(selected));
        |        button.className = buttonClass(selected);
        |      });
        |
        |      panels.forEach(function (panel) {
        |        var selected = panel.getAttribute('data-template-panel') === templateId;
        |        panel.setAttribute('data-selected', String(selected));
        |        panel.classList.toggle('hidden', !selected);
        |      });
        |    }
        |
        |    buttons.forEach(function (button) {
        |      if (button.dataset.templateWizardInit === 'true') return;
        |      button.dataset.templateWizardInit = 'true';
        |      button.addEventListener('click', function () {
        |        selectTemplate(button.getAttribute('data-template-button'));
        |      });
        |    });
        |
        |    selectTemplate(document.querySelector('[data-template-button][aria-selected="true"]')?.getAttribute('data-template-button') || '""" + defaultTemplateId + """');
        |  }
        |
        |  if (document.readyState === 'loading') {
        |    document.addEventListener('DOMContentLoaded', initWorkspaceTemplateWizard);
        |  } else {
        |    initWorkspaceTemplateWizard();
        |  }
        |})();""".stripMargin
    ))
