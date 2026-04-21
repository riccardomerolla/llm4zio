package workspace.entity

import scala.annotation.unused

import zio.*

/** Service trait for managing workspace runs. The `Live` implementation lives at
  * `workspace.control.WorkspaceRunServiceLive` because it depends on types from multiple domain modules (orchestration,
  * knowledge, agent, activity, etc.) that `workspace-domain` cannot pull in without creating dependency cycles.
  *
  * `registerSlot` is intentionally NOT part of this trait — it is an implementation detail of the Live class (an
  * internal self-call from `assign`/`continueRun`) and keeping it off the trait lets the trait stay free of
  * `orchestration.entity.SlotHandle`.
  */
trait WorkspaceRunService:
  def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun]
  def continueRun(
    runId: String,
    followUpPrompt: String,
    agentNameOverride: Option[String] = None,
  ): IO[WorkspaceError, WorkspaceRun]
  def cancelRun(runId: String): IO[WorkspaceError, Unit]
  def cleanupAfterSuccessfulMerge(@unused runId: String): UIO[Unit] = ZIO.unit
