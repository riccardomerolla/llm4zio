package orchestration.control

import zio.*

import issues.entity.IssueEvent
import taskrun.entity.TaskRunEvent

/** Typed event bus for proof-of-work signals.
  *
  * Publishers (repositories, agent runners) send domain events here. `IssueWorkReportSubscriber` consumes from these
  * hubs and updates the projection.
  */
final class WorkReportEventBus(
  taskRunHub: Hub[TaskRunEvent],
  issueHub: Hub[IssueEvent],
  parallelSessionHub: Hub[ParallelSessionEvent],
):
  def publishTaskRun(event: TaskRunEvent): UIO[Unit]                 = taskRunHub.publish(event).unit
  def publishIssue(event: IssueEvent): UIO[Unit]                     = issueHub.publish(event).unit
  def publishParallelSession(event: ParallelSessionEvent): UIO[Unit] =
    parallelSessionHub.publish(event).unit

  def subscribeTaskRun: URIO[Scope, Dequeue[TaskRunEvent]]                 = taskRunHub.subscribe
  def subscribeIssue: URIO[Scope, Dequeue[IssueEvent]]                     = issueHub.subscribe
  def subscribeParallelSession: URIO[Scope, Dequeue[ParallelSessionEvent]] = parallelSessionHub.subscribe

object WorkReportEventBus:

  def make: UIO[WorkReportEventBus] =
    for
      taskRunHub         <- Hub.unbounded[TaskRunEvent]
      issueHub           <- Hub.unbounded[IssueEvent]
      parallelSessionHub <- Hub.unbounded[ParallelSessionEvent]
    yield WorkReportEventBus(taskRunHub, issueHub, parallelSessionHub)

  val layer: ULayer[WorkReportEventBus] = ZLayer.fromZIO(make)
