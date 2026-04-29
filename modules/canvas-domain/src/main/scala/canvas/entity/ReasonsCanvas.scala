package canvas.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AnalysisDocId, CanvasId, IssueId, NormProfileId, ProjectId, SafeguardProfileId, SpecificationId, TaskRunId }

enum CanvasStatus derives JsonCodec, Schema:
  case Draft
  case InReview
  case Approved
  case Stale
  case Superseded

enum CanvasAuthorKind derives JsonCodec, Schema:
  case Human
  case Agent

final case class CanvasAuthor(
  kind: CanvasAuthorKind,
  id: String,
  displayName: String,
) derives JsonCodec,
    Schema

enum CanvasSectionId derives JsonCodec, Schema:
  case Requirements
  case Entities
  case Approach
  case Structure
  case Operations
  case Norms
  case Safeguards

final case class CanvasSection(
  content: String,
  lastUpdatedBy: CanvasAuthor,
  lastUpdatedAt: Instant,
) derives JsonCodec,
    Schema

final case class ReasonsSections(
  requirements: CanvasSection,
  entities: CanvasSection,
  approach: CanvasSection,
  structure: CanvasSection,
  operations: CanvasSection,
  norms: CanvasSection,
  safeguards: CanvasSection,
) derives JsonCodec,
    Schema:
  def get(id: CanvasSectionId): CanvasSection = id match
    case CanvasSectionId.Requirements => requirements
    case CanvasSectionId.Entities     => entities
    case CanvasSectionId.Approach     => approach
    case CanvasSectionId.Structure    => structure
    case CanvasSectionId.Operations   => operations
    case CanvasSectionId.Norms        => norms
    case CanvasSectionId.Safeguards   => safeguards

  def updated(id: CanvasSectionId, section: CanvasSection): ReasonsSections = id match
    case CanvasSectionId.Requirements => copy(requirements = section)
    case CanvasSectionId.Entities     => copy(entities = section)
    case CanvasSectionId.Approach     => copy(approach = section)
    case CanvasSectionId.Structure    => copy(structure = section)
    case CanvasSectionId.Operations   => copy(operations = section)
    case CanvasSectionId.Norms        => copy(norms = section)
    case CanvasSectionId.Safeguards   => copy(safeguards = section)

object ReasonsSections:
  def empty(author: CanvasAuthor, at: Instant): ReasonsSections =
    val blank = CanvasSection("", author, at)
    ReasonsSections(blank, blank, blank, blank, blank, blank, blank)

final case class CanvasRevision(
  version: Int,
  sections: ReasonsSections,
  status: CanvasStatus,
  author: CanvasAuthor,
  changedAt: Instant,
  @fieldDefaultValue(Nil) changedSections: List[CanvasSectionId] = Nil,
  @fieldDefaultValue(None) staleReason: Option[String] = None,
) derives JsonCodec,
    Schema

