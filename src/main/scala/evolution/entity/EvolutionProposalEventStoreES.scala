package evolution.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.EvolutionProposalId
import shared.store.{ DataStoreService, EventStore }

final case class EvolutionProposalEventStoreES(
  dataStore: DataStoreService
) extends EventStore[EvolutionProposalId, EvolutionProposalEvent]:

  private def eventKey(id: EvolutionProposalId, seq: Long): String = s"events:evolution-proposal:${id.value}:$seq"
  private def prefix(id: EvolutionProposalId): String              = s"events:evolution-proposal:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: EvolutionProposalId, event: EvolutionProposalEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendEvolutionProposalEvent")
      _   <- dataStore.store(eventKey(id, seq), event.toJson).mapError(storeErr("appendEvolutionProposalEvent"))
    yield ()

  override def events(id: EvolutionProposalId): IO[PersistenceError, List[EvolutionProposalEvent]] =
    loadEvents(id, None)

  override def eventsSince(id: EvolutionProposalId, sequence: Long)
    : IO[PersistenceError, List[EvolutionProposalEvent]] =
    loadEvents(id, Some(sequence + 1L))

  private def nextSequence(id: EvolutionProposalId, op: String): IO[PersistenceError, Long] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(
    id: EvolutionProposalId,
    minSequence: Option[Long],
  ): IO[PersistenceError, List[EvolutionProposalEvent]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadEvolutionProposalEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("loadEvolutionProposalEvents")).flatMap {
            case None       => ZIO.succeed(None)
            case Some(json) =>
              ZIO.fromEither(json.fromJson[EvolutionProposalEvent]).mapBoth(
                err => PersistenceError.SerializationFailed(key, err),
                Some(_),
              )
          }
        }
      )
      .map(_.flatten)

object EvolutionProposalEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[EvolutionProposalId, EvolutionProposalEvent]] =
    ZLayer.fromFunction(EvolutionProposalEventStoreES.apply)
