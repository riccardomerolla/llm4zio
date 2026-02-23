package config.entity

import java.time.Instant

import zio.*
import zio.json.*

import shared.errors.PersistenceError
import shared.ids.Ids
import store.{ ConfigStoreModule, CustomAgentRow, WorkflowRow }

object ConfigMigration:

  final private case class StoredSetting(value: String, updatedAt: Instant) derives JsonCodec

  def migrateLegacyRows: ZIO[ConfigStoreModule.ConfigStoreService & ConfigRepository, PersistenceError, Int] =
    for
      store      <- ZIO.service[ConfigStoreModule.ConfigStoreService]
      repository <- ZIO.service[ConfigRepository]
      settingsN  <- migrateSettings(store, repository)
      workflowsN <- migrateWorkflows(store, repository)
      agentsN    <- migrateAgents(store, repository)
    yield settingsN + workflowsN + agentsN

  private def migrateSettings(
    store: ConfigStoreModule.ConfigStoreService,
    repository: ConfigRepository,
  ): IO[PersistenceError, Int] =
    for
      keys <- store.rawStore
                .streamKeys[String]
                .filter(_.startsWith("setting:"))
                .runCollect
                .mapError(err => PersistenceError.QueryFailed("migrateSettings", err.toString))
      now  <- Clock.instant
      rows <- ZIO.foreach(keys.toList) { key =>
                val settingId = key.stripPrefix("setting:")
                store.store
                  .fetch[String, String](key)
                  .mapError(err => PersistenceError.QueryFailed("migrateSettings", err.toString))
                  .flatMap {
                    case Some(raw) =>
                      val parsed  = raw.fromJson[StoredSetting].toOption
                      val setting = parsed match
                        case Some(stored) => Setting(settingId, SettingValue.Text(stored.value), stored.updatedAt)
                        case None         => Setting(settingId, inferSettingValue(raw), now)
                      repository.putSetting(setting).as(1)
                    case None      => ZIO.succeed(0)
                  }
              }
    yield rows.sum

  private def migrateWorkflows(
    store: ConfigStoreModule.ConfigStoreService,
    repository: ConfigRepository,
  ): IO[PersistenceError, Int] =
    for
      keys <- store.rawStore
                .streamKeys[String]
                .filter(_.startsWith("workflow:"))
                .runCollect
                .mapError(err => PersistenceError.QueryFailed("migrateWorkflows", err.toString))
      rows <- ZIO.foreach(keys.toList) { key =>
                store.store
                  .fetch[String, WorkflowRow](key)
                  .mapError(err => PersistenceError.QueryFailed("migrateWorkflows", err.toString))
                  .flatMap {
                    case Some(row) =>
                      val workflow = Workflow(
                        id = Ids.WorkflowId(row.id),
                        name = row.name,
                        description = row.description.getOrElse(""),
                        steps = List(row.stepsJson),
                        isBuiltin = row.isBuiltin,
                        createdAt = row.createdAt,
                        updatedAt = row.updatedAt,
                      )
                      repository.saveWorkflow(workflow).as(1)
                    case None      => ZIO.succeed(0)
                  }
              }
    yield rows.sum

  private def migrateAgents(
    store: ConfigStoreModule.ConfigStoreService,
    repository: ConfigRepository,
  ): IO[PersistenceError, Int] =
    for
      keys <- store.rawStore
                .streamKeys[String]
                .filter(_.startsWith("agent:"))
                .runCollect
                .mapError(err => PersistenceError.QueryFailed("migrateAgents", err.toString))
      rows <- ZIO.foreach(keys.toList) { key =>
                store.store
                  .fetch[String, CustomAgentRow](key)
                  .mapError(err => PersistenceError.QueryFailed("migrateAgents", err.toString))
                  .flatMap {
                    case Some(row) =>
                      val tags  = row.tagsJson
                        .flatMap(_.fromJson[List[String]].toOption)
                        .getOrElse(Nil)
                      val agent = CustomAgent(
                        id = Ids.AgentId(row.id),
                        name = row.name,
                        displayName = row.displayName,
                        description = row.description.getOrElse(""),
                        systemPrompt = row.systemPrompt,
                        tags = tags,
                        enabled = row.enabled,
                        createdAt = row.createdAt,
                        updatedAt = row.updatedAt,
                      )
                      repository.saveAgent(agent).as(1)
                    case None      => ZIO.succeed(0)
                  }
              }
    yield rows.sum

  private def inferSettingValue(raw: String): SettingValue =
    raw.trim.toLowerCase match
      case "true"  => SettingValue.Flag(true)
      case "false" => SettingValue.Flag(false)
      case _       =>
        raw.toLongOption match
          case Some(longValue) => SettingValue.Whole(longValue)
          case None            =>
            raw.toDoubleOption match
              case Some(doubleValue) => SettingValue.Decimal(doubleValue)
              case None              => SettingValue.Text(raw)
