package conversation.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ ConversationId, TaskRunId }

sealed trait ConversationEvent derives JsonCodec, Schema:
  def conversationId: ConversationId
  def occurredAt: Instant

object ConversationEvent:
  final case class Created(
    conversationId: ConversationId,
    channel: ChannelInfo,
    title: String,
    description: String,
    runId: Option[TaskRunId],
    createdBy: Option[String],
    occurredAt: Instant,
  ) extends ConversationEvent

  final case class MessageSent(
    conversationId: ConversationId,
    message: Message,
    occurredAt: Instant,
  ) extends ConversationEvent

  final case class Closed(
    conversationId: ConversationId,
    closedAt: Instant,
    occurredAt: Instant,
  ) extends ConversationEvent

  final case class ChannelChanged(
    conversationId: ConversationId,
    channel: ChannelInfo,
    occurredAt: Instant,
  ) extends ConversationEvent
