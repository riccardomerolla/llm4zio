package issues.entity

import java.time.Instant

import zio.json.JsonCodec
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
