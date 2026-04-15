package evolution.entity

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.EvolutionProposalId
import shared.store.{ DataStoreService, EventStore }

final case class EvolutionProposalRepositoryES(
  eventStore: EventStore[EvolutionProposalId, EvolutionProposalEvent],
  dataStore: DataStoreService,
) extends EvolutionProposalRepository:

  private def snapshotKey(id: EvolutionProposalId): String = s"snapshot:evolution-proposal:${id.value}"
  private def snapshotPrefix: String                       = "snapshot:evolution-proposal:"

  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def append(event: EvolutionProposalEvent): IO[PersistenceError, Unit] =
    for
      _ <- eventStore.append(event.proposalId, event)
      _ <- rebuildSnapshot(event.proposalId)
    yield ()

  override def get(id: EvolutionProposalId): IO[PersistenceError, EvolutionProposal] =
    fetchSnapshot(id).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        =>
        eventStore.events(id).flatMap {
          case Nil => ZIO.fail(PersistenceError.NotFound("evolution_proposal", id.value))
          case _   =>
            rebuildSnapshot(id).flatMap {
              case Some(value) => ZIO.succeed(value)
              case None        => ZIO.fail(PersistenceError.NotFound("evolution_proposal", id.value))
            }
        }
    }

  override def history(id: EvolutionProposalId): IO[PersistenceError, List[EvolutionProposalEvent]] =
    eventStore.events(id)

  override def list(filter: EvolutionProposalFilter): IO[PersistenceError, List[EvolutionProposal]] =
    listAll.map(
      _.filter(matches(filter, _))
        .sortBy(_.createdAt)(Ordering[java.time.Instant].reverse)
    )

  private def rebuildSnapshot(id: EvolutionProposalId): IO[PersistenceError, Option[EvolutionProposal]] =
    for
      events <- eventStore.events(id)
      value  <- ZIO.fromEither(EvolutionProposal.fromEvents(events)).mapBoth(
                  err => PersistenceError.SerializationFailed(s"evolution:${id.value}", err),
                  Some(_),
                )
      _      <- dataStore.store(snapshotKey(id), value.get.toJson).mapError(storeErr("storeEvolutionProposalSnapshot"))
    yield value

  private def fetchSnapshot(id: EvolutionProposalId): IO[PersistenceError, Option[EvolutionProposal]] =
    dataStore.fetch[String, String](snapshotKey(id)).mapError(storeErr("fetchEvolutionProposalSnapshot")).flatMap {
      case None       => ZIO.none
      case Some(json) =>
        ZIO.fromEither(json.fromJson[EvolutionProposal]).mapBoth(
          err => PersistenceError.SerializationFailed(s"evolution:${id.value}", err),
          Some(_),
        )
    }

  private def listAll: IO[PersistenceError, List[EvolutionProposal]] =
    dataStore.streamKeys[String]
      .filter(_.startsWith(snapshotPrefix))
      .runCollect
      .mapError(storeErr("listEvolutionProposals"))
      .flatMap(keys =>
        ZIO.foreach(keys.toList) { key =>
          dataStore.fetch[String, String](key).mapError(storeErr("listEvolutionProposals")).flatMap {
            case None       => ZIO.none
            case Some(json) =>
              ZIO.fromEither(json.fromJson[EvolutionProposal]).mapBoth(
                err => PersistenceError.SerializationFailed(key, err),
                Some(_),
              )
          }
        }
      )
      .map(_.flatten)

  private def matches(filter: EvolutionProposalFilter, proposal: EvolutionProposal): Boolean =
    val matchesProject = filter.projectId.forall(_ == proposal.projectId)
    val matchesStatus  = filter.statuses.isEmpty || filter.statuses.contains(proposal.status)
    val matchesQuery   = filter.query
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .forall(query =>
        List(
          proposal.id.value,
          proposal.title,
          proposal.rationale,
          proposal.proposer.actor,
        ).exists(_.toLowerCase.contains(query))
      )

    matchesProject && matchesStatus && matchesQuery

object EvolutionProposalRepositoryES:
  val live
    : ZLayer[
      EventStore[EvolutionProposalId, EvolutionProposalEvent] & DataStoreService,
      Nothing,
      EvolutionProposalRepository,
    ] =
    ZLayer.fromZIO {
      for
        eventStore <- ZIO.service[EventStore[EvolutionProposalId, EvolutionProposalEvent]]
        dataStore  <- ZIO.service[DataStoreService]
      yield EvolutionProposalRepositoryES(eventStore, dataStore)
    }
