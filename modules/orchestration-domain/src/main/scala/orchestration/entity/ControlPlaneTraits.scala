package orchestration.entity

import zio.*

import _root_.config.entity.WorkflowDefinition
import shared.entity.TaskStep
import shared.errors.ControlPlaneError

/** Focused trait for workflow lifecycle operations.
  *
  * Consumers that only need to start/route/update workflows depend on this instead of the full
  * OrchestratorControlPlane.
  */
trait WorkflowOrchestrator:
  def startWorkflow(
    runId: String,
    workflowId: Long,
    definition: WorkflowDefinition,
  ): ZIO[Any, ControlPlaneError, String]

  def routeStep(
    runId: String,
    step: TaskStep,
    capabilities: List[AgentCapability],
  ): ZIO[Any, ControlPlaneError, String]

  def getActiveRuns: ZIO[Any, ControlPlaneError, List[ActiveRun]]
  def getRunState(runId: String): ZIO[Any, ControlPlaneError, Option[ActiveRun]]
  def updateRunState(runId: String, newState: WorkflowRunState): ZIO[Any, ControlPlaneError, Unit]
  def executeCommand(command: ControlCommand): ZIO[Any, ControlPlaneError, Unit]

/** Focused trait for resource allocation (parallelism slots).
  *
  * Consumers that only manage resource slots depend on this instead of the full OrchestratorControlPlane.
  */
trait ResourceAllocator:
  def allocateResource(runId: String): ZIO[Any, ControlPlaneError, Int]
  def releaseResource(runId: String, slot: Int): ZIO[Any, ControlPlaneError, Unit]
  def getResourceState: ZIO[Any, ControlPlaneError, ResourceAllocationState]

/** Focused trait for agent execution monitoring and control.
  *
  * Boundary controllers that display agent status or handle operator actions depend on this instead of the full
  * OrchestratorControlPlane.
  */
trait AgentMonitorService:
  def getAgentMonitorSnapshot: ZIO[Any, ControlPlaneError, AgentMonitorSnapshot]
  def getAgentExecutionHistory(limit: Int): ZIO[Any, ControlPlaneError, List[AgentExecutionEvent]]
  def pauseAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]
  def resumeAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]
  def abortAgentExecution(agentName: String): ZIO[Any, ControlPlaneError, Unit]
  def notifyWorkspaceAgent(
    agentName: String,
    state: AgentExecutionState,
    runId: Option[String],
    conversationId: Option[String],
    message: Option[String],
    tokenDelta: Long,
  ): UIO[Unit]

/** Focused trait for control plane event pub/sub.
  *
  * Consumers that only publish or subscribe to events depend on this instead of the full OrchestratorControlPlane.
  */
trait EventPublisher:
  def publishEvent(event: ControlPlaneEvent): ZIO[Any, ControlPlaneError, Unit]
  def subscribeToEvents(runId: String): ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]
  def subscribeAllEvents: ZIO[Scope, Nothing, Dequeue[ControlPlaneEvent]]
