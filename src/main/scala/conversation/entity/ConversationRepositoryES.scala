package conversation.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.ConversationId
import shared.store.{ DataStoreModule, EventStore }

final case class ConversationRepositoryES(
  eventStore: EventStore[ConversationId, ConversationEvent],
  dataStore: DataStoreModule.DataStoreService,
) extends ConversationRepository:

  private val typedStore = dataStore.store

  private def snapshotKey(id: ConversationId): String = s"snapshot:conversation:${id.value}"

  private def snapshotPrefix: String = "snapshot:conversation:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def rebuildSnapshot(id: ConversationId): IO[PersistenceError, Conversation] =
    for
      events       <- eventStore.events(id)
      conversation <- ZIO
                        .fromEither(Conversation.fromEvents(events))
                        .mapError(msg => PersistenceError.SerializationFailed(s"conversation:${id.value}", msg))
      _            <- typedStore.store(snapshotKey(id), conversation).mapError(storeErr("storeConversationSnapshot"))
    yield conversation

  override def append(event: ConversationEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.conversationId, event)
      _ <- rebuildSnapshot(event.conversationId)
    yield ()

  override def get(id: ConversationId): IO[PersistenceError, Conversation] =
    typedStore.fetch[String, Conversation](snapshotKey(id)).mapError(storeErr("getConversationSnapshot")).flatMap {
      case Some(conversation) => ZIO.succeed(conversation)
      case None               =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("conversation", id.value))
          case _   => rebuildSnapshot(id)
        }
    }

  override def list(filter: ConversationFilter): IO[PersistenceError, List[Conversation]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listConversations"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(key =>
          typedStore.fetch[String, Conversation](key).mapError(storeErr("listConversations"))
        )
      )
      .map(_.flatten)
      .map(_.filter(conversationMatches(filter, _)).slice(
        filter.offset.max(0),
        filter.offset.max(0) + filter.limit.max(0),
      ))

  private def conversationMatches(filter: ConversationFilter, conversation: Conversation): Boolean =
    val runMatches     = filter.runId.forall(expected => conversation.runId.contains(expected))
    val channelMatches = filter.channel.forall(_ == conversation.channel)
    val stateMatches   =
      filter.includeClosed || (conversation.state match
        case _: ConversationState.Active => true
        case _                           => false)
    runMatches && channelMatches && stateMatches

object ConversationRepositoryES:
  val live
    : ZLayer[EventStore[ConversationId, ConversationEvent] & DataStoreModule.DataStoreService, Nothing, ConversationRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[ConversationId, ConversationEvent]]
        dataStore  <- ZIO.service[DataStoreModule.DataStoreService]
      yield ConversationRepositoryES(eventStore, dataStore)
    }
