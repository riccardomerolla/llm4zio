package mcp

import zio.*

import agent.entity.AgentRepository
import issues.entity.IssueRepository
import llm4zio.mcp.server.{ McpError, McpServer }
import llm4zio.mcp.transport.SseTransport
import llm4zio.tools.ToolRegistry
import memory.entity.MemoryRepository
import workspace.control.WorkspaceRunService
import workspace.entity.WorkspaceRepository

/** Holds the running MCP server (SSE transport) and its controller.
  *
  * Starts the MCP message-loop as a background fiber on construction.
  * The fiber is interrupted when the ZLayer scope is closed.
  */
final class McpService(
  val transport: SseTransport,
  val controller: McpController,
  private val serverFiber: Fiber[McpError, Unit],
):
  def interrupt: UIO[Unit] = serverFiber.interrupt.unit

object McpService:

  def make(
    apiKey: Option[String],
    issueRepo: IssueRepository,
    agentRepo: AgentRepository,
    wsRepo: WorkspaceRepository,
    runService: WorkspaceRunService,
    memoryRepo: MemoryRepository,
  ): ZIO[Scope, Nothing, McpService] =
    for
      transport  <- SseTransport.make(apiKey)
      registry   <- ToolRegistry.make
      tools       = GatewayMcpTools(issueRepo, agentRepo, wsRepo, runService, memoryRepo)
      _          <- registry.registerAll(tools.all).mapError(e => new RuntimeException(e.toString)).orDie
      server     <- McpServer.make(registry, transport)
      fiber      <- server.start.forkScoped
      controller  = McpController(transport)
    yield McpService(transport, controller, fiber)

  /** ZLayer for wiring into ApplicationDI. */
  val live: ZLayer[
    IssueRepository & AgentRepository & WorkspaceRepository & WorkspaceRunService & MemoryRepository,
    Nothing,
    McpService,
  ] =
    ZLayer.scoped {
      for
        issueRepo  <- ZIO.service[IssueRepository]
        agentRepo  <- ZIO.service[AgentRepository]
        wsRepo     <- ZIO.service[WorkspaceRepository]
        runService <- ZIO.service[WorkspaceRunService]
        memoryRepo <- ZIO.service[MemoryRepository]
        svc        <- make(
                        apiKey = None, // can be configured via GatewayConfig.mcp.apiKey later
                        issueRepo,
                        agentRepo,
                        wsRepo,
                        runService,
                        memoryRepo,
                      )
      yield svc
    }
