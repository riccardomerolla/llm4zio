package app

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }

import scala.concurrent.{ ExecutionContext, Future }

import zio.*
import zio.http.netty.NettyConfig
import zio.http.{ Client, DnsResolver, ZClient }
import zio.json.*

import _root_.config.SettingsApplier
import _root_.config.boundary.{
  AgentsController as ConfigAgentsController,
  ConfigController as ConfigBoundaryController,
  SettingsController as SettingsBoundaryController,
  WorkflowsController as ConfigWorkflowsController,
}
import _root_.config.control.{ ConfigValidator, ModelService }
import _root_.config.entity.{ AIProvider, AIProviderConfig, GatewayConfig }
import activity.boundary.ActivityController
import activity.control.ActivityHub
import activity.entity.ActivityRepository
import agent.entity.{ AgentEventStoreES, AgentRepositoryES }
import app.boundary.{
  AgentMonitorController as AppAgentMonitorController,
  HealthController as AppHealthController,
  WebServer,
}
import app.control.{ FileService, HealthMonitor, HttpAIClient, LogTailer, StateService }
import com.bot4s.telegram.clients.FutureSttpClient
import conversation.boundary.{
  ChatController as ConversationChatController,
  WebSocketController as ConversationWebSocketController,
}
import db.*
import gateway.boundary.discord.DiscordGatewayService
import gateway.boundary.telegram.*
import gateway.boundary.{
  ChannelController as GatewayChannelController,
  TelegramController as GatewayTelegramController,
}
import gateway.control.{ MessageRouter, * }
import issues.boundary.IssueController as IssuesIssueController
import issues.entity.{ IssueEventStoreES, IssueRepositoryES }
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.{ AnyTool, JsonSchema, ToolRegistry }
import memory.boundary.MemoryController as MemoryBoundaryController
import memory.control.{ EmbeddingService, MemoryRepositoryES }
import memory.entity.*
import orchestration.control.*
import orchestration.control.{
  IssueAssignmentOrchestrator as OrchestrationIssueAssignmentOrchestrator,
  ProgressTracker as OrchestrationProgressTracker,
}
import shared.store.{ ConfigStoreModule, DataStoreModule, MemoryStoreModule, StoreConfig }
import shared.web.StreamAbortRegistry
import sttp.client4.DefaultFutureBackend
import taskrun.boundary.{
  DashboardController as TaskRunDashboardController,
  GraphController as TaskRunGraphController,
  LogsController as TaskRunLogsController,
  ReportsController as TaskRunReportsController,
  TasksController as TaskRunTasksController,
}
import workspace.control.{ GitService, GitWatcher, InteractiveAgentRunner, RunSessionManager, WorkspaceRunService }
import workspace.entity.WorkspaceRepository

