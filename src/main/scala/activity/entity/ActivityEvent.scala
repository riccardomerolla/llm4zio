package activity.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ ConversationId, EventId, TaskRunId }

sealed trait ActivityEventType derives JsonCodec, Schema
object ActivityEventType:
  case object RunStarted        extends ActivityEventType
  case object RunCompleted      extends ActivityEventType
  case object RunFailed         extends ActivityEventType
  case object RunStateChanged   extends ActivityEventType
  case object AgentAssigned     extends ActivityEventType
  case object MessageSent       extends ActivityEventType
  case object ConfigChanged     extends ActivityEventType
  case object AnalysisStarted   extends ActivityEventType
  case object AnalysisCompleted extends ActivityEventType
  case object AnalysisFailed    extends ActivityEventType

  val values: Array[ActivityEventType] =
    Array(
      RunStarted,
      RunCompleted,
      RunFailed,
      RunStateChanged,
      AgentAssigned,
      MessageSent,
      ConfigChanged,
      AnalysisStarted,
      AnalysisCompleted,
      AnalysisFailed,
    )

final case class ActivityEvent(
  id: EventId,
  eventType: ActivityEventType,
  source: String,
  runId: Option[TaskRunId] = None,
  conversationId: Option[ConversationId] = None,
  agentName: Option[String] = None,
  summary: String,
  payload: Option[String] = None,
  createdAt: Instant,
) derives JsonCodec, Schema
