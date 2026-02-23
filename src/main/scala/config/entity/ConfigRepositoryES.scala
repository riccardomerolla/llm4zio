package config.entity

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.error.EclipseStoreError
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, WorkflowId }
import shared.store.ConfigStoreModule

final case class ConfigRepositoryES(configStore: ConfigStoreModule.ConfigStoreService) extends ConfigRepository:

  private val typedStore = configStore.store

  private def settingKey(key: String): String                              = s"setting:$key"
  private def workflowKey(id: WorkflowId): String                          = s"workflow:${id.value}"
  private def agentKey(id: AgentId): String                                = s"agent:${id.value}"
  private def storeErr(op: String)(e: EclipseStoreError): PersistenceError =
    PersistenceError.QueryFailed(op, e.toString)

  override def getSetting(key: String): IO[PersistenceError, Setting] =
    typedStore
      .fetch[String, Setting](settingKey(key))
      .mapError(storeErr("getSetting"))
      .flatMap(_.fold[IO[PersistenceError, Setting]](ZIO.fail(PersistenceError.NotFound("setting", key)))(ZIO.succeed))

  override def putSetting(setting: Setting): IO[PersistenceError, Unit] =
    typedStore.store(settingKey(setting.key), setting).mapError(storeErr("putSetting"))

  override def listSettings: IO[PersistenceError, List[Setting]] =
    fetchByPrefix[Setting]("setting:", "listSettings").map(_.sortBy(_.key))

  override def deleteSetting(key: String): IO[PersistenceError, Unit] =
    typedStore.remove[String](settingKey(key)).mapError(storeErr("deleteSetting"))

  override def listWorkflows: IO[PersistenceError, List[Workflow]] =
    fetchByPrefix[Workflow]("workflow:", "listWorkflows").map(_.sortBy(w => (!w.isBuiltin, w.name.toLowerCase)))

  override def saveWorkflow(workflow: Workflow): IO[PersistenceError, Unit] =
    typedStore.store(workflowKey(workflow.id), workflow).mapError(storeErr("saveWorkflow"))

  override def deleteWorkflow(id: WorkflowId): IO[PersistenceError, Unit] =
    typedStore.remove[String](workflowKey(id)).mapError(storeErr("deleteWorkflow"))

  override def listAgents: IO[PersistenceError, List[CustomAgent]] =
    fetchByPrefix[CustomAgent]("agent:", "listAgents").map(_.sortBy(agent =>
      (agent.displayName.toLowerCase, agent.name.toLowerCase)
    ))

  override def saveAgent(agent: CustomAgent): IO[PersistenceError, Unit] =
    typedStore.store(agentKey(agent.id), agent).mapError(storeErr("saveAgent"))

  override def deleteAgent(id: AgentId): IO[PersistenceError, Unit] =
    typedStore.remove[String](agentKey(id)).mapError(storeErr("deleteAgent"))

  private def fetchByPrefix[V](prefix: String, op: String)(using zio.schema.Schema[V]): IO[PersistenceError, List[V]] =
    configStore.rawStore
      .streamKeys[String]
      .filter(_.startsWith(prefix))
      .runCollect
      .mapError(storeErr(op))
      .flatMap(keys =>
        ZIO.foreach(keys.toList)(key => typedStore.fetch[String, V](key).mapError(storeErr(op))).map(_.flatten)
      )

object ConfigRepositoryES:
  val live: ZLayer[ConfigStoreModule.ConfigStoreService, Nothing, ConfigRepository] =
    ZLayer.fromFunction(ConfigRepositoryES.apply)
