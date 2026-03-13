package orchestration.control

import zio.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import agent.control.AgentMatching
import agent.entity.{ Agent, AgentRepository }
import db.ConfigRepository
import issues.entity.{ AgentIssue, IssueEvent, IssueRepository, IssueState }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, EventId, TaskRunId }
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.WorkspaceRepository

trait AutoDispatcher:
  def dispatchOnce: IO[PersistenceError, Int]

object AutoDispatcher:
  val enabledSettingKey: String            = "issues.autoDispatch.enabled"
  val intervalSecondsSettingKey: String    = "issues.autoDispatch.intervalSeconds"
  val defaultInterval: Duration            = 10.seconds
  val reworkPriorityBoostTags: Set[String] = Set("rework", "rework-priority-boost")

  def dispatchOnce: ZIO[AutoDispatcher, PersistenceError, Int] =
    ZIO.serviceWithZIO[AutoDispatcher](_.dispatchOnce)

  val live
    : ZLayer[
      ConfigRepository & IssueRepository & DependencyResolver & AgentRepository & WorkspaceRepository &
        WorkspaceRunService & ActivityHub,
      Nothing,
      AutoDispatcher,
    ] =
    ZLayer.scoped {
      for
        configRepository    <- ZIO.service[ConfigRepository]
        issueRepository     <- ZIO.service[IssueRepository]
        dependencyResolver  <- ZIO.service[DependencyResolver]
        agentRepository     <- ZIO.service[AgentRepository]
        workspaceRepository <- ZIO.service[WorkspaceRepository]
        workspaceRunService <- ZIO.service[WorkspaceRunService]
        activityHub         <- ZIO.service[ActivityHub]
        service              =
          AutoDispatcherLive(
            configRepository = configRepository,
            issueRepository = issueRepository,
            dependencyResolver = dependencyResolver,
            agentRepository = agentRepository,
            workspaceRepository = workspaceRepository,
            workspaceRunService = workspaceRunService,
            activityHub = activityHub,
          )
        _                   <- service.run.forever.forkScoped
      yield service
    }

