package conversation.entity.api

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

enum MessageType derives JsonCodec, Schema:
  case Text, Code, Error, Status, ToolCall, ToolResult

enum SenderType derives JsonCodec, Schema:
  case User, Assistant, System

enum ConversationMode derives JsonCodec, Schema:
  case Chat, Plan

case class ConversationEntry(
  id: Option[String] = None,
  conversationId: String,
  sender: String,
  senderType: SenderType,
  content: String,
  messageType: MessageType = MessageType.Text,
  metadata: Option[String] = None,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

case class ChatConversation(
  id: Option[String] = None,
  runId: Option[String] = None,
  title: String,
  channel: Option[String] = None,
  description: Option[String] = None,
  status: String = "active",
  messages: List[ConversationEntry] = List.empty,
  createdAt: Instant,
  updatedAt: Instant,
  createdBy: Option[String] = None,
) derives JsonCodec

object ChatConversation:
  private val MaxAutoTitleLength = 60

  def autoTitleFromFirstMessage(content: String): Option[String] =
    val compact = content.trim.replaceAll("\\s+", " ")
    if compact.isEmpty then None
    else if compact.length <= MaxAutoTitleLength then Some(compact)
    else Some(compact.take(MaxAutoTitleLength - 3) + "...")

  def normalizeTitle(raw: String): Option[String] =
    autoTitleFromFirstMessage(raw)

case class SessionContextLink(
  channelName: String,
  sessionKey: String,
  contextJson: String,
  updatedAt: Instant,
) derives JsonCodec

case class StoredSessionContext(
  lastInboundMessageId: Option[String] = None,
  lastOutboundMessageId: Option[String] = None,
  conversationId: Option[Long] = None,
  runId: Option[Long] = None,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

case class StoredSessionContextLink(
  channelName: String,
  sessionKey: String,
  context: StoredSessionContext,
  updatedAt: Instant,
) derives JsonCodec

case class ConversationSessionMeta(
  channelName: String,
  sessionKey: String,
  linkedTaskRunId: Option[String],
  updatedAt: Instant,
) derives JsonCodec

case class ConversationMessageRequest(
  content: String,
  messageType: MessageType = MessageType.Text,
  metadata: Option[String] = None,
) derives JsonCodec

case class ChatConversationCreateRequest(
  title: String,
  description: Option[String] = None,
  runId: Option[String] = None,
) derives JsonCodec
