package daemon.entity

import java.time.Instant

import zio.*
import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ DaemonAgentSpecId, ProjectId }

final case class DaemonExecutionLimits(
  maxIssuesPerRun: Int = 1,
  cooldown: Duration = 30.minutes,
  timeout: Duration = 10.minutes,
) derives JsonCodec,
    Schema

sealed trait DaemonTriggerCondition derives JsonCodec, Schema
object DaemonTriggerCondition:
  final case class Scheduled(interval: Duration)                             extends DaemonTriggerCondition
  final case class EventDriven(triggerId: String)                            extends DaemonTriggerCondition
  final case class Continuous(pollInterval: Duration)                        extends DaemonTriggerCondition
  final case class ScheduledWithEvent(interval: Duration, triggerId: String) extends DaemonTriggerCondition

sealed trait DaemonHealth derives JsonCodec, Schema
object DaemonHealth:
  case object Idle     extends DaemonHealth
  case object Healthy  extends DaemonHealth
  case object Running  extends DaemonHealth
  case object Degraded extends DaemonHealth
  case object Paused   extends DaemonHealth
  case object Disabled extends DaemonHealth

sealed trait DaemonLifecycle derives JsonCodec, Schema
object DaemonLifecycle:
  case object Running extends DaemonLifecycle
  case object Stopped extends DaemonLifecycle

final case class DaemonAgentSpec(
  id: DaemonAgentSpecId,
  daemonKey: String,
  projectId: ProjectId,
  name: String,
  purpose: String,
  trigger: DaemonTriggerCondition,
  workspaceIds: List[String],
  agentName: String,
  prompt: String,
  limits: DaemonExecutionLimits,
  builtIn: Boolean,
  governed: Boolean,
) derives JsonCodec,
    Schema

final case class DaemonAgentRuntime(
  lifecycle: DaemonLifecycle = DaemonLifecycle.Running,
  health: DaemonHealth = DaemonHealth.Idle,
  queuedAt: Option[Instant] = None,
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  lastIssueCreatedAt: Option[Instant] = None,
  issuesCreated: Int = 0,
  lastError: Option[String] = None,
  lastSummary: Option[String] = None,
) derives JsonCodec,
    Schema

final case class DaemonAgentStatus(
  spec: DaemonAgentSpec,
  enabled: Boolean,
  runtime: DaemonAgentRuntime,
) derives JsonCodec,
    Schema

object DaemonAgentSpec:
  val TestGuardianKey: String = "test-guardian"
  val DebtDetectorKey: String = "debt-detector"

  def idFor(projectId: ProjectId, daemonKey: String): DaemonAgentSpecId =
    DaemonAgentSpecId(s"${projectId.value}__${normalizeKey(daemonKey)}")

  def normalizeKey(value: String): String =
    value.trim.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
