package issues.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids
import shared.store.{ AgentAssignmentRow, AgentIssueRow, DataStoreModule }

object IssueMigration:

  def migrateLegacyRows: ZIO[DataStoreModule.DataStoreService & IssueRepository, PersistenceError, Int] =
    for
      dataStore   <- ZIO.service[DataStoreModule.DataStoreService]
      repo        <- ZIO.service[IssueRepository]
      keys        <- dataStore.rawStore
                       .streamKeys[String]
                       .filter(_.startsWith("issue:"))
                       .runCollect
                       .mapError(err => PersistenceError.QueryFailed("migrateIssueLegacyRows", err.toString))
      issues      <- ZIO.foreach(keys.toList) { key =>
                       dataStore.store
                         .fetch[String, AgentIssueRow](key)
                         .mapError(err => PersistenceError.QueryFailed("migrateIssueLegacyRows", err.toString))
                         .flatMap {
                           case Some(row) =>
                             ZIO.foreachDiscard(toEvents(row))(repo.append) *> ZIO.succeed(1)
                           case None      => ZIO.succeed(0)
                         }
                     }
      assignments <- migrateAssignments(dataStore)
    yield issues.sum + assignments

  private def migrateAssignments(dataStore: DataStoreModule.DataStoreService): IO[PersistenceError, Int] =
    for
      keys  <- dataStore.rawStore
                 .streamKeys[String]
                 .filter(_.startsWith("assignment:"))
                 .runCollect
                 .mapError(err => PersistenceError.QueryFailed("migrateAssignmentLegacyRows", err.toString))
      count <- ZIO.foreach(keys.toList) { key =>
                 dataStore.store
                   .fetch[String, AgentAssignmentRow](key)
                   .mapError(err => PersistenceError.QueryFailed("migrateAssignmentLegacyRows", err.toString))
                   .flatMap {
                     case Some(row) =>
                       val assignment = Assignment(
                         id = Ids.AssignmentId(row.id),
                         issueId = Ids.IssueId(row.issueId),
                         agentName = row.agentName,
                         assignedAt = row.assignedAt,
                         startedAt = row.startedAt,
                         completedAt = row.completedAt,
                         executionLog = row.executionLog,
                         result = row.result,
                       )
                       dataStore.store
                         .store(s"assignment:v2:${row.id}", assignment)
                         .mapError(err => PersistenceError.QueryFailed("migrateAssignmentLegacyRows", err.toString))
                         .as(1)
                     case None      => ZIO.succeed(0)
                   }
               }
    yield count.sum

  private def toEvents(row: AgentIssueRow): List[IssueEvent] =
    val issueId = Ids.IssueId(row.id)
    val created = IssueEvent.Created(
      issueId = issueId,
      title = row.title,
      description = row.description,
      issueType = row.issueType,
      priority = row.priority,
      occurredAt = row.createdAt,
    )

    row.status.toLowerCase match
      case "open"                       => List(created)
      case "assigned"                   =>
        val agent = Ids.AgentId(row.assignedAgent.getOrElse("legacy-agent"))
        List(created, IssueEvent.Assigned(issueId, agent, row.assignedAt.getOrElse(row.updatedAt), row.updatedAt))
      case "inprogress" | "in_progress" =>
        val agent = Ids.AgentId(row.assignedAgent.getOrElse("legacy-agent"))
        List(
          created,
          IssueEvent.Assigned(issueId, agent, row.assignedAt.getOrElse(row.updatedAt), row.updatedAt),
          IssueEvent.Started(issueId, agent, row.assignedAt.getOrElse(row.updatedAt), row.updatedAt),
        )
      case "completed"                  =>
        val agent       = Ids.AgentId(row.assignedAgent.getOrElse("legacy-agent"))
        val completedAt = row.completedAt.getOrElse(row.updatedAt)
        List(
          created,
          IssueEvent.Assigned(issueId, agent, row.assignedAt.getOrElse(row.updatedAt), row.updatedAt),
          IssueEvent.Completed(issueId, agent, completedAt, row.resultData.getOrElse("legacy result"), completedAt),
        )
      case "failed"                     =>
        val agent    = Ids.AgentId(row.assignedAgent.getOrElse("legacy-agent"))
        val failedAt = row.completedAt.getOrElse(row.updatedAt)
        List(
          created,
          IssueEvent.Assigned(issueId, agent, row.assignedAt.getOrElse(row.updatedAt), row.updatedAt),
          IssueEvent.Failed(issueId, agent, failedAt, row.errorMessage.getOrElse("legacy failure"), failedAt),
        )
      case "skipped"                    =>
        List(
          created,
          IssueEvent.Skipped(issueId, row.updatedAt, row.errorMessage.getOrElse("legacy skipped"), row.updatedAt),
        )
      case _                            =>
        List(
          created,
          IssueEvent.Skipped(issueId, row.updatedAt, s"unknown legacy status: ${row.status}", row.updatedAt),
        )
