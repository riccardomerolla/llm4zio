package app

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }

import scala.concurrent.ExecutionContext

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
import _root_.config.entity.{ AIProvider, AIProviderConfig, ConfigRepository, ConfigRepositoryES, GatewayConfig }
import activity.boundary.ActivityController
import activity.control.ActivityHub
import activity.entity.ActivityRepository
import agent.entity.{ AgentEventStoreES, AgentRepositoryES }
import analysis.control.{ AnalysisAgentRunner, WorkspaceAnalysisScheduler }
import analysis.entity.{ AnalysisEventStoreES, AnalysisRepositoryES }
import app.boundary.{ AgentMonitorController as AppAgentMonitorController, HealthController as AppHealthController, * }
import app.control.{ HealthMonitor, LogTailer }
import board.boundary.BoardController as BoardBoundaryController
import board.control.*
import board.entity.BoardRepository
import conversation.boundary.{
  ChatController as ConversationChatController,
  WebSocketController as ConversationWebSocketController,
}
import daemon.boundary.DaemonsController
import daemon.control.DaemonAgentScheduler
import daemon.entity.DaemonAgentSpecRepositoryES
import db.*
import decision.control.DecisionInbox
import decision.entity.{ DecisionEventStoreES, DecisionRepositoryES }
import demo.boundary.DemoController
import demo.control.{ DemoOrchestrator, MockAgentRunner }
import demo.entity.DemoConfig
import evolution.control.EvolutionEngine
import evolution.entity.{ EvolutionProposalEventStoreES, EvolutionProposalRepositoryES }
import gateway.boundary.telegram.*
import gateway.boundary.{
  ChannelController as GatewayChannelController,
  TelegramController as GatewayTelegramController,
}
import gateway.control.{ MessageRouter, * }
import gateway.entity.*
import governance.control.{ GovernancePolicyEngine, GovernancePolicyService }
import governance.entity.{ GovernancePolicyEventStoreES, GovernancePolicyRepositoryES }
import issues.boundary.IssueController as IssuesIssueController
import issues.control.{ IssueWorkReportHydrator, IssueWorkReportSubscriber }
import issues.entity.IssueRepositoryBoard
import knowledge.boundary.KnowledgeController
import knowledge.control.{ KnowledgeExtractionService, KnowledgeGraphService }
import knowledge.entity.{ DecisionLogEventStoreES, DecisionLogRepositoryES }
import llm4zio.core.*
import llm4zio.observability.{ LlmMetrics, MeteredLlmService }
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import llm4zio.tools.ToolRegistry
import memory.boundary.MemoryController as MemoryBoundaryController
import memory.control.{ EmbeddingService, MemoryRepositoryES }
import memory.entity.*
import orchestration.control.*
import orchestration.control.{
  IssueAssignmentOrchestrator as OrchestrationIssueAssignmentOrchestrator,
  ProgressTracker as OrchestrationProgressTracker,
}
import orchestration.entity.{ AgentPoolManager, AgentRegistry, TaskExecutor, WorkflowService }
import plan.entity.{ PlanEventStoreES, PlanRepositoryES }
import project.boundary.ProjectsController
import project.control.ProjectStorageService
import project.entity.ProjectRepository
import prompts.PromptLoader
import sdlc.boundary.SdlcDashboardController
import sdlc.control.SdlcDashboardService
import shared.services.{ FileService, HttpAIClient, StateService }
import shared.store.{ ConfigStoreModule, DataStoreModule, DataStoreService, MemoryStoreModule, StoreConfig }
import shared.web.StreamAbortRegistry
import specification.entity.{ SpecificationEventStoreES, SpecificationRepositoryES }
import sttp.client4.DefaultFutureBackend
import taskrun.boundary.{
  DashboardController as TaskRunDashboardController,
  GraphController as TaskRunGraphController,
  LogsController as TaskRunLogsController,
  ReportsController as TaskRunReportsController,
  TasksController as TaskRunTasksController,
}
import taskrun.entity.{ TaskRunEventStoreES, TaskRunRepositoryES }
import workspace.boundary.WorkspacesController
import workspace.control.*
import workspace.entity.WorkspaceRepository

