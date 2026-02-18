package orchestration

import java.time.Instant

import zio.*
import zio.json.*

import core.{ RateLimiter, RateLimiterConfig }
import models.{ ControlPlaneError, GatewayConfig }

enum RunPriority derives JsonCodec:
  case Low
  case Medium
  case High
  case Critical

object RunPriority:
  extension (priority: RunPriority)
    def weight: Int = priority match
      case RunPriority.Low      => 0
      case RunPriority.Medium   => 1
      case RunPriority.High     => 2
      case RunPriority.Critical => 3

case class TokenBucketConfig(
  requestsPerMinute: Int,
  burstSize: Int,
) derives JsonCodec

case class ResourceQuotas(
  maxParallelRuns: Int,
  maxTotalMemoryGb: Int,
  maxDiskGb: Int,
  rateLimit: TokenBucketConfig,
  defaultRunPriority: RunPriority = RunPriority.Medium,
) derives JsonCodec

object ResourceQuotas:
  def fromGatewayConfig(config: GatewayConfig): ResourceQuotas =
    val defaultParallelism = 4
    ResourceQuotas(
      maxParallelRuns = math.max(1, defaultParallelism),
      maxTotalMemoryGb = math.max(1, defaultParallelism * 2),
      maxDiskGb = math.max(1, defaultParallelism * 5),
      rateLimit = TokenBucketConfig(
        requestsPerMinute = config.resolvedProviderConfig.requestsPerMinute,
        burstSize = config.resolvedProviderConfig.burstSize,
      ),
      defaultRunPriority = RunPriority.Medium,
    )

case class RunResourceRequest(
  cpuShares: Int = 1,
  memoryGb: Int = 1,
  diskGb: Int = 1,
  networkWeight: Int = 1,
) derives JsonCodec

enum WorkspaceRunState derives JsonCodec:
  case Active
  case Queued
  case Paused

case class WorkspaceRunAllocation(
  runId: Long,
  priority: RunPriority,
  request: RunResourceRequest,
  state: WorkspaceRunState,
  allocatedAt: Instant,
  updatedAt: Instant,
) derives JsonCodec

case class WorkspaceCoordinatorSnapshot(
  quotas: ResourceQuotas,
  active: List[WorkspaceRunAllocation],
  queued: List[WorkspaceRunAllocation],
  paused: List[WorkspaceRunAllocation],
  totalActiveMemoryGb: Int,
  totalActiveDiskGb: Int,
) derives JsonCodec

trait WorkspaceCoordinator:
  def acquire(
    runId: Long,
    priority: Option[RunPriority] = None,
    request: RunResourceRequest = RunResourceRequest(),
  ): IO[ControlPlaneError, Unit]
  def release(runId: Long): UIO[Unit]
  def pause(runId: Long): IO[ControlPlaneError, Unit]
  def resume(runId: Long): IO[ControlPlaneError, Unit]
  def updatePriority(runId: Long, priority: RunPriority): IO[ControlPlaneError, Unit]
  def listActive: UIO[List[WorkspaceRunAllocation]]
  def snapshot: UIO[WorkspaceCoordinatorSnapshot]
  def cancelAll: UIO[List[Long]]
  def acquireNetworkPermit(runId: Long): IO[ControlPlaneError, Unit]

