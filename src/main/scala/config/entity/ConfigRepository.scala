package config.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, WorkflowId }

trait ConfigRepository:
  def getSetting(key: String): IO[PersistenceError, Setting]
  def putSetting(setting: Setting): IO[PersistenceError, Unit]
  def listSettings: IO[PersistenceError, List[Setting]]
  def deleteSetting(key: String): IO[PersistenceError, Unit]

  def listWorkflows: IO[PersistenceError, List[Workflow]]
  def saveWorkflow(workflow: Workflow): IO[PersistenceError, Unit]
  def deleteWorkflow(id: WorkflowId): IO[PersistenceError, Unit]

  def listAgents: IO[PersistenceError, List[CustomAgent]]
  def saveAgent(agent: CustomAgent): IO[PersistenceError, Unit]
  def deleteAgent(id: AgentId): IO[PersistenceError, Unit]

object ConfigRepository:
  def getSetting(key: String): ZIO[ConfigRepository, PersistenceError, Setting] =
    ZIO.serviceWithZIO[ConfigRepository](_.getSetting(key))

  def putSetting(setting: Setting): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.putSetting(setting))

  def listSettings: ZIO[ConfigRepository, PersistenceError, List[Setting]] =
    ZIO.serviceWithZIO[ConfigRepository](_.listSettings)

  def deleteSetting(key: String): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteSetting(key))

  def listWorkflows: ZIO[ConfigRepository, PersistenceError, List[Workflow]] =
    ZIO.serviceWithZIO[ConfigRepository](_.listWorkflows)

  def saveWorkflow(workflow: Workflow): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.saveWorkflow(workflow))

  def deleteWorkflow(id: WorkflowId): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteWorkflow(id))

  def listAgents: ZIO[ConfigRepository, PersistenceError, List[CustomAgent]] =
    ZIO.serviceWithZIO[ConfigRepository](_.listAgents)

  def saveAgent(agent: CustomAgent): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.saveAgent(agent))

  def deleteAgent(id: AgentId): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteAgent(id))
