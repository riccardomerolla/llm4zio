package analysis.control

import java.time.Instant

import zio.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import analysis.entity.{ AnalysisDoc, AnalysisRepository, AnalysisType }
import db.TaskRepository
import issues.control.IssueAnalysisAttachment
import issues.entity.{ IssueEvent, IssueFilter, IssueRepository, IssueStateTag }
import shared.errors.PersistenceError
import shared.ids.Ids.{ EventId, IssueId }

final private[analysis] case class WorkspaceAnalysisJob(
  workspaceId: String,
  analysisType: AnalysisType,
)

enum WorkspaceAnalysisState:
  case Idle
  case Pending
  case Running
  case Completed
  case Failed

final case class WorkspaceAnalysisStatus(
  workspaceId: String,
  analysisType: AnalysisType,
  state: WorkspaceAnalysisState,
  queuedAt: Option[Instant] = None,
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  lastUpdatedAt: Instant,
)

trait WorkspaceAnalysisScheduler:
  def triggerForWorkspaceEvent(workspaceId: String): UIO[Unit]
  def triggerManual(workspaceId: String): UIO[Unit]
  def statusForWorkspace(workspaceId: String): IO[PersistenceError, List[WorkspaceAnalysisStatus]]

object WorkspaceAnalysisScheduler:
  val cooldownMinutesSettingKey: String = "analysis.autoTrigger.cooldownMinutes"
  val defaultCooldown: Duration         = 1.hour

  val trackedTypes: List[AnalysisType] =
    List(AnalysisType.CodeReview, AnalysisType.Architecture, AnalysisType.Security)

  def triggerForWorkspaceEvent(workspaceId: String): URIO[WorkspaceAnalysisScheduler, Unit] =
    ZIO.serviceWithZIO[WorkspaceAnalysisScheduler](_.triggerForWorkspaceEvent(workspaceId))

  def triggerManual(workspaceId: String): URIO[WorkspaceAnalysisScheduler, Unit] =
    ZIO.serviceWithZIO[WorkspaceAnalysisScheduler](_.triggerManual(workspaceId))

  def statusForWorkspace(
    workspaceId: String
  ): ZIO[WorkspaceAnalysisScheduler, PersistenceError, List[WorkspaceAnalysisStatus]] =
    ZIO.serviceWithZIO[WorkspaceAnalysisScheduler](_.statusForWorkspace(workspaceId))

  val live
    : ZLayer[
      AnalysisAgentRunner & AnalysisRepository & ActivityHub & TaskRepository & IssueRepository,
      Nothing,
      WorkspaceAnalysisScheduler,
    ] =
    ZLayer.scoped {
      for
        runner       <- ZIO.service[AnalysisAgentRunner]
        repository   <- ZIO.service[AnalysisRepository]
        activityHub  <- ZIO.service[ActivityHub]
        taskRepo     <- ZIO.service[TaskRepository]
        issueRepo    <- ZIO.service[IssueRepository]
        queue        <- Queue.unbounded[WorkspaceAnalysisJob]
        runtimeState <- Ref.Synchronized.make(Map.empty[(String, AnalysisType), WorkspaceAnalysisStatus])
        service       = WorkspaceAnalysisSchedulerLive(
                          runner = runner,
                          repository = repository,
                          activityHub = activityHub,
                          taskRepository = taskRepo,
                          issueRepository = issueRepo,
                          queue = queue,
                          runtimeState = runtimeState,
                        )
        _            <- ZIO.foreachParDiscard(1 to trackedTypes.size)(_ => service.worker.forever.forkScoped)
      yield service
    }

