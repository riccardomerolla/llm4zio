package orchestration.control

import java.time.Instant
import java.util.UUID

import scala.util.matching.Regex

import zio.*

import gateway.control.MessageRouter
import gateway.entity.SessionKey
import orchestration.entity.*
import workspace.control.WorkspaceRunService
import workspace.entity.{ AssignRunRequest, WorkspaceRepository }

trait ParallelSessionCoordinator:
  def launch(
    workflowId: String,
    workspaceId: String,
    baseBranch: String,
    requestedBy: Option[String],
  ): IO[ParallelSessionError, ParallelWorkspaceSession]

  def status(sessionId: String): IO[ParallelSessionError, ParallelWorkspaceSession]
  def cancel(sessionId: String): IO[ParallelSessionError, Unit]
  def collectResults(sessionId: String): IO[ParallelSessionError, ParallelWorkspaceSession]
  def cleanup(sessionId: String): IO[ParallelSessionError, Unit]

object ParallelSessionCoordinator:

  // ── public helpers (exposed for testing) ──────────────────────────────────

  /** Parse `git diff --stat` output to extract DiffStats. */
  def parseDiffStats(output: String): Option[DiffStats] =
    val pattern: Regex =
      raw"(\d+) files? changed,\s+(\d+) insertions?\(\+\),\s+(\d+) deletions?\(-\)".r
    pattern
      .findFirstMatchIn(output)
      .map(m => DiffStats(m.group(1).toInt, m.group(2).toInt, m.group(3).toInt))

  /** True when every worktree has reached a terminal status (Completed, Failed, Cancelled). */
  def allWorktreesDone(session: ParallelWorkspaceSession): Boolean =
    session.worktreeRuns.forall(r =>
      r.status == WorktreeRunStatus.Completed ||
      r.status == WorktreeRunStatus.Failed ||
      r.status == WorktreeRunStatus.Cancelled
    )

  /** Branches of all successfully completed worktrees. */
  def succeededBranches(session: ParallelWorkspaceSession): List[String] =
    session.worktreeRuns.collect { case r if r.status == WorktreeRunStatus.Completed => r.branch }

  // ── ZLayer ────────────────────────────────────────────────────────────────

  val live: ZLayer[
    WorkspaceRunService & WorkspaceRepository & OrchestratorControlPlane & MessageRouter,
    Nothing,
    ParallelSessionCoordinator,
  ] = ZLayer {
    for
      runService   <- ZIO.service[WorkspaceRunService]
      wsRepo       <- ZIO.service[WorkspaceRepository]
      controlPlane <- ZIO.service[OrchestratorControlPlane]
      router       <- ZIO.service[MessageRouter]
      sessions     <- Ref.make(Map.empty[String, ParallelWorkspaceSession])
    yield ParallelSessionCoordinatorLive(runService, wsRepo, controlPlane, router, sessions)
  }

