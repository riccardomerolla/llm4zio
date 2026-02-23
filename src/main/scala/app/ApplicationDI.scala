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
import _root_.models.*
import activity.boundary.ActivityController
import activity.control.ActivityHub
import activity.entity.ActivityRepository
import app.boundary.{
  AgentMonitorController as AppAgentMonitorController,
  HealthController as AppHealthController,
  WebServer,
}
import app.control.HealthMonitor
import com.bot4s.telegram.clients.FutureSttpClient
import conversation.boundary.{
  ChatController as ConversationChatController,
  WebSocketController as ConversationWebSocketController,
}
import core.*
import db.*
import gateway.boundary.telegram.*
import gateway.boundary.{
  ChannelController as GatewayChannelController,
  TelegramController as GatewayTelegramController,
}
import gateway.control.{ MessageRouter, * }
import issues.boundary.IssueController as IssuesIssueController
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.{ AnyTool, JsonSchema }
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

object ApplicationDI:

  type CommonServices =
    FileService &
      StoreConfig &
      ConfigStoreModule.ConfigStoreService &
      DataStoreModule.DataStoreService &
      MemoryStoreModule.MemoryEntriesStore &
      GatewayConfig &
      Ref[GatewayConfig] &
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
      TaskProgressNotifier &
      MemoryRepository &
      EmbeddingService

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
      // Create runtime config ref with merged DB settings
      configRefLayer,
      configAwareLlmServiceLayer,
      EmbeddingService.live,
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
    ZLayer.make[WebServer](
      commonLayers(config, storeConfig),
      ZLayer.succeed(config.resolvedProviderConfig),
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
      AgentConfigResolver.live,
      OrchestrationIssueAssignmentOrchestrator.live,
      StreamAbortRegistry.live,
      ConversationChatController.live,
      IssuesIssueController.live,
      ActivityController.live,
      MemoryBoundaryController.live,
      GatewayChannelController.live,
      AppHealthController.live,
      GatewayTelegramController.live,
      ConversationWebSocketController.live,
      WebServer.live,
    )

  private val channelRegistryLayer
    : ZLayer[Ref[GatewayConfig] & AgentRegistry & TaskRepository & TaskExecutor, Nothing, ChannelRegistry] =
    ZLayer.scoped {
      for
        configRef     <- ZIO.service[Ref[GatewayConfig]]
        agentRegistry <- ZIO.service[AgentRegistry]
        repository    <- ZIO.service[TaskRepository]
        taskExecutor  <- ZIO.service[TaskExecutor]
        channels      <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
        runtime       <- Ref.Synchronized.make(Map.empty[String, ChannelRuntime])
        clients       <- Ref.Synchronized.make(Map.empty[String, TelegramClient])
        backend       <- ZIO.attempt {
                           given ExecutionContext = ExecutionContext.global
                           DefaultFutureBackend()
                         }.orDie
        registry       = ChannelRegistryLive(channels, runtime)
        websocket     <- WebSocketChannel.make()
        telegramClient = ConfigAwareTelegramClient(configRef, clients, backend)
        telegram      <- TelegramChannel.make(
                           client = telegramClient,
                           workflowNotifier = WorkflowNotifierLive(telegramClient, agentRegistry, repository, taskExecutor),
                           taskRepository = Some(repository),
                           taskExecutor = Some(taskExecutor),
                         )
        _             <- registry.register(websocket)
        _             <- registry.register(telegram)
      yield registry
    }

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
      currentService.flatMap(_.execute(prompt))

    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      zio.stream.ZStream.unwrap(currentService.map(_.executeStream(prompt)))

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      currentService.flatMap(_.executeWithHistory(messages))

    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      zio.stream.ZStream.unwrap(currentService.map(_.executeStreamWithHistory(messages)))

    override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      currentService.flatMap(_.executeWithTools(prompt, tools))

    override def executeStructured[A: zio.json.JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      currentService.flatMap(_.executeStructured(prompt, schema))

    override def isAvailable: UIO[Boolean] =
      currentService.flatMap(_.isAvailable).orElseSucceed(false)

    private def currentService: IO[LlmError, LlmService] =
      for
        cfg <- configRef.get.map(conf => aiConfigToLlmConfig(conf.resolvedProviderConfig))
        svc <- cacheRef.modifyZIO { current =>
                 current.get(cfg) match
                   case Some(existing) => ZIO.succeed((existing, current))
                   case None           =>
                     ZIO
                       .attempt(buildProvider(cfg))
                       .mapError(th => LlmError.ConfigError(Option(th.getMessage).getOrElse(th.toString)))
                       .map(created => (created, current + (cfg -> created)))
               }
      yield svc

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
