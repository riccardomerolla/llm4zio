package shared.web

import zio.test.*

object LayoutSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("Layout")(
      test("sidebar contains a Board nav item linking to /issues/board") {
        val html = Layout.page("Test", "/issues/board")()
        assertTrue(
          html.contains("/issues/board"),
          html.contains("Board"),
        )
      },
      test("Board nav item is active when currentPath starts with /issues/board") {
        val html = Layout.page("Test", "/issues/board")()
        assertTrue(html.contains("bg-white/5"))
      },
      test("Board nav item is not active for /issues list path") {
        val html = Layout.page("Test", "/issues")()
        assertTrue(html.contains("/issues/board"))
      },
    )
