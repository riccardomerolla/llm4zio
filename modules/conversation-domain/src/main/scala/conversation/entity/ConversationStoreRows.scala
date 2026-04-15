package conversation.entity

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

final case class ConversationRow(
  id: String,
  title: String,
  description: Option[String],
  channelName: Option[String],
  status: String,
  createdAt: Instant,
  updatedAt: Instant,
  runId: Option[String],
  createdBy: Option[String],
  projectId: Option[String] = None,
  workspaceId: Option[String] = None,
) derives JsonCodec, Schema

final case class ChatMessageRow(
  id: String,
  conversationId: String,
  sender: String,
  senderType: String,
  content: String,
  messageType: String,
  metadata: Option[String],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

final case class SessionContextRow(
  channelName: String,
  sessionKey: String,
  contextJson: String,
  updatedAt: Instant,
) derives JsonCodec, Schema
