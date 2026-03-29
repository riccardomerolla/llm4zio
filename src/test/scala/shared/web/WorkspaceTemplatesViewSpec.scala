package shared.web

import zio.test.*

object WorkspaceTemplatesViewSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("WorkspaceTemplatesViewSpec")(
    test("page renders the interactive wizard experience") {
      val html = WorkspaceTemplatesView.page()
      assertTrue(
        html.contains("Workspace Prompt"),
        html.contains("Template Wizard"),
        html.contains("Question 1 of 7"),
        html.contains("Next question"),
        html.contains("Live Brief"),
      )
    },
    test("page keeps the cleaner card layout and removes install-specific copy") {
      val html = WorkspaceTemplatesView.page()
      assertTrue(
        !html.contains("Install the wizard skill"),
        html.contains("Scala 3 + ZIO"),
        html.contains("Spring Boot"),
        html.contains("React + TypeScript"),
        html.contains("How the wizard works"),
      )
    },
  )
