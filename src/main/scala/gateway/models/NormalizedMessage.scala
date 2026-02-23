package gateway.models

import java.time.Instant

import zio.*
import zio.json.*

enum MessageDirection derives JsonCodec:
  case Inbound
  case Outbound
  case Internal

enum GatewayMessageRole derives JsonCodec:
  case User
  case Assistant
  case System
  case Tool

case class NormalizedMessage(
  id: String,
  channelName: String,
  sessionKey: SessionKey,
  direction: MessageDirection,
  role: GatewayMessageRole,
  content: String,
  metadata: Map[String, String] = Map.empty,
  timestamp: Instant,
) derives JsonCodec

object NormalizedMessage:
  def userInbound(
    id: String,
    channelName: String,
    sessionKey: SessionKey,
    content: String,
    metadata: Map[String, String] = Map.empty,
  ): UIO[NormalizedMessage] =
    Clock.instant.map { now =>
      NormalizedMessage(
        id = id,
        channelName = channelName,
        sessionKey = sessionKey,
        direction = MessageDirection.Inbound,
        role = GatewayMessageRole.User,
        content = content,
        metadata = metadata,
        timestamp = now,
      )
    }
