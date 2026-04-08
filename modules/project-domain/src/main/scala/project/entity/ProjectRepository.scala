package project.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import shared.store.DataStoreService

trait ProjectRepository:
  def append(event: ProjectEvent): IO[PersistenceError, Unit]
  def list: IO[PersistenceError, List[Project]]
  def get(id: ProjectId): IO[PersistenceError, Option[Project]]
  def delete(id: ProjectId): IO[PersistenceError, Unit]

object ProjectRepository:
  val live: ZLayer[DataStoreService, Nothing, ProjectRepository] =
    ZLayer.fromFunction(ProjectRepositoryES.apply)

final case class ProjectRepositoryES(
  dataStore: DataStoreService
) extends ProjectRepository:

  private def eventKey(id: ProjectId, seq: Long): String = s"events:project:${id.value}:$seq"
  private def eventPrefix(id: ProjectId): String         = s"events:project:${id.value}:"
  private def allEventsPrefix: String                    = "events:project:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: ProjectEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSeq(eventPrefix(event.projectId), "appendProjectEvent")
      _   <- dataStore
               .store(eventKey(event.projectId, seq), event.toJson)
               .mapError(storeErr("appendProjectEvent"))
    yield ()

  override def list: IO[PersistenceError, List[Project]] =
    allProjectIds("listProjects").flatMap { ids =>
      ZIO.foreach(ids)(id => rebuildProject(id, "listProjects"))
        .map(_.flatten.sortBy(_.name.toLowerCase))
    }

  override def get(id: ProjectId): IO[PersistenceError, Option[Project]] =
    rebuildProject(id, "getProject")

  override def delete(id: ProjectId): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- append(ProjectEvent.ProjectDeleted(id, now))
      _   <- removeByPrefix(eventPrefix(id), "deleteProjectEvents")
    yield ()

  private def allProjectIds(op: String): IO[PersistenceError, List[ProjectId]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(allEventsPrefix))
      .runCollect
      .mapError(storeErr(op))
      .map { keys =>
        keys.toList
          .flatMap { key =>
            val stripped = key.stripPrefix(allEventsPrefix)
            stripped.lastIndexOf(':') match
              case -1  => None
              case idx => Some(ProjectId(stripped.substring(0, idx)))
          }
          .distinct
      }

  private def rebuildProject(id: ProjectId, op: String): IO[PersistenceError, Option[Project]] =
    loadEvents(eventPrefix(id), op).map(Project.fromEvents(_).toOption)

  private def nextSeq(prefix: String, op: String): IO[PersistenceError, Long] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(
    prefix: String,
    op: String,
  ): IO[PersistenceError, List[ProjectEvent]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(keys =>
        keys.toList
          .flatMap(key => key.stripPrefix(prefix).toLongOption.map(seq => seq -> key))
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore
            .fetch[String, String](key)
            .mapError(storeErr(op))
            .flatMap {
              case None       => ZIO.succeed(None)
              case Some(json) =>
                json.fromJson[ProjectEvent] match
                  case Right(event) => ZIO.succeed(Some(event))
                  case Left(err)    =>
                    ZIO.logWarning(s"Skipping project event at key $key due to JSON decode failure: $err").as(None)
            }
        }.map(_.flatten)
      )

  private def removeByPrefix(prefix: String, op: String): IO[PersistenceError, Unit] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .flatMap(keys =>
        ZIO.foreachDiscard(keys)(key => dataStore.remove[String](key).mapError(storeErr(op)))
      )
