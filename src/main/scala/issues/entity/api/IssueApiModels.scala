package issues.entity.api

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

enum IssuePriority derives JsonCodec, Schema:
  case Low, Medium, High, Critical

enum IssueStatus derives JsonCodec, Schema:
  case Open, Assigned, InProgress, Completed, Failed, Skipped

case class AgentIssueView(
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

case class AgentAssignmentView(
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
