package conversation.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.ConversationId

final case class AgentParticipant(
  agentName: String,
  role: AgentRole,
  joinedAt: Instant,
) derives JsonCodec, Schema

enum AgentRole derives JsonCodec, Schema:
  case Author
  case Reviewer
  case Planner
  case Triager
  case Refactorer
  case Human

enum DialogueOutcome derives JsonCodec, Schema:
  case Approved(summary: String)
  case ChangesRequested(comments: List[String])
  case Escalated(reason: String)
  case Completed(summary: String)
  case MaxTurnsReached(turnsUsed: Int)

final case class TurnState(
  conversationId: ConversationId,
  currentParticipant: String,
  turnNumber: Int,
  awaitingResponse: Boolean,
  pausedByHuman: Boolean,
) derives JsonCodec, Schema
