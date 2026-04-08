package shared.web

import zio.test.*

import orchestration.entity.{ PlannerPlanPreview, PlannerPreviewState }
import plan.entity.PlanTaskDraft

object PlanPreviewComponentsSpec extends ZIOSpecDefault:

  private val sampleState = PlannerPreviewState(
    conversationId = 42L,
    workspaceId = Some("ws-1"),
    preview = PlannerPlanPreview(
      summary = "Ship planner improvements",
      issues = List(
        PlanTaskDraft(
          draftId = "issue-1",
          title = "Model",
          description = "Create the model",
          estimate = Some("M"),
        ),
        PlanTaskDraft(
          draftId = "issue-2",
          title = "UI",
          description = "Create the UI",
          dependencyDraftIds = List("issue-1"),
          included = false,
        ),
      ),
    ),
    isGenerating = true,
  )

  def spec: Spec[Any, Nothing] =
    suite("PlanPreviewComponentsSpec")(
      test("planPanelsContent parameterizes urls by basePath") {
        val html = PlanPreviewComponents
          .planPanelsContent(sampleState, "/chat/42/plan", "/chat/42/plan-fragment")
          .render
        assertTrue(
          html.contains("/chat/42/plan-fragment"),
          html.contains("/chat/42/plan/preview"),
          html.contains("/chat/42/plan/preview/add"),
          html.contains("/chat/42/plan/preview/remove"),
        )
      },
      test("issueCard renders draft fields and remove action") {
        val html = PlanPreviewComponents.issueCard("/chat/42/plan", sampleState.preview.issues.head).render
        assertTrue(
          html.contains("""value="issue-1""""),
          html.contains("Create the model"),
          html.contains("""name="estimate""""),
          html.contains("/chat/42/plan/preview/remove"),
        )
      },
      test("planGraph computes batches and recommended order") {
        val graph = PlanPreviewComponents.planGraph(sampleState.preview.issues.map(_.copy(included = true)))
        assertTrue(
          graph.hasIssues,
          graph.mermaid.contains("issue-1"),
          graph.mermaid.contains("issue-2"),
          graph.batches.nonEmpty,
          graph.recommendedOrder.nonEmpty,
        )
      },
      test("topoBatches falls back to a single batch for cycles") {
        val batches = PlanPreviewComponents.topoBatches(
          Map(
            "issue-1" -> List("issue-2"),
            "issue-2" -> List("issue-1"),
          )
        )
        assertTrue(
          batches == List(List("issue-1", "issue-2"))
        )
      },
      test("planner scripts expose mermaid and pending helpers") {
        val streamScript = PlanPreviewComponents.plannerStreamScript(42L, isGenerating = true).render
        val mermaidInit  = PlanPreviewComponents.mermaidInitScript.render
        assertTrue(
          streamScript.contains("planner-messages-42"),
          streamScript.contains("markPending"),
          mermaidInit.contains("window.mermaid.initialize"),
          mermaidInit.contains("planner-plan-panels-"),
        )
      },
      test("planGraph sanitizes mermaid node labels and ids") {
        val drafts = List(
          PlanTaskDraft(
            draftId = "issue:one\nraw",
            title = "Create `get_greeting(name: &str,\nlang: &str) -> String` in src/main.rs [core]",
            description = "desc",
            included = true,
          )
        )
        val graph  = PlanPreviewComponents.planGraph(drafts)
        assertTrue(
          graph.mermaid.contains("node_1_issue_one_raw"),
          !graph.mermaid.contains("\nlang: &str"),
          !graph.mermaid.contains("[core]"),
        )
      },
    )
