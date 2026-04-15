package cli

import java.time.Instant

import zio.*

import _root_.config.entity.*
import shared.errors.PersistenceError
import CliStoreModule.ConfigStoreService

/** CLI-local ConfigRepository implementation. Uses ConfigStoreService directly, bypassing the root db layer. Only
  * settings methods have real implementations; workflow / custom-agent / binding methods are unsupported in the CLI
  * context.
  */
final case class CliConfigRepository(configStore: ConfigStoreService) extends ConfigRepository:

  private def settingKey(key: String): String = s"setting:$key"

  private def storeErr(op: String)(e: io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError)
    : PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def checkpointConfigStore(op: String): IO[PersistenceError, Unit] =
    configStore.checkpoint.mapError(err => PersistenceError.QueryFailed(op, err.toString))

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    for
      keys <- configStore.streamKeys[String]
                .filter(_.startsWith("setting:"))
                .runCollect
                .mapError(storeErr("getAllSettings"))
      rows <- ZIO.foreach(keys.toList) { k =>
                configStore
                  .fetch[String, String](k)
                  .mapError(storeErr("getAllSettings"))
                  .map(_.map(v => SettingRow(key = k.stripPrefix("setting:"), value = v, updatedAt = Instant.EPOCH)).toList)
              }
    yield rows.flatten.sortBy(_.key)

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    configStore
      .fetch[String, String](settingKey(key))
      .mapError(storeErr("getSetting"))
      .map(_.map(v => SettingRow(key = key, value = v, updatedAt = Instant.EPOCH)))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    configStore
      .store(settingKey(key), value)
      .mapError(storeErr("upsertSetting")) *> checkpointConfigStore("upsertSetting")

  override def deleteSetting(key: String): IO[PersistenceError, Unit] =
    configStore.remove[String](settingKey(key)).mapError(storeErr("deleteSetting")) *>
      checkpointConfigStore("deleteSetting")

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    for
      keys <- configStore.streamKeys[String]
                .filter(k => k.startsWith(s"setting:$prefix"))
                .runCollect
                .mapError(storeErr("deleteSettingsByPrefix"))
      _    <- ZIO.foreachDiscard(keys.toList) { k =>
                configStore.remove[String](k).mapError(storeErr("deleteSettingsByPrefix"))
              }
      _    <- checkpointConfigStore("deleteSettingsByPrefix")
    yield ()

  // ── Workflows — not supported in CLI context ───────────────────────────────

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
    ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "Workflow management not supported in CLI context"))

  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
    ZIO.succeed(None)

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    ZIO.succeed(None)

  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
    ZIO.succeed(Nil)

  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
    ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "Workflow management not supported in CLI context"))

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "Workflow management not supported in CLI context"))

  // ── Custom agents — not supported in CLI context ──────────────────────────

  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
    ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "Custom agent management not supported in CLI context"))

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
    ZIO.succeed(None)

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    ZIO.succeed(None)

  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
    ZIO.succeed(Nil)

  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
    ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "Custom agent management not supported in CLI context"))

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "Custom agent management not supported in CLI context"))

object CliConfigRepository:
  val live: ZLayer[ConfigStoreService, Nothing, ConfigRepository] =
    ZLayer.fromFunction(CliConfigRepository.apply)
