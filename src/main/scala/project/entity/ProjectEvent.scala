package project.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.ProjectId

sealed trait ProjectEvent derives JsonCodec, Schema:
  def projectId: ProjectId
  def occurredAt: Instant

object ProjectEvent:
  final case class ProjectCreated(
    projectId: ProjectId,
    name: String,
    description: Option[String],
    occurredAt: Instant,
  ) extends ProjectEvent

  final case class ProjectUpdated(
    projectId: ProjectId,
    name: String,
    description: Option[String],
    settings: ProjectSettings,
    occurredAt: Instant,
  ) extends ProjectEvent

  final case class ProjectDeleted(
    projectId: ProjectId,
    occurredAt: Instant,
  ) extends ProjectEvent
