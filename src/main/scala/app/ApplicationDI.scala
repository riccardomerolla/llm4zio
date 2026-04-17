package app

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }

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
import _root_.config.entity.{ ConfigRepository, ConfigRepositoryES, GatewayConfig, ProviderConfig }
import activity.boundary.ActivityController
import activity.control.ActivityHub
import activity.entity.ActivityRepository
import agent.control.AgentRegistryLive
import agent.entity.{ AgentEventStoreES, AgentRegistry, AgentRepositoryES }
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
import conversation.entity.ChatRepository
import daemon.boundary.DaemonsController
import daemon.control.DaemonAgentScheduler
import daemon.entity.DaemonAgentSpecRepositoryES
import conversation.entity.ChatRepository
import taskrun.entity.TaskRepository
import decision.control.DecisionInbox
import decision.entity.{ DecisionEventStoreES, DecisionRepositoryES }
import demo.boundary.DemoController
import demo.control.DemoOrchestrator
import evolution.control.EvolutionEngine
import evolution.entity.{ EvolutionProposalEventStoreES, EvolutionProposalRepositoryES }
import gateway.boundary.telegram.TaskProgressNotifier
import gateway.boundary.{
  ChannelController as GatewayChannelController,
  TelegramController as GatewayTelegramController,
}
import gateway.control.*
import gateway.entity.MessageRouter
import governance.control.{ GovernancePolicyEngine, GovernancePolicyService }
import governance.entity.{ GovernancePolicyEventStoreES, GovernancePolicyRepositoryES }
import issues.boundary.IssueController as IssuesIssueController
import issues.entity.IssueRepositoryBoard
import knowledge.boundary.KnowledgeController
import knowledge.control.{ KnowledgeExtractionService, KnowledgeGraphService }
import knowledge.entity.{ DecisionLogEventStoreES, DecisionLogRepositoryES }
import llm4zio.core.*
import llm4zio.observability.{ LlmMetrics, MeteredLlmService }
import llm4zio.providers.{ ConnectorFactories, GeminiCliExecutor, HttpClient }
import llm4zio.tools.ToolRegistry
import memory.boundary.MemoryController as MemoryBoundaryController
import memory.control.{ EmbeddingService, MemoryRepositoryES }
import memory.entity.*
import orchestration.control.*
import orchestration.control.{
  IssueAssignmentOrchestrator as OrchestrationIssueAssignmentOrchestrator,
  ProgressTracker as OrchestrationProgressTracker,
}
import orchestration.entity.{ AgentPoolManager, TaskExecutor, WorkflowService }
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
import taskrun.boundary.{
  DashboardController as TaskRunDashboardController,
  GraphController as TaskRunGraphController,
  LogsController as TaskRunLogsController,
  ReportsController as TaskRunReportsController,
  TasksController as TaskRunTasksController,
}
import taskrun.entity.{ TaskRepository, TaskRunEventStoreES, TaskRunRepositoryES }
import workspace.boundary.WorkspacesController
import workspace.control.*
import workspace.entity.WorkspaceRepository

private object DefaultCliProcessExecutor:
  val live: ULayer[CliProcessExecutor] = ZLayer.succeed {
    new CliProcessExecutor:
      override def run(
        argv: List[String],
        cwd: String,
        envVars: Map[String, String],
      ): IO[LlmError, ProcessResult] =
        ZIO.attemptBlocking {
          val pb     = new ProcessBuilder(argv*)
          pb.directory(new java.io.File(cwd))
          envVars.foreach((k, v) => pb.environment().put(k, v))
          pb.redirectErrorStream(true)
          val proc   = pb.start()
          val reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream))
          val lines  = Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten.toList
          val exit   = proc.waitFor()
          ProcessResult(lines, exit)
        }.mapError(th =>
          LlmError.ProviderError(s"CLI process failed: ${Option(th.getMessage).getOrElse(th.toString)}", Some(th))
        )

      override def runStreaming(
        argv: List[String],
        cwd: String,
        envVars: Map[String, String],
      ): zio.stream.ZStream[Any, LlmError, String] =
        zio.stream.ZStream.unwrap {
          ZIO.attemptBlocking {
            val pb     = new ProcessBuilder(argv*)
            pb.directory(new java.io.File(cwd))
            envVars.foreach((k, v) => pb.environment().put(k, v))
            pb.redirectErrorStream(true)
            val proc   = pb.start()
            val reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream))
            zio.stream.ZStream.fromIterator(
              Iterator.continually(Option(reader.readLine())).takeWhile(_.isDefined).flatten
            )
              .mapError(th =>
                LlmError.ProviderError(s"CLI stream failed: ${Option(th.getMessage).getOrElse(th.toString)}", Some(th))
              )
          }.mapError(th =>
            LlmError.ProviderError(
              s"CLI process start failed: ${Option(th.getMessage).getOrElse(th.toString)}",
              Some(th),
            )
          )
        }
  }

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
      ProviderConfig &
      Ref[GatewayConfig] &
      ModelService &
      HttpClient &
      GeminiCliExecutor &
      ConnectorRegistry &
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
      DefaultCliProcessExecutor.live,
      ConnectorFactories.live,
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
      ChannelRegistryFactory.live,
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
    : ZLayer[Ref[GatewayConfig] & ConnectorRegistry, Nothing, LlmService] =
    ZLayer.fromZIO {
      for
        configRef <- ZIO.service[Ref[GatewayConfig]]
        registry  <- ZIO.service[ConnectorRegistry]
        cache     <- Ref.Synchronized.make(Map.empty[ConnectorConfig, LlmService])
      yield ConfigAwareLlmService(configRef, registry, cache)
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
      WorkspaceRunServiceFactory.live,
      BoardOrchestrator.live,
      IssueTimelineService.live,
      IssueApprovalService.live,
      conversation.entity.ConversationEventStoreES.live,
      conversation.entity.ConversationRepositoryES.live,
      ZLayer.fromZIO(ZIO.service[orchestration.control.WorkReportEventBus].map(_.getDialogueHub)),
      conversation.control.AgentDialogueCoordinator.live,
      BoardBoundaryController.live,
      AutoDispatcher.live,
      DemoOrchestrator.live,
      DemoController.live,
      WorkReportEventBus.layer,
      IssueWorkReportProjectionFactory.live,
      MergeAgentService.live,
      ConversationChatController.live,
      issues.control.IssueTemplateService.live,
      issues.control.IssueBulkService.live,
      IssuesIssueController.live,
      issues.boundary.IssueTemplatesController.live,
      issues.boundary.IssueBulkController.live,
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
