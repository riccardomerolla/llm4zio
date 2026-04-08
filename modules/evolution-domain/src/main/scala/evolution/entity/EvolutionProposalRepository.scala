package evolution.entity

import zio.*
import zio.schema.{ Schema, derived }

import shared.errors.PersistenceError
import shared.ids.Ids.{ EvolutionProposalId, ProjectId }

final case class EvolutionProposalFilter(
  projectId: Option[ProjectId] = None,
  statuses: Set[EvolutionProposalStatus] = Set.empty,
  query: Option[String] = None,
) derives zio.json.JsonCodec,
    Schema

trait EvolutionProposalRepository:
  def append(event: EvolutionProposalEvent): IO[PersistenceError, Unit]
  def get(id: EvolutionProposalId): IO[PersistenceError, EvolutionProposal]
  def history(id: EvolutionProposalId): IO[PersistenceError, List[EvolutionProposalEvent]]
  def list(filter: EvolutionProposalFilter = EvolutionProposalFilter()): IO[PersistenceError, List[EvolutionProposal]]

object EvolutionProposalRepository:
  def append(event: EvolutionProposalEvent): ZIO[EvolutionProposalRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[EvolutionProposalRepository](_.append(event))

  def get(id: EvolutionProposalId): ZIO[EvolutionProposalRepository, PersistenceError, EvolutionProposal] =
    ZIO.serviceWithZIO[EvolutionProposalRepository](_.get(id))

  def history(id: EvolutionProposalId)
    : ZIO[EvolutionProposalRepository, PersistenceError, List[EvolutionProposalEvent]] =
    ZIO.serviceWithZIO[EvolutionProposalRepository](_.history(id))

  def list(
    filter: EvolutionProposalFilter = EvolutionProposalFilter()
  ): ZIO[EvolutionProposalRepository, PersistenceError, List[EvolutionProposal]] =
    ZIO.serviceWithZIO[EvolutionProposalRepository](_.list(filter))
