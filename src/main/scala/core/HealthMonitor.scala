package core

import java.lang.management.ManagementFactory

import zio.*
import zio.json.*
import zio.stream.*

import agents.AgentRegistry
import com.sun.management.OperatingSystemMXBean
import db.{ RunStatus, TaskRepository }
import gateway.{ ChannelRegistry, GatewayService }
import models.{ AgentHealthStatus, MigrationConfig }

final case class GatewayHealth(
  uptimeSeconds: Long,
  version: String,
  connectionCount: Int,
  enqueued: Long,
  processed: Long,
  failed: Long,
) derives JsonCodec

final case class AgentHealthSummary(
  active: Int,
  idle: Int,
  failed: Int,
  total: Int,
) derives JsonCodec

enum ChannelStatus derives JsonCodec:
  case Connected, Disconnected, NotConfigured, Error

final case class ChannelHealth(
  name: String,
  status: ChannelStatus,
  activeSessions: Int,
) derives JsonCodec

final case class ResourceUsage(
  cpuPercent: Double,
  usedMemoryMb: Long,
  maxMemoryMb: Long,
  tokenUsageEstimate: Long,
) derives JsonCodec

final case class ErrorStats(
  errorRate: Double,
  recentFailures: List[String],
) derives JsonCodec

final case class HealthSnapshot(
  ts: Long,
  gateway: GatewayHealth,
  agents: AgentHealthSummary,
  channels: List[ChannelHealth],
  resources: ResourceUsage,
  errors: ErrorStats,
) derives JsonCodec

trait HealthMonitor:
  def snapshot: UIO[HealthSnapshot]
  def history(limit: Int): UIO[List[HealthSnapshot]]
  def stream(interval: Duration = 2.seconds): ZStream[Any, Nothing, HealthSnapshot]

object HealthMonitor:

  def snapshot: ZIO[HealthMonitor, Nothing, HealthSnapshot] =
    ZIO.serviceWithZIO[HealthMonitor](_.snapshot)

  def history(limit: Int): ZIO[HealthMonitor, Nothing, List[HealthSnapshot]] =
    ZIO.serviceWithZIO[HealthMonitor](_.history(limit))

  def stream(interval: Duration = 2.seconds): ZStream[HealthMonitor, Nothing, HealthSnapshot] =
    ZStream.serviceWithStream[HealthMonitor](_.stream(interval))

  val live
    : ZLayer[GatewayService & ChannelRegistry & AgentRegistry & TaskRepository & MigrationConfig, Nothing, HealthMonitor] =
    ZLayer.fromZIO {
      for
        startedAt  <- Clock.instant
        historyRef <- Ref.make(List.empty[HealthSnapshot])
        gateway    <- ZIO.service[GatewayService]
        channels   <- ZIO.service[ChannelRegistry]
        agents     <- ZIO.service[AgentRegistry]
        repo       <- ZIO.service[TaskRepository]
        config     <- ZIO.service[MigrationConfig]
      yield HealthMonitorLive(startedAt, historyRef, gateway, channels, agents, repo, config)
    }

