package agent.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId
import shared.store.{ DataStoreService, EventStore }

final case class AgentEventStoreES(dataStore: DataStoreService) extends EventStore[AgentId, AgentEvent]:

  private def eventKey(id: AgentId, sequence: Long): String = s"events:agents:${id.value}:$sequence"
  private def eventPrefix(id: AgentId): String              = s"events:agents:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def listEventKeys(id: AgentId, op: String): IO[PersistenceError, List[(Long, String)]] =
    val prefix = eventPrefix(id)
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(_.toList.flatMap(key => key.stripPrefix(prefix).toLongOption.map(seq => seq -> key)).sortBy(_._1))

  override def append(id: AgentId, event: AgentEvent): IO[PersistenceError, Unit] =
    for
      existing <- listEventKeys(id, "appendAgentEvent")
      nextSeq   = existing.lastOption.map(_._1 + 1L).getOrElse(1L)
      _        <- dataStore.store(eventKey(id, nextSeq), event).mapError(storeErr("appendAgentEvent"))
    yield ()

  override def events(id: AgentId): IO[PersistenceError, List[AgentEvent]] =
    listEventKeys(id, "agentEvents")
      .map(_.map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key => dataStore.fetch[String, AgentEvent](key).mapError(storeErr("agentEvents")))
      )
      .map(_.flatten)

  override def eventsSince(id: AgentId, sequence: Long): IO[PersistenceError, List[AgentEvent]] =
    listEventKeys(id, "agentEventsSince")
      .map(_.filter(_._1 > sequence).map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key => dataStore.fetch[String, AgentEvent](key).mapError(storeErr("agentEventsSince")))
      )
      .map(_.flatten)

object AgentEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[AgentId, AgentEvent]] =
    ZLayer.fromFunction(AgentEventStoreES.apply)