final private case class ParallelSessionCoordinatorLive(
  runService: WorkspaceRunService,
  wsRepo: WorkspaceRepository,
  controlPlane: OrchestratorControlPlane,
  router: MessageRouter,
  sessions: Ref[Map[String, ParallelWorkspaceSession]],
) extends ParallelSessionCoordinator:

  override def launch(
    workflowId: String,
    workspaceId: String,
    baseBranch: String,
    requestedBy: Option[String],
  ): IO[ParallelSessionError, ParallelWorkspaceSession] =
    for
      ws           <- wsRepo
                        .get(workspaceId)
                        .mapError(e => ParallelSessionError.WorktreeError(e.toString))
                        .flatMap(
                          _.fold[IO[ParallelSessionError, workspace.entity.Workspace]](
                            ZIO.fail(ParallelSessionError.WorkspaceNotFound(workspaceId))
                          )(ZIO.succeed)
                        )
      sessionId     = UUID.randomUUID().toString
      correlationId = UUID.randomUUID().toString
      now          <- Clock.instant
      // Dispatch one run per step sequentially for now — parallel fan-out happens
      // at the WorkspaceRunService level through forkDaemon; we collect the refs.
      stepRefs     <- ZIO.foreach(List(s"step-${workflowId}-1")) { stepId =>
                        runService
                          .assign(
                            workspaceId,
                            AssignRunRequest(
                              issueRef = s"parallel-session/$sessionId",
                              prompt = s"Parallel session $sessionId — step $stepId",
                              agentName = ws.defaultAgent.getOrElse("claude"),
                            ),
                          )
                          .mapError(e => ParallelSessionError.WorktreeError(e.toString))
                          .map { run =>
                            WorktreeRunRef(
                              stepId = stepId,
                              workspaceRunId = run.id,
                              agentName = run.agentName,
                              branch = run.branchName,
                              status = WorktreeRunStatus.Running,
                              summary = None,
                              diffStats = None,
                            )
                          }
                      }
      session       = ParallelWorkspaceSession(
                        id = sessionId,
                        workflowId = workflowId,
                        correlationId = correlationId,
                        status = ParallelSessionStatus.Running,
                        baseBranch = baseBranch,
                        worktreeRuns = stepRefs,
                        createdAt = now,
                        updatedAt = now,
                        completedAt = None,
                        requestedBy = requestedBy,
                      )
      _            <- sessions.update(_ + (sessionId -> session))
      _            <- publishEvent(
                        sessionId,
                        ParallelSessionEvent.SessionStarted(
                          sessionId = sessionId,
                          workflowId = workflowId,
                          worktreeCount = stepRefs.size,
                          occurredAt = now,
                        ),
                      ).ignore
    yield session

  override def status(sessionId: String): IO[ParallelSessionError, ParallelWorkspaceSession] =
    sessions.get.flatMap(
      _.get(sessionId)
        .fold[IO[ParallelSessionError, ParallelWorkspaceSession]](
          ZIO.fail(ParallelSessionError.SessionNotFound(sessionId))
        )(ZIO.succeed)
    )

  override def cancel(sessionId: String): IO[ParallelSessionError, Unit] =
    for
      session <- status(sessionId)
      now     <- Clock.instant
      _       <- ZIO.foreachDiscard(session.worktreeRuns) { ref =>
                   runService
                     .cancelRun(ref.workspaceRunId)
                     .mapError(e => ParallelSessionError.WorktreeError(e.toString))
                     .ignore
                 }
      _       <- sessions.update(_.updated(
                   sessionId,
                   session.copy(
                     status = ParallelSessionStatus.Cancelled,
                     worktreeRuns = session.worktreeRuns.map(_.copy(status = WorktreeRunStatus.Cancelled)),
                     updatedAt = now,
                     completedAt = Some(now),
                   ),
                 ))
    yield ()

  override def collectResults(sessionId: String): IO[ParallelSessionError, ParallelWorkspaceSession] =
    for
      session  <- status(sessionId)
      now      <- Clock.instant
      enriched <- ZIO.foreach(session.worktreeRuns) { ref =>
                    if ref.status == WorktreeRunStatus.Completed then
                      gatherDiffStats(session.baseBranch, ref.branch)
                        .map(stats => ref.copy(diffStats = stats))
                    else
                      ZIO.succeed(ref)
                  }
      succeeded = enriched.count(_.status == WorktreeRunStatus.Completed)
      failed    = enriched.count(_.status == WorktreeRunStatus.Failed)
      branches  = ParallelSessionCoordinator.succeededBranches(session)
      updated   = session.copy(
                    status = ParallelSessionStatus.ReadyForReview,
                    worktreeRuns = enriched,
                    updatedAt = now,
                    completedAt = Some(now),
                  )
      _        <- sessions.update(_.updated(sessionId, updated))
      _        <- publishEvent(
                    sessionId,
                    ParallelSessionEvent.SessionReadyForReview(
                      sessionId = sessionId,
                      succeeded = succeeded,
                      failed = failed,
                      branches = branches,
                      occurredAt = now,
                    ),
                  ).ignore
    yield updated

  override def cleanup(sessionId: String): IO[ParallelSessionError, Unit] =
    for
      session <- status(sessionId)
      _       <- ZIO.foreachDiscard(session.worktreeRuns) { ref =>
                   removeWorktree(ref.branch).ignore
                 }
      _       <- sessions.update(_ - sessionId)
    yield ()

  // ── private helpers ───────────────────────────────────────────────────────

  private def gatherDiffStats(baseBranch: String, branch: String): UIO[Option[DiffStats]] =
    ZIO
      .attemptBlockingIO {
        val pb   = new ProcessBuilder("git", "diff", "--stat", s"$baseBranch...$branch")
        val proc = pb.start()
        val out  = scala.io.Source.fromInputStream(proc.getInputStream).mkString
        proc.waitFor()
        ParallelSessionCoordinator.parseDiffStats(out)
      }
      .orElseSucceed(None)

  private def removeWorktree(branch: String): UIO[Unit] =
    ZIO
      .attemptBlockingIO {
        val pb = new ProcessBuilder("git", "worktree", "remove", "--force", branch)
        pb.start().waitFor()
        ()
      }
      .orElseSucceed(())

  private def publishEvent(sessionId: String, event: ParallelSessionEvent): UIO[Unit] =
    (for
      now       <- Clock.instant
      sessionKey = SessionKey("system", s"parallel-session:$sessionId")
      msg        = ParallelSessionFormatter.toNormalizedMessage(event, "system", sessionKey)
      _         <- router.routeOutbound(msg)
    yield ()).ignore
