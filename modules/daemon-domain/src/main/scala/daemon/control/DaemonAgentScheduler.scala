package daemon.control

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.time.Instant
import java.util.Locale
import java.util.regex.Pattern

import scala.concurrent.duration.Duration as ScalaDuration
import scala.jdk.CollectionConverters.*

import zio.*

import _root_.config.entity.ConfigRepository
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import daemon.entity.*
import governance.entity.{ GovernanceDaemonTrigger, GovernancePolicy, GovernancePolicyRepository }
import issues.entity.*
import orchestration.entity.{ AgentPoolManager, PoolError }
import project.entity.{ Project, ProjectRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.{ DaemonAgentSpecId, EventId, IssueId, ProjectId }
import workspace.entity.{ Workspace, WorkspaceRepository }

trait DaemonAgentScheduler:
  def list: IO[PersistenceError, List[DaemonAgentStatus]]
  def start(id: DaemonAgentSpecId): IO[PersistenceError, Unit]
  def stop(id: DaemonAgentSpecId): IO[PersistenceError, Unit]
  def restart(id: DaemonAgentSpecId): IO[PersistenceError, Unit]
  def setEnabled(id: DaemonAgentSpecId, enabled: Boolean): IO[PersistenceError, Unit]
  def trigger(id: DaemonAgentSpecId): IO[PersistenceError, Unit]
  def triggerGovernance(projectId: ProjectId, triggerId: String): IO[PersistenceError, Unit]

object DaemonAgentScheduler:
  val coordinatorInterval: Duration = 1.minute

  def list: ZIO[DaemonAgentScheduler, PersistenceError, List[DaemonAgentStatus]] =
    ZIO.serviceWithZIO[DaemonAgentScheduler](_.list)

  def start(id: DaemonAgentSpecId): ZIO[DaemonAgentScheduler, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DaemonAgentScheduler](_.start(id))

  def stop(id: DaemonAgentSpecId): ZIO[DaemonAgentScheduler, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DaemonAgentScheduler](_.stop(id))

  def restart(id: DaemonAgentSpecId): ZIO[DaemonAgentScheduler, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DaemonAgentScheduler](_.restart(id))

  def setEnabled(id: DaemonAgentSpecId, enabled: Boolean): ZIO[DaemonAgentScheduler, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DaemonAgentScheduler](_.setEnabled(id, enabled))

  def trigger(id: DaemonAgentSpecId): ZIO[DaemonAgentScheduler, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DaemonAgentScheduler](_.trigger(id))

  def triggerGovernance(projectId: ProjectId, triggerId: String): ZIO[DaemonAgentScheduler, PersistenceError, Unit] =
    ZIO.serviceWithZIO[DaemonAgentScheduler](_.triggerGovernance(projectId, triggerId))

  val live
    : ZLayer[
      ProjectRepository & WorkspaceRepository & IssueRepository & ActivityHub & AgentPoolManager & ConfigRepository &
        GovernancePolicyRepository & DaemonAgentSpecRepository,
      Nothing,
      DaemonAgentScheduler,
    ] =
    ZLayer.scoped {
      for
        projectRepository    <- ZIO.service[ProjectRepository]
        workspaceRepository  <- ZIO.service[WorkspaceRepository]
        issueRepository      <- ZIO.service[IssueRepository]
        activityHub          <- ZIO.service[ActivityHub]
        agentPoolManager     <- ZIO.service[AgentPoolManager]
        configRepository     <- ZIO.service[ConfigRepository]
        governanceRepository <- ZIO.service[GovernancePolicyRepository]
        daemonRepository     <- ZIO.service[DaemonAgentSpecRepository]
        queue                <- Queue.unbounded[DaemonJob]
        runtimeState         <- Ref.Synchronized.make(Map.empty[DaemonAgentSpecId, DaemonAgentRuntime])
        service               = DaemonAgentSchedulerLive(
                                  projectRepository = projectRepository,
                                  workspaceRepository = workspaceRepository,
                                  issueRepository = issueRepository,
                                  activityHub = activityHub,
                                  agentPoolManager = agentPoolManager,
                                  configRepository = configRepository,
                                  governanceRepository = governanceRepository,
                                  daemonRepository = daemonRepository,
                                  queue = queue,
                                  runtimeState = runtimeState,
                                )
        _                    <- service.coordinator.forever.forkScoped
        _                    <- ZIO.foreachParDiscard(1 to 2)(_ => service.worker.forever.forkScoped)
      yield service
    }

final private[control] case class DaemonJob(
  specId: DaemonAgentSpecId,
  reason: String,
  requestedAt: Instant,
)

final case class DaemonAgentSchedulerLive(
  projectRepository: ProjectRepository,
  workspaceRepository: WorkspaceRepository,
  issueRepository: IssueRepository,
  activityHub: ActivityHub,
  agentPoolManager: AgentPoolManager,
  configRepository: ConfigRepository,
  governanceRepository: GovernancePolicyRepository,
  daemonRepository: DaemonAgentSpecRepository,
  queue: Queue[DaemonJob],
  runtimeState: Ref.Synchronized[Map[DaemonAgentSpecId, DaemonAgentRuntime]],
) extends DaemonAgentScheduler:
  import DaemonAgentScheduler.*

  override def list: IO[PersistenceError, List[DaemonAgentStatus]] =
    for
      specs         <- loadSpecs
      enabledBySpec <- ZIO.foreach(specs)(spec => resolveEnabled(spec).map(spec.id -> _)).map(_.toMap)
      runtimeBySpec <- runtimeState.get
      defaultRuntime = DaemonAgentRuntime()
    yield specs
      .map { spec =>
        val enabled = enabledBySpec.getOrElse(spec.id, true)
        val runtime = runtimeBySpec.getOrElse(spec.id, defaultRuntime)
        DaemonAgentStatus(
          spec = spec,
          enabled = enabled,
          runtime =
            if !enabled then runtime.copy(health = DaemonHealth.Disabled)
            else runtime,
        )
      }
      .sortBy(status => (status.spec.projectId.value, status.spec.name.toLowerCase(Locale.ROOT)))

  override def start(id: DaemonAgentSpecId): IO[PersistenceError, Unit] =
    setLifecycle(id, DaemonLifecycle.Running) *> trigger(id)

  override def stop(id: DaemonAgentSpecId): IO[PersistenceError, Unit] =
    setLifecycle(id, DaemonLifecycle.Stopped)

  override def restart(id: DaemonAgentSpecId): IO[PersistenceError, Unit] =
    stop(id) *> start(id)

  override def setEnabled(id: DaemonAgentSpecId, enabled: Boolean): IO[PersistenceError, Unit] =
    for
      spec <- requireSpec(id)
      _    <- configRepository
                .upsertSetting(enabledKey(spec.projectId, spec.daemonKey), enabled.toString)
                .mapError(err => PersistenceError.QueryFailed("daemon_set_enabled", err.toString))
      _    <- updateRuntime(id) { runtime =>
                runtime.copy(
                  health =
                    if enabled then
                      if runtime.health == DaemonHealth.Disabled then DaemonHealth.Idle else runtime.health
                    else DaemonHealth.Disabled
                )
              }
    yield ()

  override def trigger(id: DaemonAgentSpecId): IO[PersistenceError, Unit] =
    for
      spec    <- requireSpec(id)
      enabled <- resolveEnabled(spec)
      _       <- ZIO.fail(PersistenceError.QueryFailed("daemon_trigger", s"Daemon ${id.value} is disabled")).unless(
                   enabled
                 )
      now     <- Clock.instant
      _       <- queueJob(spec, s"manual:${spec.daemonKey}", now)
    yield ()

  override def triggerGovernance(projectId: ProjectId, triggerId: String): IO[PersistenceError, Unit] =
    for
      specs <- loadSpecs.map(_.filter(spec => spec.projectId == projectId && governanceTriggerMatches(spec, triggerId)))
      now   <- Clock.instant
      _     <- ZIO.foreachDiscard(specs)(spec => queueJob(spec, s"governance:${triggerId.trim}", now))
    yield ()

  private[control] def coordinator: UIO[Unit] =
    (for
      statuses <- list
      now      <- Clock.instant
      _        <- ZIO.foreachDiscard(statuses)(status => enqueueIfDue(status, now))
      _        <- ZIO.sleep(coordinatorInterval)
    yield ()).catchAll(err => ZIO.logWarning(s"Daemon coordinator loop failed: ${err.toString}"))

  private[control] def worker: UIO[Unit] =
    queue.take.flatMap(runJob)

  private def enqueueIfDue(status: DaemonAgentStatus, now: Instant): IO[PersistenceError, Unit] =
    if !status.enabled || status.runtime.lifecycle == DaemonLifecycle.Stopped then ZIO.unit
    else
      status.spec.trigger match
        case DaemonTriggerCondition.EventDriven(_) => ZIO.unit
        case trigger                               =>
          val interval = triggerInterval(trigger)
          val lastTick = status.runtime.completedAt.orElse(status.runtime.queuedAt)
          val pending  = status.runtime.health == DaemonHealth.Running
          val cooled   =
            lastTick.forall(last => Duration.fromMillis(java.time.Duration.between(last, now).toMillis) >= interval)
          if pending || !cooled then ZIO.unit else queueJob(status.spec, "scheduled", now)

  private def runJob(job: DaemonJob): UIO[Unit] =
    (for
      spec      <- requireSpec(job.specId)
      enabled   <- resolveEnabled(spec)
      current   <- runtimeState.get.map(_.getOrElse(spec.id, DaemonAgentRuntime()))
      available <- agentPoolManager.availableSlots(spec.agentName)
      _         <-
        if !enabled then
          markSkipped(spec.id, DaemonHealth.Disabled, Some("Disabled by settings or governance"))
        else if current.lifecycle == DaemonLifecycle.Stopped then
          markSkipped(spec.id, DaemonHealth.Idle, Some("Daemon is stopped"))
        else if available < 1 then
          markSkipped(spec.id, DaemonHealth.Paused, Some("No agent pool slots available"))
        else
          for
            handle    <- agentPoolManager
                           .acquireSlot(spec.agentName)
                           .mapError(error => PersistenceError.QueryFailed("daemon_acquire_pool", renderPoolError(error)))
            startedAt <- Clock.instant
            _         <- updateRuntime(spec.id) { runtime =>
                           runtime.copy(
                             health = DaemonHealth.Running,
                             startedAt = Some(startedAt),
                             lastError = None,
                           )
                         }
            _         <- publishActivity(
                           eventType = ActivityEventType.DaemonStarted,
                           spec = spec,
                           at = startedAt,
                           summary = s"${spec.name} started for project ${spec.projectId.value}",
                           payload = Some(s"""{"reason":"${job.reason}"}"""),
                         )
            outcome   <- execute(spec).ensuring(agentPoolManager.releaseSlot(handle))
            finished  <- Clock.instant
            _         <- updateRuntime(spec.id) { runtime =>
                           runtime.copy(
                             health = DaemonHealth.Healthy,
                             completedAt = Some(finished),
                             lastIssueCreatedAt = outcome.lastIssueCreatedAt.orElse(runtime.lastIssueCreatedAt),
                             issuesCreated = runtime.issuesCreated + outcome.issuesCreated,
                             lastError = None,
                             lastSummary = Some(outcome.summary),
                           )
                         }
            _         <- publishActivity(
                           eventType = ActivityEventType.DaemonCompleted,
                           spec = spec,
                           at = finished,
                           summary = outcome.summary,
                           payload = Some(s"""{"issuesCreated":${outcome.issuesCreated}}"""),
                         )
          yield ()
    yield ())
      .catchAll { err =>
        val errorMessage = err.toString
        Clock.instant.flatMap { failedAt =>
          updateRuntime(job.specId) { runtime =>
            runtime.copy(
              health =
                if runtime.lifecycle == DaemonLifecycle.Stopped then DaemonHealth.Idle else DaemonHealth.Degraded,
              completedAt = Some(failedAt),
              lastError = Some(errorMessage),
            )
          } *>
            requireSpec(job.specId)
              .flatMap(spec =>
                publishActivity(
                  eventType = ActivityEventType.DaemonFailed,
                  spec = spec,
                  at = failedAt,
                  summary = s"${spec.name} failed: $errorMessage",
                  payload = None,
                )
              )
              .catchAll(_ => ZIO.unit) *>
            ZIO.logWarning(s"Daemon execution failed for ${job.specId.value}: $errorMessage")
        }
      }

  private def execute(spec: DaemonAgentSpec): IO[PersistenceError, DaemonRunOutcome] =
    spec.daemonKey match
      case DaemonAgentSpec.TestGuardianKey => runTestGuardian(spec)
      case DaemonAgentSpec.DebtDetectorKey => runDebtDetector(spec)
      case _                               => runCustomDaemon(spec)

  private def runTestGuardian(spec: DaemonAgentSpec): IO[PersistenceError, DaemonRunOutcome] =
    for
      issues      <- issueRepository.list(IssueFilter(limit = Int.MaxValue))
      scopedIssues = issues.filter(issue => issue.workspaceId.exists(spec.workspaceIds.contains))
      findings    <- ZIO.foreach(scopedIssues)(issue => latestFailedCi(issue).map(issue -> _))
      candidates   = findings.collect { case (issue, Some(details)) if !isTerminal(issue.state) => issue -> details }
      created     <- createTestGuardianIssues(spec, candidates.take(spec.limits.maxIssuesPerRun))
      now         <- Clock.instant
    yield DaemonRunOutcome(
      issuesCreated = created,
      lastIssueCreatedAt = Option.when(created > 0)(now),
      summary = s"${spec.name} scanned ${scopedIssues.size} issues and created $created maintenance issue(s)",
    )

  private def runDebtDetector(spec: DaemonAgentSpec): IO[PersistenceError, DaemonRunOutcome] =
    for
      workspaces <- loadWorkspacesById(spec.workspaceIds)
      created    <- ZIO
                      .foreach(workspaces.take(spec.limits.maxIssuesPerRun))(workspace => detectDebt(spec, workspace))
                      .map(_.count(identity))
      now        <- Clock.instant
    yield DaemonRunOutcome(
      issuesCreated = created,
      lastIssueCreatedAt = Option.when(created > 0)(now),
      summary = s"${spec.name} scanned ${workspaces.size} workspace(s) and created $created maintenance issue(s)",
    )

  private def runCustomDaemon(spec: DaemonAgentSpec): IO[PersistenceError, DaemonRunOutcome] =
    for
      workspaces <- loadWorkspacesById(spec.workspaceIds)
      created    <- ZIO
                      .foreach(workspaces.take(spec.limits.maxIssuesPerRun)) { workspace =>
                        ensureMaintenanceIssue(
                          workspaceId = workspace.id,
                          fingerprint = daemonFingerprint(spec.daemonKey, workspace.id),
                          title = s"${spec.name}: execute daemon objective for ${workspace.name}",
                          description =
                            s"${spec.purpose}\n\nExecution prompt:\n${spec.prompt}".trim,
                          priority = "medium",
                          tags = List(s"daemon:${spec.daemonKey}", s"project:${spec.projectId.value}", "daemon:custom"),
                        )
                      }
                      .map(_.count(identity))
      now        <- Clock.instant
    yield DaemonRunOutcome(
      issuesCreated = created,
      lastIssueCreatedAt = Option.when(created > 0)(now),
      summary = s"${spec.name} evaluated ${workspaces.size} workspace(s) and created $created daemon issue(s)",
    )

  private def detectDebt(spec: DaemonAgentSpec, workspace: Workspace): IO[PersistenceError, Boolean] =
    for
      findings <- scanDebtMarkers(workspace.localPath)
      created  <- findings.headOption match
                    case Some(_) =>
                      ensureMaintenanceIssue(
                        workspaceId = workspace.id,
                        fingerprint = daemonFingerprint(spec.daemonKey, workspace.id),
                        title = s"Debt Detector: triage code debt in ${workspace.name}",
                        description = renderDebtDescription(workspace, findings),
                        priority = "medium",
                        tags = List(s"daemon:${spec.daemonKey}", s"project:${spec.projectId.value}"),
                      )
                    case None    => ZIO.succeed(false)
    yield created

  private def createTestGuardianIssues(
    spec: DaemonAgentSpec,
    candidates: List[(AgentIssue, String)],
  ): IO[PersistenceError, Int] =
    ZIO
      .foreach(candidates) {
        case (issue, details) =>
          ensureMaintenanceIssue(
            workspaceId = issue.workspaceId.getOrElse(""),
            fingerprint = daemonFingerprint(spec.daemonKey, issue.id.value),
            title = s"Test Guardian: investigate failing verification for #${issue.id.value}",
            description =
              s"The latest CI verification for issue #${issue.id.value} (${issue.title}) failed.\n\nLatest details:\n$details",
            priority = "high",
            tags = List(
              s"daemon:${spec.daemonKey}",
              s"project:${spec.projectId.value}",
              s"daemon-source:${issue.id.value}",
            ),
          )
      }
      .map(_.count(identity))

  private def ensureMaintenanceIssue(
    workspaceId: String,
    fingerprint: String,
    title: String,
    description: String,
    priority: String,
    tags: List[String],
  ): IO[PersistenceError, Boolean] =
    for
      issues  <- issueRepository.list(IssueFilter(limit = Int.MaxValue))
      existing = issues.exists(issue =>
                   issue.workspaceId.contains(workspaceId) &&
                   issue.tags.contains(fingerprint) &&
                   !isTerminal(issue.state)
                 )
      created <-
        if existing || workspaceId.trim.isEmpty then ZIO.succeed(false)
        else createMaintenanceIssue(workspaceId, title, description, priority, fingerprint, tags)
    yield created

  private def createMaintenanceIssue(
    workspaceId: String,
    title: String,
    description: String,
    priority: String,
    fingerprint: String,
    tags: List[String],
  ): IO[PersistenceError, Boolean] =
    for
      now    <- Clock.instant
      issueId = IssueId.generate
      _      <- issueRepository.append(
                  IssueEvent.Created(
                    issueId = issueId,
                    title = title,
                    description = description,
                    issueType = "maintenance",
                    priority = priority,
                    occurredAt = now,
                  )
                )
      _      <- issueRepository.append(IssueEvent.TagsUpdated(issueId, (tags :+ fingerprint).distinct, now))
      _      <- issueRepository.append(IssueEvent.MovedToTodo(issueId, movedAt = now, occurredAt = now))
      _      <- issueRepository.append(IssueEvent.WorkspaceLinked(issueId, workspaceId, now))
    yield true

  private def latestFailedCi(issue: AgentIssue): IO[PersistenceError, Option[String]] =
    issueRepository.history(issue.id).map { events =>
      events.collect { case event: IssueEvent.CiVerificationResult => event }.lastOption.flatMap { event =>
        Option.when(!event.passed)(event.details.trim).filter(_.nonEmpty)
      }
    }

  private def scanDebtMarkers(localPath: String): IO[PersistenceError, List[DebtFinding]] =
    ZIO
      .attemptBlocking {
        val root        = Paths.get(localPath)
        val pattern     = Pattern.compile("\\b(TODO|FIXME|HACK)\\b")
        val ignoredDirs = Set(".git", "target", "node_modules", "dist", ".idea", ".bloop", ".metals", ".scala-build")
        if !Files.exists(root) then Nil
        else
          val stream = Files.walk(root)
          try
            stream.iterator().asScala.toList
              .filter(path => Files.isRegularFile(path))
              .filterNot(path => path.iterator().asScala.exists(part => ignoredDirs.contains(part.toString)))
              .take(250)
              .flatMap { path =>
                Files.readAllLines(path, StandardCharsets.UTF_8).asScala.zipWithIndex.collect {
                  case (line, idx) if pattern.matcher(line).find() =>
                    DebtFinding(
                      path = root.relativize(path).toString,
                      line = idx + 1,
                      content = line.trim,
                    )
                }
              }
              .take(20)
          finally stream.close()
      }
      .mapError(err => PersistenceError.QueryFailed("daemon_scan_debt", err.getMessage))

  private def renderDebtDescription(workspace: Workspace, findings: List[DebtFinding]): String =
    val lines = findings.take(8).map(finding => s"- ${finding.path}:${finding.line} ${finding.content}")
    s"Debt markers were detected in workspace ${workspace.name}.\n\nSample findings:\n${lines.mkString("\n")}".trim

  private def markSkipped(id: DaemonAgentSpecId, health: DaemonHealth, message: Option[String]): UIO[Unit] =
    updateRuntime(id)(runtime => runtime.copy(health = health, lastError = message))

  private def setLifecycle(id: DaemonAgentSpecId, lifecycle: DaemonLifecycle): IO[PersistenceError, Unit] =
    requireSpec(id) *> updateRuntime(id) { runtime =>
      runtime.copy(
        lifecycle = lifecycle,
        health =
          if lifecycle == DaemonLifecycle.Stopped then DaemonHealth.Idle
          else if runtime.health == DaemonHealth.Disabled then DaemonHealth.Disabled
          else runtime.health,
      )
    }

  private def queueJob(spec: DaemonAgentSpec, reason: String, now: Instant): IO[PersistenceError, Unit] =
    runtimeState.modifyZIO { current =>
      val runtime = current.getOrElse(spec.id, DaemonAgentRuntime())
      if runtime.health == DaemonHealth.Running then ZIO.succeed(((), current))
      else
        queue
          .offer(DaemonJob(spec.id, reason, now))
          .map(_ => ((), current.updated(spec.id, runtime.copy(queuedAt = Some(now)))))
    }

  private def updateRuntime(id: DaemonAgentSpecId)(f: DaemonAgentRuntime => DaemonAgentRuntime): UIO[Unit] =
    runtimeState.update(current => current.updated(id, f(current.getOrElse(id, DaemonAgentRuntime()))))

  private def publishActivity(
    eventType: ActivityEventType,
    spec: DaemonAgentSpec,
    at: Instant,
    summary: String,
    payload: Option[String],
  ): UIO[Unit] =
    activityHub.publish(
      ActivityEvent(
        id = EventId.generate,
        eventType = eventType,
        source = "daemon-agent",
        agentName = Some(spec.agentName),
        summary = summary,
        payload = payload.orElse(Some(s"""{"daemonId":"${spec.id.value}","projectId":"${spec.projectId.value}"}""")),
        createdAt = at,
      )
    )

  private def requireSpec(id: DaemonAgentSpecId): IO[PersistenceError, DaemonAgentSpec] =
    loadSpecs.flatMap { specs =>
      ZIO.fromOption(specs.find(_.id == id)).orElseFail(PersistenceError.NotFound("daemon", id.value))
    }

  private def loadSpecs: IO[PersistenceError, List[DaemonAgentSpec]] =
    for
      projects     <- projectRepository.list
      workspaces   <- workspaceRepository.list
      builtInSpecs <- ZIO.foreach(projects)(project =>
                        deriveSpecs(project, workspaces.filter(_.projectId == project.id))
                      )
      customSpecs  <- ZIO.foreach(projects)(project => daemonRepository.listByProject(project.id))
    yield (builtInSpecs.flatten ++ customSpecs.flatten).distinctBy(_.id)

  private def deriveSpecs(project: Project, workspaces: List[Workspace]): IO[PersistenceError, List[DaemonAgentSpec]] =
    if workspaces.isEmpty then ZIO.succeed(Nil)
    else
      governanceRepository
        .getActiveByProject(project.id)
        .map(Some(_))
        .catchAll {
          case _: PersistenceError.NotFound => ZIO.succeed(None)
          case other                        => ZIO.fail(other)
        }
        .map { policyOpt =>
          val defaultAgent = project.settings.defaultAgent
            .orElse(workspaces.flatMap(_.defaultAgent).headOption)
            .getOrElse("code-agent")
          List(
            buildSpec(
              project = project,
              workspaces = workspaces,
              daemonKey = DaemonAgentSpec.TestGuardianKey,
              name = "Test Guardian",
              purpose = "Track failing verification results and open maintenance issues before quality drifts further.",
              prompt =
                "Review failing CI verification results and raise maintenance work when failures remain unresolved.",
              defaultTrigger = DaemonTriggerCondition.ScheduledWithEvent(30.minutes, DaemonAgentSpec.TestGuardianKey),
              defaultAgent = defaultAgent,
              defaultLimits = DaemonExecutionLimits(maxIssuesPerRun = 2, cooldown = 30.minutes, timeout = 10.minutes),
              policyOpt = policyOpt,
            ),
            buildSpec(
              project = project,
              workspaces = workspaces,
              daemonKey = DaemonAgentSpec.DebtDetectorKey,
              name = "Debt Detector",
              purpose =
                "Scan workspaces for TODO, FIXME, and HACK markers and convert unattended debt into actionable maintenance issues.",
              prompt = "Scan the workspace for code debt markers and file focused maintenance issues for triage.",
              defaultTrigger = DaemonTriggerCondition.Scheduled(2.hours),
              defaultAgent = defaultAgent,
              defaultLimits = DaemonExecutionLimits(maxIssuesPerRun = 1, cooldown = 2.hours, timeout = 10.minutes),
              policyOpt = policyOpt,
            ),
          )
        }

  private def buildSpec(
    project: Project,
    workspaces: List[Workspace],
    daemonKey: String,
    name: String,
    purpose: String,
    prompt: String,
    defaultTrigger: DaemonTriggerCondition,
    defaultAgent: String,
    defaultLimits: DaemonExecutionLimits,
    policyOpt: Option[GovernancePolicy],
  ): DaemonAgentSpec =
    val governanceTrigger = policyOpt.flatMap(_.daemonTriggers.find(trigger => normalize(trigger.id) == daemonKey))
    val trigger           = governanceTrigger.flatMap(toTriggerCondition).getOrElse(defaultTrigger)
    DaemonAgentSpec(
      id = DaemonAgentSpec.idFor(project.id, daemonKey),
      daemonKey = daemonKey,
      projectId = project.id,
      name = name,
      purpose = purpose,
      trigger = trigger,
      workspaceIds = workspaces.map(_.id).distinct,
      agentName = governanceTrigger.map(_.agentName.trim).filter(_.nonEmpty).getOrElse(defaultAgent),
      prompt = prompt,
      limits = defaultLimits,
      builtIn = true,
      governed = governanceTrigger.nonEmpty,
    )

  private def resolveEnabled(spec: DaemonAgentSpec): IO[PersistenceError, Boolean] =
    configRepository
      .getSetting(enabledKey(spec.projectId, spec.daemonKey))
      .mapError(err => PersistenceError.QueryFailed("daemon_enabled_setting", err.toString))
      .flatMap {
        case Some(row) => ZIO.succeed(parseBoolean(row.value).getOrElse(true))
        case None      =>
          governanceRepository
            .getActiveByProject(spec.projectId)
            .map(policy =>
              policy.daemonTriggers.find(trigger => normalize(trigger.id) == spec.daemonKey).fold(true)(_.enabled)
            )
            .catchAll {
              case _: PersistenceError.NotFound => ZIO.succeed(true)
              case other                        => ZIO.fail(other)
            }
      }

  private def loadWorkspacesById(workspaceIds: List[String]): IO[PersistenceError, List[Workspace]] =
    workspaceRepository.list.map(_.filter(ws => workspaceIds.contains(ws.id)))

  private def governanceTriggerMatches(spec: DaemonAgentSpec, triggerId: String): Boolean =
    spec.daemonKey == normalize(triggerId)

  private def triggerInterval(trigger: DaemonTriggerCondition): Duration =
    trigger match
      case DaemonTriggerCondition.Scheduled(interval)             => interval
      case DaemonTriggerCondition.Continuous(pollInterval)        => pollInterval
      case DaemonTriggerCondition.ScheduledWithEvent(interval, _) => interval
      case DaemonTriggerCondition.EventDriven(_)                  => Duration.Infinity

  private def daemonFingerprint(daemonKey: String, reference: String): String =
    s"daemon-key:${daemonKey}:${reference.trim}"

  private def enabledKey(projectId: ProjectId, daemonKey: String): String =
    s"daemons.${projectId.value}.${daemonKey}.enabled"

  private def isTerminal(state: IssueState): Boolean =
    state match
      case _: IssueState.Done | _: IssueState.Canceled | _: IssueState.Duplicated | _: IssueState.Archived |
           _: IssueState.Completed | _: IssueState.Failed | _: IssueState.Skipped => true
      case _ => false

  private def normalize(value: String): String =
    DaemonAgentSpec.normalizeKey(value)

  private def parseBoolean(value: String): Option[Boolean] =
    value.trim.toLowerCase(Locale.ROOT) match
      case "true" | "1" | "yes" => Some(true)
      case "false" | "0" | "no" => Some(false)
      case _                    => None

  private def toTriggerCondition(trigger: GovernanceDaemonTrigger): Option[DaemonTriggerCondition] =
    trigger.schedule
      .flatMap(parseDuration)
      .map(interval => DaemonTriggerCondition.ScheduledWithEvent(interval, normalize(trigger.id)))
      .orElse(Some(DaemonTriggerCondition.EventDriven(normalize(trigger.id))))

  private def parseDuration(raw: String): Option[Duration] =
    scala.util.Try(ScalaDuration(raw.trim)).toOption
      .filter(_.isFinite)
      .map(duration => Duration.fromScala(duration))

  private def renderPoolError(error: PoolError): String =
    error match
      case PoolError.AgentNotFound(agentName)             => s"Agent not found: $agentName"
      case PoolError.AgentPaused(agentName, reason)       => s"Agent paused: $agentName ($reason)"
      case PoolError.CostLimitExceeded(agentName, limit)  => s"Agent cost limit exceeded: $agentName ($limit)"
      case PoolError.InvalidCapacity(agentName, value)    => s"Invalid capacity for $agentName: $value"
      case PoolError.PersistenceFailure(operation, cause) => s"Pool persistence failure during $operation: $cause"

  final private case class DaemonRunOutcome(
    issuesCreated: Int,
    lastIssueCreatedAt: Option[Instant],
    summary: String,
  )

  final private case class DebtFinding(
    path: String,
    line: Int,
    content: String,
  )
