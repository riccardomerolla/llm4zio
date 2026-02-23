package conversation.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ ConversationId, MessageId, TaskRunId }

enum ConversationState derives JsonCodec, Schema:
  case Active(startedAt: Instant)
  case Closed(startedAt: Instant, closedAt: Instant)

enum ChannelInfo derives JsonCodec, Schema:
  case Telegram(channelName: String)
  case Web(sessionId: String)
  case Internal

sealed trait SenderType derives JsonCodec, Schema
object SenderType:
  final case class User()               extends SenderType
  final case class Assistant()          extends SenderType
  final case class System()             extends SenderType
  final case class Unknown(raw: String) extends SenderType

sealed trait MessageType derives JsonCodec, Schema
object MessageType:
  final case class Text()               extends MessageType
  final case class Code()               extends MessageType
  final case class Error()              extends MessageType
  final case class Status()             extends MessageType
  final case class Unknown(raw: String) extends MessageType

final case class Message(
  id: MessageId,
  sender: String,
  senderType: SenderType,
  content: String,
  messageType: MessageType,
  createdAt: Instant,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec, Schema

final case class Conversation(
  id: ConversationId,
  channel: ChannelInfo,
  state: ConversationState,
  title: String,
  description: String,
  messages: List[Message],
  runId: Option[TaskRunId],
  createdBy: Option[String],
) derives JsonCodec, Schema

object Conversation:
  def fromEvents(events: List[ConversationEvent]): Either[String, Conversation] =
    events match
      case Nil => Left("Cannot rebuild Conversation from an empty event stream")
      case _   =>
        events.foldLeft[Either[String, Option[Conversation]]](Right(None)) { (acc, event) =>
          acc.flatMap(current => applyEvent(current, event))
        }.flatMap {
          case Some(conversation) => Right(conversation)
          case None               => Left("Conversation stream did not produce a state")
        }

  private def applyEvent(
    current: Option[Conversation],
    event: ConversationEvent,
  ): Either[String, Option[Conversation]] =
    event match
      case created: ConversationEvent.Created =>
        current match
          case Some(_) => Left(s"Conversation ${created.conversationId.value} already initialized")
          case None    =>
            Right(
              Some(
                Conversation(
                  id = created.conversationId,
                  channel = created.channel,
                  state = ConversationState.Active(created.occurredAt),
                  title = created.title,
                  description = created.description,
                  messages = Nil,
                  runId = created.runId,
                  createdBy = created.createdBy,
                )
              )
            )

      case sent: ConversationEvent.MessageSent =>
        current
          .toRight(s"Conversation ${sent.conversationId.value} not initialized before MessageSent event")
          .map(conversation => Some(conversation.copy(messages = conversation.messages :+ sent.message)))

      case closed: ConversationEvent.Closed =>
        current
          .toRight(s"Conversation ${closed.conversationId.value} not initialized before Closed event")
          .flatMap { conversation =>
            conversation.state match
              case ConversationState.Active(startedAt) =>
                Right(Some(conversation.copy(state = ConversationState.Closed(startedAt, closed.closedAt))))
              case ConversationState.Closed(_, _)      =>
                Left(s"Conversation ${closed.conversationId.value} is already closed")
          }

      case changed: ConversationEvent.ChannelChanged =>
        current
          .toRight(s"Conversation ${changed.conversationId.value} not initialized before ChannelChanged event")
          .map(conversation => Some(conversation.copy(channel = changed.channel)))
