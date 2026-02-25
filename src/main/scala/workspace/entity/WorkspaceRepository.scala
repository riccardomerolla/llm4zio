package workspace.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.store.ConfigStoreModule

trait WorkspaceRepository:
  def list: IO[PersistenceError, List[Workspace]]
  def get(id: String): IO[PersistenceError, Option[Workspace]]
  def save(ws: Workspace): IO[PersistenceError, Unit]
  def delete(id: String): IO[PersistenceError, Unit]

  def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]
  def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]
  def saveRun(run: WorkspaceRun): IO[PersistenceError, Unit]
  def updateRunStatus(runId: String, status: RunStatus): IO[PersistenceError, Unit]

object WorkspaceRepository:
  val live: ZLayer[ConfigStoreModule.ConfigStoreService, Nothing, WorkspaceRepository] =
    ZLayer.fromFunction(WorkspaceRepositoryES.apply)

final case class WorkspaceRepositoryES(
  configStore: ConfigStoreModule.ConfigStoreService
) extends WorkspaceRepository:

  private val ts = configStore.store

  private def wsKey(id: String): String  = s"workspace:$id"
  private def runKey(id: String): String = s"workspace-run:$id"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def list: IO[PersistenceError, List[Workspace]] =
    fetchByPrefix[Workspace]("workspace:", "listWorkspaces").map(_.sortBy(_.name.toLowerCase))

  override def get(id: String): IO[PersistenceError, Option[Workspace]] =
    ts.fetch[String, Workspace](wsKey(id)).mapError(storeErr("getWorkspace"))

  override def save(ws: Workspace): IO[PersistenceError, Unit] =
    ts.store(wsKey(ws.id), ws).mapError(storeErr("saveWorkspace"))

  override def delete(id: String): IO[PersistenceError, Unit] =
    ts.remove[String](wsKey(id)).mapError(storeErr("deleteWorkspace"))

  override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
    fetchByPrefix[WorkspaceRun]("workspace-run:", "listRuns")
      .map(_.filter(_.workspaceId == workspaceId).sortBy(_.createdAt).reverse)

  override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
    ts.fetch[String, WorkspaceRun](runKey(id)).mapError(storeErr("getRun"))

  override def saveRun(run: WorkspaceRun): IO[PersistenceError, Unit] =
    ts.store(runKey(run.id), run).mapError(storeErr("saveRun"))

  override def updateRunStatus(runId: String, status: RunStatus): IO[PersistenceError, Unit] =
    for
      existing <- getRun(runId).flatMap(
                    _.fold[IO[PersistenceError, WorkspaceRun]](
                      ZIO.fail(PersistenceError.NotFound("workspace-run", runId))
                    )(ZIO.succeed)
                  )
      now      <- Clock.instant
      _        <- saveRun(existing.copy(status = status, updatedAt = now))
    yield ()

  private def fetchByPrefix[V](
    prefix: String,
    op: String,
  )(using zio.schema.Schema[V]
  ): IO[PersistenceError, List[V]] =
    configStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(key => ts.fetch[String, V](key).mapError(storeErr(op))).map(_.flatten)
      )
