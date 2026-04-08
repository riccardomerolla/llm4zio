package workspace.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import shared.store.DataStoreService

trait WorkspaceRepository:
  def append(event: WorkspaceEvent): IO[PersistenceError, Unit]
  def list: IO[PersistenceError, List[Workspace]]
  def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]
  def get(id: String): IO[PersistenceError, Option[Workspace]]
  def delete(id: String): IO[PersistenceError, Unit]

  def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]
  def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]
  def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]]
  def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]

object WorkspaceRepository:
  val live: ZLayer[DataStoreService, Nothing, WorkspaceRepository] =
    ZLayer.fromZIO(
      for
        svc <- ZIO.service[DataStoreService]
        repo = WorkspaceRepositoryES(svc)
      yield repo
    )

final case class WorkspaceRepositoryES(
  dataStore: DataStoreService
) extends WorkspaceRepository:

  // ── key conventions ──────────────────────────────────────────────────────

  private def wsEventKey(id: String, seq: Long): String = s"events:workspace:$id:$seq"
  private def wsEventPrefix(id: String): String         = s"events:workspace:$id:"
  private def wsAllEventsPrefix: String                 = "events:workspace:"

  private def runEventKey(id: String, seq: Long): String = s"events:workspace-run:$id:$seq"
  private def runEventPrefix(id: String): String         = s"events:workspace-run:$id:"
  private def runAllEventsPrefix: String                 = "events:workspace-run:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  // ── workspace events ─────────────────────────────────────────────────────

  override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSeq(wsEventPrefix(event.workspaceId), "appendWorkspaceEvent")
      json = event.toJson
      _   <- dataStore.store(wsEventKey(event.workspaceId, seq), json).mapError(storeErr("appendWorkspaceEvent"))
    yield ()

  // Read-side: always rebuilt from the event log — no snapshot stored.
  // For the small number of workspaces expected this is instant; it avoids
  // any EclipseStore type-dictionary entry for `Workspace` / `RunMode` / `RunStatus`.

  override def list: IO[PersistenceError, List[Workspace]] =
    allWorkspaceIds("listWorkspaces").flatMap { ids =>
      ZIO.foreach(ids)(id => rebuildWorkspace(id, "listWorkspaces"))
        .map(_.flatten.sortBy(_.name.toLowerCase))
    }

  override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]] =
    list.map(_.filter(_.projectId == projectId))

  override def get(id: String): IO[PersistenceError, Option[Workspace]] =
    rebuildWorkspace(id, "getWorkspace")

  override def delete(id: String): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- append(WorkspaceEvent.Deleted(id, now))
      _   <- removeByPrefix(wsEventPrefix(id), "deleteWorkspaceEvents")
    yield ()

  // ── workspace run events ──────────────────────────────────────────────────

  override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSeq(runEventPrefix(event.runId), "appendRunEvent")
      json = event.toJson
      _   <- dataStore.store(runEventKey(event.runId, seq), json).mapError(storeErr("appendRunEvent"))
    yield ()

  override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
    allRunIds("listRuns").flatMap { ids =>
      ZIO.foreach(ids)(id => rebuildRun(id, "listRuns"))
        .map(_.flatten.filter(_.workspaceId == workspaceId).sortBy(_.createdAt).reverse)
    }

  override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
    val normalizedRef = normalizeIssueRef(issueRef)
    allRunIds("listRunsByIssueRef").flatMap { ids =>
      ZIO.foreach(ids)(id => rebuildRun(id, "listRunsByIssueRef"))
        .map(
          _.flatten
            .filter(run => normalizeIssueRef(run.issueRef) == normalizedRef)
            .sortBy(_.createdAt)
            .reverse
        )
    }

  override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
    rebuildRun(id, "getRun")

  // ── private rebuild helpers ───────────────────────────────────────────────

  /** Collect all distinct workspace IDs that have at least one event. */
  private def allWorkspaceIds(op: String): IO[PersistenceError, List[String]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(wsAllEventsPrefix))
      .runCollect
      .mapError(storeErr(op))
      .map { keys =>
        keys.toList
          .flatMap { key =>
            // key format: events:workspace:<id>:<seq>
            val stripped = key.stripPrefix(wsAllEventsPrefix)
            stripped.lastIndexOf(':') match
              case -1  => None
              case idx => Some(stripped.substring(0, idx))
          }
          .distinct
      }

  /** Collect all distinct run IDs that have at least one event. */
  private def allRunIds(op: String): IO[PersistenceError, List[String]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(runAllEventsPrefix))
      .runCollect
      .mapError(storeErr(op))
      .map { keys =>
        keys.toList
          .flatMap { key =>
            val stripped = key.stripPrefix(runAllEventsPrefix)
            stripped.lastIndexOf(':') match
              case -1  => None
              case idx => Some(stripped.substring(0, idx))
          }
          .distinct
      }

  private def rebuildWorkspace(id: String, op: String): IO[PersistenceError, Option[Workspace]] =
    loadEvents[WorkspaceEvent](wsEventPrefix(id), op).map(Workspace.fromEvents(_).toOption)

  private def rebuildRun(id: String, op: String): IO[PersistenceError, Option[WorkspaceRun]] =
    loadEvents[WorkspaceRunEvent](runEventPrefix(id), op).map(WorkspaceRun.fromEvents(_).toOption)

  private def normalizeIssueRef(issueRef: String): String =
    issueRef.trim.stripPrefix("#")

  // ── helpers ───────────────────────────────────────────────────────────────

  private def nextSeq(prefix: String, op: String): IO[PersistenceError, Long] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map { keys =>
        keys.flatMap(_.stripPrefix(prefix).toLongOption).maxOption.map(_ + 1L).getOrElse(1L)
      }

  // Events are stored as JSON strings (via zio-json).  Storing typed objects would cause
  // EclipseStore's default binary serializer to handle the ADT subtype classes, which
  // creates fresh JVM instances of case objects (RunMode.Host, RunStatus.*) that fail
  // Scala's equals-based pattern matching after deserialization on restart.
  private def loadEvents[E: JsonDecoder](
    prefix: String,
    op: String,
  ): IO[PersistenceError, List[E]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(keys =>
        keys.toList
          .flatMap { key =>
            key.stripPrefix(prefix).toLongOption.map(seq => seq -> key)
          }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys)(key =>
          dataStore.fetch[String, String](key)
            .mapError(storeErr(op))
            .flatMap {
              case None       => ZIO.succeed(None)
              case Some(json) =>
                json.fromJson[E] match
                  case Right(event) => ZIO.succeed(Some(event))
                  case Left(err)    =>
                    ZIO.logWarning(
                      s"Skipping event at key $key — JSON decode failed ($err). " +
                        "This entry may have been stored in an old binary format; " +
                        "delete and re-create the workspace to recover."
                    ).as(None)
            }
        ).map(_.flatten)
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
