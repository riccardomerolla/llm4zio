package issues.entity

import java.time.Instant

import zio.schema.{ Schema, derived }

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