object ApplicationDI:

  final private case class StartupLayerFailure(component: String, detail: String)
    extends RuntimeException(s"$component failed to initialize: $detail")

  type CommonServices =
    FileService &
      StoreConfig &
      ConfigStoreModule.ConfigStoreService &
      DataStoreService &
      MemoryStoreModule.MemoryEntriesStore &
      GatewayConfig &
      AIProviderConfig &
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
      decision.entity.DecisionRepository &
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
      PromptLoader &
      MemoryRepository &
      EmbeddingService &
      GitService &
      LlmMetrics

  def aiProviderToLlmProvider(aiProvider: AIProvider): LlmProvider =
    aiProvider match
      case AIProvider.GeminiCli => LlmProvider.GeminiCli
      case AIProvider.GeminiApi => LlmProvider.GeminiApi
      case AIProvider.OpenAi    => LlmProvider.OpenAI
      case AIProvider.Anthropic => LlmProvider.Anthropic
      case AIProvider.LmStudio  => LlmProvider.LmStudio
      case AIProvider.Ollama    => LlmProvider.Ollama
      case AIProvider.OpenCode  => LlmProvider.OpenCode
      case AIProvider.Mock      => LlmProvider.Mock

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
      fatalStartupLayer("http client", httpClientLayer(config))(errorMessage),
      HttpAIClient.live,
      HttpClient.live,
      GeminiCliExecutor.live,
      StateService.live(config.stateDir),
      ZLayer.succeed(storeConfig),
      fatalStartupLayer("config store module", ConfigStoreModule.live)(_.toString),
      fatalStartupLayer("data store module", DataStoreModule.live)(_.toString),
      fatalStartupLayer("memory store module", MemoryStoreModule.live)(_.toString),
      ConfigRepositoryES.live,
      TaskRepository.live,
      ZLayer.succeed(config.resolvedProviderConfig),
      AgentConfigResolver.live,
      PromptLoader.fromSettings,
      // Create runtime config ref with merged DB settings
      configRefLayer,
      ModelService.live,
      LlmMetrics.layer,
      // configAwareLlmService composed with MeteredLlmService wrapping, yielding the metered LlmService
      (configAwareLlmServiceLayer ++ ZLayer.service[LlmMetrics]) >>> MeteredLlmService.layer,
      EmbeddingService.live,
      GitService.live,
      MemoryRepositoryES.live,
      DecisionEventStoreES.live,
      DecisionRepositoryES.live,
      WorkflowServiceLive.live,
      ActivityRepository.live,
      ActivityHub.live,
      OrchestrationProgressTracker.live,
      ChatRepository.live,
      AgentRegistryLive.live,
      WorkflowEngine.live,
      AgentDispatcher.live,
      OrchestratorControlPlane.live,
      TaskExecutorLive.live,
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

  private def fatalStartupLayer[R, E, A](component: String, layer: ZLayer[R, E, A])(render: E => String)
    : ZLayer[R, Nothing, A] =
    layer.mapError(error => StartupLayerFailure(component, render(error))).orDie

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString)

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
    ZLayer.make[WebServer & AutoDispatcher & MergeAgentService & BoardOrchestrator](
      commonLayers(config, storeConfig),
      IssueMarkdownParser.live,
      (BoardRepositoryFS.live ++ ZLayer.service[GitService]) >>> BoardCache.live(),
      GitWatcher.live,
      BoardDependencyResolver.live,
      TaskRunDashboardController.live,
      SdlcDashboardService.live,
      SdlcDashboardController.live,
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
      ProjectRepository.live,
      ProjectStorageService.live,
      SpecificationEventStoreES.live,
      SpecificationRepositoryES.live,
      PlanEventStoreES.live,
      PlanRepositoryES.live,
      DecisionLogEventStoreES.live,
      DecisionLogRepositoryES.live,
      EvolutionProposalEventStoreES.live,
      EvolutionProposalRepositoryES.live,
      GovernancePolicyEventStoreES.live,
      GovernancePolicyRepositoryES.live,
      GovernancePolicyEngine.live,
      GovernancePolicyService.live,
      WorkspaceRepository.live,
      AgentEventStoreES.live,
      AgentRepositoryES.live,
      InteractiveAgentRunner.live,
      RunSessionManager.live,
      ZLayer.fromZIO {
        for
          boardRepo      <- ZIO.service[BoardRepository]
          workspaceRepo  <- ZIO.service[WorkspaceRepository]
          projectStorage <- ZIO.service[ProjectStorageService]
          repo           <- IssueRepositoryBoard.make(
                              boardRepo,
                              workspaceRepo,
                              ws => projectStorage.projectRoot(ws.projectId).map(_.toString),
                            )
        yield repo
      },
      TaskRunEventStoreES.live,
      TaskRunRepositoryES.live,
      PlannerAgentService.live,
      AnalysisEventStoreES.live,
      AnalysisRepositoryES.live,
      AnalysisAgentRunner.live,
      WorkspaceAnalysisScheduler.live,
      KnowledgeGraphService.live,
      KnowledgeExtractionService.live,
      ProjectsController.live,
      KnowledgeController.live,
      WorkspacesController.live,
      DependencyResolver.live,
      AgentPoolManagerLive.live,
      DaemonAgentSpecRepositoryES.live,
      DaemonAgentScheduler.live,
      IssueDispatchStatusService.live,
      DecisionInbox.live,
      EvolutionEngine.live,
      workspaceRunServiceLayer,
      BoardOrchestrator.live,
      IssueTimelineService.live,
      IssueApprovalService.live,
      BoardBoundaryController.live,
      AutoDispatcher.live,
      DemoOrchestrator.live,
      DemoController.live,
      WorkReportEventBus.layer,
      issueWorkReportProjectionLayer,
      MergeAgentService.live,
      ConversationChatController.live,
      IssuesIssueController.live,
      ActivityController.live,
      MemoryBoundaryController.live,
      DaemonsController.live,
      GatewayChannelController.live,
      AppHealthController.live,
      GatewayTelegramController.live,
      ConversationWebSocketController.live,
      mcp.McpService.live,
      AdeRouteModule.live,
      CoreRouteModule.live,
      GatewayRouteModule.live,
      ConfigRouteModule.live,
      WorkspaceRouteModule.live,
      WebServer.live,
    ) >>> ZLayer.service[WebServer]

  private val workspaceRunServiceLayer
    : ZLayer[ConfigRepository & WorkspaceRunService.LiveDeps, Nothing, WorkspaceRunService] =
    ZLayer.scoped {
      for
        configRepo                               <- ZIO.service[ConfigRepository]
        rows                                     <- configRepo.getAllSettings.orElseSucceed(Nil)
        demoConfig                                = DemoConfig.fromSettings(rows.map(r => r.key -> r.value).toMap)
        mockFn                                    = MockAgentRunner.runner(demoConfig)
        // Route at invocation time: use MockAgentRunner when cliTool=="mock", real CLI otherwise.
        // This allows demo mode to work regardless of whether it was enabled at startup.
        runner: WorkspaceRunService.RunCliAgentFn =
          (argv, cwd, onLine, envVars) =>
            if argv.headOption.contains("mock") then mockFn(argv, cwd, onLine, envVars)
            else CliAgentRunner.runProcessStreaming(argv, cwd, onLine, envVars)
        wsService                                <- WorkspaceRunService.liveWithAgent(runner).build.map(_.get[WorkspaceRunService])
      yield wsService
    }

  private val issueWorkReportProjectionLayer
    : ZLayer[WorkReportEventBus & issues.entity.IssueRepository & taskrun.entity.TaskRunRepository, Nothing, issues.entity.IssueWorkReportProjection] =
    ZLayer.scoped {
      for
        bus         <- ZIO.service[WorkReportEventBus]
        issueRepo   <- ZIO.service[issues.entity.IssueRepository]
        taskRunRepo <- ZIO.service[taskrun.entity.TaskRunRepository]
        projection  <- issues.entity.IssueWorkReportProjection.make
        _           <- IssueWorkReportHydrator.runStartup(projection, issueRepo, taskRunRepo)
        _           <- IssueWorkReportSubscriber(bus, projection, issueRepo).start
      yield projection
    }

  private val channelRegistryLayer
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

  private def parseSessionScopeStrategy(raw: Option[String]): gateway.entity.SessionScopeStrategy =
    raw
      .flatMap(gateway.entity.SessionScopeStrategy.fromString)
      .getOrElse(gateway.entity.SessionScopeStrategy.PerConversation)

