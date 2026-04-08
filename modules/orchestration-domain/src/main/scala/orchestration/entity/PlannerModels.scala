package orchestration.entity

import zio.json.*

import plan.entity.PlanTaskDraft
import shared.ids.Ids.{ IssueId, PlanId, SpecificationId }

final case class PlannerPlanPreview(
  summary: String,
  issues: List[PlanTaskDraft],
) derives JsonCodec

final case class PlannerPreviewState(
  conversationId: Long,
  workspaceId: Option[String],
  preview: PlannerPlanPreview,
  specificationId: Option[SpecificationId] = None,
  planId: Option[PlanId] = None,
  confirmedIssueIds: Option[List[IssueId]] = None,
  isGenerating: Boolean = false,
  lastError: Option[String] = None,
)
