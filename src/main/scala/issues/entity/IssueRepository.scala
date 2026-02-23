package issues.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, IssueId, TaskRunId }

final case class IssueFilter(
  runId: Option[TaskRunId] = None,
  states: Set[IssueStateTag] = Set.empty,
  agentId: Option[AgentId] = None,
  offset: Int = 0,
  limit: Int = 100,
)

enum IssueStateTag:
  case Open, Assigned, InProgress, Completed, Failed, Skipped

object IssueStateTag:
  def fromState(state: IssueState): IssueStateTag =
    state match
      case _: IssueState.Open       => IssueStateTag.Open
      case _: IssueState.Assigned   => IssueStateTag.Assigned
      case _: IssueState.InProgress => IssueStateTag.InProgress
      case _: IssueState.Completed  => IssueStateTag.Completed
      case _: IssueState.Failed     => IssueStateTag.Failed
      case _: IssueState.Skipped    => IssueStateTag.Skipped

trait IssueRepository:
  def append(event: IssueEvent): IO[PersistenceError, Unit]
  def get(id: IssueId): IO[PersistenceError, AgentIssue]
  def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]]

object IssueRepository:
  def append(event: IssueEvent): ZIO[IssueRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[IssueRepository](_.append(event))

  def get(id: IssueId): ZIO[IssueRepository, PersistenceError, AgentIssue] =
    ZIO.serviceWithZIO[IssueRepository](_.get(id))

  def list(filter: IssueFilter): ZIO[IssueRepository, PersistenceError, List[AgentIssue]] =
    ZIO.serviceWithZIO[IssueRepository](_.list(filter))
