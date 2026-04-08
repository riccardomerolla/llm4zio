package daemon.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.{ DaemonAgentSpecId, ProjectId }
import shared.store.DataStoreService

final case class DaemonAgentSpecRepositoryES(
  dataStore: DataStoreService
) extends DaemonAgentSpecRepository:

  private def key(id: DaemonAgentSpecId): String = s"daemon-spec:${id.value}"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def get(id: DaemonAgentSpecId): IO[PersistenceError, DaemonAgentSpec] =
    dataStore
      .fetch[String, DaemonAgentSpec](key(id))
      .mapError(storeErr("getDaemonSpec"))
      .flatMap(_.fold[IO[PersistenceError, DaemonAgentSpec]](ZIO.fail(PersistenceError.NotFound(
        "daemon_spec",
        id.value,
      )))(ZIO.succeed))

  override def listByProject(projectId: ProjectId): IO[PersistenceError, List[DaemonAgentSpec]] =
    listAll.map(_.filter(_.projectId == projectId))

  override def listAll: IO[PersistenceError, List[DaemonAgentSpec]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith("daemon-spec:"))
      .runCollect
      .mapError(storeErr("listDaemonSpecs"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(key =>
          dataStore.fetch[String, DaemonAgentSpec](key).mapError(storeErr("listDaemonSpecs"))
        ).map(_.flatten)
      )
      .map(_.sortBy(spec => (spec.projectId.value, spec.name.toLowerCase)))

  override def save(spec: DaemonAgentSpec): IO[PersistenceError, Unit] =
    dataStore.store(key(spec.id), spec).mapError(storeErr("saveDaemonSpec"))

  override def delete(id: DaemonAgentSpecId): IO[PersistenceError, Unit] =
    dataStore.remove[String](key(id)).mapError(storeErr("deleteDaemonSpec"))

object DaemonAgentSpecRepositoryES:
  val live: ZLayer[DataStoreService, Nothing, DaemonAgentSpecRepository] =
    ZLayer.fromFunction(DaemonAgentSpecRepositoryES.apply)
