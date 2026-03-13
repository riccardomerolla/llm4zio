package orchestration.control

import java.time.Instant

import zio.*

import agent.entity.AgentRepository
import db.ConfigRepository

sealed trait PoolError derives CanEqual

object PoolError:
  final case class AgentNotFound(agentName: String)                     extends PoolError
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

  val live: ZLayer[ConfigRepository & AgentRepository, Nothing, AgentPoolManager] =
    ZLayer.fromZIO {
      for
        configRepository <- ZIO.service[ConfigRepository]
        agentRepository  <- ZIO.service[AgentRepository]
        pools            <- Ref.Synchronized.make(Map.empty[String, AgentPoolState])
        acquired         <- Ref.Synchronized.make(Map.empty[String, String])
      yield AgentPoolManagerLive(configRepository, agentRepository, pools, acquired)
    }

  private[orchestration] def normalize(agentName: String): String =
    agentName.trim.toLowerCase

final private case class AgentPoolState(
  gate: Semaphore,
  maxInstances: Ref[Int],
  inUse: Ref[Int],
  waiters: Ref[scala.collection.immutable.Queue[Promise[Nothing, Unit]]],
)

final private case class AgentPoolManagerLive(
  configRepository: ConfigRepository,
  agentRepository: AgentRepository,
  pools: Ref.Synchronized[Map[String, AgentPoolState]],
  acquiredHandles: Ref.Synchronized[Map[String, String]],
) extends AgentPoolManager:

  override def acquireSlot(agentName: String): IO[PoolError, SlotHandle] =
    for
      state  <- getOrCreatePool(agentName)
      target <- resolveConfiguredMax(agentName)
      _      <- resizeState(state, target)
      waitOn <- Promise.make[Nothing, Unit]
      queued <- state.gate.withPermit {
                  for
                    currentInUse <- state.inUse.get
                    max          <- state.maxInstances.get
                    queued       <-
                      if currentInUse < max then state.inUse.update(_ + 1).as(false)
                      else state.waiters.update(_.enqueue(waitOn)).as(true)
                  yield queued
                }
      _      <- waitOn.await.when(queued)
      now    <- Clock.instant
      handle  = SlotHandle(
                  id = java.util.UUID.randomUUID().toString,
                  agentName = AgentPoolManager.normalize(agentName),
                  acquiredAt = now,
                )
      _      <- acquiredHandles.update(_ + (handle.id -> handle.agentName))
    yield handle

  override def releaseSlot(handle: SlotHandle): UIO[Unit] =
    acquiredHandles.modify { handles =>
      handles.get(handle.id) match
        case Some(agentName) => (Some(agentName), handles - handle.id)
        case None            => (None, handles)
    }.flatMap {
      case Some(agentName) =>
        pools.get.flatMap(_.get(agentName) match
          case Some(state) => releaseStateSlot(state)
          case None        => ZIO.unit)
      case None            =>
        ZIO.unit
    }

  override def availableSlots(agentName: String): UIO[Int] =
    getOrCreatePoolFallback(agentName)
      .flatMap {
        case Some(state) => state.gate.withPermit {
            for
              max   <- state.maxInstances.get
              inUse <- state.inUse.get
            yield Math.max(0, max - inUse)
          }
        case None        => ZIO.succeed(0)
      }

  override def resize(agentName: String, newMax: Int): UIO[Unit] =
    if newMax < 1 then
      ZIO.logWarning(s"Ignoring pool resize for ${agentName.trim}: invalid max instances $newMax")
    else
      getOrCreatePoolWithMax(agentName, newMax)
        .flatMap(resizeState(_, newMax))
        .catchAll(err => ZIO.logWarning(s"Failed to resize agent pool for ${agentName.trim}: $err"))

  private def getOrCreatePool(agentName: String): IO[PoolError, AgentPoolState] =
    getOrCreatePoolFrom(agentName, resolveConfiguredMax(agentName))

  private def getOrCreatePoolFallback(agentName: String): UIO[Option[AgentPoolState]] =
    getOrCreatePoolFrom(agentName, resolveConfiguredMax(agentName))
      .map(Some(_))
      .catchAll(_ => ZIO.succeed(None))

  private def getOrCreatePoolWithMax(agentName: String, newMax: Int): IO[PoolError, AgentPoolState] =
    getOrCreatePoolFrom(agentName, ZIO.succeed(newMax))

  private def getOrCreatePoolFrom(
    agentName: String,
    resolveMax: IO[PoolError, Int],
  ): IO[PoolError, AgentPoolState] =
    val normalized = AgentPoolManager.normalize(agentName)
    pools.modifyZIO { states =>
      states.get(normalized) match
        case Some(state) =>
          ZIO.succeed((state, states))
        case None        =>
          for
            max        <- resolveMax
            gate       <- Semaphore.make(1L)
            maxRef     <- Ref.make(max)
            inUseRef   <- Ref.make(0)
            waitersRef <- Ref.make(scala.collection.immutable.Queue.empty[Promise[Nothing, Unit]])
            state       = AgentPoolState(
                            gate = gate,
                            maxInstances = maxRef,
                            inUse = inUseRef,
                            waiters = waitersRef,
                          )
          yield (state, states.updated(normalized, state))
    }

  private def resolveConfiguredMax(agentName: String): IO[PoolError, Int] =
    for
      agent   <- agentRepository
                   .findByName(agentName)
                   .mapError(err => PoolError.PersistenceFailure("find_agent_for_pool", err.toString))
                   .someOrElseZIO(ZIO.fail(PoolError.AgentNotFound(agentName.trim)))
      setting <- configRepository
                   .getSetting(AgentPoolManager.configKey(agent.name))
                   .mapError(err => PoolError.PersistenceFailure("get_agent_pool_setting", err.toString))
      max     <- setting match
                   case Some(row) =>
                     parsePositiveInt(agent.name, row.value)
                   case None      =>
                     validatePositive(agent.name, agent.maxConcurrentRuns.toString, agent.maxConcurrentRuns)
    yield max

  private def parsePositiveInt(agentName: String, rawValue: String): IO[PoolError, Int] =
    rawValue.trim.toIntOption match
      case Some(value) => validatePositive(agentName, rawValue, value)
      case None        => ZIO.fail(PoolError.InvalidCapacity(agentName, rawValue))

  private def validatePositive(agentName: String, rawValue: String, value: Int): IO[PoolError, Int] =
    if value > 0 then ZIO.succeed(value)
    else ZIO.fail(PoolError.InvalidCapacity(agentName, rawValue))

  private def resizeState(state: AgentPoolState, newMax: Int): UIO[Unit] =
    state.gate.withPermit {
      for
        currentMax <- state.maxInstances.get
        _          <- state.maxInstances.set(newMax)
        _          <- grantWaiters(state, newlyAvailable = Math.max(0, newMax - currentMax))
      yield ()
    }

  private def releaseStateSlot(state: AgentPoolState): UIO[Unit] =
    state.gate.withPermit {
      state.waiters.get.flatMap {
        case queue if queue.nonEmpty =>
          val (next, rest) = queue.dequeue
          state.waiters.set(rest) *> next.succeed(()).unit
        case _                       =>
          state.inUse.update(current => Math.max(0, current - 1))
      }
    }

  private def grantWaiters(state: AgentPoolState, newlyAvailable: Int): UIO[Unit] =
    if newlyAvailable < 1 then ZIO.unit
    else
      ZIO.foreachDiscard(1 to newlyAvailable) { _ =>
        for
          queue <- state.waiters.get
          max   <- state.maxInstances.get
          inUse <- state.inUse.get
          _     <-
            if queue.isEmpty || inUse >= max then ZIO.unit
            else
              val (next, rest) = queue.dequeue
              state.waiters.set(rest) *>
                state.inUse.update(_ + 1) *>
                next.succeed(()).unit
        yield ()
      }
