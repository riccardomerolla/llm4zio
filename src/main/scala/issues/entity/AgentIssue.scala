package issues.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ AgentId, ConversationId, IssueId, TaskRunId }

enum IssueState derives JsonCodec, Schema:
  case Open(createdAt: Instant)
  case Assigned(agent: AgentId, assignedAt: Instant)
  case InProgress(agent: AgentId, startedAt: Instant)
  case Completed(agent: AgentId, completedAt: Instant, result: String)
  case Failed(agent: AgentId, failedAt: Instant, errorMessage: String)
  case Skipped(skippedAt: Instant, reason: String)

final case class AgentIssue(
  id: IssueId,
  runId: Option[TaskRunId],
  conversationId: Option[ConversationId],
  title: String,
  description: String,
  issueType: String,
  priority: String,
  state: IssueState,
  tags: List[String],
  contextPath: String,
  sourceFolder: String,
) derives JsonCodec, Schema

object AgentIssue:
  def fromEvents(events: List[IssueEvent]): Either[String, AgentIssue] =
    events match
      case Nil => Left("Cannot rebuild AgentIssue from an empty event stream")
      case _   =>
        events.foldLeft[Either[String, Option[AgentIssue]]](Right(None)) { (acc, event) =>
          acc.flatMap(current => applyEvent(current, event))
        }.flatMap {
          case Some(issue) => Right(issue)
          case None        => Left("Issue stream did not produce a state")
        }

  private def applyEvent(current: Option[AgentIssue], event: IssueEvent): Either[String, Option[AgentIssue]] =
    event match
      case created: IssueEvent.Created =>
        current match
          case Some(_) => Left(s"Issue ${created.issueId.value} already initialized")
          case None    =>
            Right(
              Some(
                AgentIssue(
                  id = created.issueId,
                  runId = None,
                  conversationId = None,
                  title = created.title,
                  description = created.description,
                  issueType = created.issueType,
                  priority = created.priority,
                  state = IssueState.Open(created.occurredAt),
                  tags = Nil,
                  contextPath = "",
                  sourceFolder = "",
                )
              )
            )

      case assigned: IssueEvent.Assigned =>
        current
          .toRight(s"Issue ${assigned.issueId.value} not initialized before Assigned event")
          .map(issue => Some(issue.copy(state = IssueState.Assigned(assigned.agent, assigned.assignedAt))))

      case started: IssueEvent.Started =>
        current
          .toRight(s"Issue ${started.issueId.value} not initialized before Started event")
          .map(issue => Some(issue.copy(state = IssueState.InProgress(started.agent, started.startedAt))))

      case completed: IssueEvent.Completed =>
        current
          .toRight(s"Issue ${completed.issueId.value} not initialized before Completed event")
          .map(issue =>
            Some(issue.copy(state = IssueState.Completed(completed.agent, completed.completedAt, completed.result)))
          )

      case failed: IssueEvent.Failed =>
        current
          .toRight(s"Issue ${failed.issueId.value} not initialized before Failed event")
          .map(issue => Some(issue.copy(state = IssueState.Failed(failed.agent, failed.failedAt, failed.errorMessage))))

      case skipped: IssueEvent.Skipped =>
        current
          .toRight(s"Issue ${skipped.issueId.value} not initialized before Skipped event")
          .map(issue => Some(issue.copy(state = IssueState.Skipped(skipped.skippedAt, skipped.reason))))
