package conversation.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.ConversationId
import shared.store.{ DataStoreService, EventStore }

final case class ConversationRepositoryES(
  eventStore: EventStore[ConversationId, ConversationEvent],
  dataStore: DataStoreService,
) extends ConversationRepository:

  private def snapshotKey(id: ConversationId): String = s"snapshot:conversation:${id.value}"

  private def snapshotPrefix: String = "snapshot:conversation:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def rebuildSnapshot(id: ConversationId): IO[PersistenceError, Conversation] =
    for
      events       <- eventStore.events(id)
      _            <- ZIO.logDebug(s"Rebuilding conversation snapshot for ${id.value} from ${events.size} events")
      conversation <- ZIO
                        .fromEither(Conversation.fromEvents(events))
                        .mapError(msg => PersistenceError.SerializationFailed(s"conversation:${id.value}", msg))
      _            <- dataStore.store(snapshotKey(id), conversation.toJson).mapError(storeErr("storeConversationSnapshot"))
      _            <- ZIO.logDebug(
                        s"Stored conversation snapshot: ${snapshotKey(id)} [state=${conversation.state.getClass.getSimpleName}]"
                      )
    yield conversation

  override def append(event: ConversationEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.conversationId, event)
      _ <- rebuildSnapshot(event.conversationId)
    yield ()

  override def get(id: ConversationId): IO[PersistenceError, Conversation] =
    dataStore.fetch[String, String](snapshotKey(id)).mapError(storeErr("getConversationSnapshot")).flatMap {
      case Some(json) =>
        json.fromJson[Conversation] match
          case Right(conversation) =>
            ZIO.logDebug(
              s"Fetched conversation snapshot from store: ${snapshotKey(id)} [state=${conversation.state.getClass.getSimpleName}]"
            ) *>
              ZIO.succeed(conversation)
          case Left(decodeErr)     =>
            ZIO.logWarning(s"Corrupt snapshot for conversation:${id.value}, rebuilding: $decodeErr") *>
              rebuildSnapshot(id)
      case None       =>
        ZIO.logDebug(s"No snapshot found for conversation:${id.value} — rebuilding from events") *>
          eventStore.events(id).flatMap {
            case Nil => ZIO.fail(PersistenceError.NotFound("conversation", id.value))
            case _   => rebuildSnapshot(id)
          }
    }

  override def list(filter: ConversationFilter): IO[PersistenceError, List[Conversation]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listConversations"))
      .flatMap { keys =>
        ZIO.logDebug(s"Scanning ${keys.size} conversation snapshot key(s) from store") *>
          ZIO.foreach(keys.toList)(key =>
            dataStore
              .fetch[String, String](key)
              .mapError(storeErr("listConversations"))
              .map(_.flatMap(_.fromJson[Conversation].toOption))
          )
      }
      .map(_.flatten)
      .tap(all => ZIO.logDebug(s"Recovered ${all.size} conversation object(s) from store"))
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
    : ZLayer[EventStore[ConversationId, ConversationEvent] & DataStoreService, Nothing, ConversationRepository] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[ConversationId, ConversationEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield ConversationRepositoryES(eventStore, dataStore)
    }
