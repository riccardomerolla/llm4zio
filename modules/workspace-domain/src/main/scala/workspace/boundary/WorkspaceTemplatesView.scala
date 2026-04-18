package workspace.boundary

import scalatags.Text.all.*
import shared.web.{ Components, Layout }

/** Interactive template picker / wizard for new workspaces.
  *
  * Moved from `shared-web` to `workspace-domain/boundary` in phase 5A.2. The view has zero domain-entity dependencies
  * (only pulls `Layout` + `Components` from `shared-web-core`), so its natural home is the workspace domain boundary
  * layer.
  */
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
    cardFeatures: List[String],
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
        "Effect-oriented backend service built on ZIO 2.x. Ideal for REST APIs, event-sourced domains, streaming pipelines, and CLI tools.",
      stackLabel = "Scala 3",
      stackClasses = "border-red-500/30 bg-red-500/12 text-red-300",
      cardFeatures = List(
        "Scala 3 + ZIO 2.x core",
        "zio-http for REST APIs (optional)",
        "zio-json for serialization (optional)",
        "ZIO Test + sbt test framework",
        "sbt-scalafmt + sbt-scalafix",
        "ZLayer dependency injection",
        "CLAUDE.md with BCE conventions",
      ),
      focusLabel = "Best for APIs, daemons, and workflow services",
      buildCommand = "sbt compile",
      referenceLabel = "View Scala 3 + ZIO reference",
      prompt =
        "Create a new Scala 3 + ZIO workspace called payments-api. Scaffold a service that ingests payment events, exposes settlement endpoints, validates incoming payloads, and seeds the board with the next implementation issues before I start coding.",
      questions = List(
        QuestionPreview(
          "1",
          "Name",
          "name",
          List("payments-api"),
          "Keep it lowercase, stable, and usable as both the workspace name and repository slug.",
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
          "The selected template already answers the stack question and locks the scaffold to the matching reference.",
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
          "This is the source material for the next issue cards.",
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
        "Docker asks for image, worktree mount, and network before registration.",
        "Cloud asks for provider, deploy image, and optional region so remote execution can be wired later without changing the backlog.",
      ),
      scaffoldOutputs = List(
        "Create the project directory, initialize git, and write the sbt build plus source and test skeleton.",
        "Add the agent conventions file and stack-specific ignores.",
        "Register the workspace through /api/workspaces with the selected CLI tool and run mode.",
        "Turn the feature prompt into setup, feature, testing, and documentation issue cards.",
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
        "Production-ready enterprise REST API with Spring Boot 3.x and Java 21. Includes layered architecture, validation, and Actuator health endpoints.",
      stackLabel = "Java 21",
      stackClasses = "border-green-500/30 bg-green-500/12 text-green-300",
      cardFeatures = List(
        "Spring Boot 3.4.x + Java 21",
        "spring-web, spring-validation",
        "Spring Data JPA (optional)",
        "Spring Security 6 / OAuth2 (optional)",
        "spring-boot-actuator",
        "JUnit 5 + Spring Boot Test",
        "CLAUDE.md with layered-arch conventions",
      ),
      focusLabel = "Best for REST platforms and integration-heavy backends",
      buildCommand = "./mvnw compile",
      referenceLabel = "View Spring Boot reference",
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
          "The selected template already answers the stack question and pins the scaffold to Maven wrapper and Java 21 defaults.",
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
        "Docker asks for the runtime image and whether to mount the worktree for live editing.",
        "Cloud asks for provider, image, and region when the service should run outside the host from day one.",
      ),
      scaffoldOutputs = List(
        "Create the Maven wrapper, layered package structure, and starter application module.",
        "Add validation, testing, and actuator-ready placeholders so the first issues start from a production-minded baseline.",
        "Register the workspace with the chosen execution mode and CLI tool.",
        "Build the initial issue stack around setup, core APIs, observability, and test coverage.",
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
        "Modern frontend SPA with React 19, TypeScript strict mode, and Vite 6. Batteries-included with Vitest, ESLint, and optional Tailwind CSS.",
      stackLabel = "TypeScript",
      stackClasses = "border-blue-500/30 bg-blue-500/12 text-blue-300",
      cardFeatures = List(
        "React 19 + TypeScript 5 (strict)",
        "Vite 6 dev server + bundler",
        "Vitest + Testing Library",
        "ESLint 9 with react-hooks rules",
        "Tailwind CSS v4 (optional)",
        "Zustand state management (optional)",
        "CLAUDE.md with functional-component conventions",
      ),
      focusLabel = "Best for dashboards, portals, and SPA product surfaces",
      buildCommand = "npm install && npm run dev",
      referenceLabel = "View React + TypeScript reference",
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
          "The selected template already answers the stack question and locks the scaffold to the React and TypeScript reference.",
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
        "Create the Vite project shell, TypeScript configuration, and test tooling.",
        "Add the current agent-instructions file plus frontend-oriented ignores and workspace metadata.",
        "Register the workspace so agents can run from the board with the selected CLI tool.",
        "Seed the backlog with UX, state management, testing, and docs issues derived from the feature prompt.",
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

  /** Seed issues for a given template: List of (title, summary, meta). */
  def seedIssues(templateId: String): List[(String, String, String)] =
    templates.find(_.id == templateId).toList.flatMap(_.issuePreviews.map(ip => (ip.title, ip.summary, ip.meta)))

  private val defaultTemplateId = templates.headOption.map(_.id).getOrElse("scala3-zio")

  def page(projectId: Option[String] = None, projects: List[(String, String)] = Nil): String =
    Layout.page("Workspace Templates", "/workspace-templates")(
      div(cls := "space-y-8")(
        Components.pageHeader(
          title = "Workspace Templates",
          subtitle =
            "Pick a stack, refine the prompt, and walk through the seven template questions before the workspace is scaffolded and the first issue cards are generated.",
          backHref = projectId.fold("/projects")(pid => s"/projects/$pid"),
          backText = projectId.flatMap(pid => projects.find(_._1 == pid).map(_._2)).getOrElse("Projects"),
        ),
        projectContextBar(projectId, projects),
        templateSelector,
        div(cls := "space-y-5")(templates.map(tp => templatePanel(tp, projectId))*),
        flowOverview,
        selectorScript,
      )
    )

  private def projectContextBar(projectId: Option[String], projects: List[(String, String)]): Frag =
    projectId match
      case Some(pid) =>
        val projectName = projects.find(_._1 == pid).map(_._2).getOrElse(pid)
        div(cls := "rounded-lg border border-indigo-500/20 bg-indigo-500/[0.06] px-4 py-3")(
          div(cls := "flex items-center gap-3")(
            span(cls := "text-xs font-semibold uppercase tracking-[0.18em] text-indigo-300")("Target project"),
            span(cls := "text-sm font-semibold text-white")(projectName),
          )
        )
      case None      =>
        if projects.isEmpty then
          div(cls := "rounded-lg border border-amber-400/20 bg-amber-500/[0.06] px-4 py-3")(
            p(cls := "text-sm text-amber-200")(
              "No project selected. ",
              a(href := "/projects", cls := "underline hover:text-white")("Create a project"),
              " first, then use the \"From Template\" link.",
            )
          )
        else
          div(cls := "rounded-lg border border-white/10 bg-slate-900/70 px-4 py-3")(
            div(cls := "flex items-center gap-3")(
              label(cls := "text-xs font-semibold uppercase tracking-[0.18em] text-slate-400")("Target project"),
              select(
                id  := "project-selector",
                cls := "rounded-md border border-white/10 bg-black/20 px-3 py-1.5 text-sm text-slate-100",
              )(
                projects.map {
                  case (pid, pname) =>
                    option(value := pid)(pname)
                }*
              ),
            )
          )

  private def templateSelector: Frag =
    div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3")(
      templates.map(templateCard)*
    )

  private def templateCard(template: TemplateInfo): Frag =
    button(
      `type`                       := "button",
      attr("data-template-button") := template.id,
      attr("aria-controls")        := s"workspace-template-panel-${template.id}",
      attr("aria-selected")        := (template.id == defaultTemplateId).toString,
      onclick                      := s"window.__workspaceTemplateSelect && window.__workspaceTemplateSelect('${template.id}'); return false;",
      cls                          := selectorButtonClasses(template.id == defaultTemplateId),
    )(
      div(cls := "flex items-start justify-between gap-3")(
        div(
          h3(cls := "text-sm font-semibold text-white")(template.name),
          p(cls := "mt-1 text-xs leading-relaxed text-gray-400")(template.description),
        ),
        span(cls := s"shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold ${template.stackClasses}")(
          template.stackLabel
        ),
      ),
      ul(cls := "flex-1 space-y-1")(
        template.cardFeatures.map { item =>
          li(cls := "flex items-center gap-2 text-xs text-gray-400")(
            span(cls := "shrink-0 text-emerald-500")("✓"),
            span(item),
          )
        }*
      ),
      div(cls := "flex items-center gap-3 border-t border-white/8 pt-2")(
        a(
          href   := s"/static/skills/workspace-template/references/${template.id}.md",
          target := "_blank",
          cls    := "text-xs text-indigo-400 hover:text-indigo-300",
        )("View reference ↗"),
        span(cls := "text-white/20")("·"),
        span(cls := "font-mono text-[10px] text-gray-500")(template.buildCommand),
      ),
    )

  private def selectorButtonClasses(selected: Boolean): String =
    val base =
      "flex h-full flex-col space-y-4 rounded-lg border border-white/10 bg-white/3 p-5 text-left transition duration-150 focus:outline-none focus:ring-2 focus:ring-indigo-400/70"
    if selected then s"$base border-indigo-500/35 bg-indigo-500/[0.05] shadow-[0_0_0_1px_rgba(99,102,241,0.14)]"
    else s"$base hover:border-white/20 hover:bg-white/[0.05]"

  private def templatePanel(template: TemplateInfo, projectId: Option[String] = None): Frag =
    div(
      id                          := s"workspace-template-panel-${template.id}",
      attr("data-template-panel") := template.id,
      attr("data-template-name")  := template.name,
      cls                         := (if template.id == defaultTemplateId then "block" else "hidden"),
    )(
      form(
        action := "/workspace-templates",
        method := "post",
        cls    := "rounded-2xl border border-white/10 bg-[linear-gradient(180deg,rgba(255,255,255,0.04),rgba(15,23,42,0.14))] p-5 shadow-[0_18px_50px_rgba(15,23,42,0.18)]",
      )(
        input(`type`                    := "hidden", name := "templateId", value := template.id),
        input(
          `type`                        := "hidden",
          name                          := "projectId",
          value                         := projectId.getOrElse(""),
          attr("data-project-id-field") := "true",
        ),
        div(cls := "flex flex-col gap-4 border-b border-white/10 pb-5 lg:flex-row lg:items-start lg:justify-between")(
          div(
            p(cls := "text-[11px] font-semibold uppercase tracking-[0.26em] text-slate-500")("Selected template"),
            h2(cls := "mt-2 text-xl font-semibold text-white")(template.name),
            p(cls := "mt-2 max-w-3xl text-sm leading-6 text-slate-400")(template.description),
          ),
          div(cls := "flex flex-wrap items-center gap-2")(
            span(
              cls := s"rounded-full border px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] ${template.stackClasses}"
            )(
              template.stackLabel
            ),
            span(
              cls := "rounded-full border border-white/10 bg-white/4 px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-300"
            )(template.focusLabel),
            a(
              href   := s"/static/skills/workspace-template/references/${template.id}.md",
              target := "_blank",
              cls    := "rounded-full border border-white/10 px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-indigo-300 hover:bg-white/5",
            )(template.referenceLabel),
          ),
        ),
        div(cls := "mt-6 grid grid-cols-1 gap-5 xl:grid-cols-[1.08fr_0.92fr]")(
          wizardCard(template),
          liveBriefCard(template),
        ),
      )
    )

  private def wizardCard(template: TemplateInfo): Frag =
    div(
      cls := "rounded-2xl border border-white/10 bg-[linear-gradient(180deg,rgba(2,6,23,0.52),rgba(15,23,42,0.34))] p-5 shadow-[0_12px_36px_rgba(2,6,23,0.18)]"
    )(
      div(cls := "border-b border-white/10 pb-5")(
        p(cls := "text-sm font-semibold text-white")("Workspace Prompt"),
        p(cls := "mt-1 text-sm text-slate-400")(
          "Start from the user request. The wizard keeps this prompt visible while you move through the seven standard questions."
        ),
        textarea(
          name                               := "prompt",
          cls                                := textAreaClasses,
          rows                               := 6,
          attr("data-sync-field")            := "prompt",
          attr("data-template-prompt-input") := "true",
        )(template.prompt),
      ),
      div(cls := "pt-5", attr("data-wizard-root") := "true")(
        div(cls := "flex flex-wrap items-center justify-between gap-3")(
          div(
            p(cls := "text-sm font-semibold text-white")("Template Wizard"),
            p(cls := "mt-1 text-sm text-slate-400", attr("data-wizard-current") := "true")("Question 1 of 7"),
          ),
          div(cls := "flex items-center gap-2")(
            button(
              `type`                   := "button",
              cls                      := secondaryButtonClasses + " disabled:cursor-not-allowed disabled:opacity-40",
              attr("data-wizard-prev") := "true",
            )("Back"),
            button(
              `type`                   := "button",
              cls                      := primaryButtonClasses,
              attr("data-wizard-next") := "true",
            )("Next question"),
          ),
        ),
        div(cls := "mt-4")(
          div(cls := "h-1.5 rounded-full bg-white/8")(
            div(
              cls                          := "h-full rounded-full bg-indigo-400 transition-all duration-200",
              style                        := "width: 14.2857%;",
              attr("data-wizard-progress") := "true",
            )
          )
        ),
        div(cls := "mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7")(
          template.questions.map(questionNavButton)*
        ),
        div(cls := "mt-5 space-y-4")(
          promptStepSection(template, "name"),
          promptStepSection(template, "path"),
          promptStepSection(template, "description"),
          promptStepSection(template, "stack"),
          promptStepSection(template, "features"),
          promptStepSection(template, "cliTool"),
          promptStepSection(template, "runMode"),
        ),
      ),
    )

  private def questionNavButton(question: QuestionPreview): Frag =
    button(
      `type`                  := "button",
      cls                     := wizardNavClasses(active = question.number == "1"),
      attr("data-wizard-nav") := question.number,
      attr("data-step-index") := (question.number.toInt - 1).toString,
    )(
      span(cls := "text-[10px] font-semibold uppercase tracking-[0.18em]")(question.number),
      span(cls := "mt-1 block text-xs")(question.label),
    )

  private def promptStepSection(template: TemplateInfo, field: String): Frag =
    val question = questionFor(template, field)
    div(
      cls                      := (if question.number == "1" then "block" else "hidden"),
      attr("data-wizard-step") := question.number,
      attr("data-step-index")  := (question.number.toInt - 1).toString,
    )(
      div(cls := "rounded-lg border border-white/10 bg-white/[0.03] p-4")(
        div(cls := "flex items-start justify-between gap-3")(
          div(
            p(
              cls := "text-xs font-semibold uppercase tracking-[0.18em] text-slate-500"
            )(s"Question ${question.number}"),
            h3(cls := "mt-1 text-base font-semibold text-white")(question.label),
          ),
          span(
            cls := "rounded-full border border-white/10 bg-black/20 px-2.5 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-300"
          )(
            question.field
          ),
        ),
        p(cls := "mt-2 text-sm leading-6 text-slate-400")(question.helper),
        div(cls := "mt-4")(field match
          case "name"        => nameInput(template)
          case "path"        => pathInput(template)
          case "description" => descriptionInput(template)
          case "stack"       => stackSelection(template)
          case "features"    => featuresInput(template)
          case "cliTool"     => cliToolInput(template)
          case "runMode"     => runModeInput(template)
          case _             => span()),
      )
    )

  private def nameInput(template: TemplateInfo): Frag =
    input(
      `type`                           := "text",
      name                             := "name",
      value                            := singleAnswer(template, "name"),
      cls                              := textInputClasses,
      attr("data-sync-field")          := "name",
      attr("data-template-name-input") := "true",
      placeholder                      := "workspace-name",
    )

  private def pathInput(template: TemplateInfo): Frag =
    div(cls := "space-y-4")(
      input(
        `type`                  := "text",
        name                    := "localPath",
        value                   := singleAnswer(template, "path"),
        cls                     := textInputClasses,
        attr("data-sync-field") := "path",
        placeholder             := "~/workspaces/my-workspace",
      ),
      div(cls := "rounded-lg border border-white/10 bg-black/20 p-4")(
        p(cls := "text-xs font-semibold uppercase tracking-[0.18em] text-slate-500")("Clone from GitHub (optional)"),
        p(cls := "mt-1 text-sm text-slate-400")(
          "Provide a repository URL to clone instead of creating an empty directory."
        ),
        input(
          `type`                  := "text",
          name                    := "gitRepoUrl",
          cls                     := textInputClasses + " mt-3",
          attr("data-sync-field") := "gitRepoUrl",
          placeholder             := "https://github.com/org/repo.git",
        ),
      ),
    )

  private def descriptionInput(template: TemplateInfo): Frag =
    textarea(
      name                    := "description",
      cls                     := textAreaClasses,
      rows                    := 4,
      attr("data-sync-field") := "description",
    )(singleAnswer(template, "description"))

  private def stackSelection(template: TemplateInfo): Frag =
    div(cls := "rounded-lg border border-white/10 bg-black/20 p-4")(
      div(cls := "flex items-center justify-between gap-3")(
        div(
          p(cls := "text-sm font-semibold text-white")(template.name),
          p(cls := "mt-1 text-sm text-slate-400")("The stack question is answered by the selected template card above."),
        ),
        span(cls := s"rounded-full border px-2.5 py-1 text-[10px] font-semibold ${template.stackClasses}")(
          template.stackLabel
        ),
      ),
      p(cls := "mt-4 text-sm text-slate-300")(
        "Switching the template card immediately rewrites the stack answer, scaffold defaults, and issue-card suggestions."
      ),
    )

  private def featuresInput(template: TemplateInfo): Frag =
    textarea(
      name                    := "features",
      cls                     := textAreaClasses,
      rows                    := 7,
      attr("data-sync-field") := "features",
    )(multiAnswer(template, "features"))

  private def cliToolInput(template: TemplateInfo): Frag =
    select(
      name                    := "cliTool",
      cls                     := selectClasses,
      attr("data-sync-field") := "cliTool",
    )(
      cliToolOptions.map { optionValue =>
        option(
          value := optionValue,
          if optionValue == singleAnswer(template, "cliTool") then selected := "selected" else (),
        )(
          optionValue
        )
      }*
    )

  private def runModeInput(template: TemplateInfo): Frag =
    val dockerDetailsClasses =
      if singleAnswer(template, "runMode") == "docker" then
        "rounded-lg border border-white/10 bg-black/20 p-4"
      else "hidden rounded-lg border border-white/10 bg-black/20 p-4"

    val cloudDetailsClasses =
      if singleAnswer(template, "runMode") == "cloud" then
        "rounded-lg border border-white/10 bg-black/20 p-4"
      else "hidden rounded-lg border border-white/10 bg-black/20 p-4"

    div(cls := "space-y-4")(
      select(
        name                    := "runModeType",
        cls                     := selectClasses,
        attr("data-sync-field") := "runMode",
      )(
        runModeOptions.map { optionValue =>
          option(
            value := optionValue,
            if optionValue == singleAnswer(template, "runMode") then selected := "selected" else (),
          )(
            optionValue
          )
        }*
      ),
      div(
        cls                        := dockerDetailsClasses,
        attr("data-run-mode-when") := "docker",
      )(
        p(cls := "text-sm font-semibold text-white")("Docker details"),
        div(cls := "mt-3 space-y-3")(
          input(
            `type`                  := "text",
            name                    := "dockerImage",
            value                   := dockerImageFor(template),
            cls                     := textInputClasses,
            attr("data-sync-field") := "dockerImage",
            placeholder             := "ghcr.io/acme/service:latest",
          ),
          input(
            `type`                  := "text",
            name                    := "dockerNetwork",
            value                   := "workspace-network",
            cls                     := textInputClasses,
            attr("data-sync-field") := "dockerNetwork",
            placeholder             := "bridge or custom network",
          ),
        ),
      ),
      div(
        cls                        := cloudDetailsClasses,
        attr("data-run-mode-when") := "cloud",
      )(
        p(cls := "text-sm font-semibold text-white")("Cloud details"),
        div(cls := "mt-3 space-y-3")(
          input(
            `type`                  := "text",
            name                    := "cloudProvider",
            value                   := "aws-fargate",
            cls                     := textInputClasses,
            attr("data-sync-field") := "cloudProvider",
            placeholder             := "provider",
          ),
          input(
            `type`                  := "text",
            name                    := "cloudImage",
            value                   := cloudImageFor(template),
            cls                     := textInputClasses,
            attr("data-sync-field") := "cloudImage",
            placeholder             := "deploy image",
          ),
          input(
            `type`                  := "text",
            name                    := "cloudRegion",
            value                   := "eu-west-1",
            cls                     := textInputClasses,
            attr("data-sync-field") := "cloudRegion",
            placeholder             := "region",
          ),
        ),
      ),
      div(cls := "rounded-lg border border-amber-400/20 bg-amber-500/[0.06] p-4")(
        p(cls := "text-xs font-semibold uppercase tracking-[0.2em] text-amber-200")("Run-mode follow-ups"),
        ul(cls := "mt-3 space-y-2 text-sm leading-6 text-slate-300")(
          template.runModeFollowUps.map(item =>
            li(cls := "flex gap-3")(
              span(cls := "mt-1 text-amber-200")("•"),
              span(item),
            )
          )*
        ),
      ),
    )

  private def liveBriefCard(template: TemplateInfo): Frag =
    div(cls := "space-y-4", attr("data-live-brief") := "true")(
      div(
        cls := "rounded-2xl border border-white/10 bg-[linear-gradient(180deg,rgba(2,6,23,0.5),rgba(15,23,42,0.3))] p-5 shadow-[0_12px_36px_rgba(2,6,23,0.18)]"
      )(
        p(cls := "text-sm font-semibold text-white")("Live Brief"),
        p(cls := "mt-1 text-sm text-slate-400")(
          "The brief updates as the user edits the prompt and wizard answers. This is the payload the skill should hand to scaffolding and issue generation."
        ),
        div(cls := "mt-5 space-y-4")(
          summarySection(
            "Workspace prompt",
            div(
              cls               := "rounded-md border border-white/10 bg-black/30 p-4 font-mono text-[13px] leading-7 text-emerald-200 whitespace-pre-wrap",
              attr("data-bind") := "prompt",
            )(
              template.prompt
            ),
          ),
          summarySection(
            "Workspace profile",
            div(cls := "grid grid-cols-1 gap-3 sm:grid-cols-2")(
              summaryField("Name", "name", singleAnswer(template, "name")),
              summaryField("Path", "path", singleAnswer(template, "path")),
              summaryField("Git repo", "gitRepoUrl", ""),
              summaryField("Stack", "template-name", template.name),
              summaryField("CLI tool", "cliTool", singleAnswer(template, "cliTool")),
              summaryField("Run mode", "runMode", singleAnswer(template, "runMode")),
            ),
          ),
          div(
            cls                        := "hidden rounded-md border border-white/10 bg-black/20 p-4",
            attr("data-run-mode-when") := "docker",
          )(
            p(cls := "text-xs font-semibold uppercase tracking-[0.18em] text-slate-500")("Docker details"),
            div(cls := "mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2")(
              summaryField("Image", "dockerImage", dockerImageFor(template)),
              summaryField("Network", "dockerNetwork", "workspace-network"),
            ),
          ),
          div(
            cls                        := "hidden rounded-md border border-white/10 bg-black/20 p-4",
            attr("data-run-mode-when") := "cloud",
          )(
            p(cls := "text-xs font-semibold uppercase tracking-[0.18em] text-slate-500")("Cloud details"),
            div(cls := "mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2")(
              summaryField("Provider", "cloudProvider", "aws-fargate"),
              summaryField("Image", "cloudImage", cloudImageFor(template)),
              summaryField("Region", "cloudRegion", "eu-west-1"),
            ),
          ),
          summarySection(
            "Description",
            p(cls := "text-sm leading-6 text-slate-300 whitespace-pre-wrap", attr("data-bind") := "description")(
              singleAnswer(template, "description")
            ),
          ),
          summarySection(
            "Features",
            pre(
              cls               := "rounded-md border border-white/10 bg-black/20 p-4 text-sm leading-6 text-slate-300 whitespace-pre-wrap",
              attr("data-bind") := "features",
            )(multiAnswer(template, "features")),
          ),
        ),
      ),
      div(cls := "rounded-2xl border border-white/10 bg-white/3 p-5")(
        div(cls := "flex items-center justify-between gap-3")(
          div(
            p(cls := "text-sm font-semibold text-white")("Scaffold outputs"),
            p(
              cls := "mt-1 text-sm text-slate-400"
            )("What the template should create before the workspace is registered."),
          ),
          span(cls := "font-mono text-[11px] text-slate-500")(template.buildCommand),
        ),
        ul(cls := "mt-4 space-y-2")(
          template.scaffoldOutputs.map(item =>
            li(cls := "flex items-start gap-2 text-sm leading-6 text-slate-300")(
              span(cls := "mt-1 text-emerald-400")("✓"),
              span(item),
            )
          )*
        ),
      ),
      div(cls := "rounded-2xl border border-white/10 bg-white/3 p-5")(
        p(cls := "text-sm font-semibold text-white")(
          "Next issue cards for ",
          span(attr("data-bind") := "name")(singleAnswer(template, "name")),
        ),
        p(cls := "mt-1 text-sm text-slate-400")(
          "The prompt and the feature answer should drive these cards immediately after scaffolding."
        ),
        div(cls := "mt-4 space-y-3")(
          template.issuePreviews.zipWithIndex.map {
            case (issue, index) =>
              div(cls := "rounded-lg border border-white/10 bg-black/20 p-4")(
                div(cls := "flex items-start justify-between gap-3")(
                  div(
                    p(cls := "text-xs uppercase tracking-[0.18em] text-slate-500")(s"Issue ${index + 1}"),
                    h4(cls := "mt-1 text-sm font-semibold text-white")(issue.title),
                  ),
                  span(
                    cls := "rounded-full border border-indigo-500/20 bg-indigo-500/10 px-2.5 py-1 text-[10px] font-semibold text-indigo-200"
                  )(
                    issue.meta
                  ),
                ),
                p(cls := "mt-3 text-sm leading-6 text-slate-400")(issue.summary),
              )
          }*
        ),
      ),
      div(cls := "rounded-2xl border border-emerald-500/20 bg-emerald-500/[0.06] p-5")(
        div(cls := "flex items-center justify-between gap-3")(
          div(
            p(cls := "text-sm font-semibold text-white")("Ready to create"),
            p(cls := "mt-1 text-sm text-slate-400")(
              "Review the brief above, then create the workspace. A scaffolding task will be dispatched automatically."
            ),
          ),
          button(
            `type` := "submit",
            cls    := "rounded-md bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-emerald-500 focus:outline-none focus:ring-2 focus:ring-emerald-400/70",
          )("Create Workspace"),
        )
      ),
    )

  private def summarySection(title: String, content: Frag): Frag =
    div(
      p(cls := "text-xs font-semibold uppercase tracking-[0.18em] text-slate-500")(title),
      div(cls := "mt-2")(content),
    )

  private def summaryField(labelText: String, bind: String, valueText: String): Frag =
    div(cls := "rounded-md border border-white/10 bg-black/20 p-3")(
      p(cls := "text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-500")(labelText),
      p(cls := "mt-2 text-sm text-slate-200 whitespace-pre-wrap", attr("data-bind") := bind)(valueText),
    )

  private def flowOverview: Frag =
    div(cls := "rounded-lg border border-white/10 bg-white/3 p-5")(
      h3(cls := "mb-4 text-sm font-semibold text-white")("How the wizard works"),
      div(cls := "mt-4 grid grid-cols-1 gap-3 sm:grid-cols-5")(
        List(
          ("1", "Prompt", "Capture the user request that should drive the scaffold and issue backlog."),
          ("2", "Gather", "Walk through name, path, description, stack, features, CLI tool, and run mode."),
          ("3", "Scaffold", "Create the project directory, build files, conventions file, and git init."),
          ("4", "Register", "Call POST /api/workspaces with the chosen CLI tool and runtime mode."),
          ("5", "Plan", "Create the next issue cards from the prompt and the feature answer."),
        ).map {
          case (n, title, desc) =>
            div(cls := "flex gap-3")(
              div(
                cls := "flex size-6 shrink-0 items-center justify-center rounded-full bg-indigo-600/30 text-xs font-bold text-indigo-300"
              )(n),
              div(
                p(cls := "text-xs font-semibold text-gray-200")(title),
                p(cls := "mt-0.5 text-xs text-gray-500")(desc),
              ),
            )
        }*
      ),
    )

  private def questionFor(template: TemplateInfo, field: String): QuestionPreview =
    template.questions.find(_.field == field).getOrElse(
      QuestionPreview(
        number = "?",
        label = field,
        field = field,
        answerLines = Nil,
        helper = "",
      )
    )

  private def singleAnswer(template: TemplateInfo, field: String): String =
    questionFor(template, field).answerLines.headOption.getOrElse("")

  private def multiAnswer(template: TemplateInfo, field: String): String =
    questionFor(template, field).answerLines.mkString("\n")

  private def dockerImageFor(template: TemplateInfo): String =
    template.id match
      case "scala3-zio"  => "ghcr.io/acme/payments-api:dev"
      case "spring-boot" => "ghcr.io/acme/order-ops:dev"
      case "react-ts"    => "ghcr.io/acme/customer-portal:dev"
      case _             => "ghcr.io/acme/workspace:dev"

  private def cloudImageFor(template: TemplateInfo): String =
    template.id match
      case "scala3-zio"  => "ghcr.io/acme/payments-api:latest"
      case "spring-boot" => "ghcr.io/acme/order-ops:latest"
      case "react-ts"    => "ghcr.io/acme/customer-portal:latest"
      case _             => "ghcr.io/acme/workspace:latest"

  private val cliToolOptions = List("codex", "claude", "gemini", "copilot", "opencode")
  private val runModeOptions = List("host", "docker", "cloud")

  private val textInputClasses =
    "w-full rounded-md border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-400/60"

  private val textAreaClasses =
    textInputClasses + " min-h-[7rem] resize-y"

  private val selectClasses =
    textInputClasses + " pr-8"

  private val primaryButtonClasses =
    "rounded-md bg-indigo-600 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400/70"

  private val secondaryButtonClasses =
    "rounded-md border border-white/15 px-3 py-2 text-sm text-slate-300 hover:bg-white/5 focus:outline-none focus:ring-2 focus:ring-indigo-400/70"

  private def wizardNavClasses(active: Boolean): String =
    val base =
      "rounded-xl border px-3 py-2.5 text-left transition focus:outline-none focus:ring-2 focus:ring-indigo-400/60"
    if active then s"$base border-indigo-500/40 bg-indigo-500/10 text-white"
    else s"$base border-white/10 bg-black/20 text-slate-400 hover:bg-white/5"

  private def selectorScript: Frag =
    script(raw(
      s"""(function () {
        |  function selectorButtonClass(selected) {
        |    var base = 'flex h-full flex-col space-y-4 rounded-lg border border-white/10 bg-white/3 p-5 text-left transition duration-150 focus:outline-none focus:ring-2 focus:ring-indigo-400/70';
        |    return selected
        |      ? base + ' border-indigo-500/35 bg-indigo-500/[0.05] shadow-[0_0_0_1px_rgba(99,102,241,0.14)]'
        |      : base + ' hover:border-white/20 hover:bg-white/[0.05]';
        |  }
        |
        |  function wizardNavClass(active) {
        |    var base = 'rounded-md border px-2 py-2 text-left transition focus:outline-none focus:ring-2 focus:ring-indigo-400/60';
        |    return active
        |      ? base + ' border-indigo-500/40 bg-indigo-500/10 text-white'
        |      : base + ' border-white/10 bg-black/20 text-slate-400 hover:bg-white/5';
        |  }
        |
        |  function applyBindings(panel) {
        |    var bindings = panel.querySelectorAll('[data-bind]');
        |    bindings.forEach(function (node) {
        |      var key = node.getAttribute('data-bind');
        |      var input = panel.querySelector('[data-sync-field="' + key + '"]');
        |      if (input) {
        |        node.textContent = input.value || '—';
        |      } else if (key === 'template-name') {
        |        node.textContent = panel.getAttribute('data-template-name') || '';
        |      }
        |    });
        |
        |    var runModeInput = panel.querySelector('[data-sync-field="runMode"]');
        |    var mode = runModeInput ? runModeInput.value : 'host';
        |    panel.querySelectorAll('[data-run-mode-when]').forEach(function (node) {
        |      node.classList.toggle('hidden', node.getAttribute('data-run-mode-when') !== mode);
        |    });
        |  }
        |
        |  function initWizard(panel) {
        |    if (panel.dataset.wizardInit === 'true') return;
        |    panel.dataset.wizardInit = 'true';
        |
        |    var root = panel.querySelector('[data-wizard-root]');
        |    if (!root) return;
        |
        |    var steps = Array.prototype.slice.call(root.querySelectorAll('[data-wizard-step]'));
        |    var navs = Array.prototype.slice.call(root.querySelectorAll('[data-wizard-nav]'));
        |    var prev = root.querySelector('[data-wizard-prev]');
        |    var next = root.querySelector('[data-wizard-next]');
        |    var current = root.querySelector('[data-wizard-current]');
        |    var progress = root.querySelector('[data-wizard-progress]');
        |    var activeIndex = 0;
        |
        |    function showStep(index) {
        |      activeIndex = Math.max(0, Math.min(index, steps.length - 1));
        |
        |      steps.forEach(function (step, idx) {
        |        step.classList.toggle('hidden', idx !== activeIndex);
        |      });
        |
        |      navs.forEach(function (nav, idx) {
        |        nav.className = wizardNavClass(idx === activeIndex);
        |      });
        |
        |      if (current) current.textContent = 'Question ' + (activeIndex + 1) + ' of ' + steps.length;
        |      if (progress) progress.style.width = (((activeIndex + 1) / steps.length) * 100) + '%';
        |      if (prev) prev.disabled = activeIndex === 0;
        |      if (next) next.textContent = activeIndex === steps.length - 1 ? 'Review brief' : 'Next question';
        |    }
        |
        |    navs.forEach(function (nav, idx) {
        |      nav.addEventListener('click', function () {
        |        showStep(idx);
        |      });
        |    });
        |
        |    if (prev) {
        |      prev.addEventListener('click', function () {
        |        showStep(activeIndex - 1);
        |      });
        |    }
        |
        |    if (next) {
        |      next.addEventListener('click', function () {
        |        if (activeIndex === steps.length - 1) {
        |          var brief = panel.querySelector('[data-live-brief]');
        |          if (brief && brief.scrollIntoView) brief.scrollIntoView({ behavior: 'smooth', block: 'start' });
        |        } else {
        |          showStep(activeIndex + 1);
        |        }
        |      });
        |    }
        |
        |    panel.querySelectorAll('[data-sync-field]').forEach(function (input) {
        |      input.addEventListener('input', function () { applyBindings(panel); });
        |      input.addEventListener('change', function () { applyBindings(panel); });
        |    });
        |
        |    applyBindings(panel);
        |    showStep(0);
        |  }
        |
        |  function initWorkspaceTemplatePage() {
        |    var buttons = Array.prototype.slice.call(document.querySelectorAll('[data-template-button]'));
        |    var panels = Array.prototype.slice.call(document.querySelectorAll('[data-template-panel]'));
        |    if (!buttons.length || !panels.length) return;
        |
        |    panels.forEach(initWizard);
        |
        |    function selectTemplate(templateId) {
        |      buttons.forEach(function (button) {
        |        var selected = button.getAttribute('data-template-button') === templateId;
        |        button.setAttribute('aria-selected', String(selected));
        |        button.className = selectorButtonClass(selected);
        |      });
        |
        |      panels.forEach(function (panel) {
        |        var selected = panel.getAttribute('data-template-panel') === templateId;
        |        panel.classList.toggle('hidden', !selected);
        |      });
        |    }
        |
        |    window.__workspaceTemplateSelect = selectTemplate;
        |
        |    buttons.forEach(function (button) {
        |      if (button.dataset.templateWizardInit === 'true') return;
        |      button.dataset.templateWizardInit = 'true';
        |      button.addEventListener('click', function () {
        |        selectTemplate(button.getAttribute('data-template-button'));
        |      });
        |    });
        |
        |    var activeButton = document.querySelector('[data-template-button][aria-selected="true"]');
        |    selectTemplate(activeButton ? activeButton.getAttribute('data-template-button') : '$defaultTemplateId');
        |  }
        |
        |  var projectSelector = document.getElementById('project-selector');
        |  if (projectSelector) {
        |    projectSelector.addEventListener('change', function () {
        |      document.querySelectorAll('[data-project-id-field]').forEach(function (field) {
        |        field.value = projectSelector.value;
        |      });
        |    });
        |    // Initialize hidden fields with first project value
        |    document.querySelectorAll('[data-project-id-field]').forEach(function (field) {
        |      if (!field.value) field.value = projectSelector.value;
        |    });
        |  }
        |
        |  if (document.readyState === 'loading') {
        |    document.addEventListener('DOMContentLoaded', initWorkspaceTemplatePage);
        |  } else {
        |    initWorkspaceTemplatePage();
        |  }
        |})();""".stripMargin
    ))
