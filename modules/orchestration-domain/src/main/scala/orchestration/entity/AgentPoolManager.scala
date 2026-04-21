package orchestration.entity

import java.time.Instant

import scala.annotation.unused

import zio.*

sealed trait PoolError derives CanEqual

object PoolError:
  final case class AgentNotFound(agentName: String)                     extends PoolError
  final case class AgentPaused(agentName: String, reason: String)       extends PoolError
  final case class CostLimitExceeded(agentName: String, limit: Long)    extends PoolError
  final case class InvalidCapacity(agentName: String, value: String)    extends PoolError
  final case class PersistenceFailure(operation: String, cause: String) extends PoolError

final case class SlotHandle(
  id: String,
  agentName: String,
  acquiredAt: Instant,
) derives CanEqual

trait AgentPoolManager:
  def acquireSlot(agentName: String): IO[PoolError, SlotHandle]
  def releaseSlot(handle: SlotHandle): UIO[Unit]
  def availableSlots(agentName: String): UIO[Int]
  def resize(agentName: String, newMax: Int): UIO[Unit]
  def recordTokenUsage(@unused agentName: String, @unused deltaTokens: Long): IO[PoolError, Unit] = ZIO.unit
  def isPaused(@unused agentName: String): UIO[Boolean]                                           = ZIO.succeed(false)

object AgentPoolManager:
  def configKey(agentName: String): String =
    s"agents.${normalize(agentName)}.maxInstances"

  def acquireSlot(agentName: String): ZIO[AgentPoolManager, PoolError, SlotHandle] =
    ZIO.serviceWithZIO[AgentPoolManager](_.acquireSlot(agentName))

  def releaseSlot(handle: SlotHandle): ZIO[AgentPoolManager, Nothing, Unit] =
    ZIO.serviceWithZIO[AgentPoolManager](_.releaseSlot(handle))

  def availableSlots(agentName: String): ZIO[AgentPoolManager, Nothing, Int] =
    ZIO.serviceWithZIO[AgentPoolManager](_.availableSlots(agentName))

  def resize(agentName: String, newMax: Int): ZIO[AgentPoolManager, Nothing, Unit] =
    ZIO.serviceWithZIO[AgentPoolManager](_.resize(agentName, newMax))

  def recordTokenUsage(agentName: String, deltaTokens: Long): ZIO[AgentPoolManager, PoolError, Unit] =
    ZIO.serviceWithZIO[AgentPoolManager](_.recordTokenUsage(agentName, deltaTokens))

  def isPaused(agentName: String): ZIO[AgentPoolManager, Nothing, Boolean] =
    ZIO.serviceWithZIO[AgentPoolManager](_.isPaused(agentName))

  private[orchestration] def normalize(agentName: String): String =
    agentName.trim.toLowerCase
