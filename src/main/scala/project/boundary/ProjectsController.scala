package project.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

import zio.*
import zio.http.*

import _root_.config.entity.AgentInfo
import agent.entity.AgentRegistry
import analysis.control.WorkspaceAnalysisScheduler
import analysis.entity.{ WorkspaceAnalysisState, WorkspaceAnalysisStatus }
import issues.entity.api.{ AgentIssueView, IssuePriority, IssueStatus }
import issues.entity.{ AgentIssue, IssueFilter, IssueRepository, IssueState }
import project.control.ProjectStorageService
import project.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.ProjectId
import shared.web.IssuesView
import workspace.entity.{ RunMode, Workspace, WorkspaceEvent, WorkspaceRepository }

trait ProjectsController:
  def routes: Routes[Any, Response]

object ProjectsController:

  def routes: ZIO[ProjectsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[ProjectsController](_.routes)

  val live
    : ZLayer[
      ProjectRepository & WorkspaceRepository & IssueRepository & AgentRegistry & WorkspaceAnalysisScheduler &
        ProjectStorageService,
      Nothing,
      ProjectsController,
    ] =
    ZLayer {
      for
        projectRepository   <- ZIO.service[ProjectRepository]
        workspaceRepository <- ZIO.service[WorkspaceRepository]
        issueRepository     <- ZIO.service[IssueRepository]
        agentRegistry       <- ZIO.service[AgentRegistry]
        analysisScheduler   <- ZIO.service[WorkspaceAnalysisScheduler]
        storageService      <- ZIO.service[ProjectStorageService]
      yield make(
        projectRepository,
        workspaceRepository,
        issueRepository,
        agentRegistry,
        analysisScheduler,
        storageService,
      )
    }

  def make(
    projectRepository: ProjectRepository,
    workspaceRepository: WorkspaceRepository,
    issueRepository: IssueRepository,
    agentRegistry: AgentRegistry,
    analysisScheduler: WorkspaceAnalysisScheduler,
    storageService: ProjectStorageService,
  ): ProjectsController =
    new ProjectsController:
      override val routes: Routes[Any, Response] = Routes(
        Method.GET / "projects"                                           -> handler { (_: Request) =>
          listPage(
            projectRepository,
            workspaceRepository,
            issueRepository,
          ).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.POST / "projects"                                          -> handler { (req: Request) =>
          createProject(req, projectRepository, storageService)
            .catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.GET / "projects" / string("id")                            -> handler { (id: String, req: Request) =>
          detailPage(
            id = id,
            req = req,
            projectRepository = projectRepository,
            workspaceRepository = workspaceRepository,
            issueRepository = issueRepository,
            agentRegistry = agentRegistry,
            analysisScheduler = analysisScheduler,
          ).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.POST / "projects" / string("id") / "settings"              -> handler { (id: String, req: Request) =>
          updateSettings(id, req, projectRepository)
            .catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.POST / "projects" / string("id") / "workspaces" / "create" -> handler {
          (id: String, req: Request) =>
            createWorkspace(id, req, workspaceRepository, analysisScheduler)
              .catchAll(ZIO.succeed)
        },
        Method.GET / "api" / "projects" / "filter-options"                -> handler { (req: Request) =>
          projectRepository.list
            .mapError(persistErr)
            .map(projects => htmlResponse(ProjectsView.filterOptionsFragment(projects, parseProjectFilter(req))))
            .catchAll(ZIO.succeed)
        },
      )

  private def listPage(
    projectRepository: ProjectRepository,
    workspaceRepository: WorkspaceRepository,
    issueRepository: IssueRepository,
  ): IO[PersistenceError, Response] =
    for
      projects   <- projectRepository.list
      workspaces <- workspaceRepository.list
      issues     <- issueRepository.list(IssueFilter(limit = Int.MaxValue))
      now        <- Clock.instant
      items       = projects.map(projectListItem(_, workspaces, issues, now))
    yield htmlResponse(ProjectsView.page(items))

  private def detailPage(
    id: String,
    req: Request,
    projectRepository: ProjectRepository,
    workspaceRepository: WorkspaceRepository,
    issueRepository: IssueRepository,
    agentRegistry: AgentRegistry,
    analysisScheduler: WorkspaceAnalysisScheduler,
  ): IO[PersistenceError, Response] =
    for
      projectOpt   <- projectRepository.get(ProjectId(id))
      project      <- ZIO
                        .fromOption(projectOpt)
                        .orElseFail(PersistenceError.NotFound("project", id))
      workspaces   <- workspaceRepository.list
      issues       <- issueRepository.list(IssueFilter(limit = Int.MaxValue))
      agents       <- agentRegistry.getAllAgents
      activeTab     = selectedTab(req)
      statusesByWs <- loadAnalysisStatuses(workspaces.filter(_.projectId == project.id).map(_.id), analysisScheduler)
      data          = buildDetailPageData(
                        project = project,
                        workspaces = workspaces,
                        issues = issues,
                        statusesByWorkspace = statusesByWs,
                        agents = agents,
                        activeTab = activeTab,
                      )
    yield htmlResponse(ProjectsView.detailPage(data))

  private def createProject(
    req: Request,
    projectRepository: ProjectRepository,
    storageService: ProjectStorageService,
  ): IO[PersistenceError, Response] =
    for
      form       <- parseForm(req)
      name       <- required(form, "name")
      description = optional(form, "description")
      now        <- Clock.instant
      projectId   = ProjectId(UUID.randomUUID().toString)
      _          <- projectRepository.append(
                      ProjectEvent.ProjectCreated(
                        projectId = projectId,
                        name = name,
                        description = description,
                        occurredAt = now,
                      )
                    )
      _          <- storageService.initProjectStorage(projectId)
    yield redirect(s"/projects/${projectId.value}")

  private def updateSettings(
    id: String,
    req: Request,
    projectRepository: ProjectRepository,
  ): IO[PersistenceError, Response] =
    for
      projectOpt <- projectRepository.get(ProjectId(id))
      project    <- ZIO.fromOption(projectOpt).orElseFail(PersistenceError.NotFound("project", id))
      form       <- parseForm(req)
      name       <- required(form, "name")
      settings    = ProjectSettings(
                      defaultAgent = optional(form, "default_agent"),
                      mergePolicy = MergePolicy(
                        requireCi = checkbox(form, "require_ci"),
                        ciCommand = optional(form, "ci_command"),
                      ),
                      analysisSchedule = optional(form, "analysis_schedule_minutes")
                        .flatMap(_.toLongOption)
                        .filter(_ > 0)
                        .map(minutes => Duration.fromMillis(minutes * 60000L)),
                      promptTemplateDefaults = parsePromptDefaults(optional(form, "prompt_template_defaults")),
                    )
      now        <- Clock.instant
      _          <- projectRepository.append(
                      ProjectEvent.ProjectUpdated(
                        projectId = ProjectId(id),
                        name = name,
                        description = optional(form, "description").orElse(project.description),
                        settings = settings,
                        occurredAt = now,
                      )
                    )
    yield redirect(s"/projects/$id?tab=settings")

  private def buildDetailPageData(
    project: Project,
    workspaces: List[workspace.entity.Workspace],
    issues: List[AgentIssue],
    statusesByWorkspace: Map[String, List[WorkspaceAnalysisStatus]],
    agents: List[AgentInfo],
    activeTab: String,
  ): ProjectDetailPageData =
    val assignedWorkspaces = workspaces.filter(_.projectId == project.id).sortBy(_.name.toLowerCase)
    val projectIssues      =
      issues.filter(issue => issue.workspaceId.exists(wsId => assignedWorkspaces.exists(_.id == wsId)))
    val boardIssues        = projectIssues.map(domainToView).filter(_.status != IssueStatus.Canceled)
    val workspaceRows      = assignedWorkspaces.map { workspace =>
      val statuses = statusesByWorkspace.getOrElse(workspace.id, Nil)
      val health   = workspaceHealth(workspace.enabled, statuses)
      ProjectWorkspaceRow(
        workspaceId = workspace.id,
        workspaceName = workspace.name,
        description = workspace.description,
        enabled = workspace.enabled,
        defaultAgent = workspace.defaultAgent,
        healthLabel = health._1,
        healthTone = health._2,
        coverage = coverageLabel(statuses),
        lastRunAt = latestAnalysisAt(statuses),
        localPath = workspace.localPath,
        defaultBranch = workspace.defaultBranch,
        cliTool = workspace.cliTool,
        runModeLabel = workspace.runMode match
          case RunMode.Host      => "Host"
          case d: RunMode.Docker => s"Docker (${d.image})"
          case c: RunMode.Cloud  => s"Cloud (${c.provider})",
      )
    }
    val analysisRows       = assignedWorkspaces.map { workspace =>
      val statuses = statusesByWorkspace.getOrElse(workspace.id, Nil)
      ProjectAnalysisRow(
        workspaceId = workspace.id,
        workspaceName = workspace.name,
        stateLabel = workspaceHealth(workspace.enabled, statuses)._1,
        coverage = coverageLabel(statuses),
        lastRunAt = latestAnalysisAt(statuses),
      )
    }
    val boardWorkspaces    = assignedWorkspaces.map(ws => ws.id -> ws.name)
    val boardFragmentHtml  =
      if boardIssues.isEmpty then None
      else Some(IssuesView.boardColumnsFragment(boardIssues, boardWorkspaces))
    ProjectDetailPageData(
      project = project,
      activeTab = activeTab,
      assignedWorkspaces = workspaceRows,
      boardFragmentHtml = boardFragmentHtml,
      analysisRows = analysisRows,
      availableAgents = agents.filter(_.health.isEnabled).sortBy(_.displayName.toLowerCase).map(agent =>
        agent.handle.trim match
          case "" => agent.name -> agent.displayName
          case h  => h          -> agent.displayName
      ),
    )

  private def loadAnalysisStatuses(
    workspaceIds: List[String],
    analysisScheduler: WorkspaceAnalysisScheduler,
  ): IO[PersistenceError, Map[String, List[WorkspaceAnalysisStatus]]] =
    ZIO.foreach(workspaceIds.distinct)(workspaceId =>
      analysisScheduler.statusForWorkspace(workspaceId).map(workspaceId -> _)
    ).map(_.toMap)

  private def parseProjectFilter(req: Request): ProjectFilter =
    val headerVal = req.headers.get("X-Project-Filter").map(_.trim).filter(_.nonEmpty)
    val cookieVal =
      req.cookies
        .find(_.name == "project-filter")
        .map(_.content.trim)
        .filter(_.nonEmpty)
    ProjectFilter.parse(headerVal.orElse(cookieVal))

  private def createWorkspace(
    projectId: String,
    req: Request,
    workspaceRepository: WorkspaceRepository,
    analysisScheduler: WorkspaceAnalysisScheduler,
  ): IO[Response, Response] =
    for
      body         <- req.body.asString.mapError(_ => Response.internalServerError("body read failed"))
      fields        = body
                        .split("&")
                        .collect {
                          case s if s.contains("=") =>
                            val idx = s.indexOf('=')
                            urlDecode(s.substring(0, idx)) -> urlDecode(s.substring(idx + 1))
                        }
                        .toMap
      name         <- ZIO
                        .fromOption(fields.get("name").map(_.trim).filter(_.nonEmpty))
                        .orElseFail(Response.badRequest("name is required"))
      localPath    <- ZIO
                        .fromOption(fields.get("localPath").map(_.trim).filter(_.nonEmpty))
                        .orElseFail(Response.badRequest("localPath is required"))
      cliTool       = fields.get("cliTool").map(_.trim).filter(_.nonEmpty).getOrElse("claude")
      defaultBranch = Workspace.normalizeDefaultBranch(
                        fields.getOrElse("defaultBranch", Workspace.DefaultBranch)
                      )
      description   = fields.get("description").map(_.trim).filter(_.nonEmpty)
      runModeType   = fields.getOrElse("runModeType", "host")
      runMode       = runModeType match
                        case "docker" =>
                          RunMode.Docker(
                            image = fields.getOrElse("dockerImage", ""),
                            mountWorktree = fields.get("dockerMount").contains("on"),
                            network = fields.get("dockerNetwork").map(_.trim).filter(_.nonEmpty),
                          )
                        case "cloud"  =>
                          RunMode.Cloud(
                            provider = fields.getOrElse("cloudProvider", ""),
                            image = fields.getOrElse("cloudImage", ""),
                            region = fields.get("cloudRegion").map(_.trim).filter(_.nonEmpty),
                            network = fields.get("cloudNetwork").map(_.trim).filter(_.nonEmpty),
                          )
                        case _        => RunMode.Host
      now          <- Clock.instant
      id            = UUID.randomUUID().toString
      _            <- workspaceRepository
                        .append(
                          WorkspaceEvent.Created(
                            workspaceId = id,
                            projectId = ProjectId(projectId),
                            name = name,
                            localPath = localPath,
                            defaultAgent = None,
                            description = description,
                            cliTool = cliTool,
                            runMode = runMode,
                            occurredAt = now,
                          )
                        )
                        .mapError(err => Response.internalServerError(err.toString))
      _            <- workspaceRepository
                        .append(
                          WorkspaceEvent.DefaultBranchChanged(
                            workspaceId = id,
                            defaultBranch = defaultBranch,
                            occurredAt = now,
                          )
                        )
                        .mapError(err => Response.internalServerError(err.toString))
      _            <- analysisScheduler.triggerForWorkspaceEvent(id).forkDaemon
    yield redirect(s"/projects/$projectId?tab=workspaces")

  private def projectListItem(
    project: Project,
    workspaces: List[workspace.entity.Workspace],
    issues: List[AgentIssue],
    now: Instant,
  ): ProjectListItem =
    val projectWorkspaces = workspaces.filter(_.projectId == project.id)
    val workspaceCount    = projectWorkspaces.size
    val activeIssues      =
      issues.filter(issue => issue.workspaceId.exists(wsId => projectWorkspaces.exists(_.id == wsId))).map(
        domainToView
      ).count(issue =>
        isActiveIssue(issue.status)
      )
    val latestWorkspaceAt = projectWorkspaces.map(_.updatedAt).maxOption
    val latestIssueAt     =
      issues.filter(issue => issue.workspaceId.exists(wsId => projectWorkspaces.exists(_.id == wsId))).map(
        issueActivityAt
      ).maxOption
    ProjectListItem(
      id = project.id.value,
      name = project.name,
      description = project.description,
      workspaceCount = workspaceCount,
      activeIssueCount = activeIssues,
      lastActivity = List(Some(project.updatedAt), latestWorkspaceAt, latestIssueAt).flatten.maxOption.getOrElse(now),
    )

  private def issueActivityAt(issue: AgentIssue): Instant =
    issue.state match
      case IssueState.Backlog(at)         => at
      case IssueState.Todo(at)            => at
      case IssueState.Open(at)            => at
      case IssueState.Assigned(_, at)     => at
      case IssueState.InProgress(_, at)   => at
      case IssueState.HumanReview(at)     => at
      case IssueState.Rework(at, _)       => at
      case IssueState.Merging(at)         => at
      case IssueState.Done(at, _)         => at
      case IssueState.Canceled(at, _)     => at
      case IssueState.Duplicated(at, _)   => at
      case IssueState.Archived(at)        => at
      case IssueState.Completed(_, at, _) => at
      case IssueState.Failed(_, at, _)    => at
      case IssueState.Skipped(at, _)      => at

  private def isActiveIssue(status: IssueStatus): Boolean =
    status match
      case IssueStatus.Done | IssueStatus.Canceled | IssueStatus.Duplicated | IssueStatus.Archived =>
        false
      case _                                                                                       =>
        true

  private def workspaceHealth(enabled: Boolean, statuses: List[WorkspaceAnalysisStatus]): (String, String) =
    if !enabled then "Disabled" -> "slate"
    else if statuses.exists(_.state == WorkspaceAnalysisState.Failed) then "Needs attention" -> "rose"
    else if statuses.exists(_.state == WorkspaceAnalysisState.Running) then "Analyzing" -> "cyan"
    else if statuses.exists(_.state == WorkspaceAnalysisState.Pending) then "Queued" -> "amber"
    else if statuses.nonEmpty && statuses.forall(_.state == WorkspaceAnalysisState.Completed) then
      "Healthy" -> "emerald"
    else "Idle" -> "slate"

  private def latestAnalysisAt(statuses: List[WorkspaceAnalysisStatus]): Option[Instant] =
    statuses.flatMap(status => status.completedAt.orElse(status.startedAt).orElse(status.queuedAt)).maxOption

  private def coverageLabel(statuses: List[WorkspaceAnalysisStatus]): String =
    val completed = statuses.count(_.state == WorkspaceAnalysisState.Completed)
    s"$completed/${WorkspaceAnalysisScheduler.trackedTypes.size} complete"

  private def selectedTab(req: Request): String =
    req.queryParam("tab")
      .map(_.trim.toLowerCase)
      .filter(Set("workspaces", "settings", "board", "analysis"))
      .getOrElse("workspaces")

  private def parsePromptDefaults(raw: Option[String]): Map[String, String] =
    raw.toList
      .flatMap(_.split("\n").toList)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { line =>
        line.split("=", 2).toList match
          case key :: value :: Nil =>
            Some(key.trim -> value.trim)
          case _                   =>
            None
      }
      .toMap

  private def parseForm(req: Request): IO[PersistenceError, Map[String, List[String]]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap {
            _.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .groupMap(_._1)(_._2)
      }
      .mapError(err => PersistenceError.QueryFailed("parse_project_form", err.getMessage))

  private def required(form: Map[String, List[String]], key: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(optional(form, key))
      .orElseFail(PersistenceError.QueryFailed("project_form", s"Missing required field: $key"))

  private def optional(form: Map[String, List[String]], key: String): Option[String] =
    form.get(key).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  private def checkbox(form: Map[String, List[String]], key: String): Boolean =
    form.get(key).flatMap(_.headOption).exists(value => value.trim.nonEmpty)

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def redirect(location: String): Response =
    Response(status = Status.SeeOther, headers = Headers(Header.Location(URL.decode(location).getOrElse(URL.root))))

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, id)          =>
        Response.text(s"$entity with id $id not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Database query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Project data serialization failed: $cause").status(Status.InternalServerError)

  private def domainToView(i: AgentIssue): AgentIssueView =
    val (status, assignedAgent, assignedAt, completedAt, errorMessage) = i.state match
      case IssueState.Backlog(_)            => (IssueStatus.Backlog, None, None, None, None)
      case IssueState.Todo(at)              => (IssueStatus.Todo, None, Some(at), None, None)
      case IssueState.Open(_)               => (IssueStatus.Backlog, None, None, None, None)
      case IssueState.Assigned(agent, at)   => (IssueStatus.Todo, Some(agent.value), Some(at), None, None)
      case IssueState.InProgress(agent, at) => (IssueStatus.InProgress, Some(agent.value), Some(at), None, None)
      case IssueState.HumanReview(at)       => (IssueStatus.HumanReview, None, None, Some(at), None)
      case IssueState.Rework(at, msg)       => (IssueStatus.Rework, None, None, Some(at), Some(msg))
      case IssueState.Merging(at)           => (IssueStatus.Merging, None, None, Some(at), None)
      case IssueState.Done(at, _)           => (IssueStatus.Done, None, None, Some(at), None)
      case IssueState.Canceled(at, msg)     => (IssueStatus.Canceled, None, None, Some(at), Some(msg))
      case IssueState.Duplicated(at, msg)   => (IssueStatus.Duplicated, None, None, Some(at), Some(msg))
      case IssueState.Archived(at)          => (IssueStatus.Archived, None, None, Some(at), None)
      case IssueState.Completed(_, at, _)   => (IssueStatus.Done, None, None, Some(at), None)
      case IssueState.Failed(_, at, msg)    => (IssueStatus.Rework, None, None, Some(at), Some(msg))
      case IssueState.Skipped(at, reason)   => (IssueStatus.Canceled, None, None, Some(at), Some(reason))
    val priority                                                       =
      IssuePriority.values.find(_.toString.equalsIgnoreCase(i.priority)).getOrElse(IssuePriority.Medium)
    val createdAt                                                      = i.state match
      case IssueState.Backlog(at) => at
      case IssueState.Open(at)    => at
      case _                      => Instant.EPOCH
    AgentIssueView(
      id = Some(i.id.value),
      runId = i.runId.map(_.value),
      conversationId = i.conversationId.map(_.value),
      title = i.title,
      description = i.description,
      issueType = i.issueType,
      tags = if i.tags.isEmpty then None else Some(i.tags.mkString(",")),
      requiredCapabilities =
        if i.requiredCapabilities.isEmpty then None else Some(i.requiredCapabilities.mkString(",")),
      preferredAgent = None,
      contextPath = Option(i.contextPath).filter(_.nonEmpty),
      sourceFolder = Option(i.sourceFolder).filter(_.nonEmpty),
      workspaceId = i.workspaceId,
      externalRef = i.externalRef,
      externalUrl = i.externalUrl,
      estimate = i.estimate,
      priority = priority,
      status = status,
      assignedAgent = assignedAgent,
      assignedAt = assignedAt,
      completedAt = completedAt,
      errorMessage = errorMessage,
      mergeConflictFiles = i.mergeConflictFiles,
      createdAt = createdAt,
      updatedAt = assignedAt.orElse(completedAt).getOrElse(createdAt),
    )