final case class WorkspaceAnalysisSchedulerLive(
  runner: AnalysisAgentRunner,
  repository: AnalysisRepository,
  activityHub: ActivityHub,
  taskRepository: TaskRepository,
  issueRepository: IssueRepository,
  queue: Queue[WorkspaceAnalysisJob],
  runtimeState: Ref.Synchronized[Map[(String, AnalysisType), WorkspaceAnalysisStatus]],
) extends WorkspaceAnalysisScheduler:

  import WorkspaceAnalysisScheduler.*

  override def triggerForWorkspaceEvent(workspaceId: String): UIO[Unit] =
    trigger(workspaceId, bypassCooldown = false)

  override def triggerManual(workspaceId: String): UIO[Unit] =
    trigger(workspaceId, bypassCooldown = true)

  override def statusForWorkspace(workspaceId: String): IO[PersistenceError, List[WorkspaceAnalysisStatus]] =
    for
      docs    <- repository.listByWorkspace(workspaceId)
      runtime <- runtimeState.get.map(_.collect { case ((id, _), status) if id == workspaceId => status })
      statuses = trackedTypes.map(analysisType => mergeStatus(workspaceId, analysisType, docs, runtime))
    yield statuses.sortBy(statusOrder)

  private[analysis] def worker: UIO[Unit] =
    queue.take.flatMap(runJob)

  private def trigger(workspaceId: String, bypassCooldown: Boolean): UIO[Unit] =
    (for
      docs     <- repository.listByWorkspace(workspaceId)
      cooldown <- cooldownDuration
      now      <- Clock.instant
      _        <- ZIO.foreachDiscard(trackedTypes)(analysisType =>
                    enqueueIfEligible(workspaceId, analysisType, docs, cooldown, now, bypassCooldown)
                  )
    yield ())
      .catchAll(err => ZIO.logWarning(s"Analysis trigger failed for workspace $workspaceId: ${err.toString}"))

  private def enqueueIfEligible(
    workspaceId: String,
    analysisType: AnalysisType,
    docs: List[AnalysisDoc],
    cooldown: Duration,
    now: Instant,
    bypassCooldown: Boolean,
  ): IO[PersistenceError, Unit] =
    runtimeState.modifyZIO { current =>
      val key          = workspaceId -> analysisType
      val currentState = current.get(key)
      val shouldSkip   =
        currentState.exists(status =>
          status.state == WorkspaceAnalysisState.Pending || status.state == WorkspaceAnalysisState.Running
        ) ||
        (!bypassCooldown && withinCooldown(latestCompletion(docs, analysisType), now, cooldown))
      if shouldSkip then ZIO.succeed(((), current))
      else
        val nextStatus = WorkspaceAnalysisStatus(
          workspaceId = workspaceId,
          analysisType = analysisType,
          state = WorkspaceAnalysisState.Pending,
          queuedAt = Some(now),
          startedAt = currentState.flatMap(_.startedAt),
          completedAt = currentState.flatMap(_.completedAt).orElse(latestCompletion(docs, analysisType)),
          lastUpdatedAt = now,
        )
        queue.offer(WorkspaceAnalysisJob(workspaceId, analysisType)).as(((), current.updated(key, nextStatus)))
    }

  private def runJob(job: WorkspaceAnalysisJob): UIO[Unit] =
    (for
      startedAt <- Clock.instant
      _         <- updateStatus(job.workspaceId, job.analysisType) { current =>
                     current.copy(
                       state = WorkspaceAnalysisState.Running,
                       startedAt = Some(startedAt),
                       lastUpdatedAt = startedAt,
                     )
                   }
      _         <- publishActivity(job.workspaceId, job.analysisType, ActivityEventType.AnalysisStarted, startedAt, None)
      doc       <- runAnalysis(job.workspaceId, job.analysisType)
      completed <- Clock.instant
      _         <- ensureHumanReviewIssueWithAnalysis(job.workspaceId, completed)
      _         <- updateStatus(job.workspaceId, job.analysisType) { current =>
                     current.copy(
                       state = WorkspaceAnalysisState.Completed,
                       completedAt = Some(doc.updatedAt),
                       lastUpdatedAt = completed,
                     )
                   }
      _         <- publishActivity(
                     job.workspaceId,
                     job.analysisType,
                     ActivityEventType.AnalysisCompleted,
                     completed,
                     Some(doc.generatedBy.value),
                   )
    yield ())
      .catchAll { err =>
        Clock.instant.flatMap { failedAt =>
          updateStatus(job.workspaceId, job.analysisType) { current =>
            current.copy(
              state = WorkspaceAnalysisState.Failed,
              lastUpdatedAt = failedAt,
            )
          } *>
            publishActivity(
              job.workspaceId,
              job.analysisType,
              ActivityEventType.AnalysisFailed,
              failedAt,
              None,
            ) *>
            ZIO.logWarning(
              s"Analysis execution failed for ${job.workspaceId}/${renderAnalysisType(job.analysisType)}: ${renderJobError(err)}"
            )
        }
      }

  private def runAnalysis(workspaceId: String, analysisType: AnalysisType): IO[AnalysisAgentRunnerError, AnalysisDoc] =
    analysisType match
      case AnalysisType.CodeReview     => runner.runCodeReview(workspaceId)
      case AnalysisType.Architecture   => runner.runArchitecture(workspaceId)
      case AnalysisType.Security       => runner.runSecurity(workspaceId)
      case custom: AnalysisType.Custom =>
        ZIO.fail(AnalysisAgentRunnerError.ProcessFailed(
          custom.name,
          "Unsupported analysis type for workspace scheduler",
        ))

  private def publishActivity(
    workspaceId: String,
    analysisType: AnalysisType,
    eventType: ActivityEventType,
    now: Instant,
    agentName: Option[String],
  ): UIO[Unit] =
    activityHub.publish(
      ActivityEvent(
        id = EventId.generate,
        eventType = eventType,
        source = "workspace-analysis",
        agentName = agentName,
        summary =
          s"${activitySummaryPrefix(eventType)} ${renderAnalysisType(analysisType)} analysis for workspace $workspaceId",
        payload = Some(s"""{"workspaceId":"$workspaceId","analysisType":"${renderAnalysisType(analysisType)}"}"""),
        createdAt = now,
      )
    )

  private def ensureHumanReviewIssueWithAnalysis(
    workspaceId: String,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    runtimeState.modifyZIO { current =>
      upsertHumanReviewIssue(workspaceId, now).as(((), current))
    }

  private def upsertHumanReviewIssue(
    workspaceId: String,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    for
      issues      <- issueRepository.list(IssueFilter(states = Set(IssueStateTag.HumanReview), limit = Int.MaxValue))
      reviewIssues = issues.filter(_.workspaceId.contains(workspaceId))
      _           <- reviewIssues match
                       case Nil      =>
                         createHumanReviewIssue(workspaceId, now).flatMap(attachLatestAnalysis(_, now))
                       case existing =>
                         ZIO.foreachDiscard(existing)(issue => attachLatestAnalysis(issue.id, now))
    yield ()

  private def createHumanReviewIssue(
    workspaceId: String,
    now: Instant,
  ): IO[PersistenceError, IssueId] =
    val issueId     = IssueId.generate
    val title       = s"Human review for workspace $workspaceId"
    val description =
      s"Automatically created review issue for workspace $workspaceId. Review the latest code review, architecture, and security analysis docs."
    for
      _ <- issueRepository.append(
             IssueEvent.Created(
               issueId = issueId,
               title = title,
               description = description,
               issueType = "task",
               priority = "medium",
               occurredAt = now,
             )
           )
      _ <- issueRepository.append(IssueEvent.TagsUpdated(issueId, List("analysis-review", "auto-generated"), now))
      _ <- issueRepository.append(IssueEvent.WorkspaceLinked(issueId, workspaceId, now))
      _ <- issueRepository.append(IssueEvent.MovedToHumanReview(issueId, movedAt = now, occurredAt = now))
    yield issueId

  private def attachLatestAnalysis(
    issueId: IssueId,
    now: Instant,
  ): IO[PersistenceError, Unit] =
    for
      issue <- issueRepository.get(issueId)
      _     <- IssueAnalysisAttachment
                 .latestForHumanReview(issue, repository, now)
                 .flatMap {
                   case Some(event) if event.analysisDocIds != issue.analysisDocIds =>
                     issueRepository.append(event)
                   case _                                                           =>
                     ZIO.unit
                 }
    yield ()

  private def renderJobError(error: AnalysisAgentRunnerError | PersistenceError): String =
    error match
      case err: AnalysisAgentRunnerError => err.message
      case err: PersistenceError         => err.toString

  private def updateStatus(
    workspaceId: String,
    analysisType: AnalysisType,
  )(
    f: WorkspaceAnalysisStatus => WorkspaceAnalysisStatus
  ): UIO[Unit] =
    runtimeState.update { current =>
      val key    = workspaceId -> analysisType
      val status = current.getOrElse(
        key,
        WorkspaceAnalysisStatus(
          workspaceId = workspaceId,
          analysisType = analysisType,
          state = WorkspaceAnalysisState.Idle,
          lastUpdatedAt = Instant.EPOCH,
        ),
      )
      current.updated(key, f(status))
    }

  private def cooldownDuration: IO[PersistenceError, Duration] =
    taskRepository
      .getSetting(cooldownMinutesSettingKey)
      .mapError(err => PersistenceError.QueryFailed("analysisCooldownSetting", err.toString))
      .map(setting =>
        setting
          .flatMap(_.value.trim.toLongOption)
          .filter(_ >= 0L)
          .map(_.minutes)
          .getOrElse(defaultCooldown)
      )

  private def mergeStatus(
    workspaceId: String,
    analysisType: AnalysisType,
    docs: List[AnalysisDoc],
    runtime: Iterable[WorkspaceAnalysisStatus],
  ): WorkspaceAnalysisStatus =
    runtime.find(_.analysisType == analysisType).getOrElse {
      val completedAt = latestCompletion(docs, analysisType)
      WorkspaceAnalysisStatus(
        workspaceId = workspaceId,
        analysisType = analysisType,
        state =
          completedAt.fold[WorkspaceAnalysisState](WorkspaceAnalysisState.Idle)(_ => WorkspaceAnalysisState.Completed),
        completedAt = completedAt,
        lastUpdatedAt = completedAt.getOrElse(Instant.EPOCH),
      )
    }

  private def latestCompletion(docs: List[AnalysisDoc], analysisType: AnalysisType): Option[Instant] =
    docs
      .filter(_.analysisType == analysisType)
      .sortBy(_.updatedAt)
      .lastOption
      .map(_.updatedAt)

  private def withinCooldown(latest: Option[Instant], now: Instant, cooldown: Duration): Boolean =
    latest.exists(_.plusMillis(cooldown.toMillis).isAfter(now))

  private def renderAnalysisType(analysisType: AnalysisType): String =
    analysisType match
      case AnalysisType.CodeReview   => "Code review"
      case AnalysisType.Architecture => "Architecture"
      case AnalysisType.Security     => "Security"
      case AnalysisType.Custom(name) => name

  private def activitySummaryPrefix(eventType: ActivityEventType): String =
    eventType match
      case ActivityEventType.AnalysisStarted   => "Started"
      case ActivityEventType.AnalysisCompleted => "Completed"
      case ActivityEventType.AnalysisFailed    => "Failed"
      case _                                   => "Updated"

  private def statusOrder(status: WorkspaceAnalysisStatus): Int =
    status.analysisType match
      case AnalysisType.CodeReview   => 0
      case AnalysisType.Architecture => 1
      case AnalysisType.Security     => 2
      case AnalysisType.Custom(_)    => 3
