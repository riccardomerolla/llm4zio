package gateway.entity

import java.time.Instant

import zio.json.JsonCodec

final case class ChatSession(
  sessionId: String,
  channel: String,
  sessionKey: String,
  agentName: Option[String],
  messageCount: Int,
  lastActivity: Instant,
  state: String,
  conversationId: Option[Long],
  runId: Option[Long],
) derives JsonCodec
