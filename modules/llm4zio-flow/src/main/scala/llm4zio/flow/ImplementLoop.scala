package llm4zio.flow

import zio.ZIO

import java.nio.file.Path

/** Run a [[Plan]] to completion, one task at a time, persisting progress to
  * `planPath` after each task so a crashed run resumes from the first
  * incomplete task (re-load via [[PlanStore.recoverOrCreate]]).
  *
  * Already-completed tasks are skipped. Each task runs inside a [[stage]]. If
  * `perTask` fails, the loop stops with that error; everything completed before
  * it is already on disk.
  */
def implementTaskLoop[R, E >: FlowError](planPath: Path, plan: Plan)(
  perTask: Task => ZIO[R, E, Unit]
)(using FlowEvents): ZIO[R, E, Plan] =
  ZIO.foldLeft(plan.tasks)(plan) { (acc, task) =>
    if task.completed then ZIO.succeed(acc)
    else
      stage(task.title)(perTask(task)) *> {
        val updated = acc.complete(task.title)
        PlanStore.save(planPath, updated).as(updated)
      }
  }
