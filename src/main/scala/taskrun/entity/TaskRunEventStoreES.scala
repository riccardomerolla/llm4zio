package taskrun.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.TaskRunId
import shared.store.{ DataStoreModule, EventStore }

final case class TaskRunEventStoreES(dataStore: DataStoreModule.DataStoreService)
  extends EventStore[TaskRunId, TaskRunEvent]:

  private val typedStore = dataStore.store

  private def eventKey(id: TaskRunId, sequence: Long): String =
    s"events:taskrun:${id.value}:$sequence"

  private def eventPrefix(id: TaskRunId): String =
    s"events:taskrun:${id.value}:"

  private def sequenceFromKey(prefix: String, key: String): Option[Long] =
    key.stripPrefix(prefix).toLongOption

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def listEventKeys(id: TaskRunId, op: String): IO[PersistenceError, List[(Long, String)]] =
    val prefix = eventPrefix(id)
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(
        _.toList
          .flatMap(key => sequenceFromKey(prefix, key).map(seq => seq -> key))
          .sortBy(_._1)
      )

  override def append(id: TaskRunId, event: TaskRunEvent): IO[PersistenceError, Unit] =
    for
      existing <- listEventKeys(id, "appendTaskRunEvent")
      nextSeq   = existing.lastOption.map(_._1 + 1L).getOrElse(1L)
      _        <- typedStore.store(eventKey(id, nextSeq), event).mapError(storeErr("appendTaskRunEvent"))
    yield ()

  override def events(id: TaskRunId): IO[PersistenceError, List[TaskRunEvent]] =
    listEventKeys(id, "taskRunEvents")
      .map(_.map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key => typedStore.fetch[String, TaskRunEvent](key).mapError(storeErr("taskRunEvents")))
      )
      .map(_.flatten)

  override def eventsSince(id: TaskRunId, sequence: Long): IO[PersistenceError, List[TaskRunEvent]] =
    listEventKeys(id, "taskRunEventsSince")
      .map(_.filter(_._1 > sequence))
      .map(_.map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key => typedStore.fetch[String, TaskRunEvent](key).mapError(storeErr("taskRunEventsSince")))
      )
      .map(_.flatten)

object TaskRunEventStoreES:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, EventStore[TaskRunId, TaskRunEvent]] =
    ZLayer.fromFunction(TaskRunEventStoreES.apply)