object WorkspaceCoordinator:
  def acquire(
    runId: Long,
    priority: Option[RunPriority] = None,
    request: RunResourceRequest = RunResourceRequest(),
  ): ZIO[WorkspaceCoordinator, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.acquire(runId, priority, request))

  def release(runId: Long): ZIO[WorkspaceCoordinator, Nothing, Unit] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.release(runId))

  def pause(runId: Long): ZIO[WorkspaceCoordinator, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.pause(runId))

  def resume(runId: Long): ZIO[WorkspaceCoordinator, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.resume(runId))

  def updatePriority(runId: Long, priority: RunPriority): ZIO[WorkspaceCoordinator, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.updatePriority(runId, priority))

  def listActive: ZIO[WorkspaceCoordinator, Nothing, List[WorkspaceRunAllocation]] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.listActive)

  def snapshot: ZIO[WorkspaceCoordinator, Nothing, WorkspaceCoordinatorSnapshot] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.snapshot)

  def cancelAll: ZIO[WorkspaceCoordinator, Nothing, List[Long]] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.cancelAll)

  def acquireNetworkPermit(runId: Long): ZIO[WorkspaceCoordinator, ControlPlaneError, Unit] =
    ZIO.serviceWithZIO[WorkspaceCoordinator](_.acquireNetworkPermit(runId))

  def quotasLayer(config: GatewayConfig): ULayer[ResourceQuotas] =
    ZLayer.succeed(ResourceQuotas.fromGatewayConfig(config))

  val noop: ULayer[WorkspaceCoordinator] =
    ZLayer.succeed(
      new WorkspaceCoordinator:
        override def acquire(
          runId: Long,
          priority: Option[RunPriority],
          request: RunResourceRequest,
        ): IO[ControlPlaneError, Unit] = ZIO.unit
        override def release(runId: Long): UIO[Unit]                                                 = ZIO.unit
        override def pause(runId: Long): IO[ControlPlaneError, Unit]                                 = ZIO.unit
        override def resume(runId: Long): IO[ControlPlaneError, Unit]                                = ZIO.unit
        override def updatePriority(runId: Long, priority: RunPriority): IO[ControlPlaneError, Unit] = ZIO.unit
        override def listActive: UIO[List[WorkspaceRunAllocation]]                                   = ZIO.succeed(Nil)
        override def snapshot: UIO[WorkspaceCoordinatorSnapshot]                                     =
          ZIO.succeed(
            WorkspaceCoordinatorSnapshot(
              quotas = ResourceQuotas(1, 1, 1, TokenBucketConfig(1, 1), RunPriority.Medium),
              active = Nil,
              queued = Nil,
              paused = Nil,
              totalActiveMemoryGb = 0,
              totalActiveDiskGb = 0,
            )
          )
        override def cancelAll: UIO[List[Long]]                                                      = ZIO.succeed(Nil)
        override def acquireNetworkPermit(runId: Long): IO[ControlPlaneError, Unit]                  = ZIO.unit
    )

  val live: ZLayer[ResourceQuotas, Nothing, WorkspaceCoordinator] =
    ZLayer.scoped {
      for
        quotas      <- ZIO.service[ResourceQuotas]
        rateLimiter <- RateLimiter.make(
                         RateLimiterConfig(
                           requestsPerMinute = quotas.rateLimit.requestsPerMinute,
                           burstSize = quotas.rateLimit.burstSize,
                           acquireTimeout = 30.seconds,
                         )
                       )
        state       <- Ref.Synchronized.make(State.empty)
      yield WorkspaceCoordinatorLive(state, quotas, rateLimiter)
    }

final private case class QueuedRun(
  runId: Long,
  priority: RunPriority,
  request: RunResourceRequest,
  enqueuedAt: Instant,
  gate: Promise[ControlPlaneError, Unit],
)

final private case class State(
  active: Map[Long, WorkspaceRunAllocation],
  paused: Map[Long, WorkspaceRunAllocation],
  queued: Vector[QueuedRun],
):
  def totalActiveMemoryGb: Int = active.values.map(_.request.memoryGb).sum
  def totalActiveDiskGb: Int   = active.values.map(_.request.diskGb).sum

object State:
  val empty: State = State(Map.empty, Map.empty, Vector.empty)

