package demo.boundary

import demo.entity.{ DemoConfig, DemoResult, DemoStatus }
import scalatags.Text.all.*
import shared.web.SettingsShell

/**
 * Scalatags view fragments for the Demo mode settings tab and HTMX polling endpoints.
 *
 * Moved from `shared-web` to `demo-domain/boundary` in phase 5A.5. `shared.web.SettingsView.settingsShell` was just a
 * one-line passthrough to `SettingsShell.page` (defined in `shared-web-core`), so the call site is rewritten to point
 * at the core helper directly — this avoids the `demo-domain → shared-web` cycle that would otherwise be required
 * (since `sharedWeb dependsOn demoDomain` already).
 */
object DemoView:

  def demoTab(settings: Map[String, String], flash: Option[String] = None): String =
    val config = DemoConfig.fromSettings(settings)
    SettingsShell.page("demo", "Settings — Demo Mode")(
      flash.map { msg =>
        div(cls := "mb-6 rounded-md bg-green-500/10 border border-green-500/30 p-4")(
          p(cls := "text-sm text-green-400")(msg)
        )
      },
      div(cls := "mb-6 rounded-md bg-amber-500/10 border border-amber-500/20 p-4 text-sm text-amber-300")(
        p("Demo mode replaces the AI layer with a mock provider and simulates agent work with configurable delay. " +
          "All other features (board, governance, decisions, merge) remain fully real.")
      ),
      // Demo settings form
      tag("form")(
        method := "post",
        action := "/settings/demo",
        cls    := "space-y-6 max-w-2xl mb-10",
      )(
        div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6")(
          h3(cls := "text-base font-semibold text-white mb-4")("Demo Configuration"),
          div(cls := "space-y-4")(
            // Enable toggle
            div(cls := "flex items-center justify-between")(
              div(
                label(cls := "text-sm font-medium text-white")(
                  attr("for") := "demo.enabled"
                )("Demo Mode Enabled"),
                p(cls := "text-xs text-gray-400 mt-1")(
                  "When enabled, auto-sets the AI provider to Mock."
                ),
              ),
              input(
                `type` := "checkbox",
                id     := "demo.enabled",
                name   := "demo.enabled",
                cls    := "h-4 w-4 rounded border-white/20 bg-white/5 text-indigo-600 focus:ring-indigo-500",
                if config.enabled then checked else (),
              ),
            ),
            // Issue count
            div(
              label(
                cls         := "block text-sm font-medium text-white mb-2",
                attr("for") := "demo.issueCount",
              )("Issue Count"),
              select(
                name := "demo.issueCount",
                id   := "demo.issueCount",
                cls  := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
              )(
                List(10, 25, 50, 100).map { n =>
                  option(
                    value := n.toString,
                    if config.issueCount == n then selected else (),
                  )(n.toString)
                }
              ),
              p(cls := "mt-1 text-xs text-gray-400")("Number of Spring Boot issues to generate for the demo."),
            ),
            // Agent delay
            div(
              label(
                cls         := "block text-sm font-medium text-white mb-2",
                attr("for") := "demo.agentDelaySeconds",
              )("Agent Delay (seconds)"),
              select(
                name := "demo.agentDelaySeconds",
                id   := "demo.agentDelaySeconds",
                cls  := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
              )(
                List(2, 5, 10, 30).map { n =>
                  option(
                    value := n.toString,
                    if config.agentDelaySeconds == n then selected else (),
                  )(s"${n}s")
                }
              ),
              p(cls := "mt-1 text-xs text-gray-400")("How long each mock agent pauses before committing its work."),
            ),
            // Repo base directory
            div(
              label(
                cls         := "block text-sm font-medium text-white mb-2",
                attr("for") := "demo.repoBaseDir",
              )("Demo Repo Base Directory"),
              input(
                `type`      := "text",
                name        := "demo.repoBaseDir",
                id          := "demo.repoBaseDir",
                value       := config.repoBaseDir,
                placeholder := "/tmp",
                cls         := "block w-full rounded-md bg-white/5 border-0 py-1.5 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6 px-3",
              ),
              p(cls := "mt-1 text-xs text-gray-400")(
                "Parent folder where the demo git repository is created. " +
                  "A subdirectory (llm4zio-demo-…) will be initialised here."
              ),
            ),
          ),
        ),
        div(cls := "flex gap-4 pt-2")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-400",
          )("Save Demo Settings")
        ),
      ),
      // Quick Demo launcher
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 max-w-2xl mb-6")(
        h3(cls := "text-base font-semibold text-white mb-2")("Quick Demo"),
        p(cls := "text-sm text-gray-400 mb-4")(
          "Automates the full ADE workflow in one click: creates a demo project, seeds issues onto the board, " +
            "and dispatches them to mock agents. Watch issues flow through Backlog → Todo → InProgress → Review, " +
            "then approve them to move to Done."
        ),
        div(cls := "flex gap-3 items-center")(
          button(
            `type`               := "button",
            cls                  := "rounded-md bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-50",
            attr("hx-post")      := "/api/demo/quick-start",
            attr("hx-target")    := "#demo-progress",
            attr("hx-swap")      := "innerHTML",
            attr("hx-indicator") := "#demo-spinner",
          )("Start Quick Demo"),
          span(
            id  := "demo-spinner",
            cls := "htmx-indicator text-xs text-gray-400",
          )("Starting…"),
        ),
        div(id := "demo-progress", cls := "mt-4")(),
      ),
      // Status area (HTMX polling)
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 max-w-2xl mb-6")(
        h3(cls := "text-base font-semibold text-white mb-3")("Demo Progress"),
        div(
          id                 := "demo-status",
          attr("hx-get")     := "/api/demo/status",
          attr("hx-trigger") := "every 3s",
          attr("hx-swap")    := "innerHTML",
        )(statusFragment(None)),
      ),
      // Cleanup
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 max-w-2xl")(
        h3(cls := "text-base font-semibold text-white mb-2")("Cleanup"),
        p(cls := "text-sm text-gray-400 mb-4")("Remove the demo project and all associated artifacts."),
        div(id := "demo-cleanup-result")(
          button(
            `type`            := "button",
            cls               := "rounded-md border border-red-500/30 px-4 py-2 text-sm font-semibold text-red-400 hover:bg-red-500/10",
            attr("hx-get")    := "/api/demo/cleanup-confirm",
            attr("hx-target") := "#demo-cleanup-result",
            attr("hx-swap")   := "innerHTML",
          )("Remove Demo Artifacts")
        ),
      ),
    )

  def quickStartFragment(result: DemoResult): String =
    div(cls := "rounded-md bg-green-500/10 border border-green-500/30 p-4")(
      p(cls := "text-sm font-semibold text-green-400")("Quick Demo Started"),
      div(cls := "mt-2 text-xs text-green-300 space-y-1")(
        p(s"Project ID: ${result.projectId}"),
        p(s"Workspace: ${result.workspacePath}"),
        p(s"Issues seeded: ${result.issueCount}"),
        p(s"Estimated time: ~${result.estimatedSeconds}s with mock delay"),
      ),
      p(cls := "mt-3 text-xs text-green-200")(
        "Navigate to the ",
        a(href := "/board", cls := "underline")("Board"),
        " to watch issues flow through the lifecycle.",
      ),
    ).render

  def statusFragment(status: Option[DemoStatus]): String =
    status match
      case None    =>
        p(cls := "text-sm text-gray-500")("No active demo session.").render
      case Some(s) =>
        div(cls := "grid grid-cols-5 gap-4 text-center")(
          statusCell("Total", s.total, "text-gray-300"),
          statusCell("Dispatched", s.dispatched, "text-blue-400"),
          statusCell("In Progress", s.inProgress, "text-yellow-400"),
          statusCell("At Review", s.atReview, "text-orange-400"),
          statusCell("Done", s.done, "text-green-400"),
        ).render

  val cleanupFragment: String =
    div(cls := "rounded-md bg-green-500/10 border border-green-500/30 p-3")(
      p(cls := "text-sm text-green-400")("Demo artifacts removed successfully.")
    ).render

  def cleanupConfirmFragment(status: Option[DemoStatus]): String =
    status match
      case None    =>
        div(cls := "rounded-md bg-amber-500/10 border border-amber-500/30 p-4")(
          p(cls := "text-sm text-amber-300")("No active demo session found. Nothing to clean up.")
        ).render
      case Some(s) =>
        div(cls := "rounded-md bg-red-500/10 border border-red-500/30 p-4 space-y-4")(
          p(cls := "text-sm font-semibold text-red-400")("Confirm deletion"),
          p(cls := "text-sm text-gray-300")(
            "This will permanently delete the project, all workspaces, all board issues, and the on-disk directory:"
          ),
          div(cls := "rounded bg-black/40 px-3 py-2 font-mono text-xs text-red-300 break-all")(s.workspacePath),
          p(cls := "text-sm text-gray-400")("Type the path above to confirm:"),
          tag("form")(
            attr("hx-post")   := "/api/demo/cleanup",
            attr("hx-target") := "#demo-cleanup-result",
            attr("hx-swap")   := "innerHTML",
            cls               := "space-y-3",
          )(
            input(`type`   := "hidden", name := "projectId", value := s.projectId),
            input(
              `type`       := "text",
              name         := "confirmPath",
              placeholder  := s.workspacePath,
              autocomplete := "off",
              cls          := "block w-full rounded-md bg-white/5 border-0 py-1.5 font-mono text-xs text-white shadow-sm ring-1 ring-inset ring-red-500/40 focus:ring-2 focus:ring-inset focus:ring-red-500 px-3",
            ),
            div(cls := "flex gap-3")(
              button(
                `type` := "submit",
                cls    := "rounded-md bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-500",
              )("Delete permanently"),
              button(
                `type`            := "button",
                cls               := "rounded-md border border-white/20 px-4 py-2 text-sm font-semibold text-gray-300 hover:bg-white/5",
                attr("hx-get")    := "/api/demo/cleanup-cancel",
                attr("hx-target") := "#demo-cleanup-result",
                attr("hx-swap")   := "innerHTML",
              )("Cancel"),
            ),
          ),
        ).render

  val cancelCleanupFragment: String =
    div(id := "demo-cleanup-result")(
      button(
        `type`            := "button",
        cls               := "rounded-md border border-red-500/30 px-4 py-2 text-sm font-semibold text-red-400 hover:bg-red-500/10",
        attr("hx-get")    := "/api/demo/cleanup-confirm",
        attr("hx-target") := "#demo-cleanup-result",
        attr("hx-swap")   := "innerHTML",
      )("Remove Demo Artifacts")
    ).render

  def cleanupConfirmErrorFragment(expectedPath: String, typedPath: String): String =
    div(cls := "rounded-md bg-red-500/15 border border-red-500/40 p-4 space-y-3")(
      p(cls := "text-sm font-semibold text-red-400")("Path does not match — deletion cancelled"),
      p(cls := "text-xs text-gray-400")(s"Expected: $expectedPath"),
      p(cls := "text-xs text-red-300")(s"You typed: $typedPath"),
      button(
        `type`            := "button",
        cls               := "mt-1 rounded-md border border-red-500/30 px-3 py-1.5 text-xs font-semibold text-red-400 hover:bg-red-500/10",
        attr("hx-get")    := "/api/demo/cleanup-confirm",
        attr("hx-target") := "#demo-cleanup-result",
        attr("hx-swap")   := "innerHTML",
      )("Try again"),
    ).render

  private def statusCell(label: String, count: Int, textCls: String): Frag =
    div(cls := "rounded-lg bg-white/5 p-3")(
      p(cls := s"text-2xl font-bold $textCls")(count.toString),
      p(cls := "text-xs text-gray-400 mt-1")(label),
    )
