package project.entity

import java.time.Instant

import zio.*
import zio.json.*
import zio.schema.{ Schema, derived }

import shared.ids.Ids.ProjectId

final case class MergePolicy(
  requireCi: Boolean = false,
  ciCommand: Option[String] = None,
) derives JsonCodec,
    Schema

final case class ProjectSettings(
  defaultAgent: Option[String] = None,
  mergePolicy: MergePolicy = MergePolicy(),
  analysisSchedule: Option[Duration] = None,
  promptTemplateDefaults: Map[String, String] = Map.empty,
) derives JsonCodec,
    Schema

case class Project(
  id: ProjectId,
  name: String,
  description: Option[String],
  settings: ProjectSettings,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema

object Project:
  def fromEvents(events: List[ProjectEvent]): Either[String, Project] =
    events match
      case Nil => Left("Cannot rebuild Project from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[Project]]](Right(None)) { (acc, event) =>
            acc.flatMap(current => applyEvent(current, event))
          }
          .flatMap {
            case Some(project) => Right(project)
            case None          => Left("Project event stream did not produce a state")
          }

  private def applyEvent(current: Option[Project], event: ProjectEvent): Either[String, Option[Project]] =
    event match
      case e: ProjectEvent.ProjectCreated =>
        current match
          case Some(_) => Left(s"Project ${e.projectId.value} already initialised")
          case None    =>
            Right(
              Some(
                Project(
                  id = e.projectId,
                  name = e.name,
                  description = e.description,
                  settings = ProjectSettings(),
                  createdAt = e.occurredAt,
                  updatedAt = e.occurredAt,
                )
              )
            )

      case e: ProjectEvent.ProjectUpdated =>
        current
          .toRight(s"Project ${e.projectId.value} not initialised before ProjectUpdated event")
          .map(project =>
            Some(
              project.copy(
                name = e.name,
                description = e.description,
                settings = e.settings,
                updatedAt = e.occurredAt,
              )
            )
          )

      case _: ProjectEvent.ProjectDeleted =>
        Right(None)
