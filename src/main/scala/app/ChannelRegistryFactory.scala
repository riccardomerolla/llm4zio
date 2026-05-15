package app

import scala.concurrent.ExecutionContext

import zio.*

import _root_.config.entity.{ ConfigRepository, GatewayConfig }
import agent.entity.AgentRegistry
import gateway.boundary.telegram.{ ConfigAwareTelegramClient, TelegramChannel }
import gateway.control.*
import gateway.entity.SessionScopeStrategy
import orchestration.entity.TaskExecutor
import sttp.client4.DefaultFutureBackend
import taskrun.entity.TaskRepository

object ChannelRegistryFactory:

  val live
    : ZLayer[
      Ref[GatewayConfig] & AgentRegistry & TaskRepository & TaskExecutor & ConfigRepository &
        decision.entity.DecisionRepository,
      Nothing,
      ChannelRegistry,
    ] =
    ZLayer.scoped {
      for
        configRef     <- ZIO.service[Ref[GatewayConfig]]
        agentRegistry <- ZIO.service[AgentRegistry]
        repository    <- ZIO.service[TaskRepository]
        taskExecutor  <- ZIO.service[TaskExecutor]
        configRepo    <- ZIO.service[ConfigRepository]
        decisionRepo  <- ZIO.service[decision.entity.DecisionRepository]
        channels      <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
        runtime       <- Ref.Synchronized.make(Map.empty[String, ChannelRuntime])
        clients       <- Ref.Synchronized.make(Map.empty[String, TelegramClient])
        backend       <- ZIO.attempt {
                           given ExecutionContext = ExecutionContext.global
                           DefaultFutureBackend()
                         }.orDie
        registry       = ChannelRegistryLive(channels, runtime)
        settings      <- configRepo.getAllSettings.orElseSucceed(Nil)
        settingMap     = settings.map(row => row.key -> row.value).toMap
        websocket     <- WebSocketChannel.make(
                           scopeStrategy = parseSessionScopeStrategy(
                             settingMap.get("channel.websocket.sessionScopeStrategy")
                           )
                         )
        telegramClient = ConfigAwareTelegramClient(configRef, clients, backend)
        telegram      <- TelegramChannel.make(
                           client = telegramClient,
                           workflowNotifier = WorkflowNotifierLive(telegramClient, agentRegistry, repository, taskExecutor),
                           taskRepository = Some(repository),
                           taskExecutor = Some(taskExecutor),
                           decisionRepository = Some(decisionRepo),
                           scopeStrategy = parseSessionScopeStrategy(settingMap.get("telegram.sessionScopeStrategy")),
                         )
        _             <- registry.register(websocket)
        _             <- registry.register(telegram)
      yield registry
    }

  private def parseSessionScopeStrategy(raw: Option[String]): SessionScopeStrategy =
    raw
      .flatMap(SessionScopeStrategy.fromString)
      .getOrElse(SessionScopeStrategy.PerConversation)
