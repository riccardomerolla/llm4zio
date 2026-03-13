package issues.entity.api

import java.time.Instant

import zio.json.*
import zio.schema.{ Schema, derived }

enum IssuePriority derives JsonCodec, Schema:
  case Low, Medium, High, Critical

enum IssueStatus derives JsonCodec, Schema:
  case Backlog, Todo, InProgress, HumanReview, Rework, Merging, Done, Canceled, Duplicated
  // Legacy values kept for backward compatibility with existing data and clients.
  case Open, Assigned, Completed, Failed, Skipped

enum PipelineExecutionMode derives JsonCodec, Schema:
  case Sequential, Parallel

case class AgentIssueView(
  id: Option[String] = None,
  runId: Option[String] = None,
  conversationId: Option[String] = None,
  title: String,
  description: String,
  issueType: String,
  tags: Option[String] = None,
  requiredCapabilities: Option[String] = None,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  workspaceId: Option[String] = None,
  externalRef: Option[String] = None,
  externalUrl: Option[String] = None,
  priority: IssuePriority = IssuePriority.Medium,
  status: IssueStatus = IssueStatus.Backlog,
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
  requiredCapabilities: List[String] = Nil,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  workspaceId: Option[String] = None,
  priority: IssuePriority = IssuePriority.Medium,
  conversationId: Option[String] = None,
) derives JsonCodec

case class AssignIssueRequest(
  agentName: String,
  workspaceId: Option[String] = None,
) derives JsonCodec

case class IssueWorkspaceUpdateRequest(
  workspaceId: Option[String]
) derives JsonCodec

case class IssueStatusUpdateRequest(
  status: IssueStatus,
  agentName: Option[String] = None,
  reason: Option[String] = None,
  resultData: Option[String] = None,
) derives JsonCodec

case class TemplateVariable(
  name: String,
  label: String,
  description: Option[String] = None,
  required: Boolean = true,
  defaultValue: Option[String] = None,
) derives JsonCodec

case class IssueTemplate(
  id: String,
  name: String,
  description: String,
  issueType: String,
  priority: IssuePriority = IssuePriority.Medium,
  tags: List[String] = Nil,
  titleTemplate: String,
  descriptionTemplate: String,
  variables: List[TemplateVariable] = Nil,
  isBuiltin: Boolean = false,
  createdAt: Option[Instant] = None,
  updatedAt: Option[Instant] = None,
) derives JsonCodec

case class IssueTemplateUpsertRequest(
  id: Option[String] = None,
  name: String,
  description: String,
  issueType: String,
  priority: IssuePriority = IssuePriority.Medium,
  tags: List[String] = Nil,
  titleTemplate: String,
  descriptionTemplate: String,
  variables: List[TemplateVariable] = Nil,
) derives JsonCodec

case class CreateIssueFromTemplateRequest(
  runId: Option[String] = None,
  conversationId: Option[String] = None,
  workspaceId: Option[String] = None,
  preferredAgent: Option[String] = None,
  contextPath: Option[String] = None,
  sourceFolder: Option[String] = None,
  variableValues: Map[String, String] = Map.empty,
  overrideTitle: Option[String] = None,
  overrideDescription: Option[String] = None,
) derives JsonCodec

case class BulkIssueAssignRequest(
  issueIds: List[String],
  workspaceId: String,
  agentId: String,
) derives JsonCodec

case class BulkIssueStatusRequest(
  issueIds: List[String],
  status: IssueStatus,
  agentName: Option[String] = None,
  reason: Option[String] = None,
  resultData: Option[String] = None,
) derives JsonCodec

case class BulkIssueTagsRequest(
  issueIds: List[String],
  addTags: List[String] = Nil,
  removeTags: List[String] = Nil,
) derives JsonCodec

case class BulkIssueDeleteRequest(
  issueIds: List[String]
) derives JsonCodec

case class BulkIssueOperationResponse(
  requested: Int,
  succeeded: Int,
  failed: Int,
  errors: List[String] = Nil,
) derives JsonCodec

case class AutoAssignIssueRequest(
  workspaceId: Option[String] = None,
  thresholdPercent: Option[Double] = None,
) derives JsonCodec

case class AutoAssignIssueResponse(
  assigned: Boolean,
  queued: Boolean,
  agentName: Option[String] = None,
  score: Option[Double] = None,
  reason: Option[String] = None,
) derives JsonCodec

case class DispatchStatusResponse(
  waitingForAgent: Boolean = false,
  capabilityMismatch: Boolean = false,
  dependencyBlocked: Boolean = false,
  blockedByIds: List[String] = Nil,
  readyForDispatch: Boolean = false,
  reworkBoosted: Boolean = false,
) derives JsonCodec

case class PipelineStep(
  agentId: String,
  promptOverride: Option[String] = None,
  continueOnFailure: Boolean = false,
) derives JsonCodec

case class AgentPipeline(
  id: String,
  name: String,
  steps: List[PipelineStep],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

case class PipelineCreateRequest(
  name: String,
  steps: List[PipelineStep],
) derives JsonCodec

case class RunPipelineRequest(
  pipelineId: String,
  workspaceId: Option[String] = None,
  mode: PipelineExecutionMode = PipelineExecutionMode.Sequential,
  basePromptOverride: Option[String] = None,
) derives JsonCodec

case class PipelineExecutionRun(
  stepIndex: Int,
  agentId: String,
  runId: String,
  status: String,
) derives JsonCodec

case class RunPipelineResponse(
  executionId: String,
  issueId: String,
  pipelineId: String,
  mode: PipelineExecutionMode,
  status: String,
  runs: List[PipelineExecutionRun],
  message: Option[String] = None,
) derives JsonCodec

case class FolderImportPreviewItem(
  fileName: String,
  title: String,
  issueType: String,
  priority: String,
) derives JsonCodec

case class FolderImportRequest(
  folder: String
) derives JsonCodec

case class GitHubImportPreviewRequest(
  repo: String,
  state: String = "open",
  limit: Int = 50,
) derives JsonCodec

case class GitHubImportPreviewItem(
  number: Long,
  title: String,
  body: String,
  labels: List[String] = Nil,
  state: String,
  url: String,
) derives JsonCodec
