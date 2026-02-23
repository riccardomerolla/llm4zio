package taskrun.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids
import shared.store.{ DataStoreModule, TaskRunRow }

object TaskRunMigration:

  def migrateLegacyRows: ZIO[DataStoreModule.DataStoreService & TaskRunRepository, PersistenceError, Int] =
    for
      dataStore <- ZIO.service[DataStoreModule.DataStoreService]
      repo      <- ZIO.service[TaskRunRepository]
      keys      <- dataStore.rawStore
                     .streamKeys[String]
                     .filter(_.startsWith("run:"))
                     .runCollect
                     .mapError(err => PersistenceError.QueryFailed("migrateTaskRunLegacyRows", err.toString))
      count     <- ZIO.foreach(keys.toList) { key =>
                     dataStore.store
                       .fetch[String, TaskRunRow](key)
                       .mapError(err => PersistenceError.QueryFailed("migrateTaskRunLegacyRows", err.toString))
                       .flatMap {
                         case Some(row) =>
                           val events = toEvents(row)
                           ZIO.foreachDiscard(events)(repo.append) *> ZIO.succeed(1)
                         case None      => ZIO.succeed(0)
                       }
                   }
    yield count.sum

  private def toEvents(row: TaskRunRow): List[TaskRunEvent] =
    val runId      = Ids.TaskRunId(row.id)
    val workflowId = Ids.WorkflowId(row.workflowId.getOrElse("legacy-workflow"))

    val created = TaskRunEvent.Created(
      runId = runId,
      workflowId = workflowId,
      agentName = "legacy-agent",
      source = row.sourceDir,
      occurredAt = row.startedAt,
    )

    val phase = row.currentPhase.getOrElse("legacy")

    row.status.toLowerCase match
      case "pending"   => List(created)
      case "running"   => List(created, TaskRunEvent.Started(runId, phase, row.startedAt))
      case "completed" =>
        val completedAt = row.completedAt.getOrElse(row.startedAt)
        List(
          created,
          TaskRunEvent.Started(runId, phase, row.startedAt),
          TaskRunEvent.Completed(runId, s"legacy completed at $completedAt", completedAt),
        )
      case "failed"    =>
        val failedAt = row.completedAt.getOrElse(row.startedAt)
        List(
          created,
          TaskRunEvent.Started(runId, phase, row.startedAt),
          TaskRunEvent.Failed(runId, row.errorMessage.getOrElse("legacy failure"), failedAt),
        )
      case "cancelled" =>
        List(
          created,
          TaskRunEvent.Cancelled(
            runId,
            row.errorMessage.getOrElse("legacy cancelled"),
            row.completedAt.getOrElse(row.startedAt),
          ),
        )
      case _           =>
        List(
          created,
          TaskRunEvent.Failed(runId, s"Unknown legacy status '${row.status}'", row.completedAt.getOrElse(row.startedAt)),
        )
