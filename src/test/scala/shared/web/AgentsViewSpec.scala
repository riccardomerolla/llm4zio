package shared.web

import zio.test.*

object AgentsViewSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("AgentsViewSpec")(
      test("new agent form renders trust-level and token-budget registry controls") {
        val html = HtmlViews.newAgentPage(
          values = Map(
            "agentSource"        -> "registry",
            "trustLevel"         -> "Elevated",
            "maxEstimatedTokens" -> "120000",
          )
        )

        assertTrue(
          html.contains("Trust Level"),
          html.contains("name=\"trustLevel\""),
          html.contains("Elevated"),
          html.contains("Token Budget"),
          html.contains("name=\"maxEstimatedTokens\""),
          html.contains("120000"),
        )
      }
    )
