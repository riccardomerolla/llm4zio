package app

import scala.concurrent.ExecutionContext

import zio.*

import _root_.config.entity.{ ConfigRepository, GatewayConfig }
import taskrun.entity.TaskRepository
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
        _             <- registerOptionalExternalChannel(
                           registry = registry,
                           name = "discord",
                           enabled = settingMap.get("channel.discord.enabled").exists(_.equalsIgnoreCase("true")),
                           channel =
                             DiscordChannel.make(
                               scopeStrategy = parseSessionScopeStrategy(
                                 settingMap.get("channel.discord.sessionScopeStrategy")
                               ),
                               config = DiscordConfig(
                                 botToken = settingMap.getOrElse("channel.discord.botToken", ""),
                                 guildId = settingMap.get("channel.discord.guildId").map(_.trim).filter(_.nonEmpty),
                                 defaultChannelId = settingMap.get("channel.discord.channelId").map(_.trim).filter(_.nonEmpty),
                               ),
                             ),
                         )
        _             <- registerOptionalExternalChannel(
                           registry = registry,
                           name = "slack",
                           enabled = settingMap.get("channel.slack.enabled").exists(_.equalsIgnoreCase("true")),
                           channel =
                             SlackChannel.make(
                               scopeStrategy = parseSessionScopeStrategy(settingMap.get("channel.slack.sessionScopeStrategy")),
                               config = SlackConfig(
                                 appToken = settingMap.getOrElse("channel.slack.appToken", ""),
                                 botToken = settingMap.get("channel.slack.botToken").map(_.trim).filter(_.nonEmpty),
                                 defaultChannelId = settingMap.get("channel.slack.channelId").map(_.trim).filter(_.nonEmpty),
                                 socketMode = settingMap.get("channel.slack.socketMode").exists(_.equalsIgnoreCase("true")),
                               ),
                             ),
                         )
      yield registry
    }

  private def registerOptionalExternalChannel(
    registry: ChannelRegistry,
    name: String,
    enabled: Boolean,
    channel: UIO[MessageChannel],
  ): UIO[Unit] =
    if enabled then
      channel.flatMap(registry.register)
    else registry.markNotConfigured(name)

  private def parseSessionScopeStrategy(raw: Option[String]): SessionScopeStrategy =
    raw
      .flatMap(SessionScopeStrategy.fromString)
      .getOrElse(SessionScopeStrategy.PerConversation)