final case class HealthMonitorLive(
  startedAt: java.time.Instant,
  historyRef: Ref[List[HealthSnapshot]],
  gatewayService: GatewayService,
  channelRegistry: ChannelRegistry,
  agentRegistry: AgentRegistry,
  repository: TaskRepository,
  config: MigrationConfig = MigrationConfig(
    sourceDir = java.nio.file.Paths.get("."),
    outputDir = java.nio.file.Paths.get("./workspace/output"),
  ),
) extends HealthMonitor:

  private val HistoryCapacity = 180

  override def snapshot: UIO[HealthSnapshot] =
    buildSnapshot.tap(saveHistory)

  override def history(limit: Int): UIO[List[HealthSnapshot]] =
    historyRef.get.map(_.takeRight(limit.max(1)))

  override def stream(interval: Duration): ZStream[Any, Nothing, HealthSnapshot] =
    ZStream.repeatWithSchedule((), Schedule.spaced(interval)).mapZIO(_ => snapshot)

  private def buildSnapshot: UIO[HealthSnapshot] =
    for
      now            <- Clock.instant
      nowMs           = now.toEpochMilli
      gatewayMetrics <- gatewayService.metrics
      channels       <- channelRegistry.list
      runningTasks   <- repository
                          .listRuns(offset = 0, limit = 200)
                          .map(_.count(_.status == RunStatus.Running))
                          .orElseSucceed(0)
      dbReachable    <- repository.listRuns(offset = 0, limit = 1).as(true).catchAll(_ => ZIO.succeed(false))
      channelStats0  <- ZIO.foreach(channels) { channel =>
                          channel.activeSessions.map { sessions =>
                            val active = sessions.size
                            val status = channel.name.toLowerCase match
                              case "telegram" if !config.telegram.enabled =>
                                ChannelStatus.NotConfigured
                              case _ if active > 0                        =>
                                ChannelStatus.Connected
                              case _                                      =>
                                ChannelStatus.Disconnected
                            ChannelHealth(channel.name, status, active)
                          }
                        }
      channelStats    = channelStats0 ++ List(
                          ChannelHealth(
                            name = "tasks",
                            status = if runningTasks > 0 then ChannelStatus.Connected else ChannelStatus.Disconnected,
                            activeSessions = runningTasks,
                          ),
                          ChannelHealth(
                            name = "database",
                            status = if dbReachable then ChannelStatus.Connected else ChannelStatus.Error,
                            activeSessions = if dbReachable then 1 else 0,
                          ),
                        )
      connectionCount = channelStats.map(_.activeSessions).sum
      allAgents      <- agentRegistry.getAllAgents
      agentSummary   <- summarizeAgents(allAgents, now)
      cpu            <- cpuPercent
      memory         <- memoryUsage
      failedRuns     <- repository.listRuns(offset = 0, limit = 20).orElseSucceed(Nil)
      recentFailures  = failedRuns
                          .filter(_.status == RunStatus.Failed)
                          .take(5)
                          .map(run => s"Run #${run.id}: ${run.errorMessage.getOrElse("Unknown error")}")
      errorRate       =
        if gatewayMetrics.processed == 0 then 0.0
        else gatewayMetrics.failed.toDouble / gatewayMetrics.processed.toDouble
      uptimeSeconds   = java.time.Duration.between(startedAt, now).getSeconds.max(0L)
    yield HealthSnapshot(
      ts = nowMs,
      gateway = GatewayHealth(
        uptimeSeconds = uptimeSeconds,
        version = "1.0.0",
        connectionCount = connectionCount,
        enqueued = gatewayMetrics.enqueued,
        processed = gatewayMetrics.processed,
        failed = gatewayMetrics.failed,
      ),
      agents = agentSummary,
      channels = channelStats,
      resources = ResourceUsage(
        cpuPercent = cpu,
        usedMemoryMb = memory._1,
        maxMemoryMb = memory._2,
        tokenUsageEstimate = gatewayMetrics.emittedChunks,
      ),
      errors = ErrorStats(errorRate = errorRate, recentFailures = recentFailures),
    )

  private def summarizeAgents(
    allAgents: List[models.AgentInfo],
    now: java.time.Instant,
  ): UIO[AgentHealthSummary] =
    ZIO.succeed {
      val active = allAgents.count { agent =>
        agent.health.isEnabled &&
        !isFailed(agent.health.status) &&
        agent.metrics.lastInvocation.exists(ts => java.time.Duration.between(ts, now).toMinutes <= 5)
      }
      val failed = allAgents.count(agent => !agent.health.isEnabled || isFailed(agent.health.status))
      val idle   = (allAgents.length - active - failed).max(0)
      AgentHealthSummary(active = active, idle = idle, failed = failed, total = allAgents.length)
    }

  private def isFailed(status: AgentHealthStatus): Boolean =
    status == AgentHealthStatus.Unhealthy || status == AgentHealthStatus.Degraded

  private def cpuPercent: UIO[Double] =
    ZIO
      .attempt {
        ManagementFactory.getOperatingSystemMXBean match
          case bean: OperatingSystemMXBean =>
            val load = bean.getProcessCpuLoad
            if load < 0 then 0.0 else Math.min(100.0, load * 100.0)
          case _                           => 0.0
      }
      .orElseSucceed(0.0)

  private def memoryUsage: UIO[(Long, Long)] =
    ZIO
      .attempt {
        val runtime = java.lang.Runtime.getRuntime
        val used    = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val max     = runtime.maxMemory() / (1024L * 1024L)
        (used, max)
      }
      .orElseSucceed((0L, 0L))

  private def saveHistory(snapshot: HealthSnapshot): UIO[Unit] =
    historyRef.update { current =>
      val next = current :+ snapshot
      if next.length > HistoryCapacity then next.drop(next.length - HistoryCapacity)
      else next
    }
