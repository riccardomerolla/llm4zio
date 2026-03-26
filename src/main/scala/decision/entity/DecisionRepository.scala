package decision.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, IssueId }

final case class DecisionFilter(
  statuses: Set[DecisionStatus] = Set.empty,
  sourceKind: Option[DecisionSourceKind] = None,
  urgency: Option[DecisionUrgency] = None,
  workspaceId: Option[String] = None,
  issueId: Option[IssueId] = None,
  query: Option[String] = None,
  offset: Int = 0,
  limit: Int = 100,
)

trait DecisionRepository:
  def append(event: DecisionEvent): IO[PersistenceError, Unit]
  def get(id: DecisionId): IO[PersistenceError, Decision]
  def history(id: DecisionId): IO[PersistenceError, List[DecisionEvent]]
  def list(filter: DecisionFilter = DecisionFilter()): IO[PersistenceError, List[Decision]]

object DecisionRepository:
  def append(event: DecisionEvent): ZIO[DecisionRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DecisionRepository](_.append(event))

  def get(id: DecisionId): ZIO[DecisionRepository, PersistenceError, Decision] =
    ZIO.serviceWithZIO[DecisionRepository](_.get(id))

  def history(id: DecisionId): ZIO[DecisionRepository, PersistenceError, List[DecisionEvent]] =
    ZIO.serviceWithZIO[DecisionRepository](_.history(id))

  def list(filter: DecisionFilter = DecisionFilter()): ZIO[DecisionRepository, PersistenceError, List[Decision]] =
    ZIO.serviceWithZIO[DecisionRepository](_.list(filter))
