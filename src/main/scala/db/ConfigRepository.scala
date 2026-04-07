package db

import java.time.Instant

import zio.*

import _root_.config.entity.{
  AgentChannelBinding,
  CustomAgentRow,
  SettingRow,
  StoredCustomAgentRow,
  StoredWorkflowRow,
  WorkflowRow,
}
import io.github.riccardomerolla.zio.eclipsestore.service.{ LifecycleCommand, LifecycleStatus }
import shared.errors.PersistenceError
import shared.ids.Ids.AgentId
import shared.store.ConfigStoreModule

final case class ConfigRepositoryES(
  configStore: ConfigStoreModule.ConfigStoreService
):

  private val builtInAgentNamesLower: Set[String] = Set(
    "chat-agent",
    "code-agent",
    "task-planner",
    "web-search-agent",
    "file-agent",
    "report-agent",
    "router-agent",
  )

  private def settingKey(key: String): String = s"setting:$key"
  private def workflowKey(id: Long): String   = s"workflow:$id"
  private def agentKey(id: Long): String      = s"agent:$id"

  def getAllSettings: IO[PersistenceError, List[SettingRow]] =
    for
      keys <- configStore.rawStore
                .streamKeys[String]
                .filter(_.startsWith("setting:"))
                .runCollect
                .mapError(storeErr("getAllSettings"))
      rows <- ZIO.foreach(keys.toList) { k =>
                configStore
                  .fetch[String, String](k)
                  .mapError(storeErr("getAllSettings"))
                  .map(_.map(raw => decodeSetting(k.stripPrefix("setting:"), raw)).toList)
              }
    yield rows.flatten.sortBy(_.key)

  def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
    configStore.fetch[String, String](settingKey(key)).mapError(storeErr("getSetting")).map(_.map(value =>
      decodeSetting(key, value)
    ))

  def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    configStore
      .store(settingKey(key), value)
      .mapError(storeErr("upsertSetting")) *> checkpointConfigStore("upsertSetting")

  def deleteSetting(key: String): IO[PersistenceError, Unit] =
    configStore.remove[String](settingKey(key)).mapError(storeErr("deleteSetting")) *> checkpointConfigStore(
      "deleteSetting"
    )

  def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    for
      keys <- configStore.rawStore
                .streamKeys[String]
                .filter(k => k.startsWith(s"setting:$prefix"))
                .runCollect
                .mapError(storeErr("deleteSettingsByPrefix"))
      _    <- ZIO.foreachDiscard(keys.toList) { k =>
                configStore.remove[String](k).mapError(storeErr("deleteSettingsByPrefix"))
              }
      _    <- checkpointConfigStore("deleteSettingsByPrefix")
    yield ()

  def listAgentChannelBindings: IO[PersistenceError, List[AgentChannelBinding]] =
    getAllSettings.map(_.filter(_.key.startsWith("agent.binding.")).flatMap(row => parseBindingKey(row.key)))

  def upsertAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] =
    upsertSetting(bindingKey(binding), "true")

  def deleteAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] =
    deleteSetting(bindingKey(binding))

  def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
    for
      id <- nextId("createWorkflow")
      _  <- configStore
              .store(workflowKey(id), toStoreWorkflowRow(workflow, id))
              .mapError(storeErr("createWorkflow"))
    yield id

  def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] =
    configStore
      .fetch[String, StoredWorkflowRow](workflowKey(id))
      .map(_.flatMap(fromStoreWorkflowRow))
      .mapError(storeErr("getWorkflow"))

  def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    fetchAllByPrefix[StoredWorkflowRow]("workflow:", "getWorkflowByName")
      .map(_.flatMap(fromStoreWorkflowRow).find(_.name.equalsIgnoreCase(name.trim)))

  def listWorkflows: IO[PersistenceError, List[WorkflowRow]] =
    fetchAllByPrefix[StoredWorkflowRow]("workflow:", "listWorkflows")
      .map(_.flatMap(fromStoreWorkflowRow).sortBy(w => (!w.isBuiltin, w.name.toLowerCase)))

  def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(workflow.id)
                    .orElseFail(PersistenceError.QueryFailed("updateWorkflow", "Missing id for workflow update"))
      existing <-
        configStore.fetch[String, StoredWorkflowRow](workflowKey(id)).mapError(storeErr("updateWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id.toString))
                    .when(existing.isEmpty)
      _        <- configStore
                    .store(workflowKey(id), toStoreWorkflowRow(workflow, id))
                    .mapError(storeErr("updateWorkflow"))
    yield ()

  def deleteWorkflow(id: Long): IO[PersistenceError, Unit] =
    for
      existing <-
        configStore.fetch[String, StoredWorkflowRow](workflowKey(id)).mapError(storeErr("deleteWorkflow"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("workflows", id.toString))
                    .when(existing.isEmpty)
      _        <- configStore.remove[String](workflowKey(id)).mapError(storeErr("deleteWorkflow"))
    yield ()

  def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
    for
      _  <- validateCustomAgentName(agent.name, "createCustomAgent")
      id <- nextId("createCustomAgent")
      _  <- configStore
              .store(agentKey(id), toStoreAgentRow(agent, id))
              .mapError(storeErr("createCustomAgent"))
    yield id

  def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
    configStore
      .fetch[String, StoredCustomAgentRow](agentKey(id))
      .map(_.flatMap(fromStoreAgentRow))
      .mapError(storeErr("getCustomAgent"))

  def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    fetchAllByPrefix[StoredCustomAgentRow]("agent:", "getCustomAgentByName")
      .map(_.flatMap(fromStoreAgentRow).find(_.name.equalsIgnoreCase(name.trim)))

  def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] =
    fetchAllByPrefix[StoredCustomAgentRow]("agent:", "listCustomAgents")
      .map(_.flatMap(fromStoreAgentRow).sortBy(agent => (agent.displayName.toLowerCase, agent.name.toLowerCase)))

  def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
    for
      id       <- ZIO
                    .fromOption(agent.id)
                    .orElseFail(PersistenceError.QueryFailed("updateCustomAgent", "Missing id for custom agent update"))
      _        <- validateCustomAgentName(agent.name, "updateCustomAgent")
      existing <-
        configStore.fetch[String, StoredCustomAgentRow](agentKey(id)).mapError(storeErr("updateCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id.toString))
                    .when(existing.isEmpty)
      _        <- configStore
                    .store(agentKey(id), toStoreAgentRow(agent, id))
                    .mapError(storeErr("updateCustomAgent"))
    yield ()

  def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] =
    for
      existing <-
        configStore.fetch[String, StoredCustomAgentRow](agentKey(id)).mapError(storeErr("deleteCustomAgent"))
      _        <- ZIO
                    .fail(PersistenceError.NotFound("custom_agents", id.toString))
                    .when(existing.isEmpty)
      _        <- configStore.remove[String](agentKey(id)).mapError(storeErr("deleteCustomAgent"))
    yield ()

  private def bindingKey(binding: AgentChannelBinding): String =
    val base = s"agent.binding.${binding.agentId.value}.${binding.channelName.trim.toLowerCase}"
    binding.accountId.map(_.trim).filter(_.nonEmpty).map(id => s"$base.$id").getOrElse(base)

  private def parseBindingKey(key: String): Option[AgentChannelBinding] =
    key.stripPrefix("agent.binding.").split("\\.", -1).toList match
      case agentId :: channelName :: Nil                                   =>
        Some(AgentChannelBinding(AgentId(agentId), channelName, None))
      case agentId :: channelName :: accountParts if accountParts.nonEmpty =>
        val accountId = accountParts.mkString(".").trim
        Some(
          AgentChannelBinding(
            agentId = AgentId(agentId),
            channelName = channelName,
            accountId = Option.when(accountId.nonEmpty)(accountId),
          )
        )
      case _                                                               => None

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
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(k => configStore.fetch[String, V](k).mapError(storeErr(op))).map(_.flatten)
      )

  private def toStoreWorkflowRow(workflow: WorkflowRow, id: Long): StoredWorkflowRow =
    StoredWorkflowRow(
      id = id.toString,
      name = workflow.name,
      description = workflow.description,
      stepsJson = workflow.steps,
      isBuiltin = workflow.isBuiltin,
      createdAt = workflow.createdAt,
      updatedAt = workflow.updatedAt,
    )

  private def fromStoreWorkflowRow(workflow: StoredWorkflowRow): Option[WorkflowRow] =
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

  private def toStoreAgentRow(agent: CustomAgentRow, id: Long): StoredCustomAgentRow =
    StoredCustomAgentRow(
      id = id.toString,
      name = agent.name,
      displayName = agent.displayName,
      description = agent.description,
      systemPrompt = agent.systemPrompt,
      tagsJson = agent.tags,
      enabled = agent.enabled,
      createdAt = agent.createdAt,
      updatedAt = agent.updatedAt,
    )

  private def fromStoreAgentRow(agent: StoredCustomAgentRow): Option[CustomAgentRow] =
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

  private def decodeSetting(key: String, raw: String): SettingRow =
    SettingRow(key = key, value = raw, updatedAt = Instant.EPOCH)

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
