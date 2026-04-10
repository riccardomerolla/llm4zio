package shared.web

import scalatags.Text.all.*
import scalatags.Text.tags2.nav as navTag

object SettingsShell:

  val tabs: List[(String, String)] = List(
    ("ai", "AI Models"),
    ("channels", "Channels"),
    ("gateway", "Gateway"),
    ("issues-templates", "Issue Templates"),
    ("governance", "Governance"),
    ("daemons", "Daemons"),
    ("system", "System"),
    ("advanced", "Advanced Config"),
    ("demo", "Demo"),
  )

  def page(activeTab: String, pageTitle: String)(bodyContent: Frag*): String =
    Layout.page(pageTitle, s"/settings/$activeTab")(
      Components.pageHeader(title = "Settings"),
      div(cls := "border-b border-white/10 mb-6")(
        navTag(cls := "-mb-px flex space-x-6", attr("aria-label") := "Settings tabs")(
          tabs.map {
            case (tab, label) =>
              val isActive = tab == activeTab
              a(
                href := s"/settings/$tab",
                cls  :=
                  (if isActive then
                     "border-b-2 border-indigo-500 py-4 px-1 text-sm font-medium text-white whitespace-nowrap"
                   else
                     "border-b-2 border-transparent py-4 px-1 text-sm font-medium text-gray-400 hover:text-white hover:border-white/30 whitespace-nowrap"),
                if isActive then attr("aria-current") := "page" else (),
              )(label)
          }
        )
      ),
      div(bodyContent*),
    )
