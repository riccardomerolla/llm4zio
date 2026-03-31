package mcp

import zio.*

import agent.entity.AgentRepository
import analysis.entity.AnalysisRepository
import daemon.control.DaemonAgentScheduler
import decision.control.DecisionInbox
import evolution.control.EvolutionEngine
import governance.control.GovernancePolicyService
import issues.entity.IssueRepository
import knowledge.control.KnowledgeGraphService
import llm4zio.mcp.server.{ McpError, McpServer }
import llm4zio.mcp.transport.SseTransport
import llm4zio.tools.ToolRegistry
import memory.entity.MemoryRepository
import plan.entity.PlanRepository
import sdlc.control.SdlcDashboardService
import specification.entity.SpecificationRepository
import workspace.control.WorkspaceRunService
import workspace.entity.WorkspaceRepository

/** Holds the running MCP server (SSE transport) and its controller.
  *
  * Starts the MCP message-loop as a background fiber on construction. The fiber is interrupted when the ZLayer scope is
  * closed.
  */
final class McpService(
  val transport: SseTransport,
  val controller: McpController,
  private val serverFiber: Fiber[McpError, Unit],
):
  def interrupt: UIO[Unit] = serverFiber.interrupt.unit

object McpService:

  enum McpServiceInitError:
    case ToolRegistration(detail: String)

    def message: String =
      this match
        case ToolRegistration(detail) => s"Failed to register MCP tools: $detail"

  def make(
    apiKey: Option[String],
    issueRepo: IssueRepository,
    agentRepo: AgentRepository,
    wsRepo: WorkspaceRepository,
    runService: WorkspaceRunService,
    decisionInbox: DecisionInbox,
    evolutionEngine: EvolutionEngine,
    memoryRepo: MemoryRepository,
    analysisRepo: AnalysisRepository,
    knowledgeGraph: KnowledgeGraphService,
    governancePolicyService: GovernancePolicyService,
    specificationRepository: SpecificationRepository,
    planRepository: PlanRepository,
    daemonScheduler: DaemonAgentScheduler,
    sdlcDashboardService: SdlcDashboardService,
  ): ZIO[Scope, McpServiceInitError, McpService] =
    for
      transport <- SseTransport.make(apiKey)
      registry  <- ToolRegistry.make
      tools      = GatewayMcpTools(
                     issueRepo,
                     agentRepo,
                     wsRepo,
                     runService,
                     decisionInbox,
                     evolutionEngine,
                     memoryRepo,
                     analysisRepo,
                     knowledgeGraph,
                     governancePolicyService,
                     specificationRepository,
                     planRepository,
                     daemonScheduler,
                     sdlcDashboardService,
                   )
      _         <- registry
                     .registerAll(tools.all)
                     .mapError(error => McpServiceInitError.ToolRegistration(error.toString))
      server    <- McpServer.make(registry, transport)
      fiber     <- server.start.forkScoped
      controller = McpController(transport)
    yield McpService(transport, controller, fiber)

  /** ZLayer for wiring into ApplicationDI. */
  val live: ZLayer[
    IssueRepository & AgentRepository & WorkspaceRepository & WorkspaceRunService & DecisionInbox & EvolutionEngine & MemoryRepository & AnalysisRepository & KnowledgeGraphService & GovernancePolicyService & SpecificationRepository & PlanRepository & DaemonAgentScheduler & SdlcDashboardService,
    Nothing,
    McpService,
  ] =
    ZLayer.scoped {
      for
        issueRepo      <- ZIO.service[IssueRepository]
        agentRepo      <- ZIO.service[AgentRepository]
        wsRepo         <- ZIO.service[WorkspaceRepository]
        runService     <- ZIO.service[WorkspaceRunService]
        decisionInbox  <- ZIO.service[DecisionInbox]
        evolution      <- ZIO.service[EvolutionEngine]
        memoryRepo     <- ZIO.service[MemoryRepository]
        analysisRepo   <- ZIO.service[AnalysisRepository]
        knowledge      <- ZIO.service[KnowledgeGraphService]
        governance     <- ZIO.service[GovernancePolicyService]
        specifications <- ZIO.service[SpecificationRepository]
        plans          <- ZIO.service[PlanRepository]
        daemons        <- ZIO.service[DaemonAgentScheduler]
        dashboard      <- ZIO.service[SdlcDashboardService]
        svc            <- make(
                            apiKey = None, // can be configured via GatewayConfig.mcp.apiKey later
                            issueRepo,
                            agentRepo,
                            wsRepo,
                            runService,
                            decisionInbox,
                            evolution,
                            memoryRepo,
                            analysisRepo,
                            knowledge,
                            governance,
                            specifications,
                            plans,
                            daemons,
                            dashboard,
                          ).catchAll(error => ZIO.logError(error.message) *> ZIO.dieMessage(error.message))
      yield svc
    }
