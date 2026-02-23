package models

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

enum MessageType derives JsonCodec, Schema:
  case Text, Code, Error, Status

enum SenderType derives JsonCodec, Schema:
  case User, Assistant, System

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

case class ConversationSessionMeta(
  channelName: String,
  sessionKey: String,
  linkedTaskRunId: Option[String],
  updatedAt: Instant,
) derives JsonCodec

enum IssuePriority derives JsonCodec, Schema:
  case Low, Medium, High, Critical

enum IssueStatus derives JsonCodec, Schema:
  case Open, Assigned, InProgress, Completed, Failed, Skipped

case class AgentIssue(
  id: Option[String] = None,
  runId: Option[String] = None,
  conversationId: Option[String] = None,
  title: String,
  description: String,
  issueType: String,
  tags: Option[String] = None,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  priority: IssuePriority = IssuePriority.Medium,
  status: IssueStatus = IssueStatus.Open,
  assignedAgent: Option[String] = None,
  assignedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  errorMessage: Option[String] = None,
  resultData: Option[String] = None,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

case class AgentAssignment(
  id: Option[String] = None,
  issueId: String,
  agentName: String,
  status: String = "pending",
  assignedAt: Instant,
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  executionLog: Option[String] = None,
  result: Option[String] = None,
) derives JsonCodec

// Request/Response DTOs
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

case class AgentIssueCreateRequest(
  runId: Option[String] = None,
  title: String,
  description: String,
  issueType: String,
  tags: Option[String] = None,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  priority: IssuePriority = IssuePriority.Medium,
  conversationId: Option[String] = None,
) derives JsonCodec

case class AssignIssueRequest(
  agentName: String
) derives JsonCodec
