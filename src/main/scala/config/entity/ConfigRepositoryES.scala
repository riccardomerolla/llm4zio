package config.entity

import zio.*

import db.ConfigRepositoryES as DbConfigRepositoryES
import shared.errors.PersistenceError
import shared.store.ConfigStoreModule

final case class ConfigRepositoryES(delegate: DbConfigRepositoryES) extends ConfigRepository:
  override def getAllSettings: IO[PersistenceError, List[SettingRow]] = delegate.getAllSettings

  override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] = delegate.getSetting(key)

  override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] =
    delegate.upsertSetting(key, value)

  override def deleteSetting(key: String): IO[PersistenceError, Unit] = delegate.deleteSetting(key)

  override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit] =
    delegate.deleteSettingsByPrefix(prefix)

  override def listAgentChannelBindings: IO[PersistenceError, List[AgentChannelBinding]] =
    delegate.listAgentChannelBindings

  override def upsertAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] =
    delegate.upsertAgentChannelBinding(binding)

  override def deleteAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] =
    delegate.deleteAgentChannelBinding(binding)

  override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long] =
    delegate.createWorkflow(workflow)

  override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]] = delegate.getWorkflow(id)

  override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]] =
    delegate.getWorkflowByName(name)

  override def listWorkflows: IO[PersistenceError, List[WorkflowRow]] = delegate.listWorkflows

  override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit] =
    delegate.updateWorkflow(workflow)

  override def deleteWorkflow(id: Long): IO[PersistenceError, Unit] = delegate.deleteWorkflow(id)

  override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long] =
    delegate.createCustomAgent(agent)

  override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]] =
    delegate.getCustomAgent(id)

  override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
    delegate.getCustomAgentByName(name)

  override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]] = delegate.listCustomAgents

  override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit] =
    delegate.updateCustomAgent(agent)

  override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit] = delegate.deleteCustomAgent(id)

object ConfigRepositoryES:
  val live: ZLayer[ConfigStoreModule.ConfigStoreService, Nothing, ConfigRepository] =
    ZLayer.fromFunction((configStore: ConfigStoreModule.ConfigStoreService) =>
      ConfigRepositoryES(DbConfigRepositoryES(configStore))
    )
