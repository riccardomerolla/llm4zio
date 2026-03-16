package shared.web

import zio.test.*

object LayoutSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("Layout")(
      test("sidebar contains OPERATE and CONFIGURE sections with Board, Planner, and Projects links") {
        val html = Layout.page("Test", "/board")()
        assertTrue(
          html.contains("Operate"),
          html.contains("Configure"),
          html.contains("/board"),
          html.contains("/planner"),
          html.contains("/projects"),
          html.contains("Command Center"),
          html.contains("Planner"),
          html.contains("Projects"),
          html.contains("Settings"),
          html.contains("Board"),
        )
      },
      test("Board nav item is active when currentPath starts with /board") {
        val html = Layout.page("Test", "/board")()
        assertTrue(html.contains("bg-white/5"))
      },
      test("Board nav item exists for non-board paths") {
        val html = Layout.page("Test", "/issues")()
        assertTrue(html.contains("/board"))
      },
      test("mobile sidebar toggle and drawer markup are present") {
        val html = Layout.page("Test", "/board")()
        assertTrue(
          html.contains("id=\"mobile-sidebar-open\""),
          html.contains("id=\"mobile-sidebar-close\""),
          html.contains("id=\"mobile-sidebar\""),
          html.contains("id=\"mobile-sidebar-backdrop\""),
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
    )
