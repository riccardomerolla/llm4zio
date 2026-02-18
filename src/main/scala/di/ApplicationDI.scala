package di

import scala.concurrent.{ ExecutionContext, Future }

import zio.*
import zio.http.netty.NettyConfig
import zio.http.{ Client, DnsResolver, ZClient }

import _root_.config.SettingsApplier
import _root_.models.*
import agents.*
import com.bot4s.telegram.clients.FutureSttpClient
import core.*
import db.*
import gateway.*
import gateway.telegram.*
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.{ AnyTool, JsonSchema }
import orchestration.*
import sttp.client4.DefaultFutureBackend
import web.controllers.*
import web.{ ActivityHub, StreamAbortRegistry, WebServer }

object ApplicationDI:

  type CommonServices =
    FileService &
      GatewayConfig &
      Ref[GatewayConfig] &
      HttpAIClient &
      LlmService &
      StateService &
      javax.sql.DataSource &
      TaskRepository &
      WorkflowService &
      ActivityRepository &
      ActivityHub &
      ProgressTracker &
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
      TelegramPollingService

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

  def commonLayers(config: GatewayConfig, dbPath: java.nio.file.Path): ZLayer[Any, Nothing, CommonServices] =
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
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:$dbPath")),
      Database.live.mapError(err => new RuntimeException(err.toString)).orDie,
      TaskRepository.live,
      // Create runtime config ref with merged DB settings
      configRefLayer,
      configAwareLlmServiceLayer,
      WorkflowService.live,
      ActivityRepository.live.mapError(err => new RuntimeException(err.toString)).orDie,
      ActivityHub.live,
      ProgressTracker.live,
      ChatRepository.live.mapError(err => new RuntimeException(err.toString)).orDie,
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
    )

  /** Create a Ref[GatewayConfig] that reads and merges DB settings on startup */
  private val configRefLayer: ZLayer[GatewayConfig & TaskRepository, Nothing, Ref[GatewayConfig]] =
    ZLayer.fromZIO {
      for
        baseConfig  <- ZIO.service[GatewayConfig]
        repository  <- ZIO.service[TaskRepository]
        dbSettings  <- repository.getAllSettings
                         .mapError(_ => ())
                         .orElseSucceed(Seq.empty)
        settingsMap  = dbSettings.map(r => r.key -> r.value).toMap
        mergedConfig = if settingsMap.nonEmpty then SettingsApplier.toGatewayConfig(settingsMap) else baseConfig
        ref         <- Ref.make(mergedConfig)
      yield ref
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

  private val configAwareLlmServiceLayer: ZLayer[Ref[GatewayConfig] & HttpClient & GeminiCliExecutor, Nothing, LlmService] =
    ZLayer.fromZIO {
      for
        configRef <- ZIO.service[Ref[GatewayConfig]]
        http      <- ZIO.service[HttpClient]
        cliExec   <- ZIO.service[GeminiCliExecutor]
        cache     <- Ref.Synchronized.make(Map.empty[LlmConfig, LlmService])
      yield ConfigAwareLlmService(configRef, http, cliExec, cache)
    }

  def webServerLayer(config: GatewayConfig, dbPath: java.nio.file.Path): ZLayer[Any, Nothing, WebServer] =
    ZLayer.make[WebServer](
      commonLayers(config, dbPath),
      ZLayer.succeed(config.resolvedProviderConfig),
      DashboardController.live,
      TasksController.live,
      ReportsController.live,
      GraphController.live,
      SettingsController.live,
      ConfigController.live,
      AgentsController.live,
      AgentMonitorController.live,
      WorkflowsController.live,
      LogsController.live,
      AgentConfigResolver.live,
      IssueAssignmentOrchestrator.live,
      StreamAbortRegistry.live,
      ChatController.live,
      ActivityController.live,
      ChannelController.live,
      HealthController.live,
      TelegramController.live,
      WebSocketController.live,
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
