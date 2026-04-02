package workspace.control

import zio.*
import zio.json.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import agent.entity.AgentPermissions
import analysis.entity.AnalysisRepository
import conversation.entity.api.{ ConversationEntry, MessageType, SenderType }
import db.ChatRepository
import issues.control.IssueAnalysisAttachment
import issues.entity.{ AgentIssue as DomainIssue, IssueEvent, IssueRepository }
import orchestration.control.SlotHandle
import shared.ids.Ids.{ EventId, IssueId, TaskRunId }
import workspace.control.WorkspaceErrorSupport.*
import workspace.entity.*

private[control] object WorkspaceRunLifecycleSupport:
  final case class GitSnapshot(
    headRevision: String,
    statusSummary: String,
  )

  enum CleanupMode:
    case OnCancelled, AfterMergeSuccess

final private[control] case class WorkspaceRunLifecycleSupport(
  wsRepo: WorkspaceRepository,
  chatRepo: ChatRepository,
  issueRepo: IssueRepository,
  analysisRepository: AnalysisRepository,
  worktreeRemove: String => Task[Unit],
  branchDelete: (String, String) => Task[Unit],
  activityPublish: ActivityEvent => UIO[Unit],
  slotRegistry: Ref[Map[String, SlotHandle]],
  releaseAgentSlot: SlotHandle => UIO[Unit],
  onIssueEnteredHumanReview: DomainIssue => IO[WorkspaceError, Unit],
  extractKnowledgeFromCompletedRun: (WorkspaceRun, Option[DomainIssue]) => IO[WorkspaceError, Unit],
):

  import WorkspaceRunLifecycleSupport.*

  def ensureNoActiveRunOnWorktree(run: WorkspaceRun): IO[WorkspaceError, Unit] =
    wsRepo.listRuns(run.workspaceId).mapWorkspacePersistence("list_workspace_runs_for_worktree_guard").flatMap { runs =>
      val hasActive = runs.exists(other =>
        other.id != run.id &&
        other.worktreePath == run.worktreePath &&
        (other.status == RunStatus.Pending || other.status.isInstanceOf[RunStatus.Running])
      )
      if hasActive then
        ZIO.fail(
          WorkspaceError.InvalidRunState(
            run.id,
            "no active run on worktree",
            "another continuation is already running",
          )
        )
      else ZIO.unit
    }

  def buildContinuationPrompt(parentRun: WorkspaceRun, followUpPrompt: String): IO[WorkspaceError, String] =
    for
      history    <- chatRepo
                      .getMessages(parentRun.conversationId.toLongOption.getOrElse(0L))
                      .mapWorkspacePersistence("load_continuation_messages")
      gitCtx     <- loadGitStatus(parentRun.worktreePath).orElseSucceed("git status unavailable")
      historyText = history
                      .takeRight(120)
                      .map(m => s"- ${m.senderType}:${m.sender}: ${m.content}")
                      .mkString("\n")
    yield s"""Continuation run for ${parentRun.issueRef}
             |Parent run: ${parentRun.id}
             |Branch: ${parentRun.branchName}
             |Worktree: ${parentRun.worktreePath}
             |
             |Conversation history:
             |$historyText
             |
             |Current worktree state:
             |$gitCtx
             |
             |New instructions:
             |$followUpPrompt
             |""".stripMargin

  def captureGitSnapshot(
    worktreePath: String,
    permissions: AgentPermissions,
  ): IO[WorkspaceError, Option[GitSnapshot]] =
    if !permissions.git.rollback then ZIO.succeed(None)
    else
      ZIO
        .attemptBlockingIO {
          val headPb = new ProcessBuilder("git", "-C", worktreePath, "rev-parse", "HEAD")
          headPb.redirectErrorStream(true)
          val headP  = headPb.start()
          val head   = scala.io.Source.fromInputStream(headP.getInputStream).mkString.trim
          val headRc = headP.waitFor()

          val statusPb = new ProcessBuilder("git", "-C", worktreePath, "status", "--short")
          statusPb.redirectErrorStream(true)
          val statusP  = statusPb.start()
          val status   = scala.io.Source.fromInputStream(statusP.getInputStream).mkString.trim
          val statusRc = statusP.waitFor()

          if headRc == 0 && statusRc == 0 && head.nonEmpty then
            Some(GitSnapshot(headRevision = head, statusSummary = status))
          else None
        }
        .mapWorktreeFailure("capture_git_snapshot")

  def rollbackToSnapshot(
    run: WorkspaceRun,
    snapshot: Option[GitSnapshot],
    permissions: AgentPermissions,
  ): IO[WorkspaceError, Unit] =
    if !permissions.git.rollback then ZIO.unit
    else
      snapshot match
        case None           => ZIO.unit
        case Some(snapshot) =>
          ZIO
            .attemptBlockingIO {
              val resetPb = new ProcessBuilder("git", "-C", run.worktreePath, "reset", "--hard", snapshot.headRevision)
              resetPb.redirectErrorStream(true)
              val resetP  = resetPb.start()
              val reset   = scala.io.Source.fromInputStream(resetP.getInputStream).mkString.trim
              val resetRc = resetP.waitFor()

              val cleanPb = new ProcessBuilder("git", "-C", run.worktreePath, "clean", "-fd")
              cleanPb.redirectErrorStream(true)
              val cleanP  = cleanPb.start()
              val clean   = scala.io.Source.fromInputStream(cleanP.getInputStream).mkString.trim
              val cleanRc = cleanP.waitFor()

              if resetRc == 0 && cleanRc == 0 then ""
              else s"reset=$reset clean=$clean"
            }
            .mapWorktreeFailure("rollback_worktree")
            .flatMap { details =>
              val suffix = if details.nonEmpty then s" ($details)" else ""
              appendToConversation(
                run.conversationId,
                s"Rollback executed for ${run.worktreePath} at ${snapshot.headRevision}$suffix",
              ).ignore
            }

  def updateRunStatus(runId: String, status: RunStatus): IO[WorkspaceError, Unit] =
    for
      now <- Clock.instant
      _   <- wsRepo
               .appendRun(WorkspaceRunEvent.StatusChanged(runId, status, now))
               .mapWorkspacePersistence("append_run_status_change")
      _   <- releaseRegisteredSlot(runId, status)
      _   <- publishRunLifecycle(runId, status)
      _   <- syncIssueLifecycle(runId, status).catchAll(err =>
               ZIO.logWarning(s"[run:$runId] failed to sync issue lifecycle for status $status: $err")
             )
    yield ()

  def maybeCleanupWorktree(run: WorkspaceRun, cleanup: CleanupMode): IO[WorkspaceError, Unit] =
    cleanup match
      case CleanupMode.OnCancelled       =>
        wsRepo.listRuns(run.workspaceId).mapWorkspacePersistence("list_workspace_runs_for_cleanup").flatMap { runs =>
          val hasActiveSibling = runs.exists(other =>
            other.id != run.id &&
            other.worktreePath == run.worktreePath &&
            (other.status == RunStatus.Pending || other.status.isInstanceOf[RunStatus.Running])
          )
          if hasActiveSibling then ZIO.unit
          else worktreeRemove(run.worktreePath).ignore
        }
      case CleanupMode.AfterMergeSuccess =>
        for
          runs          <- wsRepo.listRuns(run.workspaceId).mapWorkspacePersistence("list_workspace_runs_for_post_merge")
          otherRunExists = runs.exists(other =>
                             other.id != run.id &&
                             other.worktreePath == run.worktreePath
                           )
          _             <-
            if otherRunExists then
              recordCleanupAudit(
                run = run,
                worktreeRemoved = false,
                branchDeleted = false,
                details = s"Skipped cleanup because another run still references ${run.worktreePath}",
              ) *>
                ZIO.logWarning(
                  s"[run:${run.id}] skipping post-merge cleanup because another run references ${run.worktreePath}"
                )
            else cleanupMergedWorktree(run)
        yield ()

  private def loadGitStatus(worktreePath: String): Task[String] =
    ZIO.attemptBlockingIO {
      val pb   = new ProcessBuilder("git", "-C", worktreePath, "status", "--short", "--branch")
      pb.redirectErrorStream(true)
      val p    = pb.start()
      val out  = scala.io.Source.fromInputStream(p.getInputStream).mkString.trim
      val code = p.waitFor()
      if code == 0 then out else s"git status failed (exit=$code): $out"
    }

  private def releaseRegisteredSlot(runId: String, status: RunStatus): UIO[Unit] =
    status match
      case RunStatus.Completed | RunStatus.Failed | RunStatus.Cancelled =>
        slotRegistry.modify(slots => (slots.get(runId), slots - runId)).flatMap {
          case Some(handle) => releaseAgentSlot(handle)
          case None         => ZIO.unit
        }
      case _                                                            =>
        ZIO.unit

  private def appendToConversation(conversationId: String, line: String): IO[WorkspaceError, Unit] =
    for
      now  <- Clock.instant
      entry = ConversationEntry(
                conversationId = conversationId,
                sender = "agent",
                senderType = SenderType.Assistant,
                content = line,
                messageType = MessageType.Status,
                createdAt = now,
                updatedAt = now,
              )
      _    <- chatRepo.addMessage(entry).mapWorkspacePersistence("append_run_conversation_message")
    yield ()

  private def publishRunLifecycle(runId: String, status: RunStatus): UIO[Unit] =
    Clock.instant.flatMap { now =>
      val eventType = status match
        case RunStatus.Running(_) => ActivityEventType.RunStarted
        case RunStatus.Completed  => ActivityEventType.RunCompleted
        case RunStatus.Failed     => ActivityEventType.RunFailed
        case _                    => ActivityEventType.MessageSent
      activityPublish(
        ActivityEvent(
          id = EventId.generate,
          eventType = eventType,
          source = "workspace-run-service",
          runId = Some(TaskRunId(runId)),
          summary = s"Run $runId status changed to $status",
          payload = Some(status.toJson),
          createdAt = now,
        )
      )
    }

  private def syncIssueLifecycle(runId: String, status: RunStatus): IO[WorkspaceError, Unit] =
    status match
      case RunStatus.Completed | RunStatus.Failed | RunStatus.Cancelled =>
        for
          runOpt <- wsRepo.getRun(runId).mapWorkspacePersistence("load_run_for_issue_lifecycle_sync")
          _      <- runOpt match
                      case Some(run) =>
                        issueIdFromIssueRef(run.issueRef) match
                          case Some(issueId) => appendIssueLifecycleEvent(issueId, runId, status)
                          case None          => ZIO.unit
                      case None      => ZIO.unit
        yield ()
      case _                                                            =>
        ZIO.unit

  private def issueIdFromIssueRef(issueRef: String): Option[IssueId] =
    Option(issueRef).map(_.trim).filter(_.nonEmpty).map(_.stripPrefix("#")).filter(_.nonEmpty).map(IssueId.apply)

  private def appendIssueLifecycleEvent(
    issueId: IssueId,
    runId: String,
    status: RunStatus,
  ): IO[WorkspaceError, Unit] =
    for
      now <- Clock.instant
      _   <- status match
               case RunStatus.Completed =>
                 for
                   issue  <- issueRepo.get(issueId).mapWorkspacePersistence("load_issue_for_completion_sync")
                   events <- IssueAnalysisAttachment
                               .latestForHumanReview(issue, analysisRepository, now)
                               .map(attached =>
                                 IssueEvent.MovedToHumanReview(
                                   issueId = issueId,
                                   movedAt = now,
                                   occurredAt = now,
                                 ) :: attached.toList
                               )
                               .mapWorkspacePersistence("load_issue_analysis_attachments")
                   _      <- ZIO.foreachDiscard(events)(event =>
                               issueRepo.append(event).mapWorkspacePersistence("append_issue_completion_event")
                             )
                   _      <- onIssueEnteredHumanReview(issue)
                   _      <- wsRepo
                               .getRun(runId)
                               .mapWorkspacePersistence("load_completed_run_for_knowledge")
                               .flatMap {
                                 case Some(run) => extractKnowledgeFromCompletedRun(run, Some(issue))
                                 case None      => ZIO.unit
                               }
                 yield ()
               case RunStatus.Failed    =>
                 issueRepo
                   .append(
                     IssueEvent.RunFailed(
                       issueId = issueId,
                       runId = runId,
                       reason = s"Workspace run $runId failed",
                       occurredAt = now,
                     )
                   )
                   .mapWorkspacePersistence("append_issue_run_failed_event")
               case RunStatus.Cancelled =>
                 issueRepo
                   .append(IssueEvent.MovedToTodo(issueId = issueId, movedAt = now, occurredAt = now))
                   .mapWorkspacePersistence("append_issue_cancellation_event")
               case _                   =>
                 issueRepo
                   .append(IssueEvent.MovedToTodo(issueId = issueId, movedAt = now, occurredAt = now))
                   .mapWorkspacePersistence("append_issue_default_transition")
    yield ()

  private def cleanupMergedWorktree(run: WorkspaceRun): IO[WorkspaceError, Unit] =
    for
      workspace <- wsRepo
                     .get(run.workspaceId)
                     .mapWorkspacePersistence("load_workspace_for_post_merge_cleanup")
                     .flatMap {
                       case Some(value) => ZIO.succeed(value)
                       case None        => ZIO.fail(WorkspaceError.NotFound(run.workspaceId))
                     }
      removed   <-
        worktreeRemove(run.worktreePath).as(true).catchAll(error =>
          ZIO.logError(s"[run:${run.id}] failed to remove worktree ${run.worktreePath}: ${error.getMessage}") *>
            recordCleanupAudit(
              run = run,
              worktreeRemoved = false,
              branchDeleted = false,
              details = s"Cleanup failed removing worktree: ${error.getMessage}",
            ).as(false)
        )
      _         <- if !removed then ZIO.unit
                   else
                     branchDelete(workspace.localPath, run.branchName).either.flatMap {
                       case Right(_)    =>
                         recordCleanupAudit(
                           run = run,
                           worktreeRemoved = true,
                           branchDeleted = true,
                           details = s"Removed worktree ${run.worktreePath} and deleted branch ${run.branchName}",
                         )
                       case Left(error) =>
                         ZIO.logError(s"[run:${run.id}] failed to delete branch ${run.branchName}: ${error.getMessage}") *>
                           recordCleanupAudit(
                             run = run,
                             worktreeRemoved = true,
                             branchDeleted = false,
                             details = s"Removed worktree but failed to delete branch: ${error.getMessage}",
                           )
                     }
    yield ()

  private def recordCleanupAudit(
    run: WorkspaceRun,
    worktreeRemoved: Boolean,
    branchDeleted: Boolean,
    details: String,
  ): IO[WorkspaceError, Unit] =
    for
      now <- Clock.instant
      _   <- wsRepo
               .appendRun(
                 WorkspaceRunEvent.CleanupRecorded(
                   runId = run.id,
                   worktreeRemoved = worktreeRemoved,
                   branchDeleted = branchDeleted,
                   details = details,
                   occurredAt = now,
                 )
               )
               .mapWorkspacePersistence("append_cleanup_audit")
    yield ()
