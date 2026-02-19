package web.views

import zio.test.*

object IssuesViewSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("IssuesViewSpec")(
    test("markdownFragment renders markdown tables as HTML table") {
      val markdown =
        """| name | age |
          || --- | --- |
          || Alice | 30 |
          || Bob | 35 |
          |""".stripMargin

      val rendered = IssuesView.markdownFragment(markdown).render
      assertTrue(
        rendered.contains("<table"),
        rendered.contains("<thead"),
        rendered.contains("<tbody"),
        rendered.contains("Alice"),
      )
    }
  )