final private case class WorkspaceCoordinatorLive(
  stateRef: Ref.Synchronized[State],
  quotas: ResourceQuotas,
  rateLimiter: RateLimiter,
) extends WorkspaceCoordinator:

  override def acquire(
    runId: Long,
    priority: Option[RunPriority],
    request: RunResourceRequest,
  ): IO[ControlPlaneError, Unit] =
    for
      now  <- Clock.instant
      gate <- Promise.make[ControlPlaneError, Unit]
      prio  = priority.getOrElse(quotas.defaultRunPriority)
      wait <- stateRef.modifyZIO { state =>
                if state.active.contains(runId) || state.paused.contains(runId) || state.queued.exists(_.runId == runId)
                then
                  ZIO.succeed((ZIO.unit, state))
                else if fits(state, request) then
                  val allocation = WorkspaceRunAllocation(
                    runId = runId,
                    priority = prio,
                    request = request,
                    state = WorkspaceRunState.Active,
                    allocatedAt = now,
                    updatedAt = now,
                  )
                  ZIO.succeed((ZIO.unit, state.copy(active = state.active + (runId -> allocation))))
                else
                  val queued = QueuedRun(runId, prio, request, now, gate)
                  ZIO.succeed((gate.await, state.copy(queued = sortQueue(state.queued :+ queued))))
              }
      _    <- wait
    yield ()

  override def release(runId: Long): UIO[Unit] =
    stateRef.modifyZIO { state =>
      val prunedQueue          = state.queued.filterNot(_.runId == runId)
      val removedQueue         = state.queued.find(_.runId == runId)
      val updated              = state.copy(
        active = state.active - runId,
        paused = state.paused - runId,
        queued = prunedQueue,
      )
      val (nextState, effects) = allocateQueued(updated)
      val cancelQueueEffect    = ZIO.foreachDiscard(removedQueue.toList)(_.gate.fail(
        ControlPlaneError.ResourceAllocationFailed(runId.toString, "Run removed before allocation")
      ))
      ZIO.succeed((cancelQueueEffect *> ZIO.foreachDiscard(effects)(identity), nextState))
    }.flatten

  override def pause(runId: Long): IO[ControlPlaneError, Unit] =
    for
      now <- Clock.instant
      eff <- stateRef.modifyZIO { state =>
               state.active.get(runId) match
                 case None             =>
                   ZIO.fail(ControlPlaneError.ActiveRunNotFound(runId.toString))
                 case Some(allocation) =>
                   val paused               = allocation.copy(state = WorkspaceRunState.Paused, updatedAt = now)
                   val moved                = state.copy(
                     active = state.active - runId,
                     paused = state.paused + (runId -> paused),
                   )
                   val (nextState, wakeups) = allocateQueued(moved)
                   ZIO.succeed((ZIO.foreachDiscard(wakeups)(identity), nextState))
             }
      _   <- eff
    yield ()

  override def resume(runId: Long): IO[ControlPlaneError, Unit] =
    for
      now <- Clock.instant
      _   <- stateRef.modifyZIO { state =>
               state.paused.get(runId) match
                 case None             => ZIO.fail(ControlPlaneError.ActiveRunNotFound(runId.toString))
                 case Some(allocation) =>
                   if fits(state, allocation.request) then
                     val resumed = allocation.copy(state = WorkspaceRunState.Active, updatedAt = now)
                     ZIO.succeed((
                       (),
                       state.copy(paused = state.paused - runId, active = state.active + (runId -> resumed)),
                     ))
                   else
                     ZIO.fail(
                       ControlPlaneError.ResourceAllocationFailed(
                         runId.toString,
                         "Insufficient quota to resume run",
                       )
                     )
             }
    yield ()

  override def updatePriority(runId: Long, priority: RunPriority): IO[ControlPlaneError, Unit] =
    for
      now <- Clock.instant
      _   <- stateRef.modifyZIO { state =>
               state.active.get(runId) match
                 case Some(allocation) =>
                   val updated = allocation.copy(priority = priority, updatedAt = now)
                   ZIO.succeed(((), state.copy(active = state.active + (runId -> updated))))
                 case None             =>
                   state.paused.get(runId) match
                     case Some(paused) =>
                       val updated = paused.copy(priority = priority, updatedAt = now)
                       ZIO.succeed(((), state.copy(paused = state.paused + (runId -> updated))))
                     case None         =>
                       if state.queued.exists(_.runId == runId) then
                         val queued = state.queued.map { q =>
                           if q.runId == runId then q.copy(priority = priority)
                           else q
                         }
                         ZIO.succeed(((), state.copy(queued = sortQueue(queued))))
                       else ZIO.fail(ControlPlaneError.ActiveRunNotFound(runId.toString))
             }
    yield ()

  override def listActive: UIO[List[WorkspaceRunAllocation]] =
    stateRef.get.map(_.active.values.toList.sortBy(_.runId))

  override def snapshot: UIO[WorkspaceCoordinatorSnapshot] =
    stateRef.get.map { state =>
      WorkspaceCoordinatorSnapshot(
        quotas = quotas,
        active = state.active.values.toList.sortBy(_.runId),
        queued = state.queued.map(q =>
          WorkspaceRunAllocation(
            runId = q.runId,
            priority = q.priority,
            request = q.request,
            state = WorkspaceRunState.Queued,
            allocatedAt = q.enqueuedAt,
            updatedAt = q.enqueuedAt,
          )
        ).toList,
        paused = state.paused.values.toList.sortBy(_.runId),
        totalActiveMemoryGb = state.totalActiveMemoryGb,
        totalActiveDiskGb = state.totalActiveDiskGb,
      )
    }

  override def cancelAll: UIO[List[Long]] =
    stateRef.modifyZIO { state =>
      val affected = (state.active.keySet ++ state.paused.keySet ++ state.queued.map(_.runId)).toList.sorted
      val failAll  = ZIO.foreachDiscard(state.queued)(_.gate.fail(
        ControlPlaneError.ResourceAllocationFailed("global", "Global cancel invoked")
      ))
      ZIO.succeed((failAll.as(affected), State.empty))
    }.flatten

  override def acquireNetworkPermit(runId: Long): IO[ControlPlaneError, Unit] =
    rateLimiter
      .acquireFor(runId.toString)
      .mapError(err => ControlPlaneError.ResourceAllocationFailed(runId.toString, err.message))

  private def fits(state: State, request: RunResourceRequest): Boolean =
    val nextParallel = state.active.size + 1
    val nextMemory   = state.totalActiveMemoryGb + request.memoryGb
    val nextDisk     = state.totalActiveDiskGb + request.diskGb
    nextParallel <= quotas.maxParallelRuns && nextMemory <= quotas.maxTotalMemoryGb && nextDisk <= quotas.maxDiskGb

  private def sortQueue(queue: Vector[QueuedRun]): Vector[QueuedRun] =
    queue.sortBy(q => (-q.priority.weight, q.enqueuedAt.toEpochMilli))

  private def allocateQueued(state: State): (State, List[UIO[Unit]]) =
    val ordered = sortQueue(state.queued)

    val (finalState, wakeups) = ordered.foldLeft((state.copy(queued = Vector.empty), List.empty[UIO[Unit]])) {
      case ((currentState, effects), queuedRun) =>
        if fits(currentState, queuedRun.request) then
          val allocation = WorkspaceRunAllocation(
            runId = queuedRun.runId,
            priority = queuedRun.priority,
            request = queuedRun.request,
            state = WorkspaceRunState.Active,
            allocatedAt = queuedRun.enqueuedAt,
            updatedAt = queuedRun.enqueuedAt,
          )
          val next       = currentState.copy(active = currentState.active + (queuedRun.runId -> allocation))
          (next, queuedRun.gate.succeed(()).unit :: effects)
        else
          (currentState.copy(queued = currentState.queued :+ queuedRun), effects)
    }

    (finalState, wakeups.reverse)
