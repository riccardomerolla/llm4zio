package canvas.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AnalysisDocId, CanvasId, IssueId, NormProfileId, ProjectId, SafeguardProfileId, SpecificationId, TaskRunId }

final case class CanvasSectionUpdate(
  sectionId: CanvasSectionId,
  content: String,
) derives JsonCodec,
    Schema

sealed trait CanvasEvent derives JsonCodec, Schema:
  def canvasId: CanvasId
  def occurredAt: Instant

object CanvasEvent:
  final case class Created(
    canvasId: CanvasId,
    projectId: ProjectId,
    title: String,
    sections: ReasonsSections,
    storyIssueId: Option[IssueId],
    analysisId: Option[AnalysisDocId],
    specificationId: Option[SpecificationId],
    normProfileId: Option[NormProfileId],
    safeguardProfileId: Option[SafeguardProfileId],
    author: CanvasAuthor,
    occurredAt: Instant,
  ) extends CanvasEvent

  final case class SectionUpdated(
    canvasId: CanvasId,
    updates: List[CanvasSectionUpdate],
    author: CanvasAuthor,
    rationale: Option[String],
    occurredAt: Instant,
  ) extends CanvasEvent

  final case class Approved(
    canvasId: CanvasId,
    approvedBy: CanvasAuthor,
    occurredAt: Instant,
  ) extends CanvasEvent

  final case class MarkedStale(
    canvasId: CanvasId,
    reason: String,
    markedBy: CanvasAuthor,
    occurredAt: Instant,
  ) extends CanvasEvent

  final case class Superseded(
    canvasId: CanvasId,
    supersededBy: Option[CanvasId],
    occurredAt: Instant,
  ) extends CanvasEvent

  final case class LinkedToTaskRun(
    canvasId: CanvasId,
    taskRunId: TaskRunId,
    occurredAt: Instant,
  ) extends CanvasEvent
