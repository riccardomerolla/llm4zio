package conversation.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ BoardIssueId, ConversationId }

sealed trait DialogueEvent derives JsonCodec, Schema:
  def conversationId: ConversationId
  def occurredAt: Instant

object DialogueEvent:
  final case class DialogueStarted(
    conversationId: ConversationId,
    issueId: BoardIssueId,
    participants: List[AgentParticipant],
    topic: String,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema

  final case class MessagePosted(
    conversationId: ConversationId,
    sender: String,
    senderRole: AgentRole,
    content: String,
    turnNumber: Int,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema

  final case class TurnChanged(
    conversationId: ConversationId,
    nextParticipant: String,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema

  final case class HumanIntervened(
    conversationId: ConversationId,
    userId: String,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema

  final case class DialogueConcluded(
    conversationId: ConversationId,
    outcome: DialogueOutcome,
    occurredAt: Instant,
  ) extends DialogueEvent derives JsonCodec, Schema
