package models

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

enum ActivityEventType derives JsonCodec, Schema:
  case RunStarted, RunCompleted, RunFailed, AgentAssigned, MessageSent, ConfigChanged

case class ActivityEvent(
  id: Option[String] = None,
  eventType: ActivityEventType,
  source: String,
  runId: Option[String] = None,
  conversationId: Option[String] = None,
  agentName: Option[String] = None,
  summary: String,
  payload: Option[String] = None,
  createdAt: Instant,
) derives JsonCodec
