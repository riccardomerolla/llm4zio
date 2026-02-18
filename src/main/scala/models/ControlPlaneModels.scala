package models

import java.time.Instant

import zio.json.*

/** Workflow execution status
  */
enum WorkflowStatus derives JsonCodec:
  case Pending, Running, Completed, Failed, Cancelled

/** Control plane event types for workflow coordination
  */
sealed trait ControlPlaneEvent derives JsonCodec:
  def correlationId: String
  def timestamp: Instant

case class WorkflowStarted(
  correlationId: String,
  runId: String,
  workflowId: Long,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

case class WorkflowCompleted(
  correlationId: String,
  runId: String,
  status: WorkflowStatus,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

case class WorkflowFailed(
  correlationId: String,
  runId: String,
  error: String,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

case class StepStarted(
  correlationId: String,
  runId: String,
  step: TaskStep,
  assignedAgent: String,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

case class StepProgress(
  correlationId: String,
  runId: String,
  step: TaskStep,
  itemsProcessed: Int,
  itemsTotal: Int,
  message: String,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

case class StepCompleted(
  correlationId: String,
  runId: String,
  step: TaskStep,
  status: WorkflowStatus,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

case class StepFailed(
  correlationId: String,
  runId: String,
  step: TaskStep,
  error: String,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

case class ResourceAllocated(
  correlationId: String,
  runId: String,
  parallelismSlot: Int,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

case class ResourceReleased(
  correlationId: String,
  runId: String,
  parallelismSlot: Int,
  timestamp: Instant,
) extends ControlPlaneEvent
  derives JsonCodec

sealed trait ControlCommand derives JsonCodec:
  def runId: String

case class PauseWorkflow(runId: String) extends ControlCommand derives JsonCodec

case class ResumeWorkflow(runId: String) extends ControlCommand derives JsonCodec

case class CancelWorkflow(runId: String) extends ControlCommand derives JsonCodec

/** Active run tracking in control plane
  */
case class ActiveRun(
  runId: String,
  workflowId: Long,
  correlationId: String,
  state: WorkflowRunState,
  currentStep: Option[TaskStep],
  startTime: Instant,
  lastUpdateTime: Instant,
) derives JsonCodec

enum WorkflowRunState derives JsonCodec:
  case Pending, Running, Paused, Completed, Failed, Cancelled

/** Resource allocation tracking
  */
case class ResourceAllocationState(
  maxParallelism: Int,
  currentParallelism: Int,
  allocatedSlots: List[Int],
  rateLimit: Option[RateLimitConfig],
) derives JsonCodec

case class RateLimitConfig(
  tokensPerSecond: Int,
  burstSize: Int,
) derives JsonCodec

/** Agent capability matcher for workflow routing
  */
case class AgentCapability(
  agentName: String,
  supportedSteps: List[TaskStep],
  isEnabled: Boolean = true,
) derives JsonCodec

enum AgentExecutionState derives JsonCodec:
  case Idle, Executing, WaitingForTool, Paused, Aborted, Failed

case class AgentExecutionInfo(
  agentName: String,
  state: AgentExecutionState,
  runId: Option[String],
  step: Option[TaskStep],
  task: Option[String],
  conversationId: Option[String],
  tokensUsed: Long,
  latencyMs: Long,
  cost: Double,
  lastUpdatedAt: Instant,
  message: Option[String],
) derives JsonCodec

case class AgentExecutionEvent(
  id: String,
  agentName: String,
  state: AgentExecutionState,
  runId: Option[String],
  step: Option[TaskStep],
  detail: String,
  timestamp: Instant,
) derives JsonCodec

case class AgentMonitorSnapshot(
  generatedAt: Instant,
  agents: List[AgentExecutionInfo],
) derives JsonCodec
