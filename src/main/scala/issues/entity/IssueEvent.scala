package issues.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.annotation.fieldDefaultValue
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AgentId, IssueId }

sealed trait IssueEvent derives JsonCodec, Schema:
  def issueId: IssueId
  def occurredAt: Instant

object IssueEvent:
  final case class Created(
    issueId: IssueId,
    title: String,
    description: String,
    issueType: String,
    priority: String,
    occurredAt: Instant,
    @fieldDefaultValue(Nil) requiredCapabilities: List[String] = Nil,
  ) extends IssueEvent

  final case class Assigned(
    issueId: IssueId,
    agent: AgentId,
    assignedAt: Instant,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class Started(
    issueId: IssueId,
    agent: AgentId,
    startedAt: Instant,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class Completed(
    issueId: IssueId,
    agent: AgentId,
    completedAt: Instant,
    result: String,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class Failed(
    issueId: IssueId,
    agent: AgentId,
    failedAt: Instant,
    errorMessage: String,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class Skipped(
    issueId: IssueId,
    skippedAt: Instant,
    reason: String,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class WorkspaceLinked(
    issueId: IssueId,
    workspaceId: String,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class WorkspaceUnlinked(
    issueId: IssueId,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class TagsUpdated(
    issueId: IssueId,
    tags: List[String],
    occurredAt: Instant,
  ) extends IssueEvent

  final case class Reopened(
    issueId: IssueId,
    reopenedAt: Instant,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class MetadataUpdated(
    issueId: IssueId,
    title: String,
    description: String,
    issueType: String,
    priority: String,
    requiredCapabilities: List[String],
    contextPath: String,
    sourceFolder: String,
    occurredAt: Instant,
  ) extends IssueEvent

  final case class ExternalRefLinked(
    issueId: IssueId,
    externalRef: String,
    externalUrl: Option[String],
    occurredAt: Instant,
  ) extends IssueEvent

  final case class ExternalRefSynced(
    issueId: IssueId,
    @fieldDefaultValue(Map.empty) updatedFields: Map[String, String] = Map.empty,
    occurredAt: Instant,
  ) extends IssueEvent
