package web.views

import scalatags.Text.all.*
import scalatags.Text.svgAttrs.{ d, viewBox }
import scalatags.Text.svgTags.{ path, svg }
import scalatags.Text.tags2.{ nav, title as titleTag }

object Layout:

  def page(pageTitleText: String, currentPath: String = "/")(bodyContent: Frag*): String =
    "<!DOCTYPE html>" + html(cls := "h-full bg-gray-900")(
      head(
        meta(charset := "utf-8"),
        meta(name    := "viewport", content := "width=device-width, initial-scale=1"),
        titleTag(s"$pageTitleText — A-B-Normal"),
        script(src   := "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"),
        script(src   := "https://unpkg.com/htmx.org@2.0.4"),
        script(src   := "https://unpkg.com/htmx-ext-sse@2.0.0/sse.js"),
      ),
      body(cls := "h-full")(
        desktopSidebar(currentPath),
        div(cls := "lg:pl-72")(
          tag("main")(cls := "py-4")(
            div(cls := "px-4 sm:px-6 lg:px-8")(bodyContent)
          )
        ),
      ),
    ).render

  // ---------------------------------------------------------------------------
  // Sidebar
  // ---------------------------------------------------------------------------

  private def desktopSidebar(currentPath: String): Frag =
    div(
      cls := "hidden bg-gray-900 ring-1 ring-white/10 lg:fixed lg:inset-y-0 lg:z-50 lg:flex lg:w-72 lg:flex-col"
    )(
      div(cls := "flex grow flex-col gap-y-5 overflow-y-auto bg-black/10 px-6 pb-4")(
        div(cls := "flex h-16 shrink-0 items-center")(
          span(cls := "text-xl font-bold text-white")("A-B-Normal")
        ),
        nav(cls := "flex flex-1 flex-col")(
          ul(attr("role") := "list", cls := "flex flex-1 flex-col gap-y-7")(
            li(
              ul(attr("role") := "list", cls := "-mx-2 space-y-1")(
                navItem("/", "Dashboard", Icons.home, currentPath == "/"),
                navItem(
                  "/tasks",
                  "Tasks",
                  Icons.folder,
                  currentPath.startsWith("/tasks") && currentPath != "/tasks/new",
                ),
                navItem(
                  "/tasks/new",
                  "New Task",
                  Icons.plusCircle,
                  currentPath == "/tasks/new",
                ),
                navItem(
                  "/workflows",
                  "Workflows",
                  Icons.workflow,
                  currentPath.startsWith("/workflows"),
                ),
              )
            ),
            li(
              div(cls := "text-xs/6 font-semibold text-gray-400")("Analysis"),
              ul(attr("role") := "list", cls := "-mx-2 mt-2 space-y-1")(
                navItem("/reports", "Reports", Icons.document, currentPath.startsWith("/reports")),
                navItem("/graph", "Graph", Icons.chart, currentPath.startsWith("/graph")),
              ),
            ),
            li(
              div(cls := "text-xs/6 font-semibold text-gray-400")("Agents"),
              ul(attr("role") := "list", cls := "-mx-2 mt-2 space-y-1")(
                navItem("/agents", "Agents", Icons.cpuChip, currentPath.startsWith("/agents")),
                navItem("/agent-monitor", "Agent Monitor", Icons.monitor, currentPath.startsWith("/agent-monitor")),
              ),
            ),
            li(
              div(cls := "text-xs/6 font-semibold text-gray-400")("Collaboration"),
              ul(attr("role") := "list", cls := "-mx-2 mt-2 space-y-1")(
                navItem("/chat", "Chat", Icons.chat, currentPath.startsWith("/chat")),
                navItem("/issues", "Issues", Icons.flag, currentPath.startsWith("/issues")),
                navItem("/activity", "Activity", Icons.activity, currentPath.startsWith("/activity")),
                navItem("/health", "Health", Icons.pulse, currentPath.startsWith("/health")),
                navItem("/logs", "Logs", Icons.logs, currentPath.startsWith("/logs")),
              ),
            ),
            li(cls := "mt-auto")(
              ul(attr("role") := "list", cls := "-mx-2 space-y-1")(
                navItem("/config", "Config", Icons.sliders, currentPath.startsWith("/config")),
                navItem("/settings", "Settings", Icons.cog, currentPath == "/settings"),
              )
            ),
          )
        ),
      )
    )

  private def navItem(href: String, label: String, icon: Frag, active: Boolean): Frag =
    val classes =
      if active then "group flex gap-x-3 rounded-md bg-white/5 p-2 text-sm/6 font-semibold text-white"
      else "group flex gap-x-3 rounded-md p-2 text-sm/6 font-semibold text-gray-400 hover:bg-white/5 hover:text-white"
    li(a(attr("href") := href, cls := classes)(icon, label))

  // ---------------------------------------------------------------------------
  // Icons (Heroicons outline 24x24)
  // ---------------------------------------------------------------------------

  private object Icons:
    private def icon(pathD: String): Frag =
      svg(
        viewBox              := "0 0 24 24",
        attr("fill")         := "none",
        attr("stroke")       := "currentColor",
        attr("stroke-width") := "1.5",
        cls                  := "size-6 shrink-0",
      )(path(d := pathD, attr("stroke-linecap") := "round", attr("stroke-linejoin") := "round"))

    val home: Frag = icon(
      "m2.25 12 8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25"
    )

    val folder: Frag = icon(
      "M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z"
    )

    val plusCircle: Frag = icon("M12 9v6m3-3H9m12 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z")

    val workflow: Frag = icon(
      "M4.5 6h6.75m-6.75 6h6.75m-6.75 6h6.75m3.75-10.5L18 6m0 0 2.25 1.5M18 6v4.5m0 3L18 18m0 0 2.25-1.5M18 18v-4.5"
    )

    val document: Frag = icon(
      "M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z"
    )

    val chart: Frag = icon(
      "M10.5 6a7.5 7.5 0 1 0 7.5 7.5h-7.5V6Z M13.5 10.5H21A7.5 7.5 0 0 0 13.5 3v7.5Z"
    )

    val chat: Frag = icon(
      "M2.25 12a8.25 8.25 0 1 1 14.59 5.28L21.75 21l-4.38-2.19A8.21 8.21 0 0 1 12 20.25 8.25 8.25 0 0 1 2.25 12Z"
    )

    val flag: Frag = icon(
      "M3 3v18m0-12h11.25l-1.5 3 1.5 3H3"
    )

    val cpuChip: Frag = icon(
      "M9 3.75H7.5A2.25 2.25 0 0 0 5.25 6v1.5m0 9V18A2.25 2.25 0 0 0 7.5 20.25H9m6-16.5h1.5A2.25 2.25 0 0 1 18.75 6v1.5m0 9V18A2.25 2.25 0 0 1 16.5 20.25H15m-6-13.5h6a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H9a1.5 1.5 0 0 1-1.5-1.5v-6A1.5 1.5 0 0 1 9 6.75Zm-3-1.5V9m0 6v2.25m12-12V9m0 6v2.25M3.75 9H6m12 0h2.25M3.75 15H6m12 0h2.25"
    )

    val monitor: Frag = icon(
      "M3.75 4.5h16.5v10.5H3.75V4.5Zm0 13.5h16.5m-6-3v3m-4.5 0h9"
    )

    val activity: Frag = icon(
      "M12 6v6h4.5m4.5 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
    )

    val pulse: Frag = icon(
      "M3 12h4.5l2.25-6 3.5 12 2.25-6H21"
    )

    val logs: Frag = icon(
      "M3.75 5.25h16.5m-16.5 6h16.5m-16.5 6h10.5"
    )

    val cog: Frag = icon(
      "M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.325.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.241-.438.613-.43.992a7.723 7.723 0 0 1 0 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.955.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 0 1-.22.128c-.331.183-.581.495-.644.869l-.213 1.281c-.09.543-.56.94-1.11.94h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 0 1-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.827c.292-.24.437-.613.43-.991a6.932 6.932 0 0 1 0-.255c.007-.38-.138-.751-.43-.992l-1.004-.827a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.086.22-.128.332-.183.582-.495.644-.869l.214-1.28Z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"
    )

    val sliders: Frag = icon(
      "M10.5 6h9m-15 0h1.5m4.5 0a2.25 2.25 0 1 1-4.5 0 2.25 2.25 0 0 1 4.5 0Zm7.5 12h-9m15 0H18m-4.5 0a2.25 2.25 0 1 1 4.5 0 2.25 2.25 0 0 1-4.5 0ZM6 12h12m-12 0a2.25 2.25 0 1 1-4.5 0A2.25 2.25 0 0 1 6 12Z"
    )
