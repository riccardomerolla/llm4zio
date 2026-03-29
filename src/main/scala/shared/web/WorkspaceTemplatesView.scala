package shared.web

import scalatags.Text.all.*

object WorkspaceTemplatesView:

  private final case class TemplateInfo(
    id: String,
    name: String,
    description: String,
    stackLabel: String,
    stackColor: String,
    features: List[String],
    buildTool: String,
  )

  private val templates: List[TemplateInfo] = List(
    TemplateInfo(
      id = "scala3-zio",
      name = "Scala 3 + ZIO",
      description =
        "Effect-oriented backend service built on ZIO 2.x. Ideal for REST APIs, event-sourced domains, streaming pipelines, and CLI tools.",
      stackLabel = "Scala 3",
      stackColor = "bg-red-500/15 text-red-300",
      features = List(
        "Scala 3 + ZIO 2.x core",
        "zio-http for REST APIs (optional)",
        "zio-json for serialization (optional)",
        "ZIO Test + sbt test framework",
        "sbt-scalafmt + sbt-scalafix",
        "ZLayer dependency injection",
        "CLAUDE.md with BCE conventions",
      ),
      buildTool = "sbt compile",
    ),
    TemplateInfo(
      id = "spring-boot",
      name = "Spring Boot",
      description =
        "Production-ready enterprise REST API with Spring Boot 3.x and Java 21. Includes layered architecture, validation, and Actuator health endpoints.",
      stackLabel = "Java 21",
      stackColor = "bg-green-500/15 text-green-300",
      features = List(
        "Spring Boot 3.4.x + Java 21",
        "spring-web, spring-validation",
        "Spring Data JPA (optional)",
        "Spring Security 6 / OAuth2 (optional)",
        "spring-boot-actuator",
        "JUnit 5 + Spring Boot Test",
        "CLAUDE.md with layered-arch conventions",
      ),
      buildTool = "./mvnw compile",
    ),
    TemplateInfo(
      id = "react-ts",
      name = "React + TypeScript",
      description =
        "Modern frontend SPA with React 19, TypeScript strict mode, and Vite 6. Batteries-included with Vitest, ESLint, and optional Tailwind CSS.",
      stackLabel = "TypeScript",
      stackColor = "bg-blue-500/15 text-blue-300",
      features = List(
        "React 19 + TypeScript 5 (strict)",
        "Vite 6 dev server + bundler",
        "Vitest + Testing Library",
        "ESLint 9 with react-hooks rules",
        "Tailwind CSS v4 (optional)",
        "Zustand state management (optional)",
        "CLAUDE.md with functional-component conventions",
      ),
      buildTool = "npm install && npm run dev",
    ),
  )

  def page(): String =
    Layout.page("Workspace Templates", "/workspace-templates")(
      div(cls := "space-y-8")(
        Components.pageHeader(
          "Workspace Templates",
          "Bootstrap new projects from scratch — scaffolding, git init, workspace registration, and initial board issues.",
          a(
            href := "/workspaces",
            cls  := "rounded-md border border-white/15 px-3 py-1.5 text-sm text-slate-300 hover:bg-white/5",
          )("← Workspaces"),
        ),

        // Install skill section
        div(cls := "rounded-lg border border-indigo-500/30 bg-indigo-500/5 p-5")(
          div(cls := "flex items-start gap-3")(
            div(cls := "mt-0.5 text-indigo-400 shrink-0")(
              Components.svgIcon(
                "M9.813 15.904 9 18l-.813-2.096L6 15l2.187-.904L9 12l.813 2.096L12 15l-2.187.904ZM17.25 8.25 16.5 10.5l-2.25.75 2.25.75.75 2.25.75-2.25 2.25-.75-2.25-.75-.75-2.25ZM4.5 4.5 4 6l-1.5.5 1.5.5.5 1.5.5-1.5L6.5 6 5 5.5 4.5 4.5Z",
                "size-5",
              )
            ),
            div(cls := "flex-1 min-w-0")(
              p(cls := "text-sm font-semibold text-indigo-300 mb-1")("Install the wizard skill"),
              p(cls := "text-xs text-gray-400 mb-3")(
                "Run once with the gateway active. After install, trigger in Claude Code by saying ",
                span(cls := "font-mono bg-white/10 px-1 rounded text-gray-200")(
                  "\"create a new workspace\""
                ),
                " or ",
                span(cls := "font-mono bg-white/10 px-1 rounded text-gray-200")("\"scaffold project\""),
                ".",
              ),
              pre(
                cls := "overflow-x-auto rounded-md bg-gray-950 border border-white/10 px-4 py-3 text-xs text-green-300 font-mono leading-relaxed"
              )(
                """GATEWAY=http://localhost:8080
SKILL_DIR=~/.claude/skills/workspace-template
mkdir -p "$SKILL_DIR/references"
curl -s "$GATEWAY/static/skills/workspace-template/SKILL.md"                         -o "$SKILL_DIR/SKILL.md"
curl -s "$GATEWAY/static/skills/workspace-template/references/scala3-zio.md"         -o "$SKILL_DIR/references/scala3-zio.md"
curl -s "$GATEWAY/static/skills/workspace-template/references/spring-boot.md"        -o "$SKILL_DIR/references/spring-boot.md"
curl -s "$GATEWAY/static/skills/workspace-template/references/react-ts.md"           -o "$SKILL_DIR/references/react-ts.md""""
              ),
            ),
          ),
        ),

        // Template cards
        div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3")(
          templates.map(templateCard)*
        ),

        // How it works
        div(cls := "rounded-lg border border-white/10 bg-white/3 p-5")(
          h3(cls := "text-sm font-semibold text-white mb-4")("How the wizard works"),
          div(cls := "grid grid-cols-1 gap-3 sm:grid-cols-5")(
            List(
              ("1", "Gather", "Answers 7 questions: name, path, description, stack, features, CLI tool, run mode."),
              ("2", "Scaffold", "Creates project directory, build files, CLAUDE.md, .gitignore, and git init."),
              ("3", "Register", "Calls POST /api/workspaces to register with the gateway."),
              ("4", "Plan", "Generates 5–15 board issues from your feature descriptions using LLM."),
              ("5", "Verify", "Confirms workspace is accessible and board is populated."),
            ).map { case (n, title, desc) =>
              div(cls := "flex gap-3")(
                div(cls := "shrink-0 flex items-center justify-center size-6 rounded-full bg-indigo-600/30 text-xs font-bold text-indigo-300")(n),
                div(
                  p(cls := "text-xs font-semibold text-gray-200")(title),
                  p(cls := "text-xs text-gray-500 mt-0.5")(desc),
                ),
              )
            }*
          ),
        ),
      )
    )

  private def templateCard(t: TemplateInfo): Frag =
    div(cls := "flex flex-col rounded-lg border border-white/10 bg-white/3 p-5 space-y-4")(
      // Header
      div(cls := "flex items-start justify-between gap-2")(
        div(
          h3(cls := "text-sm font-semibold text-white")(t.name),
          p(cls := "mt-1 text-xs text-gray-400 leading-relaxed")(t.description),
        ),
        span(cls := s"shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold ${t.stackColor}")(t.stackLabel),
      ),

      // Feature list
      ul(cls := "space-y-1 flex-1")(
        t.features.map { f =>
          li(cls := "flex items-center gap-2 text-xs text-gray-400")(
            span(cls := "text-emerald-500 shrink-0")("✓"),
            f,
          )
        }*
      ),

      // Footer links
      div(cls := "flex items-center gap-3 pt-2 border-t border-white/8")(
        a(
          href   := s"/static/skills/workspace-template/references/${t.id}.md",
          target := "_blank",
          cls    := "text-xs text-indigo-400 hover:text-indigo-300",
        )("View reference ↗"),
        span(cls := "text-white/20")("·"),
        span(cls := "font-mono text-[10px] text-gray-500")(t.buildTool),
      ),
    )
