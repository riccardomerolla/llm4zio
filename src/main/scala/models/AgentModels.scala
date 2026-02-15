package models

import java.time.Instant

import zio.json.*

enum AgentType derives JsonCodec:
  case BuiltIn, Custom

/** Agent skill/capability specification
  */
case class AgentSkill(
  skill: String,
  description: String,
  inputTypes: List[String],
  outputTypes: List[String],
  constraints: List[AgentConstraint] = Nil,
) derives JsonCodec

/** Constraint on agent execution
  */
enum AgentConstraint derives JsonCodec:
  case RequiresAI
  case RequiresDatabase
  case RequiresFileSystem
  case MaxConcurrency(limit: Int)
  case MinMemoryMB(memory: Int)
  case MaxExecutionSeconds(seconds: Int)

/** Agent usage metrics
  */
case class AgentMetrics(
  invocations: Long = 0,
  successCount: Long = 0,
  failureCount: Long = 0,
  totalLatencyMs: Long = 0,
  lastInvocation: Option[Instant] = None,
) derives JsonCodec:
  def averageLatencyMs: Double =
    if invocations == 0 then 0.0
    else totalLatencyMs.toDouble / invocations.toDouble

  def successRate: Double =
    if invocations == 0 then 0.0
    else successCount.toDouble / invocations.toDouble

  def recordInvocation(success: Boolean, latencyMs: Long, timestamp: Instant): AgentMetrics =
    copy(
      invocations = invocations + 1,
      successCount = if success then successCount + 1 else successCount,
      failureCount = if !success then failureCount + 1 else failureCount,
      totalLatencyMs = totalLatencyMs + latencyMs,
      lastInvocation = Some(timestamp),
    )

/** Agent health status
  */
enum AgentHealthStatus derives JsonCodec:
  case Healthy, Degraded, Unhealthy, Unknown

case class AgentHealth(
  status: AgentHealthStatus = AgentHealthStatus.Unknown,
  lastCheckTime: Option[Instant] = None,
  consecutiveFailures: Int = 0,
  isEnabled: Boolean = true,
  message: Option[String] = None,
) derives JsonCodec:
  def recordFailure(timestamp: Instant, msg: String): AgentHealth =
    val newFailures = consecutiveFailures + 1
    val newStatus   = if newFailures >= 5 then AgentHealthStatus.Unhealthy
    else if newFailures >= 3 then AgentHealthStatus.Degraded
    else AgentHealthStatus.Healthy

    copy(
      status = newStatus,
      lastCheckTime = Some(timestamp),
      consecutiveFailures = newFailures,
      message = Some(msg),
    )

  def recordSuccess(timestamp: Instant): AgentHealth =
    copy(
      status = AgentHealthStatus.Healthy,
      lastCheckTime = Some(timestamp),
      consecutiveFailures = 0,
      message = None,
    )

  def disable(reason: String): AgentHealth =
    copy(isEnabled = false, message = Some(reason))

  def enable(): AgentHealth =
    copy(isEnabled = true, message = None)

case class AgentInfo(
  name: String,
  displayName: String,
  description: String,
  agentType: AgentType,
  usesAI: Boolean,
  tags: List[String],
  skills: List[AgentSkill] = Nil,
  supportedSteps: List[MigrationStep] = Nil,
  version: String = "1.0.0",
  metrics: AgentMetrics = AgentMetrics(),
  health: AgentHealth = AgentHealth(),
) derives JsonCodec

/** Agent registration request
  */
case class RegisterAgentRequest(
  name: String,
  displayName: String,
  description: String,
  agentType: AgentType,
  usesAI: Boolean,
  tags: List[String],
  skills: List[AgentSkill],
  supportedSteps: List[MigrationStep],
  version: String = "1.0.0",
) derives JsonCodec

/** Agent query filters
  */
case class AgentQuery(
  skill: Option[String] = None,
  inputType: Option[String] = None,
  outputType: Option[String] = None,
  supportedStep: Option[MigrationStep] = None,
  minSuccessRate: Option[Double] = None,
  onlyEnabled: Boolean = true,
) derives JsonCodec
