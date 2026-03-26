package knowledge.entity

import zio.IO

import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionLogId, IssueId, PlanId, SpecificationId }

final case class DecisionLogFilter(
  workspaceId: Option[String] = None,
  issueId: Option[IssueId] = None,
  specificationId: Option[SpecificationId] = None,
  planId: Option[PlanId] = None,
  runId: Option[String] = None,
  query: Option[String] = None,
  limit: Int = 50,
  offset: Int = 0,
)

trait DecisionLogRepository:
  def append(event: DecisionLogEvent): IO[PersistenceError, Unit]
  def get(id: DecisionLogId): IO[PersistenceError, DecisionLog]
  def history(id: DecisionLogId): IO[PersistenceError, List[DecisionLogEvent]]
  def list(filter: DecisionLogFilter = DecisionLogFilter()): IO[PersistenceError, List[DecisionLog]]
