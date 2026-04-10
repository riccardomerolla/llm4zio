package governance.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.GovernancePolicyId
import shared.store.{ DataStoreService, EventStore }

final case class GovernancePolicyEventStoreES(
  dataStore: DataStoreService
) extends EventStore[GovernancePolicyId, GovernancePolicyEvent]:

  private def eventKey(id: GovernancePolicyId, seq: Long): String = s"events:governance-policy:${id.value}:$seq"
  private def prefix(id: GovernancePolicyId): String              = s"events:governance-policy:${id.value}:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(id: GovernancePolicyId, event: GovernancePolicyEvent): IO[PersistenceError, Unit] =
    for
      seq <- nextSequence(id, "appendGovernancePolicyEvent")
      _   <- dataStore
               .store(eventKey(id, seq), event.toJson)
               .mapError(storeErr("appendGovernancePolicyEvent"))
    yield ()

  override def events(id: GovernancePolicyId): IO[PersistenceError, List[GovernancePolicyEvent]] =
    loadEvents(id, minSequence = None)

  override def eventsSince(id: GovernancePolicyId, sequence: Long): IO[PersistenceError, List[GovernancePolicyEvent]] =
    loadEvents(id, minSequence = Some(sequence + 1L))

  private def nextSequence(id: GovernancePolicyId, op: String): IO[PersistenceError, Long] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr(op))
      .map(keys => keys.flatMap(_.stripPrefix(prefix(id)).toLongOption).maxOption.map(_ + 1L).getOrElse(1L))

  private def loadEvents(
    id: GovernancePolicyId,
    minSequence: Option[Long],
  ): IO[PersistenceError, List[GovernancePolicyEvent]] =
    dataStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix(id)))
      .runCollect
      .mapError(storeErr("loadGovernancePolicyEvents"))
      .map(
        _.toList
          .flatMap(key => key.stripPrefix(prefix(id)).toLongOption.map(_ -> key))
          .filter { case (sequence, _) => minSequence.forall(sequence >= _) }
          .sortBy(_._1)
          .map(_._2)
      )
      .flatMap(keys =>
        ZIO.foreach(keys) { key =>
          dataStore
            .fetch[String, String](key)
            .mapError(storeErr("loadGovernancePolicyEvents"))
            .flatMap {
              case None       => ZIO.succeed(None)
              case Some(json) =>
                ZIO
                  .fromEither(json.fromJson[GovernancePolicyEvent])
                  .mapBoth(
                    err => PersistenceError.SerializationFailed(key, err),
                    Some(_),
                  )
            }
        }
      )
      .map(_.flatten)

object GovernancePolicyEventStoreES:
  val live: ZLayer[DataStoreService, Nothing, EventStore[GovernancePolicyId, GovernancePolicyEvent]] =
    ZLayer.fromFunction(GovernancePolicyEventStoreES.apply)
