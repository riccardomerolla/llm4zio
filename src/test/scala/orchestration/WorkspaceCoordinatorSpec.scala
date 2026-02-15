package orchestration

import zio.*
import zio.test.*

object WorkspaceCoordinatorSpec extends ZIOSpecDefault:

  private val quotas = ResourceQuotas(
    maxParallelRuns = 1,
    maxTotalMemoryGb = 4,
    maxDiskGb = 4,
    rateLimit = TokenBucketConfig(requestsPerMinute = 120, burstSize = 2),
    defaultRunPriority = RunPriority.Medium,
  )

  private val layer: ZLayer[Any, Nothing, WorkspaceCoordinator] =
    ZLayer.succeed(quotas) >>> WorkspaceCoordinator.live

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspaceCoordinatorSpec")(
    test("enforces maxParallelRuns by queueing and unblocking on release") {
      for
        _      <- WorkspaceCoordinator.acquire(1L)
        second <- WorkspaceCoordinator.acquire(2L).fork
        poll0  <- second.poll
        _      <- WorkspaceCoordinator.release(1L)
        _      <- second.join
        active <- WorkspaceCoordinator.listActive
      yield assertTrue(
        poll0.isEmpty,
        active.map(_.runId) == List(2L),
      )
    },
    test("schedules queued runs by priority") {
      for
        _         <- WorkspaceCoordinator.acquire(1L)
        lowFiber  <- WorkspaceCoordinator.acquire(2L, priority = Some(RunPriority.Low)).fork
        highFiber <- WorkspaceCoordinator.acquire(3L, priority = Some(RunPriority.High)).fork
        _         <- (lowFiber.poll zip highFiber.poll)
                       .repeatUntil {
                         case (low, high) =>
                           low.isEmpty && high.isEmpty
                       }
                       .timeoutFail("queue fibers did not block before release")(2.seconds)
        _         <- WorkspaceCoordinator.release(1L)
        _         <- highFiber.await.timeoutFail("high-priority run did not start")(2.seconds)
        active1   <- WorkspaceCoordinator.listActive
        lowPoll   <- lowFiber.poll
        _         <- WorkspaceCoordinator.release(3L)
        _         <- lowFiber.join.timeoutFail("low-priority run did not start after high release")(2.seconds)
        active2   <- WorkspaceCoordinator.listActive
      yield assertTrue(
        active1.map(_.runId) == List(3L),
        lowPoll.isEmpty,
        active2.map(_.runId) == List(2L),
      )
    },
    test("cancelAll returns affected runs and clears active/queued state") {
      for
        _         <- WorkspaceCoordinator.acquire(10L)
        queuedRun <- WorkspaceCoordinator.acquire(11L).fork
        _         <- WorkspaceCoordinator.snapshot
                       .repeatUntil(_.queued.exists(_.runId == 11L))
                       .timeoutFail("run 11 was not queued before cancelAll")(2.seconds)
        ids       <- WorkspaceCoordinator.cancelAll
        result    <- queuedRun.await
        summary   <- WorkspaceCoordinator.snapshot
      yield assertTrue(
        ids == List(10L, 11L),
        result.isFailure,
        summary.active.isEmpty,
        summary.queued.isEmpty,
      )
    },
    test("supports pause and resume with state tracking") {
      for
        _       <- WorkspaceCoordinator.acquire(22L)
        _       <- WorkspaceCoordinator.pause(22L)
        paused  <- WorkspaceCoordinator.snapshot
        _       <- WorkspaceCoordinator.resume(22L)
        resumed <- WorkspaceCoordinator.snapshot
      yield assertTrue(
        paused.paused.map(_.runId) == List(22L),
        resumed.active.map(_.runId) == List(22L),
      )
    },
  ).provideLayer(layer) @@ TestAspect.timeout(10.seconds)