final case class ReasonsCanvas(
  id: CanvasId,
  projectId: ProjectId,
  storyIssueId: Option[IssueId],
  analysisId: Option[AnalysisDocId],
  specificationId: Option[SpecificationId],
  normProfileId: Option[NormProfileId],
  safeguardProfileId: Option[SafeguardProfileId],
  title: String,
  sections: ReasonsSections,
  status: CanvasStatus,
  version: Int,
  revisions: List[CanvasRevision],
  linkedTaskRunIds: List[TaskRunId],
  author: CanvasAuthor,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema

object ReasonsCanvas:
  def fromEvents(events: List[CanvasEvent]): Either[String, ReasonsCanvas] =
    events match
      case Nil => Left("Cannot rebuild ReasonsCanvas from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[ReasonsCanvas]]](Right(None))((acc, e) => acc.flatMap(applyEvent(_, e)))
          .flatMap {
            case Some(canvas) => Right(canvas)
            case None         => Left("Canvas event stream did not produce a state")
          }

  private def applyEvent(
    current: Option[ReasonsCanvas],
    event: CanvasEvent,
  ): Either[String, Option[ReasonsCanvas]] =
    event match
      case created: CanvasEvent.Created            =>
        current match
          case Some(_) => Left(s"Canvas ${created.canvasId.value} already initialized")
          case None    =>
            val initialRevision = CanvasRevision(
              version = 1,
              sections = created.sections,
              status = CanvasStatus.Draft,
              author = created.author,
              changedAt = created.occurredAt,
              changedSections = CanvasSectionId.values.toList,
            )
            Right(
              Some(
                ReasonsCanvas(
                  id = created.canvasId,
                  projectId = created.projectId,
                  storyIssueId = created.storyIssueId,
                  analysisId = created.analysisId,
                  specificationId = created.specificationId,
                  normProfileId = created.normProfileId,
                  safeguardProfileId = created.safeguardProfileId,
                  title = created.title,
                  sections = created.sections,
                  status = CanvasStatus.Draft,
                  version = 1,
                  revisions = List(initialRevision),
                  linkedTaskRunIds = Nil,
                  author = created.author,
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                )
              )
            )

      case sectionUpdated: CanvasEvent.SectionUpdated =>
        current
          .toRight(s"Canvas ${sectionUpdated.canvasId.value} not initialized before SectionUpdated")
          .map { canvas =>
            val updatedSections = sectionUpdated.updates.foldLeft(canvas.sections) { (acc, update) =>
              acc.updated(
                update.sectionId,
                CanvasSection(update.content, sectionUpdated.author, sectionUpdated.occurredAt),
              )
            }
            val nextVersion     = canvas.version + 1
            val newStatus       =
              if canvas.status == CanvasStatus.Approved then CanvasStatus.InReview
              else canvas.status
            val revision        = CanvasRevision(
              version = nextVersion,
              sections = updatedSections,
              status = newStatus,
              author = sectionUpdated.author,
              changedAt = sectionUpdated.occurredAt,
              changedSections = sectionUpdated.updates.map(_.sectionId),
            )
            Some(
              canvas.copy(
                sections = updatedSections,
                status = newStatus,
                version = nextVersion,
                revisions = canvas.revisions :+ revision,
                updatedAt = sectionUpdated.occurredAt,
              )
            )
          }

      case approved: CanvasEvent.Approved =>
        current
          .toRight(s"Canvas ${approved.canvasId.value} not initialized before Approved")
          .map(canvas =>
            Some(
              canvas.copy(
                status = CanvasStatus.Approved,
                updatedAt = approved.occurredAt,
              )
            )
          )

      case stale: CanvasEvent.MarkedStale =>
        current
          .toRight(s"Canvas ${stale.canvasId.value} not initialized before MarkedStale")
          .map(canvas =>
            val nextVersion = canvas.version
            val revision    = canvas.revisions.lastOption match
              case Some(last) => last.copy(staleReason = Some(stale.reason))
              case None       => CanvasRevision(nextVersion, canvas.sections, CanvasStatus.Stale, stale.markedBy, stale.occurredAt, Nil, Some(stale.reason))
            Some(
              canvas.copy(
                status = CanvasStatus.Stale,
                revisions = canvas.revisions.dropRight(1) :+ revision,
                updatedAt = stale.occurredAt,
              )
            )
          )

      case superseded: CanvasEvent.Superseded =>
        current
          .toRight(s"Canvas ${superseded.canvasId.value} not initialized before Superseded")
          .map(canvas =>
            Some(
              canvas.copy(
                status = CanvasStatus.Superseded,
                updatedAt = superseded.occurredAt,
              )
            )
          )

      case linked: CanvasEvent.LinkedToTaskRun =>
        current
          .toRight(s"Canvas ${linked.canvasId.value} not initialized before LinkedToTaskRun")
          .map(canvas =>
            Some(
              canvas.copy(
                linkedTaskRunIds = (canvas.linkedTaskRunIds :+ linked.taskRunId).distinct,
                updatedAt = linked.occurredAt,
              )
            )
          )