final case class AutoDispatcherLive(
  configRepository: ConfigRepository,
  issueRepository: IssueRepository,
  dependencyResolver: DependencyResolver,
  agentRepository: AgentRepository,
  workspaceRepository: WorkspaceRepository,
  workspaceRunService: WorkspaceRunService,
  activityHub: ActivityHub,
) extends AutoDispatcher:

  override def dispatchOnce: IO[PersistenceError, Int] =
    for
      enabled <- isEnabled
      count   <- if enabled then dispatchCycle else ZIO.succeed(0)
    yield count

  private[orchestration] def run: UIO[Unit] =
    (for
      dispatched <- dispatchOnce
      _          <- ZIO.when(dispatched > 0)(ZIO.logInfo(s"Auto-dispatcher queued $dispatched issue(s)"))
    yield ()).catchAll(err => ZIO.logWarning(s"Auto-dispatcher cycle failed: $err")) *>
      pollInterval
        .catchAll(err =>
          ZIO.logWarning(
            s"Auto-dispatcher interval lookup failed, using default: $err"
          ).as(AutoDispatcher.defaultInterval)
        )
        .flatMap(duration => ZIO.sleep(duration))

  private def dispatchCycle: IO[PersistenceError, Int] =
    for
      readyIssues <- dependencyResolver.currentReadyToDispatch
      agents      <- agentRepository.list()
      activeMap   <- activeRunsByAgent
      sorted       = sortReadyIssues(readyIssues)
      count       <- dispatchIssues(sorted, agents, activeMap, dispatched = 0)
    yield count

  private def dispatchIssues(
    issues: List[AgentIssue],
    agents: List[Agent],
    activeRuns: Map[String, Int],
    dispatched: Int,
  ): IO[PersistenceError, Int] =
    issues match
      case Nil           => ZIO.succeed(dispatched)
      case issue :: tail =>
        dispatchIssue(issue, agents, activeRuns).flatMap {
          case Some(agentName) =>
            val key = agentName.trim.toLowerCase
            dispatchIssues(tail, agents, activeRuns.updated(key, activeRuns.getOrElse(key, 0) + 1), dispatched + 1)
          case None            =>
            dispatchIssues(tail, agents, activeRuns, dispatched)
        }

  private def dispatchIssue(
    issue: AgentIssue,
    agents: List[Agent],
    activeRuns: Map[String, Int],
  ): IO[PersistenceError, Option[String]] =
    issue.workspaceId match
      case None              =>
        ZIO.logDebug(s"Skipping auto-dispatch for issue ${issue.id.value}: no workspace linked").as(None)
      case Some(workspaceId) =>
        AgentMatching.rankAgents(agents, issue.requiredCapabilities, activeRuns).headOption match
          case None         =>
            ZIO.logDebug(s"Skipping auto-dispatch for issue ${issue.id.value}: no agent match available").as(None)
          case Some(result) =>
            val prompt = buildPrompt(issue)
            for
              run <- workspaceRunService
                       .assign(
                         workspaceId,
                         AssignRunRequest(
                           issueRef = s"#${issue.id.value}",
                           prompt = prompt,
                           agentName = result.agent.name,
                         ),
                       )
                       .mapError(err => PersistenceError.QueryFailed("auto_dispatch_assign", err.toString))
              _   <- markIssueStarted(issue, result.agent.name)
              _   <- publishDispatchActivity(issue, result.agent.name, run.id)
            yield Some(result.agent.name)

  private def buildPrompt(issue: AgentIssue): String =
    issue.promptTemplate
      .flatMap(template => Option(renderPromptTemplate(template, issue)).map(_.trim).filter(_.nonEmpty))
      .orElse(Option(issue.description).map(_.trim).filter(_.nonEmpty))
      .getOrElse(issue.title)

  private def renderPromptTemplate(template: String, issue: AgentIssue): String =
    template
      .replace("${title}", issue.title)
      .replace("${description}", issue.description)
      .replace("${acceptanceCriteria}", issue.acceptanceCriteria.getOrElse(""))
      .replace("${contextPath}", issue.contextPath)
      .replace("${sourceFolder}", issue.sourceFolder)

  private def markIssueStarted(issue: AgentIssue, agentName: String): IO[PersistenceError, Unit] =
    for
      now <- Clock.instant
      _   <- issueRepository.append(
               IssueEvent.Assigned(
                 issueId = issue.id,
                 agent = AgentId(agentName),
                 assignedAt = now,
                 occurredAt = now,
               )
             )
      _   <- issueRepository.append(
               IssueEvent.Started(
                 issueId = issue.id,
                 agent = AgentId(agentName),
                 startedAt = now,
                 occurredAt = now,
               )
             )
    yield ()

  private def publishDispatchActivity(issue: AgentIssue, agentName: String, runId: String): UIO[Unit] =
    Clock.instant.flatMap { now =>
      activityHub.publish(
        ActivityEvent(
          id = EventId.generate,
          eventType = ActivityEventType.AgentAssigned,
          source = "auto-dispatch",
          runId = Some(TaskRunId(runId)),
          agentName = Some(agentName),
          summary = s"Auto-dispatched issue #${issue.id.value} to ${agentName}",
          payload = Some(s"""{"issueId":"${issue.id.value}","workspaceId":"${issue.workspaceId.getOrElse("")}"}"""),
          createdAt = now,
        )
      )
    }

  private def activeRunsByAgent: IO[PersistenceError, Map[String, Int]] =
    for
      workspaces <- workspaceRepository.list
      runs       <- ZIO.foreach(workspaces)(ws => workspaceRepository.listRuns(ws.id)).map(_.flatten)
    yield AgentMatching.activeRunsByAgent(runs)

  private def sortReadyIssues(issues: List[AgentIssue]): List[AgentIssue] =
    issues.sortBy(issue => (priorityRank(issue), createdAt(issue)))

  private def priorityRank(issue: AgentIssue): Int =
    if hasReworkPriorityBoost(issue) then 0
    else
      issue.priority.trim.toLowerCase match
        case "critical" => 0
        case "high"     => 1
        case "medium"   => 2
        case "low"      => 3
        case _          => 4

  private def hasReworkPriorityBoost(issue: AgentIssue): Boolean =
    issue.tags.exists(tag => AutoDispatcher.reworkPriorityBoostTags.contains(tag.trim.toLowerCase))

  private def createdAt(issue: AgentIssue): java.time.Instant =
    issue.state match
      case IssueState.Todo(at)            => at
      case IssueState.Backlog(at)         => at
      case IssueState.Open(at)            => at
      case IssueState.Assigned(_, at)     => at
      case IssueState.InProgress(_, at)   => at
      case IssueState.HumanReview(at)     => at
      case IssueState.Rework(at, _)       => at
      case IssueState.Merging(at)         => at
      case IssueState.Done(at, _)         => at
      case IssueState.Canceled(at, _)     => at
      case IssueState.Duplicated(at, _)   => at
      case IssueState.Completed(_, at, _) => at
      case IssueState.Failed(_, at, _)    => at
      case IssueState.Skipped(at, _)      => at

  private def isEnabled: IO[PersistenceError, Boolean] =
    configRepository
      .getSetting(AutoDispatcher.enabledSettingKey)
      .mapError(err => PersistenceError.QueryFailed("auto_dispatch_enabled", err.toString))
      .map(_.exists(_.value.trim.equalsIgnoreCase("true")))

  private def pollInterval: IO[PersistenceError, Duration] =
    configRepository
      .getSetting(AutoDispatcher.intervalSecondsSettingKey)
      .mapError(err => PersistenceError.QueryFailed("auto_dispatch_interval", err.toString))
      .map { setting =>
        setting
          .flatMap(row => row.value.trim.toLongOption)
          .filter(_ > 0L)
          .map(_.seconds)
          .getOrElse(AutoDispatcher.defaultInterval)
      }
