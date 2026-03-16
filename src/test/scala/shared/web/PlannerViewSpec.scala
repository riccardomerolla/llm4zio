package shared.web

import zio.test.*

import orchestration.control.{ PlannerIssueDraft, PlannerPlanPreview, PlannerPreviewState }

object PlannerViewSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("PlannerViewSpec")(
      test("start page locks the submit button while the planner session starts") {
        val html = PlannerView.startPage(
          workspaces = List("ws-1" -> "Main Workspace"),
          initialRequest = "Plan the migration",
          selectedWorkspaceId = Some("ws-1"),
          errorMessage = Some("Planner agent failed"),
        )

        assertTrue(
          html.contains("Start planner session"),
          html.contains("Starting planner..."),
          html.contains("cursor-not-allowed"),
          html.contains("aria-busy"),
          html.contains("Planner warning"),
          html.contains("Plan the migration"),
          !html.contains("fields.forEach"),
        )
      },
      test("detail page renders mermaid graph, create-all action, include toggle, and canonical path sync") {
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
                estimate = Some("M"),
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
          isGenerating = true,
        )

        val html = PlannerView.detailPage(
          state = state,
          messages = List(
            conversation.entity.api.ConversationEntry(
              id = Some("m-1"),
              conversationId = "42",
              sender = "user",
              senderType = conversation.entity.api.SenderType.User,
              content = "Break this down",
              messageType = conversation.entity.api.MessageType.Text,
              createdAt = java.time.Instant.EPOCH,
              updatedAt = java.time.Instant.EPOCH,
            )
          ),
          workspaces = List("ws-1" -> "Main Workspace"),
          errorMessage = Some("Planner agent failed"),
          canonicalPath = Some("/planner/42"),
        )

        assertTrue(
          html.contains("Planner Conversation"),
          html.contains("planner-messages-42"),
          html.contains("Create all"),
          html.contains("Execution Graph"),
          html.contains("Open chat"),
          html.contains("Break this down"),
          html.contains("Planner warning"),
          html.contains("Generating preview..."),
          html.contains("/planner/42/plan-fragment"),
          html.contains("chat-message-stream"),
          html.contains("Sending refinement..."),
          html.contains("planner-mermaid"),
          html.contains("Recommended order"),
          html.contains("name=\"included\""),
          html.contains("Estimate"),
          html.contains("value=\"M\""),
          html.contains("Excluded"),
          html.contains("window.history.replaceState"),
          html.contains("/planner/42"),
        )
      },
    )