object ApplicationDI:

  type CommonServices =
    FileService &
      StoreConfig &
      ConfigStoreModule.ConfigStoreService &
      DataStoreModule.DataStoreService &
      MemoryStoreModule.MemoryEntriesStore &
      GatewayConfig &
      Ref[GatewayConfig] &
      ModelService &
      HttpClient &
      GeminiCliExecutor &
      HttpAIClient &
      LlmService &
      StateService &
      TaskRepository &
      ConfigRepository &
      WorkflowService &
      ActivityRepository &
      ActivityHub &
      OrchestrationProgressTracker &
      ChatRepository &
      AgentRegistry &
      WorkflowEngine &
      AgentDispatcher &
      OrchestratorControlPlane &
      TaskExecutor &
      LogTailer &
      HealthMonitor &
      ConfigValidator &
      ChannelRegistry &
      MessageRouter &
      GatewayService &
      TelegramPollingService &
      DiscordGatewayService &
      TaskProgressNotifier &
      AgentConfigResolver &
      MemoryRepository &
      EmbeddingService &
      GitService &
      GitWatcher

  def aiProviderToLlmProvider(aiProvider: AIProvider): LlmProvider =
    aiProvider match
      case AIProvider.GeminiCli => LlmProvider.GeminiCli
      case AIProvider.GeminiApi => LlmProvider.GeminiApi
      case AIProvider.OpenAi    => LlmProvider.OpenAI
      case AIProvider.Anthropic => LlmProvider.Anthropic
      case AIProvider.LmStudio  => LlmProvider.LmStudio
      case AIProvider.Ollama    => LlmProvider.Ollama
      case AIProvider.OpenCode  => LlmProvider.OpenCode

  def aiConfigToLlmConfig(aiConfig: AIProviderConfig): LlmConfig =
    LlmConfig(
      provider = aiProviderToLlmProvider(aiConfig.provider),
      model = aiConfig.model,
      baseUrl = aiConfig.baseUrl,
      apiKey = aiConfig.apiKey,
      timeout = aiConfig.timeout,
      maxRetries = aiConfig.maxRetries,
      requestsPerMinute = aiConfig.requestsPerMinute,
      burstSize = aiConfig.burstSize,
      acquireTimeout = aiConfig.acquireTimeout,
      temperature = aiConfig.temperature,
      maxTokens = aiConfig.maxTokens,
    )

  def commonLayers(config: GatewayConfig, storeConfig: StoreConfig): ZLayer[Any, Nothing, CommonServices] =
    ZLayer.make[CommonServices](
      // Core services and configuration
      FileService.live,
      ZLayer.succeed(config),

      // Service implementations
      httpClientLayer(config).orDie,
      HttpAIClient.live,
      HttpClient.live,
      GeminiCliExecutor.live,
      StateService.live(config.stateDir),
      ZLayer.succeed(storeConfig),
      ConfigStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      DataStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      MemoryStoreModule.live.mapError(err => new RuntimeException(err.toString)).orDie,
      ConfigRepository.live,
      TaskRepository.live,
      ZLayer.succeed(config.resolvedProviderConfig),
      AgentConfigResolver.live,
      // Create runtime config ref with merged DB settings
      configRefLayer,
      ModelService.live,
      configAwareLlmServiceLayer,
      EmbeddingService.live,
      GitService.live,
      GitWatcher.live,
      MemoryRepositoryES.live,
      WorkflowService.live,
      ActivityRepository.live,
      ActivityHub.live,
      OrchestrationProgressTracker.live,
      ChatRepository.live,
      AgentRegistry.live,
      WorkflowEngine.live,
      AgentDispatcher.live,
      OrchestratorControlPlane.live,
      TaskExecutor.live,
      LogTailer.live,
      HealthMonitor.live,
      ConfigValidator.live,
      channelRegistryLayer,
      MessageRouter.live,
      GatewayService.live,
      TelegramPollingService.live,
      DiscordGatewayService.live,
      TaskProgressNotifier.live,
    )

  /** Create a Ref[GatewayConfig] that reads and merges DB settings on startup */
  private val configRefLayer: ZLayer[GatewayConfig & ConfigRepository & StoreConfig, Nothing, Ref[GatewayConfig]] =
    ZLayer.fromZIO {
      for
        baseConfig   <- ZIO.service[GatewayConfig]
        configRepo   <- ZIO.service[ConfigRepository]
        storeConfig  <- ZIO.service[StoreConfig]
        dbSettings   <- configRepo.getAllSettings.orElseSucceed(Nil)
        dbMap         = dbSettings.map(row => row.key -> row.value).toMap
        snapshotMap  <- loadSettingsSnapshot(storeConfig).orElseSucceed(Map.empty)
        effectiveMap <-
          if dbMap.nonEmpty then ZIO.succeed(dbMap)
          else if snapshotMap.nonEmpty then
            configRepo.upsertSettings(snapshotMap).orElseSucceed(()) *> ZIO.succeed(snapshotMap)
          else ZIO.succeed(Map.empty[String, String])
        mergedConfig  = if effectiveMap.nonEmpty then SettingsApplier.toGatewayConfig(effectiveMap) else baseConfig
        ref          <- Ref.make(mergedConfig)
      yield ref
    }

  private def loadSettingsSnapshot(storeConfig: StoreConfig): IO[Throwable, Map[String, String]] =
    ZIO.attemptBlocking {
      val snapshot = Paths.get(storeConfig.configStorePath).resolve("settings.snapshot.json")
      if Files.exists(snapshot) then
        val raw = Files.readString(snapshot, StandardCharsets.UTF_8)
        raw.fromJson[Map[String, String]].getOrElse(Map.empty)
      else Map.empty
    }

  private def httpClientLayer(config: GatewayConfig): ZLayer[Any, Throwable, Client] =
    val timeout      = config.resolvedProviderConfig.timeout
    val idleTimeout  = timeout + 300.seconds
    val clientConfig = ZClient.Config.default.copy(
      idleTimeout = Some(idleTimeout),
      connectionTimeout = Some(300.seconds),
    )
    (ZLayer.succeed(clientConfig) ++ ZLayer.succeed(NettyConfig.defaultWithFastShutdown) ++
      DnsResolver.default) >>> Client.live

  private val configAwareLlmServiceLayer
    : ZLayer[Ref[GatewayConfig] & HttpClient & GeminiCliExecutor, Nothing, LlmService] =
    ZLayer.fromZIO {
      for
        configRef <- ZIO.service[Ref[GatewayConfig]]
        http      <- ZIO.service[HttpClient]
        cliExec   <- ZIO.service[GeminiCliExecutor]
        cache     <- Ref.Synchronized.make(Map.empty[LlmConfig, LlmService])
      yield ConfigAwareLlmService(configRef, http, cliExec, cache)
    }

  def webServerLayer(config: GatewayConfig, storeConfig: StoreConfig): ZLayer[Any, Nothing, WebServer] =
    ZLayer.make[WebServer & AutoDispatcher](
      commonLayers(config, storeConfig),
      TaskRunDashboardController.live,
      TaskRunTasksController.live,
      TaskRunReportsController.live,
      TaskRunGraphController.live,
      SettingsBoundaryController.live,
      ConfigBoundaryController.live,
      ConfigAgentsController.live,
      AppAgentMonitorController.live,
      ConfigWorkflowsController.live,
      TaskRunLogsController.live,
      OrchestrationIssueAssignmentOrchestrator.live,
      StreamAbortRegistry.live,
      ToolRegistry.layer,
      WorkspaceRepository.live,
      AgentEventStoreES.live,
      AgentRepositoryES.live,
      InteractiveAgentRunner.live,
      RunSessionManager.live,
      IssueEventStoreES.live,
      IssueRepositoryES.live,
      DependencyResolver.live,
      AgentPoolManager.live,
      IssueDispatchStatusService.live,
      WorkspaceRunService.live,
      AutoDispatcher.live,
      ConversationChatController.live,
      IssuesIssueController.live,
      ActivityController.live,
      MemoryBoundaryController.live,
      GatewayChannelController.live,
      AppHealthController.live,
      GatewayTelegramController.live,
      ConversationWebSocketController.live,
      mcp.McpService.live,
      WebServer.live,
    ) >>> ZLayer.service[WebServer]

  private val channelRegistryLayer
    : ZLayer[Ref[GatewayConfig] & AgentRegistry & TaskRepository & TaskExecutor & ConfigRepository, Nothing, ChannelRegistry] =
    ZLayer.scoped {
      for
        configRef     <- ZIO.service[Ref[GatewayConfig]]
        agentRegistry <- ZIO.service[AgentRegistry]
        repository    <- ZIO.service[TaskRepository]
        taskExecutor  <- ZIO.service[TaskExecutor]
        configRepo    <- ZIO.service[ConfigRepository]
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

  private def parseSessionScopeStrategy(raw: Option[String]): gateway.entity.SessionScopeStrategy =
    raw
      .flatMap(gateway.entity.SessionScopeStrategy.fromString)
      .getOrElse(gateway.entity.SessionScopeStrategy.PerConversation)

  final private case class ConfigAwareTelegramClient(
    configRef: Ref[GatewayConfig],
    clientsRef: Ref.Synchronized[Map[String, TelegramClient]],
    backend: sttp.client4.WebSocketBackend[Future],
  ) extends TelegramClient:

    private given ExecutionContext = ExecutionContext.global

    override def getUpdates(
      offset: Option[Long],
      limit: Int,
      timeoutSeconds: Int,
      timeout: Duration,
    ): IO[TelegramClientError, List[TelegramUpdate]] =
      currentClient.flatMap(_.getUpdates(offset, limit, timeoutSeconds, timeout))

    override def sendMessage(
      request: TelegramSendMessage,
      timeout: Duration,
    ): IO[TelegramClientError, TelegramMessage] =
      currentClient.flatMap(_.sendMessage(request, timeout))

    override def sendDocument(
      request: TelegramSendDocument,
      timeout: Duration,
    ): IO[TelegramClientError, TelegramMessage] =
      currentClient.flatMap(_.sendDocument(request, timeout))

    private def currentClient: IO[TelegramClientError, TelegramClient] =
      for
        config <- configRef.get
        token  <- ZIO
                    .fromOption(config.telegram.botToken.map(_.trim).filter(_.nonEmpty))
                    .orElseFail(
                      TelegramClientError.InvalidConfig(
                        "telegram bot token is not configured; set telegram.botToken in Settings"
                      )
                    )
        client <- clientsRef.modifyZIO { current =>
                    current.get(token) match
                      case Some(existing) =>
                        ZIO.succeed((existing, current))
                      case None           =>
                        ZIO
                          .attempt {
                            val handler = FutureSttpClient(
                              token = token,
                              telegramHost = "api.telegram.org",
                            )(using backend, summon[ExecutionContext])
                            TelegramClient.fromRequestHandler(handler)
                          }
                          .mapError(err =>
                            TelegramClientError.InvalidConfig(
                              s"failed to initialize telegram client: ${Option(err.getMessage).getOrElse(err.toString)}"
                            )
                          )
                          .map(created => (created, current + (token -> created)))
                  }
      yield client

  final private case class ConfigAwareLlmService(
    configRef: Ref[GatewayConfig],
    http: HttpClient,
    cliExec: GeminiCliExecutor,
    cacheRef: Ref.Synchronized[Map[LlmConfig, LlmService]],
  ) extends LlmService:

    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      withFailover(_.execute(prompt))

    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      zio.stream.ZStream.unwrap(serviceChain.map(chain => failoverStream(chain)(_.executeStream(prompt))))

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      withFailover(_.executeWithHistory(messages))

    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      zio.stream.ZStream.unwrap(serviceChain.map(chain => failoverStream(chain)(_.executeStreamWithHistory(messages))))

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      withFailover(_.executeWithTools(prompt, tools))

    override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      withFailover(_.executeStructured(prompt, schema))

    override def isAvailable: UIO[Boolean] =
      serviceChain
        .flatMap { chain =>
          ZIO.foreach(chain)(_.isAvailable).map(_.exists(identity))
        }
        .orElseSucceed(false)

    private def serviceChain: IO[LlmError, List[LlmService]] =
      for
        aiCfg <- configRef.get.map(_.resolvedProviderConfig)
        cfgs   = fallbackConfigs(aiCfg)
        svcs  <- ZIO.foreach(cfgs)(providerFor)
      yield svcs

    private def providerFor(cfg: LlmConfig): IO[LlmError, LlmService] =
      cacheRef.modifyZIO { current =>
        current.get(cfg) match
          case Some(existing) => ZIO.succeed((existing, current))
          case None           =>
            ZIO
              .attempt(buildProvider(cfg))
              .mapError(th => LlmError.ConfigError(Option(th.getMessage).getOrElse(th.toString)))
              .map(created => (created, current + (cfg -> created)))
      }

    private def fallbackConfigs(primary: AIProviderConfig): List[LlmConfig] =
      val primaryLlm = aiConfigToLlmConfig(primary)
      val fallback   = primary.fallbackChain.models.map { ref =>
        aiConfigToLlmConfig(
          AIProviderConfig.withDefaults(
            primary.copy(
              provider = ref.provider.getOrElse(primary.provider),
              model = ref.modelId,
            )
          )
        )
      }
      (primaryLlm :: fallback).distinct

    private def withFailover[A](run: LlmService => IO[LlmError, A]): IO[LlmError, A] =
      serviceChain.flatMap { chain =>
        failoverIO(chain)(run)
      }

    private def failoverIO[A](services: List[LlmService])(run: LlmService => IO[LlmError, A]): IO[LlmError, A] =
      services match
        case head :: tail =>
          run(head).catchAll { err =>
            tail match
              case Nil => ZIO.fail(err)
              case _   => failoverIO(tail)(run)
          }
        case Nil          =>
          ZIO.fail(LlmError.ConfigError("No LLM provider configured"))

    private def failoverStream(
      services: List[LlmService]
    )(
      run: LlmService => zio.stream.Stream[LlmError, LlmChunk]
    ): zio.stream.Stream[LlmError, LlmChunk] =
      services match
        case head :: tail =>
          run(head).catchAll { err =>
            tail match
              case Nil => zio.stream.ZStream.fail(err)
              case _   => failoverStream(tail)(run)
          }
        case Nil          =>
          zio.stream.ZStream.fail(LlmError.ConfigError("No LLM provider configured"))

    private def buildProvider(cfg: LlmConfig): LlmService =
      import llm4zio.providers.*
      cfg.provider match
        case LlmProvider.GeminiCli => GeminiCliProvider.make(cfg, cliExec)
        case LlmProvider.GeminiApi => GeminiApiProvider.make(cfg, http)
        case LlmProvider.OpenAI    => OpenAIProvider.make(cfg, http)
        case LlmProvider.Anthropic => AnthropicProvider.make(cfg, http)
        case LlmProvider.LmStudio  => LmStudioProvider.make(cfg, http)
        case LlmProvider.Ollama    => OllamaProvider.make(cfg, http)
        case LlmProvider.OpenCode  => OpenCodeProvider.make(cfg, http)
