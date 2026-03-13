package analysis.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AgentId, AnalysisDocId }

sealed trait AnalysisType derives JsonCodec, Schema

object AnalysisType:
  case object CodeReview                extends AnalysisType
  case object Architecture              extends AnalysisType
  case object Security                  extends AnalysisType
  final case class Custom(name: String) extends AnalysisType

final case class AnalysisDoc(
  id: AnalysisDocId,
  workspaceId: String,
  analysisType: AnalysisType,
  content: String,
  filePath: String,
  generatedBy: AgentId,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

object AnalysisDoc:
  def fromEvents(events: List[AnalysisEvent]): Either[String, Option[AnalysisDoc]] =
    events match
      case Nil => Left("Cannot rebuild AnalysisDoc from an empty event stream")
      case _   =>
        events.foldLeft[Either[String, Option[AnalysisDoc]]](Right(None)) { (acc, event) =>
          acc.flatMap(current => applyEvent(current, event))
        }

  private def applyEvent(
    current: Option[AnalysisDoc],
    event: AnalysisEvent,
  ): Either[String, Option[AnalysisDoc]] =
    event match
      case created: AnalysisEvent.AnalysisCreated =>
        current match
          case Some(_) => Left(s"AnalysisDoc ${created.docId.value} already initialized")
          case None    =>
            Right(
              Some(
                AnalysisDoc(
                  id = created.docId,
                  workspaceId = created.workspaceId,
                  analysisType = created.analysisType,
                  content = created.content,
                  filePath = created.filePath,
                  generatedBy = created.generatedBy,
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                )
              )
            )

      case updated: AnalysisEvent.AnalysisUpdated =>
        current
          .toRight(s"AnalysisDoc ${updated.docId.value} not initialized before AnalysisUpdated event")
          .map(doc =>
            Some(
              doc.copy(
                content = updated.content,
                updatedAt = updated.updatedAt,
              )
            )
          )

      case deleted: AnalysisEvent.AnalysisDeleted =>
        current
          .toRight(s"AnalysisDoc ${deleted.docId.value} not initialized before AnalysisDeleted event")
          .map(_ => None)
