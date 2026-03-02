package workspace.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

/** Immutable domain events for the Workspace aggregate.
  *
  * Events are the source of truth. The `Workspace` read-side projection is rebuilt by folding these events. Adding a
  * new field to `Workspace` requires a new event variant — existing events never change, so EclipseStore's
  * null-field-on-schema-evolution problem cannot occur.
  */
sealed trait WorkspaceEvent derives JsonCodec, Schema:
  def workspaceId: String
  def occurredAt: Instant

object WorkspaceEvent:
  final case class Created(
    workspaceId: String,
    name: String,
    localPath: String,
    defaultAgent: Option[String],
    description: Option[String],
    cliTool: String,
    runMode: RunMode,
    occurredAt: Instant,
  ) extends WorkspaceEvent

  final case class Updated(
    workspaceId: String,
    name: String,
    localPath: String,
    defaultAgent: Option[String],
    description: Option[String],
    cliTool: String,
    runMode: RunMode,
    occurredAt: Instant,
  ) extends WorkspaceEvent

  final case class Enabled(workspaceId: String, occurredAt: Instant)  extends WorkspaceEvent
  final case class Disabled(workspaceId: String, occurredAt: Instant) extends WorkspaceEvent
  final case class Deleted(workspaceId: String, occurredAt: Instant)  extends WorkspaceEvent

/** Immutable domain events for the WorkspaceRun aggregate. */
sealed trait WorkspaceRunEvent derives JsonCodec, Schema:
  def runId: String
  def occurredAt: Instant

object WorkspaceRunEvent:
  final case class Assigned(
    runId: String,
    workspaceId: String,
    parentRunId: Option[String] = None,
    issueRef: String,
    agentName: String,
    prompt: String,
    conversationId: String,
    worktreePath: String,
    branchName: String,
    occurredAt: Instant,
  ) extends WorkspaceRunEvent

  final case class StatusChanged(
    runId: String,
    status: RunStatus,
    occurredAt: Instant,
  ) extends WorkspaceRunEvent

  final case class UserAttached(
    runId: String,
    userId: String,
    occurredAt: Instant,
  ) extends WorkspaceRunEvent

  final case class UserDetached(
    runId: String,
    userId: String,
    occurredAt: Instant,
  ) extends WorkspaceRunEvent

  final case class RunInterrupted(
    runId: String,
    userId: String,
    occurredAt: Instant,
  ) extends WorkspaceRunEvent

  final case class RunResumed(
    runId: String,
    userId: String,
    prompt: String,
    occurredAt: Instant,
  ) extends WorkspaceRunEvent
