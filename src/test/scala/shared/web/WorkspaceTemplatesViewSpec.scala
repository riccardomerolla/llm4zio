package shared.web

import zio.test.*

object WorkspaceTemplatesViewSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("WorkspaceTemplatesViewSpec")(
    test("page renders the wizard-first workspace template experience") {
      val html = WorkspaceTemplatesView.page()
      assertTrue(
        html.contains("Wizard-first flow"),
        html.contains("User prompt to place above the wizard questions"),
        html.contains("Seven standard answers for this template"),
        html.contains("Backlog preview generated from the user prompt"),
      )
    },
    test("page removes install-specific copy and keeps all curated templates available") {
      val html = WorkspaceTemplatesView.page()
      assertTrue(
        !html.contains("Install the wizard skill"),
        html.contains("Scala 3 + ZIO"),
        html.contains("Spring Boot"),
        html.contains("React + TypeScript"),
        html.contains("Alternatives: claude, gemini, copilot, opencode"),
      )
    },
  )
