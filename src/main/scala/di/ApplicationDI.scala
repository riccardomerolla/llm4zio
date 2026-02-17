package di

import zio.*
import zio.http.netty.NettyConfig
import zio.http.{ Client, DnsResolver, ZClient }

import _root_.models.*
import agents.*
import core.*
import db.*
import gateway.*
import gateway.telegram.*
import llm4zio.core.{ LlmConfig, LlmProvider, LlmService }
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import orchestration.*
import web.controllers.*
import web.{ ActivityHub, StreamAbortRegistry, WebServer, WebSocketServer }

object ApplicationDI:

  type CommonServices =
    FileService &
      MigrationConfig &
      HttpAIClient &
      LlmService &
      StateService &
      javax.sql.DataSource &
      MigrationRepository &
      WorkflowService &
      ActivityRepository &
      ActivityHub &
      ProgressTracker &
      ResultPersister &
      ChatRepository &
      AgentRegistry &
      LogTailer &
      HealthMonitor &
      ChannelRegistry &
      MessageRouter &
      GatewayService &
      TelegramPollingService &
      CobolDiscoveryAgent &
      CobolAnalyzerAgent &
      BusinessLogicExtractorAgent &
      DependencyMapperAgent &
      JavaTransformerAgent &
      ValidationAgent &
      DocumentationAgent

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

  def commonLayers(config: MigrationConfig, dbPath: java.nio.file.Path): ZLayer[Any, Nothing, CommonServices] =
    val llmConfig = aiConfigToLlmConfig(config.resolvedProviderConfig)
    ZLayer.make[CommonServices](
      // Core services and configuration
      FileService.live,
      ZLayer.succeed(config),
      ZLayer.succeed(llmConfig),

      // Service implementations
      httpClientLayer(config).orDie,
      HttpAIClient.live,
      HttpClient.live,
      GeminiCliExecutor.live,
      LlmService.fromConfig,
      StateService.live(config.stateDir),
      ZLayer.succeed(DatabaseConfig(s"jdbc:sqlite:$dbPath")),
      Database.live.mapError(err => new RuntimeException(err.toString)).orDie,
      MigrationRepository.live,
      WorkflowService.live,
      ActivityRepository.live.mapError(err => new RuntimeException(err.toString)).orDie,
      ActivityHub.live,
      ProgressTracker.live,
      ResultPersister.live,
      ChatRepository.live.mapError(err => new RuntimeException(err.toString)).orDie,
      AgentRegistry.live,
      LogTailer.live,
      HealthMonitor.live,
      channelRegistryLayer,
      MessageRouter.live,
      GatewayService.live,
      TelegramPollingService.live,

      // Agent implementations
      CobolDiscoveryAgent.live,
      CobolAnalyzerAgent.live,
      BusinessLogicExtractorAgent.live,
      DependencyMapperAgent.live,
      JavaTransformerAgent.live,
      ValidationAgent.live,
      DocumentationAgent.live,
    )

  private def httpClientLayer(config: MigrationConfig): ZLayer[Any, Throwable, Client] =
    val timeout      = config.resolvedProviderConfig.timeout
    val idleTimeout  = timeout + 300.seconds
    val clientConfig = ZClient.Config.default.copy(
      idleTimeout = Some(idleTimeout),
      connectionTimeout = Some(300.seconds),
    )
    (ZLayer.succeed(clientConfig) ++ ZLayer.succeed(NettyConfig.defaultWithFastShutdown) ++
      DnsResolver.default) >>> Client.live

  def orchestratorLayer(config: MigrationConfig): ZLayer[Any, Nothing, MigrationOrchestrator] =
    orchestratorLayer(config, config.stateDir.resolve("migration.db"))

  def orchestratorLayer(config: MigrationConfig, dbPath: java.nio.file.Path)
    : ZLayer[Any, Nothing, MigrationOrchestrator] =
    ZLayer.make[MigrationOrchestrator](
      commonLayers(config, dbPath),
      WorkspaceCoordinator.quotasLayer(config),
      WorkspaceCoordinator.live,

      // Orchestration
      MigrationOrchestrator.live,
    )

  def webServerLayer(config: MigrationConfig, dbPath: java.nio.file.Path): ZLayer[Any, Nothing, WebServer] =
    ZLayer.make[WebServer](
      commonLayers(config, dbPath),
      ZLayer.succeed(config.resolvedProviderConfig),
      WorkspaceCoordinator.quotasLayer(config),
      WorkspaceCoordinator.live,
      OrchestratorControlPlane.live,
      MigrationOrchestrator.live,
      RunsController.live,
      AnalysisController.live,
      GraphController.live,
      DashboardController.live,
      SettingsController.live,
      AgentsController.live,
      AgentMonitorController.live,
      WorkflowsController.live,
      LogsController.live,
      AgentConfigResolver.live,
      IssueAssignmentOrchestrator.live,
      StreamAbortRegistry.live,
      ChatController.live,
      ActivityController.live,
      HealthController.live,
      TelegramController.live,
      WebSocketServer.live,
      WebServer.live,
    )

  private val channelRegistryLayer: ULayer[ChannelRegistry] =
    ZLayer.fromZIO {
      for
        ref       <- Ref.Synchronized.make(Map.empty[String, MessageChannel])
        registry   = ChannelRegistryLive(ref)
        websocket <- WebSocketChannel.make()
        telegram  <- TelegramChannel.make(noopTelegramClient)
        _         <- registry.register(websocket)
        _         <- registry.register(telegram)
      yield registry
    }

  private val noopTelegramClient: TelegramClient =
    new TelegramClient:
      override def getUpdates(
        offset: Option[Long],
        limit: Int,
        timeoutSeconds: Int,
        timeout: Duration,
      ): IO[TelegramClientError, List[TelegramUpdate]] =
        ZIO.succeed(Nil)

      override def sendMessage(
        request: TelegramSendMessage,
        timeout: Duration,
      ): IO[TelegramClientError, TelegramMessage] =
        ZIO.fail(TelegramClientError.Network("telegram client is not configured"))
