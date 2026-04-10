package board.entity

import java.time.Instant

case class LinkedPlan(
  id: String,
  summary: String,
  status: String,
  taskCount: Int,
  validationStatus: Option[String],
  specificationId: Option[String],
  createdAt: Instant,
)

case class LinkedSpec(
  id: String,
  title: String,
  status: String,
  version: Int,
  author: String,
  contentPreview: String,
  reviewCommentCount: Int,
  createdAt: Instant,
)

case class IssueContext(
  timeline: List[TimelineEntry],
  linkedPlans: List[LinkedPlan],
  linkedSpecs: List[LinkedSpec],
)
