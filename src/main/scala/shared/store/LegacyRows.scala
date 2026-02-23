package shared.store

import java.time.Instant

import zio.schema.{ Schema, derived }

// Temporary compatibility types while legacy db package is still in use.
final case class TaskRunRow(
  id: String,
  sourceDir: String,
  outputDir: String,
  status: String,
  workflowId: Option[String],
  currentPhase: Option[String],
  errorMessage: Option[String],
  startedAt: Instant,
  completedAt: Option[Instant],
  totalFiles: Int,
  processedFiles: Int,
  successfulConversions: Int,
  failedConversions: Int,
) derives Schema

final case class TaskReportRow(
  id: String,
  taskRunId: String,
  stepName: String,
  reportType: String,
  content: String,
  createdAt: Instant,
) derives Schema

final case class TaskArtifactRow(
  id: String,
  taskRunId: String,
  stepName: String,
  key: String,
  value: String,
  createdAt: Instant,
) derives Schema

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
) derives Schema

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
) derives Schema

final case class SessionContextRow(
  channelName: String,
  sessionKey: String,
  contextJson: String,
  updatedAt: Instant,
) derives Schema

final case class AgentIssueRow(
  id: String,
  runId: Option[String],
  conversationId: Option[String],
  title: String,
  description: String,
  issueType: String,
  tags: Option[String],
  preferredAgent: Option[String],
  contextPath: Option[String],
  sourceFolder: Option[String],
  priority: String,
  status: String,
  assignedAgent: Option[String],
  assignedAt: Option[Instant],
  completedAt: Option[Instant],
  errorMessage: Option[String],
  resultData: Option[String],
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema

final case class AgentAssignmentRow(
  id: String,
  issueId: String,
  agentName: String,
  status: String,
  assignedAt: Instant,
  startedAt: Option[Instant],
  completedAt: Option[Instant],
  executionLog: Option[String],
  result: Option[String],
) derives Schema

final case class WorkflowRow(
  id: String,
  name: String,
  description: Option[String],
  stepsJson: String,
  isBuiltin: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema

final case class CustomAgentRow(
  id: String,
  name: String,
  displayName: String,
  description: Option[String],
  systemPrompt: String,
  tagsJson: Option[String],
  enabled: Boolean,
  createdAt: Instant,
  updatedAt: Instant,
) derives Schema
