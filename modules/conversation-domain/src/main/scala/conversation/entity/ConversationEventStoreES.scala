package conversation.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.ConversationId
import shared.store.{ DataStoreService, EventStore }

// ConversationEvent is stored as JSON strings (via zio-json),
// not as typed EclipseStore objects, to avoid the default binary serializer
// creating fresh case-object instances (e.g. AgentRole enum members) that
// break Scala pattern matching on restart. Same approach as WorkspaceEvent.
final case class ConversationEventStoreES(dataStore: DataStoreService)
  extends EventStore[ConversationId, ConversationEvent]:

  private def eventKey(id: ConversationId, sequence: Long): String = s"events:conversation:${id.value}:$sequence"

  private def eventPrefix(id: ConversationId): String = s"events:conversation:${id.value}:"

  private def sequenceFromKey(prefix: String, key: String): Option[Long] =
    key.stripPrefix(prefix).toLongOption

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def listEventKeys(id: ConversationId, op: String): IO[PersistenceError, List[(Long, String)]] =
    val prefix = eventPrefix(id)
    dataStore.streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(_.toList.flatMap(key => sequenceFromKey(prefix, key).map(seq => seq -> key)).sortBy(_._1))

  override def append(id: ConversationId, event: ConversationEvent): IO[PersistenceError, Unit] =
    for
      existing <- listEventKeys(id, "appendConversationEvent")
      nextSeq   = existing.lastOption.map(_._1 + 1L).getOrElse(1L)
      _        <- dataStore.store(eventKey(id, nextSeq), event.toJson).mapError(storeErr("appendConversationEvent"))
    yield ()

  override def events(id: ConversationId): IO[PersistenceError, List[ConversationEvent]] =
    listEventKeys(id, "conversationEvents")
      .map(_.map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key =>
          dataStore
            .fetch[String, String](key)
            .mapError(storeErr("conversationEvents"))
            .map(_.flatMap(_.fromJson[ConversationEvent].toOption))
        )
      )
      .map(_.flatten)

  override def eventsSince(id: ConversationId, sequence: Long): IO[PersistenceError, List[ConversationEvent]] =
    listEventKeys(id, "conversationEventsSince")
      .map(_.filter(_._1 > sequence).map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key =>
          dataStore
            .fetch[String, String](key)
            .mapError(storeErr("conversationEventsSince"))
            .map(_.flatMap(_.fromJson[ConversationEvent].toOption))
        )
      )
      .map(_.flatten)

object ConversationEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[ConversationId, ConversationEvent]] =
    ZLayer.fromFunction(ConversationEventStoreES.apply)
