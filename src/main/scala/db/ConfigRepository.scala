package db

import java.time.Instant

import zio.*
import zio.json.*

import io.github.riccardomerolla.zio.eclipsestore.service.{ LifecycleCommand, LifecycleStatus }
import store.ConfigStoreModule

trait ConfigRepository:
  // Settings
  def getAllSettings: IO[PersistenceError, List[SettingRow]]
  def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]
  def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]
  def upsertSettings(settings: Map[String, String]): IO[PersistenceError, Unit]   =
    ZIO.foreachDiscard(settings) { case (key, value) => upsertSetting(key, value) }
  def deleteSetting(key: String): IO[PersistenceError, Unit]
  def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[SettingRow]] =
    getAllSettings.map(_.filter(_.key.startsWith(prefix)))
  def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]

  // Workflows
  def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]
  def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]
  def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]
  def listWorkflows: IO[PersistenceError, List[WorkflowRow]]
  def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]
  def deleteWorkflow(id: Long): IO[PersistenceError, Unit]

  // Custom agents
  def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]
  def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]
  def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]]
  def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]
  def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]
  def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]

object ConfigRepository:
  // Settings
  def getAllSettings: ZIO[ConfigRepository, PersistenceError, List[SettingRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getAllSettings)

  def getSetting(key: String): ZIO[ConfigRepository, PersistenceError, Option[SettingRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getSetting(key))

  def upsertSetting(key: String, value: String): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.upsertSetting(key, value))

  def upsertSettings(settings: Map[String, String]): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.upsertSettings(settings))

  def deleteSetting(key: String): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteSetting(key))

  def getSettingsByPrefix(prefix: String): ZIO[ConfigRepository, PersistenceError, List[SettingRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getSettingsByPrefix(prefix))

  def deleteSettingsByPrefix(prefix: String): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteSettingsByPrefix(prefix))

  // Workflows
  def createWorkflow(workflow: WorkflowRow): ZIO[ConfigRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ConfigRepository](_.createWorkflow(workflow))

  def getWorkflow(id: Long): ZIO[ConfigRepository, PersistenceError, Option[WorkflowRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getWorkflow(id))

  def getWorkflowByName(name: String): ZIO[ConfigRepository, PersistenceError, Option[WorkflowRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getWorkflowByName(name))

  def listWorkflows: ZIO[ConfigRepository, PersistenceError, List[WorkflowRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.listWorkflows)

  def updateWorkflow(workflow: WorkflowRow): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.updateWorkflow(workflow))

  def deleteWorkflow(id: Long): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteWorkflow(id))

  // Custom agents
  def createCustomAgent(agent: CustomAgentRow): ZIO[ConfigRepository, PersistenceError, Long] =
    ZIO.serviceWithZIO[ConfigRepository](_.createCustomAgent(agent))

  def getCustomAgent(id: Long): ZIO[ConfigRepository, PersistenceError, Option[CustomAgentRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getCustomAgent(id))

  def getCustomAgentByName(name: String): ZIO[ConfigRepository, PersistenceError, Option[CustomAgentRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.getCustomAgentByName(name))

  def listCustomAgents: ZIO[ConfigRepository, PersistenceError, List[CustomAgentRow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.listCustomAgents)

  def updateCustomAgent(agent: CustomAgentRow): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.updateCustomAgent(agent))

  def deleteCustomAgent(id: Long): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteCustomAgent(id))

  val live
    : ZLayer[
      ConfigStoreModule.ConfigStoreService,
      Nothing,
      ConfigRepository,
    ] =
    ConfigRepositoryES.live

  val fromTaskRepository: ZLayer[TaskRepository, Nothing, ConfigRepository] =
    ZLayer.fromFunction((taskRepository: TaskRepository) =>
      new ConfigRepository:
        override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
          taskRepository.getAllSettings

        override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
          taskRepository.getSetting(key)

        override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
          taskRepository.upsertSetting(key, value)

        override def upsertSettings(settings: Map[String, String]): IO[PersistenceError, Unit] =
          ZIO.foreachDiscard(settings) { case (key, value) => taskRepository.upsertSetting(key, value) }

        override def deleteSetting(key: String): IO[PersistenceError, Unit] =
          ZIO.fail(PersistenceError.QueryFailed(
            "deleteSetting",
            s"Operation not supported by TaskRepository adapter: $key",
          ))

        override def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[SettingRow]] =
          taskRepository.getSettingsByPrefix(prefix)

        override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
          taskRepository.deleteSettingsByPrefix(prefix)

        override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
          taskRepository.createWorkflow(workflow)

        override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
          taskRepository.getWorkflow(id)

        override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
          taskRepository.getWorkflowByName(name)

        override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
          taskRepository.listWorkflows

        override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
          taskRepository.updateWorkflow(workflow)

        override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
          taskRepository.deleteWorkflow(id)

        override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
          taskRepository.createCustomAgent(agent)

        override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
          taskRepository.getCustomAgent(id)

        override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
          taskRepository.getCustomAgentByName(name)

        override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
          taskRepository.listCustomAgents

        override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
          taskRepository.updateCustomAgent(agent)

        override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
          taskRepository.deleteCustomAgent(id)
    )

final case class ConfigRepositoryES(
  configStore: ConfigStoreModule.ConfigStoreService
) extends ConfigRepository:

  private val kv = configStore.store

  private val builtInAgentNamesLower: Set[String] = Set(
    "chat-agent",
    "code-agent",
    "task-planner",
    "web-search-agent",
    "file-agent",
    "report-agent",
    "router-agent",
  )

  // Key helpers
  private def settingKey(key: String): String = s"setting:$key"
  private def workflowKey(id: Long): String   = s"workflow:$id"
  private def agentKey(id: Long): String      = s"agent:$id"

  override def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    for
      keys <- configStore.rawStore
                .streamKeys[String]
                .filter(_.startsWith("setting:"))
                .runCollect
                .mapError(storeErr("getAllSettings"))
      now  <- Clock.instant
      rows <- ZIO.foreach(keys.toList) { k =>
                kv
                  .fetch[String, String](k)
                  .mapError(storeErr("getAllSettings"))
                  .map(_.map(raw => decodeSetting(k.stripPrefix("setting:"), raw, now)).toList)
              }
    yield rows.flatten.sortBy(_.key)

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    for
      raw <- kv.fetch[String, String](settingKey(key)).mapError(storeErr("getSetting"))
      now <- Clock.instant
    yield raw.map(value => decodeSetting(key, value, now))

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- kv
               .store(settingKey(key), StoredSetting(value, now).toJson)
               .mapError(storeErr("upsertSetting"))
      _   <- checkpointConfigStore("upsertSetting")
    yield ()

  override def deleteSetting(key: String): IO[PersistenceError, Unit] =
    kv.remove[String](settingKey(key)).mapError(storeErr("deleteSetting")) *> checkpointConfigStore("deleteSetting")

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    for
      keys <- configStore.rawStore
                .streamKeys[String]
                .filter(k => k.startsWith(s"setting:$prefix"))
                .runCollect
                .mapError(storeErr("deleteSettingsByPrefix"))
      _    <- ZIO.foreachDiscard(keys.toList) { k =>
                kv.remove[String](k).mapError(storeErr("deleteSettingsByPrefix"))
              }
      _    <- checkpointConfigStore("deleteSettingsByPrefix")
    yield ()

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
    for
      id <- nextId("createWorkflow")
      _  <- kv
              .store(workflowKey(id), toStoreWorkflowRow(workflow.copy(id = Some(id))))
              .mapError(storeErr("createWorkflow"))
    yield id

  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
    kv
      .fetch[String, store.WorkflowRow](workflowKey(id))
      .map(_.flatMap(fromStoreWorkflowRow))
      .mapError(storeErr("getWorkflow"))

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    fetchAllByPrefix[store.WorkflowRow]("workflow:", "getWorkflowByName")
      .map(_.flatMap(fromStoreWorkflowRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
    fetchAllByPrefix[store.WorkflowRow]("workflow:", "listWorkflows")
      .map(_.flatMap(fromStoreWorkflowRow).sortBy(w => (!w.isBuiltin, w.name.toLowerCase)))

  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(workflow.id)
                    .orElseFail(PersistenceError.QueryFailed("updateWorkflow", "Missing id for workflow update"))
      existing <- kv.fetch[String, store.WorkflowRow](workflowKey(id)).mapError(storeErr("updateWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id))
                    .when(existing.isEmpty)
      _        <- kv
                    .store(workflowKey(id), toStoreWorkflowRow(workflow))
                    .mapError(storeErr("updateWorkflow"))
    yield ()

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- kv.fetch[String, store.WorkflowRow](workflowKey(id)).mapError(storeErr("deleteWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id))
                    .when(existing.isEmpty)
      _        <- kv.remove[String](workflowKey(id)).mapError(storeErr("deleteWorkflow"))
    yield ()

  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
    for
      _  <- validateCustomAgentName(agent.name, "createCustomAgent")
      id <- nextId("createCustomAgent")
      _  <- kv
              .store(agentKey(id), toStoreAgentRow(agent.copy(id = Some(id))))
              .mapError(storeErr("createCustomAgent"))
    yield id

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
    kv
      .fetch[String, store.CustomAgentRow](agentKey(id))
      .map(_.flatMap(fromStoreAgentRow))
      .mapError(storeErr("getCustomAgent"))

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    fetchAllByPrefix[store.CustomAgentRow]("agent:", "getCustomAgentByName")
      .map(_.flatMap(fromStoreAgentRow).find(_.name.equalsIgnoreCase(name.trim)))

  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
    fetchAllByPrefix[store.CustomAgentRow]("agent:", "listCustomAgents")
      .map(_.flatMap(fromStoreAgentRow).sortBy(agent => (agent.displayName.toLowerCase, agent.name.toLowerCase)))

  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(agent.id)
                    .orElseFail(PersistenceError.QueryFailed("updateCustomAgent", "Missing id for custom agent update"))
      _        <- validateCustomAgentName(agent.name, "updateCustomAgent")
      existing <- kv.fetch[String, store.CustomAgentRow](agentKey(id)).mapError(storeErr("updateCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id))
                    .when(existing.isEmpty)
      _        <- kv
                    .store(agentKey(id), toStoreAgentRow(agent))
                    .mapError(storeErr("updateCustomAgent"))
    yield ()

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    for
      existing <- kv.fetch[String, store.CustomAgentRow](agentKey(id)).mapError(storeErr("deleteCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id))
                    .when(existing.isEmpty)
      _        <- kv.remove[String](agentKey(id)).mapError(storeErr("deleteCustomAgent"))
    yield ()

  // -------- Internals
  private def validateCustomAgentName(name: String, context: String): IO[PersistenceError, Unit] =
    val normalized = name.trim.toLowerCase
    if normalized.isEmpty then ZIO.fail(PersistenceError.QueryFailed(context, "Custom agent name cannot be empty"))
    else if builtInAgentNamesLower.contains(normalized) then
      ZIO.fail(PersistenceError.QueryFailed(context, s"Custom agent name '$name' conflicts with built-in agent name"))
    else ZIO.unit

  private def fetchAllByPrefix[V](prefix: String, op: String)(using zio.schema.Schema[V])
    : IO[PersistenceError, List[V]] =
    configStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .flatMap(keys => ZIO.foreach(keys.toList)(k => kv.fetch[String, V](k).mapError(storeErr(op))).map(_.flatten))

  private def toStoreWorkflowRow(workflow: WorkflowRow): store.WorkflowRow =
    store.WorkflowRow(
      id = workflow.id.getOrElse(0L).toString,
      name = workflow.name,
      description = workflow.description,
      stepsJson = workflow.steps,
      isBuiltin = workflow.isBuiltin,
      createdAt = workflow.createdAt,
      updatedAt = workflow.updatedAt,
    )

  private def fromStoreWorkflowRow(workflow: store.WorkflowRow): Option[WorkflowRow] =
    workflow.id.toLongOption.map { parsedId =>
      WorkflowRow(
        id = Some(parsedId),
        name = workflow.name,
        description = workflow.description,
        steps = workflow.stepsJson,
        isBuiltin = workflow.isBuiltin,
        createdAt = workflow.createdAt,
        updatedAt = workflow.updatedAt,
      )
    }

  private def toStoreAgentRow(agent: CustomAgentRow): store.CustomAgentRow =
    store.CustomAgentRow(
      id = agent.id.getOrElse(0L).toString,
      name = agent.name,
      displayName = agent.displayName,
      description = agent.description,
      systemPrompt = agent.systemPrompt,
      tagsJson = agent.tags,
      enabled = agent.enabled,
      createdAt = agent.createdAt,
      updatedAt = agent.updatedAt,
    )

  private def fromStoreAgentRow(agent: store.CustomAgentRow): Option[CustomAgentRow] =
    agent.id.toLongOption.map { parsedId =>
      CustomAgentRow(
        id = Some(parsedId),
        name = agent.name,
        displayName = agent.displayName,
        description = agent.description,
        systemPrompt = agent.systemPrompt,
        tags = agent.tagsJson,
        enabled = agent.enabled,
        createdAt = agent.createdAt,
        updatedAt = agent.updatedAt,
      )
    }

  private def nextId(op: String): IO[PersistenceError, Long] =
    ZIO
      .attempt(java.util.UUID.randomUUID().getMostSignificantBits & Long.MaxValue)
      .mapError(storeErrThrowable(op))
      .flatMap(id => if id == 0L then nextId(op) else ZIO.succeed(id))

  private def storeErr(op: String)(e: io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError)
    : PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  private def storeErrThrowable(op: String)(t: Throwable): PersistenceError =
    PersistenceError.QueryFailed(op, Option(t.getMessage).getOrElse(t.toString))

  final private case class StoredSetting(value: String, updatedAt: Instant) derives JsonCodec

  private def decodeSetting(key: String, raw: String, fallbackUpdatedAt: Instant): SettingRow =
    raw.fromJson[StoredSetting] match
      case Right(stored) => SettingRow(key = key, value = stored.value, updatedAt = stored.updatedAt)
      case Left(_)       => SettingRow(key = key, value = raw, updatedAt = fallbackUpdatedAt)

  private def checkpointConfigStore(op: String): IO[PersistenceError, Unit] =
    for
      status <- configStore.rawStore
                  .maintenance(LifecycleCommand.Checkpoint)
                  .mapError(err => PersistenceError.QueryFailed(op, err.toString))
      _      <- status match
                  case LifecycleStatus.Failed(message) =>
                    ZIO.fail(PersistenceError.QueryFailed(op, s"Config store checkpoint failed: $message"))
                  case _                               => ZIO.unit
    yield ()

object ConfigRepositoryES:
  val live
    : ZLayer[
      ConfigStoreModule.ConfigStoreService,
      Nothing,
      ConfigRepository,
    ] =
    ZLayer.fromZIO {
      for
        configSvc <- ZIO.service[ConfigStoreModule.ConfigStoreService]
      yield ConfigRepositoryES(configSvc)
    }
