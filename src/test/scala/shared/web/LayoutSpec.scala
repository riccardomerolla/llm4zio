package shared.web

import zio.test.*

object LayoutSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("Layout")(
      test("sidebar contains OPERATE and CONFIGURE sections with Board, Projects, and Specifications links") {
        val html = Layout.page("Test", "/board")()
        assertTrue(
          html.contains("Operate"),
          html.contains("Configure"),
          html.contains("/board"),
          html.contains("/projects"),
          html.contains("/specifications"),
          html.contains("Command Center"),
          html.contains("Projects"),
          html.contains("Specifications"),
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
