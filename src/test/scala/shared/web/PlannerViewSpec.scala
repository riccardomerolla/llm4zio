package shared.web

import zio.test.*

import orchestration.control.{ PlannerIssueDraft, PlannerPlanPreview, PlannerPreviewState }

object PlannerViewSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("PlannerViewSpec")(
      test("detail page renders mermaid graph, create-all action, and include toggle") {
        val state = PlannerPreviewState(
          conversationId = 42L,
          workspaceId = Some("ws-1"),
          preview = PlannerPlanPreview(
            summary = "Ship planner improvements",
            issues = List(
              PlannerIssueDraft(
                draftId = "issue-1",
                title = "Model",
                description = "Create the model",
              ),
              PlannerIssueDraft(
                draftId = "issue-2",
                title = "UI",
                description = "Create the UI",
                dependencyDraftIds = List("issue-1"),
                included = false,
              ),
            ),
          ),
        )

        val html = PlannerView.detailPage(state, List("ws-1" -> "Main Workspace"))

        assertTrue(
          html.contains("Create all"),
          html.contains("Execution Graph"),
          html.contains("planner-mermaid"),
          html.contains("Recommended order"),
          html.contains("name=\"included\""),
          html.contains("Excluded"),
        )
      }
    )
