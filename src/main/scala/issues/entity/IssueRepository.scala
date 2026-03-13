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
  case Backlog, Todo, InProgress, HumanReview, Rework, Merging, Done, Canceled, Duplicated
  case Open, Assigned, Completed, Failed, Skipped

object IssueStateTag:
  def fromState(state: IssueState): IssueStateTag =
    state match
      case _: IssueState.Backlog     => IssueStateTag.Backlog
      case _: IssueState.Todo        => IssueStateTag.Todo
      case _: IssueState.Open        => IssueStateTag.Open
      case _: IssueState.Assigned    => IssueStateTag.Assigned
      case _: IssueState.InProgress  => IssueStateTag.InProgress
      case _: IssueState.HumanReview => IssueStateTag.HumanReview
      case _: IssueState.Rework      => IssueStateTag.Rework
      case _: IssueState.Merging     => IssueStateTag.Merging
      case _: IssueState.Done        => IssueStateTag.Done
      case _: IssueState.Canceled    => IssueStateTag.Canceled
      case _: IssueState.Duplicated  => IssueStateTag.Duplicated
      case _: IssueState.Completed   => IssueStateTag.Completed
      case _: IssueState.Failed      => IssueStateTag.Failed
      case _: IssueState.Skipped     => IssueStateTag.Skipped

trait IssueRepository:
  def append(event: IssueEvent): IO[PersistenceError, Unit]
  def get(id: IssueId): IO[PersistenceError, AgentIssue]
  def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]
  def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]]
  def delete(id: IssueId): IO[PersistenceError, Unit]

object IssueRepository:
  def append(event: IssueEvent): ZIO[IssueRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[IssueRepository](_.append(event))

  def get(id: IssueId): ZIO[IssueRepository, PersistenceError, AgentIssue] =
    ZIO.serviceWithZIO[IssueRepository](_.get(id))

  def history(id: IssueId): ZIO[IssueRepository, PersistenceError, List[IssueEvent]] =
    ZIO.serviceWithZIO[IssueRepository](_.history(id))

  def list(filter: IssueFilter): ZIO[IssueRepository, PersistenceError, List[AgentIssue]] =
    ZIO.serviceWithZIO[IssueRepository](_.list(filter))

  def delete(id: IssueId): ZIO[IssueRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[IssueRepository](_.delete(id))
