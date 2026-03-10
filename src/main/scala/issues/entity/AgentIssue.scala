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
  requiredCapabilities: List[String],
  state: IssueState,
  tags: List[String],
  contextPath: String,
  sourceFolder: String,
  workspaceId: Option[String] = None,
  externalRef: Option[String] = None,
  externalUrl: Option[String] = None,
) derives JsonCodec, Schema

object AgentIssue:
  /** Safely iterate a list that may be null or contain null elements due to EclipseStore schema evolution. */
  private def safeList[A](list: List[A]): List[A] =
    try
      Option(list).fold(Nil)(_.filter(a => Option(a).isDefined))
    catch case _: Throwable => Nil

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
                  requiredCapabilities = safeList(created.requiredCapabilities)
                    .flatMap(s => Option(s)).map(_.trim).filter(_.nonEmpty).distinct,
                  state = IssueState.Open(created.occurredAt),
                  tags = Nil,
                  contextPath = "",
                  sourceFolder = "",
                  workspaceId = None,
                  externalRef = None,
                  externalUrl = None,
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

      case linked: IssueEvent.WorkspaceLinked =>
        current
          .toRight(s"Issue ${linked.issueId.value} not initialized before WorkspaceLinked event")
          .map(issue => Some(issue.copy(workspaceId = Some(linked.workspaceId))))

      case unlinked: IssueEvent.WorkspaceUnlinked =>
        current
          .toRight(s"Issue ${unlinked.issueId.value} not initialized before WorkspaceUnlinked event")
          .map(issue => Some(issue.copy(workspaceId = None)))

      case tagsUpdated: IssueEvent.TagsUpdated =>
        current
          .toRight(s"Issue ${tagsUpdated.issueId.value} not initialized before TagsUpdated event")
          .map(issue => Some(issue.copy(tags = safeList(tagsUpdated.tags))))

      case reopened: IssueEvent.Reopened =>
        current
          .toRight(s"Issue ${reopened.issueId.value} not initialized before Reopened event")
          .map(issue => Some(issue.copy(state = IssueState.Open(reopened.reopenedAt))))

      case updated: IssueEvent.MetadataUpdated =>
        current
          .toRight(s"Issue ${updated.issueId.value} not initialized before MetadataUpdated event")
          .map(issue =>
            Some(
              issue.copy(
                title = updated.title,
                description = updated.description,
                issueType = updated.issueType,
                priority = updated.priority,
                requiredCapabilities = safeList(updated.requiredCapabilities)
                  .flatMap(s => Option(s)).map(_.trim).filter(_.nonEmpty).distinct,
                contextPath = updated.contextPath,
                sourceFolder = updated.sourceFolder,
              )
            )
          )

      case linked: IssueEvent.ExternalRefLinked =>
        current
          .toRight(s"Issue ${linked.issueId.value} not initialized before ExternalRefLinked event")
          .map(issue =>
            Some(
              issue.copy(
                externalRef = Option(linked.externalRef).map(_.trim).filter(_.nonEmpty),
                externalUrl = linked.externalUrl.flatMap(v => Option(v).map(_.trim).filter(_.nonEmpty)),
              )
            )
          )

      case synced: IssueEvent.ExternalRefSynced =>
        current
          .toRight(s"Issue ${synced.issueId.value} not initialized before ExternalRefSynced event")
          .map { issue =>
            val fields = Option(synced.updatedFields).getOrElse(Map.empty).collect {
              case (k, v) if Option(k).exists(_.trim.nonEmpty) => k.trim.toLowerCase -> Option(v).getOrElse("").trim
            }
            Some(
              issue.copy(
                title = fields.get("title").filter(_.nonEmpty).getOrElse(issue.title),
                description = fields.get("description").filter(_.nonEmpty).getOrElse(issue.description),
                issueType = fields.get("issuetype").filter(_.nonEmpty).getOrElse(issue.issueType),
                priority = fields.get("priority").filter(_.nonEmpty).getOrElse(issue.priority),
                externalRef = fields.get("externalref").filter(_.nonEmpty).orElse(issue.externalRef),
                externalUrl = fields.get("externalurl").filter(_.nonEmpty).orElse(issue.externalUrl),
              )
            )
          }
