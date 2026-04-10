package config.entity

import zio.*

import shared.errors.PersistenceError
import shared.ids.Ids.AgentId

trait ConfigRepository:
  def getAllSettings: IO[PersistenceError, List[SettingRow]]
  def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]
  def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]
  def upsertSettings(settings: Map[String, String]): IO[PersistenceError, Unit]   =
    ZIO.foreachDiscard(settings) { case (key, value) => upsertSetting(key, value) }
  def deleteSetting(key: String): IO[PersistenceError, Unit]
  def getSettingsByPrefix(prefix: String): IO[PersistenceError, List[SettingRow]] =
    getAllSettings.map(_.filter(_.key.startsWith(prefix)))
  def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]

  def listAgentChannelBindings: IO[PersistenceError, List[AgentChannelBinding]] =
    getSettingsByPrefix("agent.binding.").map(_.flatMap(row => parseBindingKey(row.key)))

  def upsertAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] =
    upsertSetting(bindingKey(binding), "true")

  def deleteAgentChannelBinding(binding: AgentChannelBinding): IO[PersistenceError, Unit] =
    deleteSetting(bindingKey(binding))

  def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]
  def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]
  def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]
  def listWorkflows: IO[PersistenceError, List[WorkflowRow]]
  def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]
  def deleteWorkflow(id: Long): IO[PersistenceError, Unit]

  def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]
  def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]
  def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]]
  def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]
  def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]
  def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]

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

object ConfigRepository:
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

  def listAgentChannelBindings: ZIO[ConfigRepository, PersistenceError, List[AgentChannelBinding]] =
    ZIO.serviceWithZIO[ConfigRepository](_.listAgentChannelBindings)

  def upsertAgentChannelBinding(binding: AgentChannelBinding): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.upsertAgentChannelBinding(binding))

  def deleteAgentChannelBinding(binding: AgentChannelBinding): ZIO[ConfigRepository, PersistenceError, Unit] =
    ZIO.serviceWithZIO[ConfigRepository](_.deleteAgentChannelBinding(binding))

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
