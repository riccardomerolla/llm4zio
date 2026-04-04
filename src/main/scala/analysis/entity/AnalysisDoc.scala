package analysis.entity

import java.time.Instant

import zio.json.{ JsonCodec, JsonDecoder, JsonEncoder }
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AgentId, AnalysisDocId }

sealed trait AnalysisType derives Schema

object AnalysisType:
  case object CodeReview                extends AnalysisType
  case object Architecture              extends AnalysisType
  case object Security                  extends AnalysisType
  final case class Custom(name: String) extends AnalysisType

  /** Normalize a potentially stale (binary-deserialized) AnalysisType back to a fresh JVM instance.
    *
    * EclipseStore binary serialization creates new JVM objects whose `ordinal()` may not match the
    * compile-time derived zio-json codec, causing "Index N out of bounds" errors. Pattern matching
    * on field values (not identity) produces a clean instance safe for JSON encoding.
    */
  def normalize(at: AnalysisType): AnalysisType = at match
    case _: CodeReview.type   => CodeReview
    case _: Architecture.type => Architecture
    case _: Security.type     => Security
    case c: Custom            => Custom(c.name)

  // Hand-rolled codec avoids ordinal-based encoding which breaks after EclipseStore
  // binary deserialization (see WorkspaceRepository.scala line 171 for the same issue).
  // Format matches the derived codec: {"CodeReview":{}} or {"Custom":{"name":"foo"}}
  given JsonCodec[AnalysisType] = JsonCodec(analysisTypeEncoder, analysisTypeDecoder)

  private val analysisTypeEncoder: JsonEncoder[AnalysisType] =
    JsonEncoder[zio.json.ast.Json].contramap { at =>
      import zio.json.ast.Json.*
      normalize(at) match
        case CodeReview   => Obj("CodeReview" -> Obj())
        case Architecture => Obj("Architecture" -> Obj())
        case Security     => Obj("Security" -> Obj())
        case Custom(name) => Obj("Custom" -> Obj("name" -> Str(name)))
    }

  private val analysisTypeDecoder: JsonDecoder[AnalysisType] =
    JsonDecoder[zio.json.ast.Json].mapOrFail {
      case zio.json.ast.Json.Obj(fields) =>
        fields.headOption match
          case Some(("CodeReview", _))   => Right(CodeReview)
          case Some(("Architecture", _)) => Right(Architecture)
          case Some(("Security", _))     => Right(Security)
          case Some(("Custom", zio.json.ast.Json.Obj(innerFields))) =>
            innerFields.collectFirst { case ("name", zio.json.ast.Json.Str(n)) => n } match
              case Some(name) => Right(Custom(name))
              case None       => Left("Custom AnalysisType missing 'name' field")
          case other => Left(s"Unknown AnalysisType variant: $other")
      case other => Left(s"Expected JSON object for AnalysisType, got: $other")
    }

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
                  analysisType = AnalysisType.normalize(created.analysisType),
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
