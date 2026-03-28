package shared.web

import zio.test.*

object LayoutSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("Layout")(
      test(
        "top nav bar contains core gateway and ADE sections with governance and evolution links"
      ) {
        val html = Layout.page("Test", "/board")()
        assertTrue(
          html.contains("/sdlc"),
          html.contains("/checkpoints"),
          html.contains("/board"),
          html.contains("/governance"),
          html.contains("/evolution"),
          html.contains("/projects"),
          html.contains("/plans"),
          html.contains("/knowledge"),
          html.contains("/daemons"),
          html.contains("/specifications"),
          html.contains("Command Center"),
          html.contains("SDLC Dashboard"),
          html.contains("Checkpoints"),
          html.contains("Governance"),
          html.contains("Evolution"),
          html.contains("Projects"),
          html.contains("Plans"),
          html.contains("Knowledge"),
          html.contains("Daemons"),
          html.contains("Specifications"),
          html.contains("Settings"),
          html.contains("Board"),
          html.contains("data-nav-dropdown"),
        )
      },
      test("board nav item is active when currentPath starts with /board") {
        val html = Layout.page("Test", "/board")()
        assertTrue(
          html.contains("aria-current=\"page\""),
        )
      },
      test("Board nav item exists for non-board paths") {
        val html = Layout.page("Test", "/issues")()
        assertTrue(html.contains("/board"))
      },
      test("ADE items include live badge loaders for board, checkpoints, and decisions") {
        val html = Layout.page("Test", "/decisions", pendingDecisions = Some(3))()
        assertTrue(
          html.contains("/nav/badges/board"),
          html.contains("/nav/badges/checkpoints"),
          html.contains("/nav/badges/decisions"),
          html.contains(">3<"),
        )
      },
      test("top nav bar markup is present and sidebar markup is absent") {
        val html = Layout.page("Test", "/board")()
        assertTrue(
          html.contains("id=\"app-main-shell\""),
          html.contains("pt-10"),
          html.contains("data-nav-dropdown"),
          !html.contains("id=\"mobile-sidebar\""),
          !html.contains("id=\"desktop-sidebar\""),
          !html.contains("id=\"desktop-sidebar-restore\""),
        )
      },
      test("workspace chat groups are collapsed by default and show load more after 10 chats") {
        val chats = (1 to 12).toList.map { idx =>
          Layout.ChatNavItem(
            conversationId = s"c-$idx",
            title = s"Chat $idx",
            href = s"/chat/$idx",
            active = idx == 12,
            messageCount = idx,
            createdAt = java.time.Instant.EPOCH.plusSeconds(idx.toLong),
          )
        }
        val html  = Layout
          .chatWorkspacesTree(
            Layout.ChatWorkspaceNav(
              groups = List(
                Layout.ChatWorkspaceGroup(
                  id = "chat",
                  label = "Chat",
                  chats = chats,
                  expanded = false,
                )
              ),
              renderedAt = java.time.Instant.EPOCH.plusSeconds(1000),
            )
          )
          .render

        assertTrue(
          html.contains("Chat (12)"),
          html.contains("Load 2 more"),
          !html.contains("open=\"open\""),
          html.contains("Chat 12"),
        )
      },
      test("workspace chat items render a Plan badge when flagged") {
        val html = Layout
          .chatWorkspacesTree(
            Layout.ChatWorkspaceNav(
              groups = List(
                Layout.ChatWorkspaceGroup(
                  id = "chat",
                  label = "Chat",
                  chats = List(
                    Layout.ChatNavItem(
                      conversationId = "c-plan",
                      title = "Planner session",
                      href = "/chat/1",
                      active = false,
                      isPlan = true,
                    )
                  ),
                )
              )
            )
          )
          .render

        assertTrue(
          html.contains("Planner session"),
          html.contains("Plan"),
        )
      },
    )
