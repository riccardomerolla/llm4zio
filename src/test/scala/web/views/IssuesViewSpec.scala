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
    },
    test("markdownFragment renders inline markdown and breaks inside table cells") {
      val markdown =
        """| Aspect | Details |
          || --- | --- |
          || **Primary** | Line one<br>Line **two** |
          |""".stripMargin

      val rendered = IssuesView.markdownFragment(markdown).render
      assertTrue(
        rendered.contains("<strong>Primary</strong>"),
        rendered.contains("Line one"),
        rendered.contains("Line <strong>two</strong>"),
        rendered.contains("<br"),
      )
    },
  )
