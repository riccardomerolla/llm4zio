package shared.web

import zio.test.*

object LayoutSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("Layout")(
      test("sidebar contains OPERATE and CONFIGURE sections with Board linking to /board") {
        val html = Layout.page("Test", "/board")()
        assertTrue(
          html.contains("Operate"),
          html.contains("Configure"),
          html.contains("/board"),
          html.contains("Command Center"),
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
    )
