package conversation.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.ConversationId
import shared.store.{ DataStoreModule, EventStore }

final case class ConversationEventStoreES(dataStore: DataStoreModule.DataStoreService)
  extends EventStore[ConversationId, ConversationEvent]:

  private val typedStore = dataStore.store

  private def eventKey(id: ConversationId, sequence: Long): String = s"events:conversation:${id.value}:$sequence"

  private def eventPrefix(id: ConversationId): String = s"events:conversation:${id.value}:"

  private def sequenceFromKey(prefix: String, key: String): Option[Long] =
    key.stripPrefix(prefix).toLongOption

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def listEventKeys(id: ConversationId, op: String): IO[PersistenceError, List[(Long, String)]] =
    val prefix = eventPrefix(id)
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .map(_.toList.flatMap(key => sequenceFromKey(prefix, key).map(seq => seq -> key)).sortBy(_._1))

  override def append(id: ConversationId, event: ConversationEvent): IO[PersistenceError, Unit] =
    for
      existing <- listEventKeys(id, "appendConversationEvent")
      nextSeq   = existing.lastOption.map(_._1 + 1L).getOrElse(1L)
      _        <- typedStore.store(eventKey(id, nextSeq), event).mapError(storeErr("appendConversationEvent"))
    yield ()

  override def events(id: ConversationId): IO[PersistenceError, List[ConversationEvent]] =
    listEventKeys(id, "conversationEvents")
      .map(_.map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key =>
          typedStore.fetch[String, ConversationEvent](key).mapError(storeErr("conversationEvents"))
        )
      )
      .map(_.flatten)

  override def eventsSince(id: ConversationId, sequence: Long): IO[PersistenceError, List[ConversationEvent]] =
    listEventKeys(id, "conversationEventsSince")
      .map(_.filter(_._1 > sequence).map(_._2))
      .flatMap(keys =>
        ZIO.foreach(keys)(key =>
          typedStore.fetch[String, ConversationEvent](key).mapError(storeErr("conversationEventsSince"))
        )
      )
      .map(_.flatten)

object ConversationEventStoreES:
  val live: ZLayer[DataStoreModule.DataStoreService, Nothing, EventStore[ConversationId, ConversationEvent]] =
    ZLayer.fromFunction(ConversationEventStoreES.apply)
