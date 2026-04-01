package issues.boundary

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.time.Instant
import java.util.UUID

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import zio.*
import zio.http.*
import zio.json.*

import _root_.config.entity.ConfigRepository
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import agent.control.AgentMatching
import agent.entity.AgentRepository
import agent.entity.api.AgentMatchSuggestion
import analysis.entity.AnalysisRepository
import board.control.BoardOrchestrator
import board.entity.{ IssueEstimate as BoardIssueEstimate, IssuePriority as BoardIssuePriority, * }
import db.{ ChatRepository, TaskRepository }
import decision.control.DecisionInbox
import decision.entity.*
import issues.control.IssueAnalysisAttachment
import issues.entity.api.*
import issues.entity.{ AgentIssue as DomainIssue, * }
import orchestration.control.{ IssueAssignmentOrchestrator, IssueDispatchStatusService }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, BoardIssueId, EventId, IssueId, TaskRunId }
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.WorkspaceRepository
import project.control.ProjectStorageService

trait IssueController:
  def routes: Routes[Any, Response]

object IssueController:

  def routes: ZIO[IssueController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[IssueController](_.routes)

  val live
    : ZLayer[
      ChatRepository & TaskRepository & ConfigRepository & AgentRepository & IssueAssignmentOrchestrator &
        IssueRepository & WorkspaceRepository & WorkspaceRunService & ActivityHub & IssueDispatchStatusService &
        BoardOrchestrator & BoardRepository & DecisionInbox &
        AnalysisRepository & IssueWorkReportProjection & ProjectStorageService,
      Nothing,
      IssueController,
    ] =
    ZLayer.fromFunction(IssueControllerLive.apply)

final case class IssueControllerLive(
  chatRepository: ChatRepository,
  taskRepository: TaskRepository,
  configRepository: ConfigRepository,
  agentRepository: AgentRepository,
  issueAssignmentOrchestrator: IssueAssignmentOrchestrator,
  issueRepository: IssueRepository,
  workspaceRepository: WorkspaceRepository,
  workspaceRunService: WorkspaceRunService,
  activityHub: ActivityHub,
  issueDispatchStatusService: IssueDispatchStatusService,
  boardOrchestrator: BoardOrchestrator,
  boardRepository: BoardRepository,
  decisionInbox: DecisionInbox,
  analysisRepository: AnalysisRepository,
  issueWorkReportProjection: IssueWorkReportProjection,
  projectStorageService: ProjectStorageService,
) extends IssueController:

  final private case class TimedResult[+A](value: A, durationMs: Long)
  import IssueControllerSupport.*

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "issues"                                            -> handler { (req: Request) =>
      ZIO.succeed(redirectPermanent(withQuery("/board?mode=list", req)))
    },
    Method.GET / "board"                                             -> handler { (req: Request) =>
      boardPage(req)
    },
    Method.GET / "board" / "fragment"                                -> handler { (req: Request) =>
      boardFragment(req)
    },
    Method.GET / "issues" / "board"                                  -> handler { (req: Request) =>
      ZIO.succeed(redirectPermanent(withQuery("/board", req)))
    },
    Method.GET / "issues" / "board" / "fragment"                     -> handler { (req: Request) =>
      ZIO.succeed(redirectPermanent(withQuery("/board/fragment", req)))
    },
    Method.GET / "issues" / "new"                                    -> handler { (req: Request) =>
      val runId = req.queryParam("run_id").map(_.trim).filter(_.nonEmpty)
      ErrorHandlingMiddleware.fromPersistence {
        for
          workspaces <- workspaceRepository.list.mapError(mapIssueRepoError)
          templates  <- listIssueTemplates
        yield html(
          HtmlViews.issueCreateForm(runId, workspaces.map(ws => ws.id -> ws.name), templates)
        )
      }
    },
    Method.GET / "settings" / "issues-templates"                     -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          templates <- listIssueTemplates
        yield html(HtmlViews.settingsIssueTemplatesTab(templates))
      }
    },
    Method.POST / "issues"                                           -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form        <- parseForm(req)
          title       <- required(form, "title")
          content     <- required(form, "description")
          estimate    <- parseEstimate(optional(form, "estimate"))
          workspaceId  = parseWorkspaceSelection(form)
          tags         = parseTagList(form.get("tags"))
          requiredCaps = parseCapabilityList(form.get("requiredCapabilities"))
          _           <- workspaceId match
                           case Some(wsId) =>
                             createWorkspaceBoardIssue(
                               workspaceId = wsId,
                               title = title,
                               description = content,
                               priority = parseIssuePriority(optional(form, "priority").getOrElse("medium")),
                               tags = tags,
                               requiredCapabilities = requiredCaps,
                               acceptanceCriteria = optional(form, "acceptanceCriteria"),
                               estimate = estimate,
                             ).unit
                           case None       =>
                             for
                               now    <- Clock.instant
                               issueId = IssueId.generate
                               event   = IssueEvent.Created(
                                           issueId = issueId,
                                           title = title,
                                           description = content,
                                           issueType = form.get("issueType").map(_.trim).filter(_.nonEmpty).getOrElse("task"),
                                           priority = form.get("priority").getOrElse("medium"),
                                           occurredAt = now,
                                           requiredCapabilities = requiredCaps,
                                         )
                               _      <- issueRepository.append(event).mapError(mapIssueRepoError)
                               _      <-
                                 ZIO.when(tags.nonEmpty) {
                                   issueRepository.append(IssueEvent.TagsUpdated(issueId, tags, now)).mapError(mapIssueRepoError)
                                 }
                               _      <- persistStructuredFields(
                                           issueId = issueId,
                                           promptTemplate = optional(form, "promptTemplate"),
                                           acceptanceCriteria = optional(form, "acceptanceCriteria"),
                                           estimate = estimate,
                                           kaizenSkill = optional(form, "kaizenSkill"),
                                           proofOfWorkRequirements = parseProofOfWorkRequirements(
                                             form.get("proofOfWorkRequirements")
                                           ),
                                           now = now,
                                         )
                             yield ()
          redirect     = form.get("runId").map(id => s"/board?mode=list&run_id=$id").getOrElse("/board?mode=list")
        yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", redirect)))
      }
    },
    Method.POST / "issues" / "import"                                -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          imported <- importIssuesFromConfiguredFolder
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", s"/board?mode=list&imported=$imported")),
        )
      }
    },
    Method.GET / "issues" / string("id")                             -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          workspaceHint                                      <- ZIO.succeed(req.queryParam("workspace").map(_.trim).filter(_.nonEmpty))
          boardIssueOpt                                      <- resolveWorkspaceBoardIssue(id, workspaceHint)
          issueRuns                                          <- workspaceRepository.listRunsByIssueRef(s"#$id").mapError(mapIssueRepoError)
          workspaces                                         <- workspaceRepository.list.mapError(mapIssueRepoError)
          allAgents                                          <- agentRepository.list().mapError(mapIssueRepoError)
          availableAgents                                     = allAgents.filter(_.enabled).map(registryAgentToAgentInfo)
          viewAndMeta                                        <- boardIssueOpt match
                                                                  case Some((ws, boardIssue)) =>
                                                                    for
                                                                      analysisDocs <- loadWorkspaceAnalysisContext(ws.id).mapError(
                                                                                        mapIssueRepoError
                                                                                      )
                                                                      mergeHistory <- loadMergeHistory(IssueId(id))
                                                                      workReport   <- issueWorkReportProjection.get(IssueId(id))
                                                                    yield (boardToView(boardIssue, ws.id), analysisDocs, mergeHistory, workReport)
                                                                  case None                   =>
                                                                    for
                                                                      issue        <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
                                                                      analysisDocs <- loadIssueAnalysisContext(issue).mapError(mapIssueRepoError)
                                                                      mergeHistory <- loadMergeHistory(issue.id)
                                                                      workReport   <- issueWorkReportProjection.get(issue.id)
                                                                    yield (domainToView(issue), analysisDocs, mergeHistory, workReport)
          (issueView, analysisDocs, mergeHistory, workReport) = viewAndMeta
          decisions                                          <- decisionInbox
                                                                  .list(
                                                                    DecisionFilter(
                                                                      issueId = Some(IssueId(id)),
                                                                      limit = Int.MaxValue,
                                                                    )
                                                                  )
                                                                  .mapError(mapIssueRepoError)
        yield html(
          HtmlViews.issueDetail(
            issueView,
            issueRuns,
            availableAgents,
            analysisDocs,
            mergeHistory,
            workspaces.map(ws => ws.id -> ws.name),
            workReport,
            decisions,
          )
        )
      }
    },
    Method.GET / "api" / "issues" / string("id") / "dispatch-status" -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        issueDispatchStatusService
          .statusFor(IssueId(id))
          .mapError(mapIssueRepoError)
          .map(status => Response.json(status.toJson))
      }
    },
    Method.GET / "issues" / string("id") / "edit"                    -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          workspaceHint <- ZIO.succeed(req.queryParam("workspace").map(_.trim).filter(_.nonEmpty))
          boardIssueOpt <- resolveWorkspaceBoardIssue(id, workspaceHint)
          issue         <- boardIssueOpt match
                             case Some((ws, boardIssue)) => ZIO.succeed(boardToView(boardIssue, ws.id))
                             case None                   => issueRepository.get(IssueId(id)).mapError(mapIssueRepoError).map(domainToView)
          workspaces    <- workspaceRepository.list.mapError(mapIssueRepoError)
        yield html(HtmlViews.issueEditForm(issue, workspaces.map(ws => ws.id -> ws.name)))
      }
    },
    Method.POST / "issues" / string("id") / "edit"                   -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form          <- parseForm(req)
          workspaceHint  = parseWorkspaceSelection(form)
          boardIssueOpt <- resolveWorkspaceBoardIssue(id, workspaceHint)
          _             <- boardIssueOpt match
                             case Some((ws, _)) =>
                               updateWorkspaceBoardIssueFromForm(
                                 issueId = id,
                                 form = form,
                                 workspaceIdHint = Some(ws.id),
                               )
                             case None          =>
                               for
                                 title    <- required(form, "title")
                                 content  <- required(form, "description")
                                 estimate <- parseEstimate(optional(form, "estimate"))
                                 now      <- Clock.instant
                                 issueId   = IssueId(id)
                                 caps      = parseCapabilityList(form.get("requiredCapabilities"))
                                 event     = IssueEvent.MetadataUpdated(
                                               issueId = issueId,
                                               title = title,
                                               description = content,
                                               issueType = form.get("issueType").map(_.trim).filter(_.nonEmpty).getOrElse("task"),
                                               priority = form.get("priority").map(_.trim).filter(_.nonEmpty).getOrElse("medium"),
                                               requiredCapabilities = caps,
                                               contextPath = form.get("contextPath").map(_.trim).getOrElse(""),
                                               sourceFolder = form.get("sourceFolder").map(_.trim).getOrElse(""),
                                               occurredAt = now,
                                             )
                                 _        <- issueRepository.append(event).mapError(mapIssueRepoError)
                                 tags      = parseTagList(form.get("tags"))
                                 _        <- issueRepository
                                               .append(IssueEvent.TagsUpdated(issueId, tags, now))
                                               .mapError(mapIssueRepoError)
                                 _        <- persistStructuredFields(
                                               issueId = issueId,
                                               promptTemplate = optional(form, "promptTemplate"),
                                               acceptanceCriteria = optional(form, "acceptanceCriteria"),
                                               estimate = estimate,
                                               kaizenSkill = optional(form, "kaizenSkill"),
                                               proofOfWorkRequirements = parseProofOfWorkRequirements(
                                                 form.get("proofOfWorkRequirements")
                                               ),
                                               now = now,
                                             )
                                 _        <- parseWorkspaceSelection(form) match
                                               case Some(wsId) =>
                                                 for
                                                   _ <- ensureWorkspaceExists(wsId)
                                                   _ <- issueRepository
                                                          .append(IssueEvent.WorkspaceLinked(issueId, wsId, now))
                                                          .mapError(mapIssueRepoError)
                                                 yield ()
                                               case None       =>
                                                 issueRepository
                                                   .append(IssueEvent.WorkspaceUnlinked(issueId, now))
                                                   .mapError(mapIssueRepoError)
                               yield ()
        yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", s"/issues/$id")))
      }
    },
    Method.POST / "issues" / string("id") / "status"                 -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form         <- parseForm(req)
          rawStatus     = form.get("status").map(_.trim).filter(_.nonEmpty).getOrElse("open")
          issueId       = IssueId(id)
          issue        <- issueRepository.get(issueId).mapError(mapIssueRepoError)
          now          <- Clock.instant
          agentFallback = Option(issue.state).flatMap {
                            case IssueState.Assigned(a, _)     => Some(a.value)
                            case IssueState.InProgress(a, _)   => Some(a.value)
                            case IssueState.Completed(a, _, _) => Some(a.value)
                            case IssueState.Failed(a, _, _)    => Some(a.value)
                            case _                             => None
                          }.getOrElse("manual")
          status       <- ZIO
                            .fromOption(parseIssueStatusToken(rawStatus))
                            .orElseFail(PersistenceError.QueryFailed("status_parse", s"Unknown status: $rawStatus"))
          _            <- ensureTransitionAllowed(issue.state, status, issueId.value)
          events       <- statusToEvents(issue, IssueStatusUpdateRequest(status = status), agentFallback, now)
          _            <- ZIO.foreachDiscard(events)(issueRepository.append(_).mapError(mapIssueRepoError))
          _            <- syncDecisionInbox(issue, status, agentFallback)
          _            <- publishBoardStatusActivity(issue, status, agentFallback, now)
        yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", s"/issues/$id")))
      }
    },
    Method.POST / "issues" / string("id") / "approve"                -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form      <- parseForm(req)
          issueId    = IssueId(id)
          issue     <- issueRepository.get(issueId).mapError(mapIssueRepoError)
          now       <- Clock.instant
          approvedBy = form.get("approvedBy").map(_.trim).filter(_.nonEmpty).getOrElse("human")
          _         <- ensureHumanReviewApprovalAllowed(issue)
          autoMerge <- loadAutoMergePolicy
          events     = approvalEvents(issue, approvedBy, autoMerge, now)
          _         <- ZIO.foreachDiscard(events)(issueRepository.append(_).mapError(mapIssueRepoError))
          _         <- decisionInbox
                         .syncOpenIssueReviewDecision(
                           issue.id,
                           DecisionResolutionKind.Approved,
                           approvedBy,
                           s"Approved by $approvedBy",
                         )
                         .mapError(mapIssueRepoError)
          _         <- publishBoardStatusActivity(
                         issue,
                         if autoMerge then IssueStatus.Merging else IssueStatus.Done,
                         fallbackAgent = "human",
                         now = now,
                       )
        yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", s"/issues/$id")))
      }
    },
    Method.POST / "issues" / string("id") / "assign"                 -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form               <- parseForm(req)
          agentName          <- required(form, "agentName")
          issue              <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          selectedWorkspaceId = parseWorkspaceSelection(form)
          workspaceForRun     = issue.workspaceId.orElse(selectedWorkspaceId)
          _                  <- issueAssignmentOrchestrator.assignIssue(
                                  id,
                                  agentName,
                                  skipConversationBootstrap = workspaceForRun.isDefined,
                                )
          _                  <- workspaceForRun.fold[IO[PersistenceError, Unit]](ZIO.unit) { workspaceId =>
                                  assignWorkspaceRunAndMarkStarted(
                                    issue = issue,
                                    workspaceId = workspaceId,
                                    agentName = agentName,
                                  )
                                }
        yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", s"/issues/$id")))
      }
    },
    Method.POST / "api" / "issues"                                   -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body         <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          issueRequest <- ZIO
                            .fromEither(body.fromJson[AgentIssueCreateRequest])
                            .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          estimate     <- parseEstimate(issueRequest.estimate)
          tags          = parseTagList(issueRequest.tags)
          required      = issueRequest.requiredCapabilities.map(_.trim).filter(_.nonEmpty).distinct
          created      <- issueRequest.workspaceId.map(_.trim).filter(_.nonEmpty) match
                            case Some(workspaceId) =>
                              createWorkspaceBoardIssue(
                                workspaceId = workspaceId,
                                title = issueRequest.title,
                                description = issueRequest.description,
                                priority = issueRequest.priority,
                                tags = tags,
                                requiredCapabilities = required,
                                acceptanceCriteria = issueRequest.acceptanceCriteria,
                                estimate = estimate,
                                assignedAgent = issueRequest.preferredAgent,
                              )
                            case None              =>
                              for
                                now     <- Clock.instant
                                issueId  = IssueId.generate
                                event    = IssueEvent.Created(
                                             issueId = issueId,
                                             title = issueRequest.title,
                                             description = issueRequest.description,
                                             issueType = issueRequest.issueType,
                                             priority = issueRequest.priority.toString,
                                             occurredAt = now,
                                             requiredCapabilities = required,
                                           )
                                _       <- issueRepository.append(event).mapError(mapIssueRepoError)
                                _       <- ZIO.when(tags.nonEmpty) {
                                             issueRepository
                                               .append(IssueEvent.TagsUpdated(issueId, tags, now))
                                               .mapError(mapIssueRepoError)
                                           }
                                _       <- persistStructuredFields(
                                             issueId = issueId,
                                             promptTemplate = issueRequest.promptTemplate,
                                             acceptanceCriteria = issueRequest.acceptanceCriteria,
                                             estimate = estimate,
                                             kaizenSkill = issueRequest.kaizenSkill,
                                             proofOfWorkRequirements = sanitizeProofRequirements(
                                               issueRequest.proofOfWorkRequirements
                                             ),
                                             now = now,
                                           )
                                created <- issueRepository.get(issueId).mapError(mapIssueRepoError)
                              yield domainToView(created)
        yield Response.json(created.toJson)
      }
    },
    Method.POST / "issues" / "from-template" / string("templateId")  -> handler { (templateId: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body       <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          createReq  <- ZIO
                          .fromEither(
                            if body.trim.isEmpty then Right(CreateIssueFromTemplateRequest())
                            else body.fromJson[CreateIssueFromTemplateRequest]
                          )
                          .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          template   <- getTemplateById(templateId)
          variableMap = resolveTemplateVariables(template, normalizeVariableValues(createReq.variableValues))
          _          <- validateTemplateVariables(template, variableMap)
          title       = createReq.overrideTitle
                          .map(_.trim)
                          .filter(_.nonEmpty)
                          .getOrElse(applyTemplateVariables(template.titleTemplate, variableMap))
          description = createReq.overrideDescription
                          .map(_.trim)
                          .filter(_.nonEmpty)
                          .getOrElse(applyTemplateVariables(template.descriptionTemplate, variableMap))
          _          <- ZIO
                          .fail(PersistenceError.QueryFailed("template", "Template produced an empty title"))
                          .when(title.trim.isEmpty)
          _          <- ZIO
                          .fail(PersistenceError.QueryFailed("template", "Template produced an empty description"))
                          .when(description.trim.isEmpty)
          created    <- createReq.workspaceId.map(_.trim).filter(_.nonEmpty) match
                          case Some(workspaceId) =>
                            createWorkspaceBoardIssue(
                              workspaceId = workspaceId,
                              title = title,
                              description = description,
                              priority = template.priority,
                              tags = template.tags.distinct,
                              requiredCapabilities = Nil,
                              acceptanceCriteria = None,
                              estimate = None,
                              assignedAgent = createReq.preferredAgent,
                            )
                          case None              =>
                            for
                              now     <- Clock.instant
                              issueId  = IssueId.generate
                              event    = IssueEvent.Created(
                                           issueId = issueId,
                                           title = title,
                                           description = description,
                                           issueType = template.issueType,
                                           priority = template.priority.toString,
                                           occurredAt = now,
                                           requiredCapabilities = Nil,
                                         )
                              _       <- issueRepository.append(event).mapError(mapIssueRepoError)
                              _       <- ZIO.when(template.tags.nonEmpty) {
                                           issueRepository
                                             .append(IssueEvent.TagsUpdated(issueId, template.tags.distinct, now))
                                             .mapError(mapIssueRepoError)
                                         }
                              created <- issueRepository.get(issueId).mapError(mapIssueRepoError)
                            yield domainToView(created)
        yield Response.json(created.toJson)
      }
    },
    Method.GET / "api" / "issue-templates"                           -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        listIssueTemplates.map(templates => Response.json(templates.toJson))
      }
    },
    Method.POST / "api" / "issue-templates"                          -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body      <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          upsertReq <- ZIO
                         .fromEither(body.fromJson[IssueTemplateUpsertRequest])
                         .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          template  <- createCustomTemplate(upsertReq)
        yield Response.json(template.toJson).copy(status = Status.Created)
      }
    },
    Method.PUT / "api" / "issue-templates" / string("id")            -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body      <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          upsertReq <- ZIO
                         .fromEither(body.fromJson[IssueTemplateUpsertRequest])
                         .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          template  <- updateCustomTemplate(id, upsertReq)
        yield Response.json(template.toJson)
      }
    },
    Method.DELETE / "api" / "issue-templates" / string("id")         -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        deleteCustomTemplate(id).as(Response(status = Status.NoContent))
      }
    },
    Method.GET / "api" / "issues"                                    -> handler { (req: Request) =>
      val runIdStr = req.queryParam("run_id").map(_.trim).filter(_.nonEmpty)
      ErrorHandlingMiddleware.fromPersistence {
        loadApiIssues(runIdStr).map(issues => Response.json(issues.toJson))
      }
    },
    Method.GET / "api" / "pipelines"                                 -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        listPipelines.map(values => Response.json(values.toJson))
      }
    },
    Method.POST / "api" / "pipelines"                                -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body     <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          create   <- ZIO
                        .fromEither(body.fromJson[PipelineCreateRequest])
                        .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          pipeline <- createPipeline(create)
        yield Response.json(pipeline.toJson).copy(status = Status.Created)
      }
    },
    Method.POST / "api" / "issues" / "bulk" / "assign"               -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body        <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          bulkRequest <- ZIO
                           .fromEither(body.fromJson[BulkIssueAssignRequest])
                           .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          response    <- bulkAssignIssues(bulkRequest)
        yield Response.json(response.toJson)
      }
    },
    Method.POST / "api" / "issues" / "bulk" / "status"               -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body        <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          bulkRequest <- ZIO
                           .fromEither(body.fromJson[BulkIssueStatusRequest])
                           .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          response    <- bulkUpdateStatus(bulkRequest)
        yield Response.json(response.toJson)
      }
    },
    Method.POST / "api" / "issues" / "bulk" / "tags"                 -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body        <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          bulkRequest <- ZIO
                           .fromEither(body.fromJson[BulkIssueTagsRequest])
                           .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          response    <- bulkUpdateTags(bulkRequest)
        yield Response.json(response.toJson)
      }
    },
    Method.DELETE / "api" / "issues" / "bulk"                        -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body        <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          bulkRequest <- ZIO
                           .fromEither(body.fromJson[BulkIssueDeleteRequest])
                           .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          response    <- bulkDeleteIssues(bulkRequest)
        yield Response.json(response.toJson)
      }
    },
    Method.POST / "api" / "issues" / "import" / "folder" / "preview" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body    <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          request <- ZIO
                       .fromEither(body.fromJson[FolderImportRequest])
                       .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          items   <- previewIssuesFromFolder(request)
        yield Response.json(items.toJson)
      }
    },
    Method.POST / "api" / "issues" / "import" / "folder"             -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body    <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          request <- ZIO
                       .fromEither(body.fromJson[FolderImportRequest])
                       .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          result  <- importIssuesFromFolderDetailed(request)
        yield Response.json(result.toJson)
      }
    },
    Method.POST / "api" / "issues" / "import" / "github" / "preview" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body    <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          preview <- ZIO
                       .fromEither(body.fromJson[GitHubImportPreviewRequest])
                       .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          items   <- previewGitHubIssues(preview)
        yield Response.json(items.toJson)
      }
    },
    Method.POST / "api" / "issues" / "import" / "github"             -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body     <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          preview  <- ZIO
                        .fromEither(body.fromJson[GitHubImportPreviewRequest])
                        .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          imported <- importGitHubIssues(preview)
        yield Response.json(imported.toJson)
      }
    },
    Method.GET / "api" / "issues" / string("id")                     -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val workspaceHint = req.queryParam("workspace").map(_.trim).filter(_.nonEmpty)
        resolveWorkspaceBoardIssue(id, workspaceHint).flatMap {
          case Some((ws, boardIssue)) =>
            ZIO.succeed(Response.json(boardToView(boardIssue, ws.id).toJson))
          case None                   =>
            issueRepository
              .get(IssueId(id))
              .mapError(mapIssueRepoError)
              .map(issue => Response.json(domainToView(issue).toJson))
        }
      }
    },
    Method.PATCH / "api" / "issues" / string("id") / "assign"        -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body           <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          assignRequest  <- ZIO
                              .fromEither(body.fromJson[AssignIssueRequest])
                              .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          issue          <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          workspaceForRun = issue.workspaceId.orElse(assignRequest.workspaceId.map(_.trim).filter(_.nonEmpty))
          _              <- issueAssignmentOrchestrator.assignIssue(
                              id,
                              assignRequest.agentName,
                              skipConversationBootstrap = workspaceForRun.isDefined,
                            )
          _              <- workspaceForRun.fold[IO[PersistenceError, Unit]](ZIO.unit) { workspaceId =>
                              assignWorkspaceRunAndMarkStarted(
                                issue = issue,
                                workspaceId = workspaceId,
                                agentName = assignRequest.agentName,
                              )
                            }
          updated        <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
        yield Response.json(domainToView(updated).toJson)
      }
    },
    Method.POST / "api" / "issues" / string("id") / "auto-assign"    -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body          <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          assignRequest <- ZIO
                             .fromEither(
                               if body.trim.isEmpty then Right(AutoAssignIssueRequest())
                               else body.fromJson[AutoAssignIssueRequest]
                             )
                             .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          issue         <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          threshold      = assignRequest.thresholdPercent.getOrElse(60.0).max(0.0).min(100.0) / 100.0
          required       = issue.requiredCapabilities.map(_.trim).filter(_.nonEmpty).distinct
          ranked        <- rankedAgentSuggestions(required)
          candidate      = ranked.headOption
          response      <- candidate match
                             case Some(best) if best.score >= threshold =>
                               val selectedWorkspaceId = issue.workspaceId.orElse(
                                 assignRequest.workspaceId.map(_.trim).filter(_.nonEmpty)
                               )
                               for
                                 _ <- issueAssignmentOrchestrator.assignIssue(
                                        id,
                                        best.agentName,
                                        skipConversationBootstrap = selectedWorkspaceId.isDefined,
                                      )
                                 _ <- selectedWorkspaceId.fold[IO[PersistenceError, Unit]](ZIO.unit) { workspaceId =>
                                        assignWorkspaceRunAndMarkStarted(
                                          issue = issue,
                                          workspaceId = workspaceId,
                                          agentName = best.agentName,
                                        )
                                      }
                               yield AutoAssignIssueResponse(
                                 assigned = true,
                                 queued = false,
                                 agentName = Some(best.agentName),
                                 score = Some(best.score),
                                 reason = None,
                               )
                             case Some(best)                            =>
                               ZIO.succeed(
                                 AutoAssignIssueResponse(
                                   assigned = false,
                                   queued = true,
                                   agentName = None,
                                   score = Some(best.score),
                                   reason =
                                     Some(f"Best score ${best.score * 100}%.1f%% below threshold ${threshold * 100}%.1f%%"),
                                 )
                               )
                             case None                                  =>
                               ZIO.succeed(
                                 AutoAssignIssueResponse(
                                   assigned = false,
                                   queued = true,
                                   reason = Some("No available agents matched the required capabilities"),
                                 )
                               )
        yield Response.json(response.toJson)
      }
    },
    Method.POST / "board" / "auto-dispatch"                          -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form    <- parseForm(req)
          enabled  = form.get("enabled").exists(v => Option(v).getOrElse("").trim.equalsIgnoreCase("on"))
          returnTo = form.get("returnTo").map(_.trim).filter(v => v.nonEmpty && v.startsWith("/")).getOrElse("/board")
          _       <- configRepository.upsertSetting("issues.autoDispatch.enabled", enabled.toString)
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", returnTo)),
        )
      }
    },
    Method.POST / "api" / "issues" / string("id") / "run-pipeline"   -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body        <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          runRequest  <- ZIO
                           .fromEither(body.fromJson[RunPipelineRequest])
                           .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          issue       <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          pipeline    <- getPipelineById(runRequest.pipelineId)
          _           <- validatePipelineSteps(pipeline.steps)
          workspaceId <- ZIO
                           .fromOption(issue.workspaceId.orElse(runRequest.workspaceId.map(_.trim).filter(_.nonEmpty)))
                           .orElseFail(PersistenceError.QueryFailed("pipeline", "workspaceId is required"))
          _           <- ensureWorkspaceExists(workspaceId)
          executionId  = UUID.randomUUID().toString
          response    <- runRequest.mode match
                           case PipelineExecutionMode.Parallel   =>
                             executeParallelPipeline(
                               issueId = id,
                               issue = issue,
                               pipeline = pipeline,
                               workspaceId = workspaceId,
                               runRequest = runRequest,
                               executionId = executionId,
                             )
                           case PipelineExecutionMode.Sequential =>
                             executeSequentialPipeline(
                               issueId = id,
                               issue = issue,
                               pipeline = pipeline,
                               workspaceId = workspaceId,
                               runRequest = runRequest,
                               executionId = executionId,
                             )
        yield Response.json(response.toJson)
      }
    },
    Method.PUT / "api" / "issues" / string("id") / "workspace"       -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body          <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          updateRequest <- ZIO
                             .fromEither(body.fromJson[IssueWorkspaceUpdateRequest])
                             .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          issueId        = IssueId(id)
          _             <- issueRepository.get(issueId).mapError(mapIssueRepoError)
          now           <- Clock.instant
          _             <- updateRequest.workspaceId.map(_.trim).filter(_.nonEmpty) match
                             case Some(workspaceId) =>
                               ensureWorkspaceExists(workspaceId) *>
                                 issueRepository
                                   .append(
                                     IssueEvent.WorkspaceLinked(
                                       issueId = issueId,
                                       workspaceId = workspaceId,
                                       occurredAt = now,
                                     )
                                   )
                                   .mapError(mapIssueRepoError)
                             case None              =>
                               issueRepository
                                 .append(IssueEvent.WorkspaceUnlinked(issueId = issueId, occurredAt = now))
                                 .mapError(mapIssueRepoError)
          updated       <- issueRepository.get(issueId).mapError(mapIssueRepoError)
        yield Response.json(domainToView(updated).toJson)
      }
    },
    Method.PATCH / "api" / "issues" / string("id") / "status"        -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body          <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          updateRequest <- ZIO
                             .fromEither(body.fromJson[IssueStatusUpdateRequest])
                             .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          issueId        = IssueId(id)
          issue         <- issueRepository.get(issueId).mapError(mapIssueRepoError)
          now           <- Clock.instant
          fallbackAgent  = updateRequest.agentName
                             .map(_.trim)
                             .filter(_.nonEmpty)
                             .orElse(Option(issue.state).flatMap {
                               case IssueState.Assigned(agent, _)     => Some(agent.value)
                               case IssueState.InProgress(agent, _)   => Some(agent.value)
                               case IssueState.Completed(agent, _, _) => Some(agent.value)
                               case IssueState.Failed(agent, _, _)    => Some(agent.value)
                               case _                                 => None
                             })
                             .getOrElse("board")
          _             <- ensureTransitionAllowed(issue.state, updateRequest.status, issueId.value)
          events        <- statusToEvents(issue, updateRequest, fallbackAgent, now)
          _             <- ZIO.foreachDiscard(events)(issueRepository.append(_).mapError(mapIssueRepoError))
          _             <- maybeStartWorkspaceRunOnInProgress(
                             issue = issue,
                             requestedStatus = updateRequest.status,
                             requestedAgentName = updateRequest.agentName,
                             fallbackAgent = fallbackAgent,
                           )
          _             <- publishBoardStatusActivity(issue, updateRequest.status, fallbackAgent, now)
          updated       <- issueRepository.get(issueId).mapError(mapIssueRepoError)
        yield Response.json(domainToView(updated).toJson)
      }
    },
    Method.GET / "api" / "issues" / "unassigned" / string("runId")   -> handler { (runId: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val filter = IssueFilter(
          runId = Some(TaskRunId(runId)),
          states = IssueFilter.assignableTodoCandidates,
        )
        issueRepository.list(filter).mapError(mapIssueRepoError)
          .map(issues => Response.json(issues.map(domainToView).toJson))
      }
    },
    Method.DELETE / "api" / "issues" / string("id")                  -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        issueRepository.delete(IssueId(id)).mapError(mapIssueRepoError).as(Response(status = Status.NoContent))
      }
    },
  )

  private def boardPage(req: Request): UIO[Response] =
    val mode            = req.queryParam("mode").map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("board")
    val query           = req.queryParam("q").map(_.trim).filter(_.nonEmpty)
    val tagFilter       = req.queryParam("tag").map(_.trim).filter(_.nonEmpty)
    val workspaceFilter = req.queryParam("workspace").map(_.trim).filter(_.nonEmpty)
    val agentFilter     = req.queryParam("agent").map(_.trim).filter(_.nonEmpty)
    val priorityFilter  = req.queryParam("priority").map(_.trim.toLowerCase).filter(_.nonEmpty)
    val statusFilter    = req.queryParam("status").map(_.trim.toLowerCase).filter(_.nonEmpty)
    ErrorHandlingMiddleware.fromPersistence {
      for
        startedAt         <- Clock.nanoTime
        workspacesFiber   <- timed("board.workspaces")(workspaceRepository.list.mapError(mapIssueRepoError)).fork
        issuesFiber       <- timed("board.issues")(
                               loadBoardIssues(
                                 query,
                                 tagFilter,
                                 workspaceFilter,
                                 agentFilter,
                                 priorityFilter,
                                 statusFilter,
                               )
                             ).fork
        workReportsFiber  <- timedUio("board.workReports")(issueWorkReportProjection.getAll).fork
        autoDispatchFiber <- timed("board.autoDispatch") {
                               settingBoolean("issues.autoDispatch.enabled", default = false)
                             }.fork
        workspacesTimed   <- workspacesFiber.join
        issuesTimed       <- issuesFiber.join
        workReportsTimed  <- workReportsFiber.join
        autoDispatchTimed <- autoDispatchFiber.join
        dispatchTimed     <- timed("board.dispatchStatuses")(loadDispatchStatuses(issuesTimed.value))
        completedAt       <- Clock.nanoTime
        totalDurationMs    = nanosToMillis(completedAt - startedAt)
        workReports        = selectWorkReports(workReportsTimed.value, issuesTimed.value)
        rendered          <- mode match
                               case "list" =>
                                 ZIO.succeed(
                                   HtmlViews.issuesBoardList(
                                     issues = issuesTimed.value,
                                     statusFilter = statusFilter,
                                     query = query,
                                     tagFilter = tagFilter,
                                     workspaceFilter = workspaceFilter,
                                     agentFilter = agentFilter,
                                     priorityFilter = priorityFilter,
                                   )
                                 )
                               case _      =>
                                 ZIO.succeed(
                                   HtmlViews.issuesBoard(
                                     issues = issuesTimed.value,
                                     workspaces = workspacesTimed.value.map(ws => ws.id -> ws.name),
                                     workReports = workReports,
                                     workspaceFilter = workspaceFilter,
                                     agentFilter = agentFilter,
                                     priorityFilter = priorityFilter,
                                     tagFilter = tagFilter,
                                     query = query,
                                     statusFilter = statusFilter,
                                     dispatchStatuses = dispatchTimed.value,
                                     autoDispatchEnabled = autoDispatchTimed.value,
                                   )
                                 )
        _                 <- logBoardTiming(
                               route = "page",
                               totalDurationMs = totalDurationMs,
                               workspacesDurationMs = workspacesTimed.durationMs,
                               issuesDurationMs = issuesTimed.durationMs,
                               workReportsDurationMs = workReportsTimed.durationMs,
                               dispatchDurationMs = dispatchTimed.durationMs,
                               issueCount = issuesTimed.value.size,
                             )
      yield html(rendered).addHeaders(boardTimingHeaders(
        route = "page",
        totalDurationMs = totalDurationMs,
        workspacesDurationMs = workspacesTimed.durationMs,
        issuesDurationMs = issuesTimed.durationMs,
        workReportsDurationMs = workReportsTimed.durationMs,
        dispatchDurationMs = dispatchTimed.durationMs,
        issueCount = issuesTimed.value.size,
      ))
    }

  private def boardFragment(req: Request): UIO[Response] =
    val query           = req.queryParam("q").map(_.trim).filter(_.nonEmpty)
    val tagFilter       = req.queryParam("tag").map(_.trim).filter(_.nonEmpty)
    val workspaceFilter = req.queryParam("workspace").map(_.trim).filter(_.nonEmpty)
    val agentFilter     = req.queryParam("agent").map(_.trim).filter(_.nonEmpty)
    val priorityFilter  = req.queryParam("priority").map(_.trim.toLowerCase).filter(_.nonEmpty)
    val statusFilter    = req.queryParam("status").map(_.trim.toLowerCase).filter(_.nonEmpty)
    ErrorHandlingMiddleware.fromPersistence {
      val fragmentEffect =
        for
          startedAt        <- Clock.nanoTime
          workspacesFiber  <-
            timed("boardFragment.workspaces")(workspaceRepository.list.mapError(mapIssueRepoError)).fork
          issuesFiber      <- timed("boardFragment.issues")(
                                loadBoardIssues(
                                  query,
                                  tagFilter,
                                  workspaceFilter,
                                  agentFilter,
                                  priorityFilter,
                                  statusFilter,
                                )
                              ).fork
          workReportsFiber <- timedUio("boardFragment.workReports")(issueWorkReportProjection.getAll).fork
          workspacesTimed  <- workspacesFiber.join
          issuesTimed      <- issuesFiber.join
          workReportsTimed <- workReportsFiber.join
          dispatchTimed    <- timed("boardFragment.dispatchStatuses")(loadDispatchStatuses(issuesTimed.value))
          completedAt      <- Clock.nanoTime
          totalDurationMs   = nanosToMillis(completedAt - startedAt)
          workReports       = selectWorkReports(workReportsTimed.value, issuesTimed.value)
          _                <- logBoardTiming(
                                route = "fragment",
                                totalDurationMs = totalDurationMs,
                                workspacesDurationMs = workspacesTimed.durationMs,
                                issuesDurationMs = issuesTimed.durationMs,
                                workReportsDurationMs = workReportsTimed.durationMs,
                                dispatchDurationMs = dispatchTimed.durationMs,
                                issueCount = issuesTimed.value.size,
                              )
        yield html(
          HtmlViews.issuesBoardColumns(
            issues = issuesTimed.value,
            workspaces = workspacesTimed.value.map(ws => ws.id -> ws.name),
            workReports = workReports,
            dispatchStatuses = dispatchTimed.value,
          )
        ).addHeaders(boardTimingHeaders(
          route = "fragment",
          totalDurationMs = totalDurationMs,
          workspacesDurationMs = workspacesTimed.durationMs,
          issuesDurationMs = issuesTimed.durationMs,
          workReportsDurationMs = workReportsTimed.durationMs,
          dispatchDurationMs = dispatchTimed.durationMs,
          issueCount = issuesTimed.value.size,
        ))
      fragmentEffect
        .timeoutFail(PersistenceError.QueryFailed("boardFragment", "timed out after 10s"))(10.seconds)
    }

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def mapIssueRepoError(e: shared.errors.PersistenceError): PersistenceError =
    e match
      case shared.errors.PersistenceError.NotFound(entity, id)               =>
        PersistenceError.QueryFailed(s"$entity", s"Not found: $id")
      case shared.errors.PersistenceError.QueryFailed(op, cause)             =>
        PersistenceError.QueryFailed(op, cause)
      case shared.errors.PersistenceError.SerializationFailed(entity, cause) =>
        PersistenceError.QueryFailed(entity, cause)
      case shared.errors.PersistenceError.StoreUnavailable(msg)              =>
        PersistenceError.QueryFailed("store", msg)

  private def mapBoardError(error: BoardError): PersistenceError =
    error match
      case BoardError.BoardNotFound(value)            => PersistenceError.QueryFailed("board", s"Not found: $value")
      case BoardError.IssueNotFound(value)            => PersistenceError.QueryFailed("board_issue", s"Not found: $value")
      case BoardError.IssueAlreadyExists(value)       => PersistenceError.QueryFailed("board_issue_exists", value)
      case BoardError.InvalidColumn(value)            => PersistenceError.QueryFailed("board_column", value)
      case BoardError.ParseError(message)             => PersistenceError.QueryFailed("board_parse", message)
      case BoardError.WriteError(path, message)       => PersistenceError.QueryFailed("board_write", s"$path: $message")
      case BoardError.GitOperationFailed(op, message) => PersistenceError.QueryFailed("board_git", s"$op: $message")
      case BoardError.DependencyCycle(issueIds)       =>
        PersistenceError.QueryFailed("board_cycle", issueIds.mkString(","))
      case BoardError.ConcurrencyConflict(message)    => PersistenceError.QueryFailed("board_concurrency", message)

  private def domainToView(i: DomainIssue): AgentIssueView =
    domainIssueToView(i)

  private def boardToView(issue: BoardIssue, workspaceId: String): AgentIssueView =
    val frontmatter                                                                                  = issue.frontmatter
    val status: IssueStatus                                                                          = issue.column match
      case BoardColumn.Backlog    => IssueStatus.Backlog
      case BoardColumn.Todo       => IssueStatus.Todo
      case BoardColumn.InProgress => IssueStatus.InProgress
      case BoardColumn.Review     => IssueStatus.HumanReview
      case BoardColumn.Done       => IssueStatus.Done
      case BoardColumn.Archive    =>
        if frontmatter.failureReason.isDefined then IssueStatus.Canceled else IssueStatus.Archived
    val priority: IssuePriority                                                                      = frontmatter.priority match
      case BoardIssuePriority.Critical => IssuePriority.Critical
      case BoardIssuePriority.High     => IssuePriority.High
      case BoardIssuePriority.Medium   => IssuePriority.Medium
      case BoardIssuePriority.Low      => IssuePriority.Low
    val estimate: Option[String]                                                                     = frontmatter.estimate.map {
      case BoardIssueEstimate.XS => "XS"
      case BoardIssueEstimate.S  => "S"
      case BoardIssueEstimate.M  => "M"
      case BoardIssueEstimate.L  => "L"
      case BoardIssueEstimate.XL => "XL"
    }
    val (assignedAgent, assignedAt, errorMessage): (Option[String], Option[Instant], Option[String]) =
      frontmatter.transientState match
        case board.entity.TransientState.Assigned(agent, at) => (Some(agent), Some(at), frontmatter.failureReason)
        case board.entity.TransientState.Rework(reason, at)  => (frontmatter.assignedAgent, Some(at), Some(reason))
        case board.entity.TransientState.Merging(at)         => (frontmatter.assignedAgent, Some(at), frontmatter.failureReason)
        case board.entity.TransientState.None                => (frontmatter.assignedAgent, None, frontmatter.failureReason)

    AgentIssueView(
      id = Some(frontmatter.id.value),
      title = frontmatter.title,
      description = issue.body,
      issueType = "task",
      tags = Option.when(frontmatter.tags.nonEmpty)(frontmatter.tags.mkString(",")),
      requiredCapabilities =
        Option.when(frontmatter.requiredCapabilities.nonEmpty)(frontmatter.requiredCapabilities.mkString(",")),
      workspaceId = Some(workspaceId),
      acceptanceCriteria =
        Option.when(frontmatter.acceptanceCriteria.nonEmpty)(frontmatter.acceptanceCriteria.mkString("\n")),
      estimate = estimate,
      proofOfWorkRequirements = frontmatter.proofOfWork,
      priority = priority,
      status = status,
      assignedAgent = assignedAgent,
      assignedAt = assignedAt,
      completedAt = frontmatter.completedAt,
      errorMessage = errorMessage,
      createdAt = frontmatter.createdAt,
      updatedAt = assignedAt.orElse(frontmatter.completedAt).getOrElse(frontmatter.createdAt),
    )

  private def resolveWorkspace(workspaceId: String): IO[PersistenceError, workspace.entity.Workspace] =
    workspaceRepository
      .get(workspaceId)
      .mapError(mapIssueRepoError)
      .flatMap(opt =>
        ZIO.fromOption(opt).orElseFail(PersistenceError.QueryFailed("workspace", s"Not found: $workspaceId"))
      )

  private def resolveWorkspaceBoardIssue(
    issueId: String,
    workspaceHint: Option[String] = None,
  ): IO[PersistenceError, Option[(workspace.entity.Workspace, BoardIssue)]] =
    parseBoardIssueId(issueId).either.flatMap {
      case Left(_)        => ZIO.none
      case Right(boardId) =>
        workspaceRepository
          .list
          .mapError(mapIssueRepoError)
          .flatMap { workspaces =>
            val prioritized = workspaceHint match
              case Some(wsId) =>
                workspaces.sortBy(ws => if ws.id == wsId then 0 else 1)
              case None       => workspaces

            ZIO
              .foreach(prioritized) { ws =>
                resolveWorkspaceBoardIssueById(ws, boardId).map(_.map(ws -> _))
              }
              .map(_.collectFirst { case Some(found) => found })
          }
    }

  private def resolveWorkspaceBoardIssueById(
    ws: workspace.entity.Workspace,
    issueId: BoardIssueId,
  ): IO[PersistenceError, Option[BoardIssue]] =
    projectStorageService.projectRoot(ws.projectId).flatMap { root =>
      val boardPath = root.toString
      boardRepository
        .readIssue(boardPath, issueId)
        .map(Some(_))
        .catchAll {
          case _: BoardError.IssueNotFound =>
            ZIO
              .foreach(boardColumns)(column => boardRepository.listIssues(boardPath, column))
              .map(_.flatten.find(_.frontmatter.id == issueId))
              .catchAll {
                case _: BoardError.BoardNotFound => ZIO.none
                case other                       => ZIO.fail(mapBoardError(other))
              }
          case _: BoardError.BoardNotFound => ZIO.none
          case other                       => ZIO.fail(mapBoardError(other))
        }
    }

  private def createWorkspaceBoardIssue(
    workspaceId: String,
    title: String,
    description: String,
    priority: IssuePriority,
    tags: List[String],
    requiredCapabilities: List[String],
    acceptanceCriteria: Option[String],
    estimate: Option[String],
    assignedAgent: Option[String] = None,
  ): IO[PersistenceError, AgentIssueView] =
    for
      workspace <- resolveWorkspace(workspaceId)
      boardPath <- projectStorageService.projectRoot(workspace.projectId).map(_.toString)
      _         <- boardRepository.initBoard(boardPath).mapError(mapBoardError)
      now       <- Clock.instant
      boardId    = BoardIssueId(IssueId.generate.value.toLowerCase)
      boardPrio  = toBoardPriority(priority)
      boardEst  <- toBoardEstimate(estimate)
      issue     <- boardRepository
                     .createIssue(
                       boardPath,
                       BoardColumn.Backlog,
                       BoardIssue(
                         frontmatter = IssueFrontmatter(
                           id = boardId,
                           title = title.trim,
                           priority = boardPrio,
                           assignedAgent = assignedAgent.map(_.trim).filter(_.nonEmpty),
                           requiredCapabilities = requiredCapabilities,
                           blockedBy = Nil,
                           tags = tags,
                           acceptanceCriteria = parseAcceptanceCriteria(acceptanceCriteria),
                           estimate = boardEst,
                           proofOfWork = Nil,
                           transientState = board.entity.TransientState.None,
                           branchName = None,
                           failureReason = None,
                           completedAt = None,
                           createdAt = now,
                         ),
                         body = description.trim,
                         column = BoardColumn.Backlog,
                         directoryPath = "",
                       ),
                     )
                     .mapError(mapBoardError)
    yield boardToView(issue, workspace.id)

  private def updateWorkspaceBoardIssueFromForm(
    issueId: String,
    form: Map[String, String],
    workspaceIdHint: Option[String],
  ): IO[PersistenceError, Unit] =
    for
      title                 <- required(form, "title")
      description           <- required(form, "description")
      found                 <- resolveWorkspaceBoardIssue(issueId, workspaceIdHint.orElse(parseWorkspaceSelection(form)))
      (workspace, existing) <-
        ZIO
          .fromOption(found)
          .orElseFail(PersistenceError.QueryFailed("board_issue", s"Not found: $issueId"))
      caps                   = parseCapabilityList(form.get("requiredCapabilities"))
      tags                   = parseTagList(form.get("tags"))
      boardPrio              = toBoardPriority(parseIssuePriority(optional(form, "priority").getOrElse("medium")))
      estimate              <- parseEstimate(optional(form, "estimate"))
      boardEst              <- toBoardEstimate(estimate)
      updatedIssue           = existing.copy(
                                 frontmatter = existing.frontmatter.copy(
                                   title = title.trim,
                                   priority = boardPrio,
                                   requiredCapabilities = caps,
                                   tags = tags,
                                   acceptanceCriteria = parseAcceptanceCriteria(optional(form, "acceptanceCriteria")),
                                   estimate = boardEst,
                                 ),
                                 body = description.trim,
                               )
      // Use the actual directory name on disk (not the frontmatter UUID) so that
      // locateIssue can find the issue even when the directory was named after the
      // issue title rather than its ID.
      dirKey                 = Option(Paths.get(existing.directoryPath).getFileName)
                                 .fold(existing.frontmatter.id)(p => BoardIssueId(p.toString))
      boardPath             <- projectStorageService.projectRoot(workspace.projectId).map(_.toString)
      _                     <- boardRepository
                                 .deleteIssue(boardPath, dirKey)
                                 .mapError(mapBoardError)
      _                     <- boardRepository
                                 .createIssue(
                                   boardPath,
                                   existing.column,
                                   updatedIssue,
                                 )
                                 .unit
                                 .mapError(mapBoardError)
    yield ()

  private val boardColumns: List[BoardColumn] = List(
    BoardColumn.Backlog,
    BoardColumn.Todo,
    BoardColumn.InProgress,
    BoardColumn.Review,
    BoardColumn.Done,
    BoardColumn.Archive,
  )

  private def loadWorkspaceBoardIssues(workspaceFilter: Option[String] = None)
    : IO[PersistenceError, List[AgentIssueView]] =
    workspaceRepository
      .list
      .mapError(mapIssueRepoError)
      .map(_.filter(ws => workspaceFilter.forall(_.equalsIgnoreCase(ws.id))))
      .flatMap { workspaces =>
        ZIO.foreachPar(workspaces) { ws =>
          projectStorageService.projectRoot(ws.projectId).flatMap { root =>
            ZIO
              .foreachPar(boardColumns)(column => boardRepository.listIssues(root.toString, column))
              .withParallelism(boardColumns.size)
              .map(_.flatten.map(issue => boardToView(issue, ws.id)))
              .catchAll {
                case _: BoardError.BoardNotFound => ZIO.succeed(Nil)
                case other                       => ZIO.fail(mapBoardError(other))
              }
          }
        }.withParallelism(math.max(1, math.min(workspaces.size, 4))).map(_.flatten)
      }

  private def loadApiIssues(runIdStr: Option[String]): IO[PersistenceError, List[AgentIssueView]] =
    runIdStr match
      case Some(runId) =>
        issueRepository
          .list(IssueFilter(runId = Some(TaskRunId(runId))))
          .mapError(mapIssueRepoError)
          .map(_.map(domainToView))
      case None        =>
        for
          boardIssues <- loadWorkspaceBoardIssues()
          esIssues    <- issueRepository
                           .list(IssueFilter())
                           .mapError(mapIssueRepoError)
                           .map(_.filter(_.workspaceId.forall(_.trim.isEmpty)).map(domainToView))
        yield boardIssues ++ esIssues

  private def loadBoardIssues(
    query: Option[String],
    tagFilter: Option[String],
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
    statusFilter: Option[String],
  ): IO[PersistenceError, List[AgentIssueView]] =
    for
      pair                   <- loadWorkspaceBoardIssues(workspaceFilter).zipPar(
                                  issueRepository
                                    .list(IssueFilter())
                                    .mapError(mapIssueRepoError)
                                    .map(_.filter(_.workspaceId.forall(_.trim.isEmpty)).map(domainToView))
                                )
      (boardIssues, esIssues) = pair
      merged                  = boardIssues ++ esIssues
      filtered                = filterIssues(merged, query, tagFilter).filter(issue =>
                                  workspaceFilter.forall(_.equalsIgnoreCase(issue.workspaceId.getOrElse(""))) &&
                                  agentFilter.forall(agent =>
                                    issue.assignedAgent.exists(_.equalsIgnoreCase(agent)) || issue.preferredAgent.exists(
                                      _.equalsIgnoreCase(agent)
                                    )
                                  ) &&
                                  statusFilter.forall(status => statusMatches(issue.status, status)) &&
                                  priorityFilter.forall(p => issue.priority.toString.equalsIgnoreCase(p))
                                )
    yield filtered

  private def loadIssueAnalysisContext(
    issue: DomainIssue
  ): IO[shared.errors.PersistenceError, List[AnalysisContextDocView]] =
    ZIO.foreach(issue.analysisDocIds) { docId =>
      analysisRepository.get(docId).either.flatMap {
        case Right(doc) =>
          loadWorkspacePath(doc.workspaceId).map { workspacePath =>
            Some(
              AnalysisContextDocView(
                title = analysisTitle(doc.analysisType),
                content = doc.content,
                filePath = doc.filePath,
                vscodeUrl = workspacePath.map(buildVscodeUrl(_, doc.filePath)),
              )
            )
          }
        case Left(_)    =>
          ZIO.succeed(None)
      }
    }.map(_.flatten)

  private def loadWorkspaceAnalysisContext(
    workspaceId: String
  ): IO[shared.errors.PersistenceError, List[AnalysisContextDocView]] =
    for
      docs          <- analysisRepository.listByWorkspace(workspaceId)
      workspacePath <- loadWorkspacePath(workspaceId)
    yield docs
      .sortBy(_.updatedAt)
      .reverse
      .map(doc =>
        AnalysisContextDocView(
          title = analysisTitle(doc.analysisType),
          content = doc.content,
          filePath = doc.filePath,
          vscodeUrl = workspacePath.map(buildVscodeUrl(_, doc.filePath)),
        )
      )

  private def loadWorkspacePath(workspaceId: String): IO[shared.errors.PersistenceError, Option[String]] =
    workspaceRepository.get(workspaceId).map(_.flatMap(ws => Option(ws.localPath).map(_.trim).filter(_.nonEmpty)))

  private def loadAutoMergePolicy: IO[PersistenceError, Boolean] =
    configRepository
      .getSetting("mergePolicy.autoMerge")
      .mapError(err => PersistenceError.QueryFailed("config_get:mergePolicy.autoMerge", err.toString))
      .map(_.flatMap(row => Option(row.value).map(_.trim)).filter(_.nonEmpty))
      .map {
        case None        => true
        case Some(value) => parseBooleanConfig(value)
      }

  private def publishBoardStatusActivity(
    issue: DomainIssue,
    status: IssueStatus,
    fallbackAgent: String,
    now: Instant,
  ): UIO[Unit] =
    activityHub.publish(
      ActivityEvent(
        id = EventId.generate,
        eventType = ActivityEventType.RunStateChanged,
        source = "issues-board",
        runId = issue.runId.map(r => TaskRunId(r.value)),
        agentName = Some(fallbackAgent),
        summary = s"Issue #${issue.id.value} moved to ${status.toString}",
        payload =
          Some(
            s"""{"issueId":"${issue.id.value}","status":${status.toString.toJson}}"""
          ),
        createdAt = now,
      )
    )

  private def syncDecisionInbox(
    issue: DomainIssue,
    status: IssueStatus,
    actor: String,
  ): IO[PersistenceError, Unit] =
    status match
      case IssueStatus.HumanReview                =>
        decisionInbox.openIssueReviewDecision(issue).mapError(mapIssueRepoError).unit
      case IssueStatus.Rework                     =>
        decisionInbox
          .syncOpenIssueReviewDecision(
            issue.id,
            DecisionResolutionKind.ReworkRequested,
            actor,
            s"Rework requested for issue #${issue.id.value}",
          )
          .mapError(mapIssueRepoError)
          .unit
      case IssueStatus.Done | IssueStatus.Merging =>
        decisionInbox
          .syncOpenIssueReviewDecision(
            issue.id,
            DecisionResolutionKind.Approved,
            actor,
            s"Issue #${issue.id.value} approved",
          )
          .mapError(mapIssueRepoError)
          .unit
      case _                                      =>
        ZIO.unit

  private def statusToEvents(
    issue: DomainIssue,
    request: IssueStatusUpdateRequest,
    fallbackAgent: String,
    now: Instant,
  ): IO[PersistenceError, List[IssueEvent]] =
    val issueId                  = issue.id
    val primaryEvent: IssueEvent = request.status match
      case IssueStatus.Backlog     => IssueEvent.MovedToBacklog(issueId = issueId, movedAt = now, occurredAt = now)
      case IssueStatus.Todo        => IssueEvent.MovedToTodo(issueId = issueId, movedAt = now, occurredAt = now)
      case IssueStatus.InProgress  =>
        IssueEvent.Started(
          issueId = issueId,
          agent = AgentId(fallbackAgent),
          startedAt = now,
          occurredAt = now,
        )
      case IssueStatus.HumanReview => IssueEvent.MovedToHumanReview(issueId = issueId, movedAt = now, occurredAt = now)
      case IssueStatus.Rework      =>
        IssueEvent.MovedToRework(
          issueId = issueId,
          movedAt = now,
          reason = request.reason.map(_.trim).filter(_.nonEmpty).getOrElse("Needs rework"),
          occurredAt = now,
        )
      case IssueStatus.Merging     => IssueEvent.MovedToMerging(issueId = issueId, movedAt = now, occurredAt = now)
      case IssueStatus.Done        =>
        IssueEvent.MarkedDone(
          issueId = issueId,
          doneAt = now,
          result = request.resultData.map(_.trim).filter(_.nonEmpty).getOrElse("Marked done from board"),
          occurredAt = now,
        )
      case IssueStatus.Canceled    =>
        IssueEvent.Canceled(
          issueId = issueId,
          canceledAt = now,
          reason = request.reason.map(_.trim).filter(_.nonEmpty).getOrElse("Canceled from board"),
          occurredAt = now,
        )
      case IssueStatus.Duplicated  =>
        IssueEvent.Duplicated(
          issueId = issueId,
          duplicatedAt = now,
          reason = request.reason.map(_.trim).filter(_.nonEmpty).getOrElse("Marked duplicated from board"),
          occurredAt = now,
        )
      case IssueStatus.Archived    =>
        IssueEvent.Archived(
          issueId = issueId,
          archivedAt = now,
          occurredAt = now,
        )
    request.status match
      case IssueStatus.HumanReview =>
        IssueAnalysisAttachment
          .latestForHumanReview(issue, analysisRepository, now)
          .map(attached => primaryEvent :: attached.toList)
          .mapError(mapIssueRepoError)
      case _                       =>
        ZIO.succeed(List(primaryEvent))

  private def filterIssues(
    issues: List[AgentIssueView],
    query: Option[String],
    tag: Option[String],
  ): List[AgentIssueView] =
    val byQuery = query match
      case Some(term) =>
        val needles = term
          .split("[,\\s]+")
          .toList
          .map(_.trim.toLowerCase)
          .filter(_.nonEmpty)
        issues.filter { issue =>
          needles.exists { needle =>
            issue.id.exists(_.toLowerCase.contains(needle)) ||
            issue.title.toLowerCase.contains(needle) ||
            issue.description.toLowerCase.contains(needle) ||
            issue.issueType.toLowerCase.contains(needle)
          }
        }
      case None       => issues
    tag match
      case Some(value) =>
        val needle = value.toLowerCase
        byQuery.filter(_.tags.exists(_.toLowerCase.split(",").map(_.trim).contains(needle)))
      case None        => byQuery

  private val templateSettingPrefix  = "issue.template.custom."
  private val pipelineSettingPrefix  = "pipeline.custom."
  private val templatePattern: Regex =
    "\\{\\{\\s*([a-zA-Z0-9_-]+)\\s*\\}\\}".r

  private val builtInTemplates: List[IssueTemplate] = List(
    IssueTemplate(
      id = "bug-fix",
      name = "Bug Fix",
      description = "Patch a defect with root cause analysis and validation.",
      issueType = "bug",
      priority = IssuePriority.High,
      tags = List("bug", "fix"),
      titleTemplate = "Fix {{component}} failure in {{area}}",
      descriptionTemplate =
        """# Problem
          |{{problem}}
          |
          |# Root Cause
          |{{root_cause}}
          |
          |# Acceptance Criteria
          |- [ ] Reproduce the issue
          |- [ ] Implement fix in {{component}}
          |- [ ] Add regression coverage for {{area}}
          |""".stripMargin,
      variables = List(
        TemplateVariable("component", "Component", Some("Subsystem affected by the bug"), required = true),
        TemplateVariable("area", "Area", Some("Functional area where the bug happens"), required = true),
        TemplateVariable("problem", "Problem Summary", Some("What is broken"), required = true),
        TemplateVariable("root_cause", "Root Cause", Some("Known or suspected cause"), required = false),
      ),
      isBuiltin = true,
    ),
    IssueTemplate(
      id = "feature",
      name = "Feature",
      description = "Define a feature request with user value and deliverables.",
      issueType = "feature",
      priority = IssuePriority.Medium,
      tags = List("feature"),
      titleTemplate = "Implement {{feature_name}}",
      descriptionTemplate =
        """# Goal
          |{{goal}}
          |
          |# User Value
          |{{user_value}}
          |
          |# Scope
          |{{scope}}
          |
          |# Acceptance Criteria
          |- [ ] Feature available for {{target_user}}
          |- [ ] Documentation updated
          |""".stripMargin,
      variables = List(
        TemplateVariable("feature_name", "Feature Name", required = true),
        TemplateVariable("goal", "Goal", required = true),
        TemplateVariable("user_value", "User Value", required = true),
        TemplateVariable("scope", "Scope", required = true),
        TemplateVariable("target_user", "Target User", required = true),
      ),
      isBuiltin = true,
    ),
    IssueTemplate(
      id = "refactor",
      name = "Refactor",
      description = "Track structural improvements without behavior changes.",
      issueType = "refactor",
      priority = IssuePriority.Medium,
      tags = List("refactor", "tech-debt"),
      titleTemplate = "Refactor {{module}} for {{objective}}",
      descriptionTemplate =
        """# Objective
          |{{objective}}
          |
          |# Current Pain
          |{{pain}}
          |
          |# Refactor Plan
          |{{plan}}
          |
          |# Safety Checks
          |- [ ] No behavioral regressions
          |- [ ] Tests updated for {{module}}
          |""".stripMargin,
      variables = List(
        TemplateVariable("module", "Module", required = true),
        TemplateVariable("objective", "Objective", required = true),
        TemplateVariable("pain", "Current Pain", required = true),
        TemplateVariable("plan", "Refactor Plan", required = true),
      ),
      isBuiltin = true,
    ),
    IssueTemplate(
      id = "code-review",
      name = "Code Review",
      description = "Request a targeted review with explicit risk focus.",
      issueType = "review",
      priority = IssuePriority.Medium,
      tags = List("review"),
      titleTemplate = "Review {{scope}} changes",
      descriptionTemplate =
        """# Context
          |{{context}}
          |
          |# Review Scope
          |{{scope}}
          |
          |# Focus Areas
          |- Correctness
          |- Regressions
          |- Test coverage gaps
          |
          |# Notes
          |{{notes}}
          |""".stripMargin,
      variables = List(
        TemplateVariable("scope", "Scope", required = true),
        TemplateVariable("context", "Context", required = true),
        TemplateVariable("notes", "Notes", required = false),
      ),
      isBuiltin = true,
    ),
  )

  private def listIssueTemplates: IO[PersistenceError, List[IssueTemplate]] =
    for
      rows      <- configRepository.getSettingsByPrefix(templateSettingPrefix)
      customRaw <- ZIO.foreach(rows.sortBy(_.key)) { row =>
                     ZIO
                       .fromEither(row.value.fromJson[IssueTemplate])
                       .map { parsed =>
                         val id = row.key.stripPrefix(templateSettingPrefix)
                         parsed.copy(
                           id = id,
                           isBuiltin = false,
                           createdAt = Some(row.updatedAt),
                           updatedAt = Some(row.updatedAt),
                         )
                       }
                       .either
                       .flatMap {
                         case Right(template) => ZIO.succeed(Some(template))
                         case Left(error)     =>
                           ZIO.logWarning(
                             s"Skipping invalid issue template setting key=${row.key}: $error"
                           ) *> ZIO.succeed(None)
                       }
                   }
    yield builtInTemplates ++ customRaw.flatten

  private def getTemplateById(id: String): IO[PersistenceError, IssueTemplate] =
    listIssueTemplates.flatMap { templates =>
      ZIO
        .fromOption(templates.find(_.id == id))
        .orElseFail(PersistenceError.QueryFailed("issue_template", s"Template not found: $id"))
    }

  private def listPipelines: IO[PersistenceError, List[AgentPipeline]] =
    for
      rows      <- configRepository.getSettingsByPrefix(pipelineSettingPrefix)
      pipelines <- ZIO.foreach(rows.sortBy(_.key)) { row =>
                     ZIO
                       .fromEither(row.value.fromJson[AgentPipeline])
                       .map(_.copy(id = row.key.stripPrefix(pipelineSettingPrefix), updatedAt = row.updatedAt))
                       .either
                       .flatMap {
                         case Right(p) => ZIO.succeed(Some(p))
                         case Left(e)  =>
                           ZIO.logWarning(s"Skipping invalid pipeline setting key=${row.key}: $e") *>
                             ZIO.succeed(None)
                       }
                   }
    yield pipelines.flatten

  private def getPipelineById(id: String): IO[PersistenceError, AgentPipeline] =
    listPipelines.flatMap { values =>
      ZIO
        .fromOption(values.find(_.id == id))
        .orElseFail(PersistenceError.QueryFailed("pipeline", s"Pipeline not found: $id"))
    }

  private def createPipeline(request: PipelineCreateRequest): IO[PersistenceError, AgentPipeline] =
    for
      _        <- validatePipelineName(request.name)
      _        <- validatePipelineSteps(request.steps)
      now      <- Clock.instant
      cleanName = request.name.trim
      id        = s"${normalizeTemplateId(cleanName)}-${now.toEpochMilli}"
      pipeline  = AgentPipeline(
                    id = id,
                    name = cleanName,
                    steps = request.steps.map(step =>
                      step.copy(
                        agentId = step.agentId.trim,
                        promptOverride = step.promptOverride.map(_.trim).filter(_.nonEmpty),
                      )
                    ),
                    createdAt = now,
                    updatedAt = now,
                  )
      _        <- configRepository.upsertSetting(pipelineSettingPrefix + id, pipeline.toJson)
    yield pipeline

  private def validatePipelineName(name: String): IO[PersistenceError, Unit] =
    ZIO
      .fail(PersistenceError.QueryFailed("pipeline", "Pipeline name is required"))
      .when(name.trim.isEmpty)
      .unit

  private def validatePipelineSteps(steps: List[PipelineStep]): IO[PersistenceError, Unit] =
    for
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("pipeline", "Pipeline must contain at least one step"))
             .when(steps.isEmpty)
      _ <- ZIO.foreachDiscard(steps.zipWithIndex) {
             case (step, idx) =>
               ZIO
                 .fail(PersistenceError.QueryFailed("pipeline", s"Pipeline step ${idx + 1} requires an agentId"))
                 .when(step.agentId.trim.isEmpty)
           }
    yield ()

  private def createCustomTemplate(request: IssueTemplateUpsertRequest): IO[PersistenceError, IssueTemplate] =
    for
      now       <- Clock.instant
      templateId = request.id.map(normalizeTemplateId).filter(_.nonEmpty).getOrElse(s"custom-${now.toEpochMilli}")
      _         <- ensureCustomTemplateIdAllowed(templateId)
      template  <- buildTemplate(templateId, request, now)
      _         <- configRepository.upsertSetting(templateSettingPrefix + templateId, template.toJson)
    yield template

  private def updateCustomTemplate(id: String, request: IssueTemplateUpsertRequest)
    : IO[PersistenceError, IssueTemplate] =
    for
      normalized <- ZIO.succeed(normalizeTemplateId(id))
      _          <- ensureCustomTemplateIdAllowed(normalized)
      existing   <- configRepository.getSetting(templateSettingPrefix + normalized)
      _          <- ZIO
                      .fromOption(existing)
                      .orElseFail(PersistenceError.QueryFailed("issue_template", s"Template not found: $normalized"))
      now        <- Clock.instant
      template   <- buildTemplate(normalized, request.copy(id = Some(normalized)), now)
      _          <- configRepository.upsertSetting(templateSettingPrefix + normalized, template.toJson)
    yield template

  private def deleteCustomTemplate(id: String): IO[PersistenceError, Unit] =
    val normalized = normalizeTemplateId(id)
    if builtInTemplates.exists(_.id == normalized) then
      ZIO.fail(PersistenceError.QueryFailed("issue_template", s"Built-in template cannot be deleted: $normalized"))
    else
      configRepository.deleteSetting(templateSettingPrefix + normalized)

  private def buildTemplate(
    id: String,
    request: IssueTemplateUpsertRequest,
    timestamp: Instant,
  ): IO[PersistenceError, IssueTemplate] =
    for
      normalizedTags <- ZIO.succeed(request.tags.map(_.trim).filter(_.nonEmpty))
      normalizedVars <- ZIO.succeed(request.variables.map(v =>
                          v.copy(
                            name = v.name.trim,
                            label = v.label.trim,
                            description = v.description.map(_.trim).filter(_.nonEmpty),
                            defaultValue = v.defaultValue.map(_.trim).filter(_.nonEmpty),
                          )
                        ))
      _              <- validateTemplatePayload(request, normalizedVars)
    yield IssueTemplate(
      id = id,
      name = request.name.trim,
      description = request.description.trim,
      issueType = request.issueType.trim,
      priority = request.priority,
      tags = normalizedTags,
      titleTemplate = request.titleTemplate,
      descriptionTemplate = request.descriptionTemplate,
      variables = normalizedVars,
      isBuiltin = false,
      createdAt = Some(timestamp),
      updatedAt = Some(timestamp),
    )

  private def validateTemplatePayload(
    request: IssueTemplateUpsertRequest,
    variables: List[TemplateVariable],
  ): IO[PersistenceError, Unit] =
    for
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template name is required"))
             .when(request.name.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template description is required"))
             .when(request.description.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template issueType is required"))
             .when(request.issueType.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template titleTemplate is required"))
             .when(request.titleTemplate.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template descriptionTemplate is required"))
             .when(request.descriptionTemplate.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template variable names must be unique"))
             .when(variables.map(_.name).distinct.size != variables.size)
      _ <- ZIO.foreachDiscard(variables) { variable =>
             ZIO
               .fail(PersistenceError.QueryFailed("issue_template", "Template variable name cannot be empty"))
               .when(variable.name.trim.isEmpty) *>
               ZIO
                 .fail(PersistenceError.QueryFailed("issue_template", "Template variable label cannot be empty"))
                 .when(variable.label.trim.isEmpty)
           }
    yield ()

  private def normalizeTemplateId(id: String): String =
    id.trim.toLowerCase.replaceAll("[^a-z0-9_-]+", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-")

  private def ensureCustomTemplateIdAllowed(id: String): IO[PersistenceError, Unit] =
    for
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template id cannot be empty"))
             .when(id.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", s"Template id reserved by built-in template: $id"))
             .when(builtInTemplates.exists(_.id == id))
    yield ()

  private def normalizeVariableValues(values: Map[String, String]): Map[String, String] =
    values.collect { case (k, v) if k.trim.nonEmpty => k.trim -> v }

  private def resolveTemplateVariables(template: IssueTemplate, provided: Map[String, String]): Map[String, String] =
    template.variables.foldLeft(provided) { (acc, variable) =>
      val current = acc.get(variable.name).map(_.trim).filter(_.nonEmpty)
      val merged  = current.orElse(variable.defaultValue.map(_.trim).filter(_.nonEmpty))
      merged match
        case Some(value) => acc.updated(variable.name, value)
        case None        => acc
    }

  private def validateTemplateVariables(
    template: IssueTemplate,
    values: Map[String, String],
  ): IO[PersistenceError, Unit] =
    ZIO.foreachDiscard(template.variables) { variable =>
      val value = values.get(variable.name).map(_.trim).filter(_.nonEmpty)
      ZIO
        .fail(
          PersistenceError.QueryFailed(
            "issue_template",
            s"Missing required template variable: ${variable.name}",
          )
        )
        .when(variable.required && value.isEmpty)
    }

  private def applyTemplateVariables(source: String, values: Map[String, String]): String =
    templatePattern.replaceAllIn(source, m => values.getOrElse(m.group(1), ""))

  private def bulkAssignIssues(request: BulkIssueAssignRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      issueIds <- validateIssueIds(request.issueIds)
      _        <- ensureWorkspaceExists(request.workspaceId)
      results  <- ZIO.foreach(issueIds) { issueId =>
                    (for
                      issue <- issueRepository.get(IssueId(issueId)).mapError(mapIssueRepoError)
                      now   <- Clock.instant
                      _     <- issueRepository
                                 .append(
                                   IssueEvent.WorkspaceLinked(
                                     issueId = IssueId(issueId),
                                     workspaceId = request.workspaceId,
                                     occurredAt = now,
                                   )
                                 )
                                 .mapError(mapIssueRepoError)
                      _     <- issueAssignmentOrchestrator.assignIssue(
                                 issueId,
                                 request.agentId,
                                 skipConversationBootstrap = true,
                               )
                      _     <- assignWorkspaceRunAndMarkStarted(
                                 issue = issue,
                                 workspaceId = request.workspaceId,
                                 agentName = request.agentId,
                               )
                    yield ()).either
                  }
    yield toBulkResponse(issueIds.size, results)

  private def bulkUpdateStatus(request: BulkIssueStatusRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      issueIds <- validateIssueIds(request.issueIds)
      results  <- ZIO.foreach(issueIds) { issueId =>
                    (for
                      issue        <- issueRepository.get(IssueId(issueId)).mapError(mapIssueRepoError)
                      now          <- Clock.instant
                      fallbackAgent = request.agentName
                                        .map(_.trim)
                                        .filter(_.nonEmpty)
                                        .orElse(assignedAgentFromState(issue.state))
                                        .getOrElse("bulk")
                      _            <- ensureTransitionAllowed(issue.state, request.status, issueId)
                      events       <- statusToEvents(
                                        issue,
                                        IssueStatusUpdateRequest(
                                          status = request.status,
                                          agentName = request.agentName,
                                          reason = request.reason,
                                          resultData = request.resultData,
                                        ),
                                        fallbackAgent,
                                        now,
                                      )
                      _            <- ZIO.foreachDiscard(events)(issueRepository.append(_).mapError(mapIssueRepoError))
                    yield ()).either
                  }
    yield toBulkResponse(issueIds.size, results)

  private def bulkUpdateTags(request: BulkIssueTagsRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      issueIds    <- validateIssueIds(request.issueIds)
      tagsToAdd    = request.addTags.map(_.trim).filter(_.nonEmpty)
      tagsToRemove = request.removeTags.map(_.trim).filter(_.nonEmpty).toSet
      results     <- ZIO.foreach(issueIds) { issueId =>
                       (for
                         issue   <- issueRepository.get(IssueId(issueId)).mapError(mapIssueRepoError)
                         now     <- Clock.instant
                         existing = issue.tags.map(_.trim).filter(_.nonEmpty)
                         merged   = (existing.filterNot(t => tagsToRemove.contains(t)) ++ tagsToAdd).distinct
                         _       <- issueRepository
                                      .append(IssueEvent.TagsUpdated(IssueId(issueId), merged, now))
                                      .mapError(mapIssueRepoError)
                       yield ()).either
                     }
    yield toBulkResponse(issueIds.size, results)

  private def bulkDeleteIssues(request: BulkIssueDeleteRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      issueIds <- validateIssueIds(request.issueIds)
      results  <-
        ZIO.foreach(issueIds)(issueId => issueRepository.delete(IssueId(issueId)).mapError(mapIssueRepoError).either)
    yield toBulkResponse(issueIds.size, results)

  private def toBulkResponse(
    requested: Int,
    results: List[Either[PersistenceError, Unit]],
  ): BulkIssueOperationResponse =
    val errors = results.collect { case Left(err) => err.toString }
    BulkIssueOperationResponse(
      requested = requested,
      succeeded = results.count(_.isRight),
      failed = errors.size,
      errors = errors,
    )

  private def validateIssueIds(issueIds: List[String]): IO[PersistenceError, List[String]] =
    val normalized = issueIds.map(_.trim).filter(_.nonEmpty).distinct
    ZIO
      .fromOption(Option.when(normalized.nonEmpty)(normalized))
      .orElseFail(PersistenceError.QueryFailed("bulk", "issueIds must contain at least one issue id"))

  private def assignedAgentFromState(state: IssueState): Option[String] =
    state match
      case IssueState.Assigned(agent, _)     => Some(agent.value)
      case IssueState.InProgress(agent, _)   => Some(agent.value)
      case IssueState.Completed(agent, _, _) => Some(agent.value)
      case IssueState.Failed(agent, _, _)    => Some(agent.value)
      case _                                 => None

  private def maybeStartWorkspaceRunOnInProgress(
    issue: DomainIssue,
    requestedStatus: IssueStatus,
    requestedAgentName: Option[String],
    fallbackAgent: String,
  ): IO[PersistenceError, Unit] =
    if canonicalBoardStatus(requestedStatus) != IssueStatus.InProgress then ZIO.unit
    else
      issue.workspaceId.map(_.trim).filter(_.nonEmpty) match
        case None              => ZIO.unit
        case Some(workspaceId) =>
          val resolvedAgent =
            requestedAgentName
              .map(_.trim)
              .filter(_.nonEmpty)
              .orElse(assignedAgentFromState(issue.state))
              .orElse(Option(fallbackAgent).map(_.trim).filter(v => v.nonEmpty && !v.equalsIgnoreCase("board")))
          resolvedAgent match
            case None            => ZIO.unit
            case Some(agentName) =>
              workspaceRunService
                .assign(
                  workspaceId,
                  AssignRunRequest(
                    issueRef = s"#${issue.id.value}",
                    prompt = executionPrompt(issue),
                    agentName = agentName,
                  ),
                )
                .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
                .unit

  private def assignWorkspaceRunAndMarkStarted(
    issue: DomainIssue,
    workspaceId: String,
    agentName: String,
  ): IO[PersistenceError, Unit] =
    for
      run <- workspaceRunService
               .assign(
                 workspaceId,
                 AssignRunRequest(
                   issueRef = s"#${issue.id.value}",
                   prompt = executionPrompt(issue),
                   agentName = agentName,
                 ),
               )
               .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
      _   <- issue.workspaceId match
               case Some(_) =>
                 markWorkspaceBoardIssueStarted(
                   issueId = issue.id.value,
                   workspaceId = workspaceId,
                   agentName = agentName,
                   branchName = run.branchName,
                 )
               case None    =>
                 ZIO.unit
    yield ()

  private def markWorkspaceBoardIssueStarted(
    issueId: String,
    workspaceId: String,
    agentName: String,
    branchName: String,
  ): IO[PersistenceError, Unit] =
    for
      workspaceOpt <- workspaceRepository.get(workspaceId).mapError(mapIssueRepoError)
      workspace    <- ZIO
                        .fromOption(workspaceOpt)
                        .orElseFail(PersistenceError.QueryFailed("workspace", s"Not found: $workspaceId"))
      boardIssueId <- ZIO
                        .fromEither(BoardIssueId.fromString(issueId))
                        .mapError(err => PersistenceError.QueryFailed("board_issue_id", err))
      boardPath    <- projectStorageService.projectRoot(workspace.projectId).map(_.toString)
      _            <- boardOrchestrator
                        .markIssueStarted(boardPath, boardIssueId, agentName, branchName)
                        .mapError(mapBoardError)
    yield ()

  private def executeParallelPipeline(
    issueId: String,
    issue: DomainIssue,
    pipeline: AgentPipeline,
    workspaceId: String,
    runRequest: RunPipelineRequest,
    executionId: String,
  ): IO[PersistenceError, RunPipelineResponse] =
    for
      runs <- ZIO.foreach(pipeline.steps.zipWithIndex) {
                case (step, index) =>
                  val prompt = pipelinePrompt(issue, step, runRequest.basePromptOverride)
                  workspaceRunService
                    .assign(
                      workspaceId,
                      AssignRunRequest(
                        issueRef = s"#$issueId",
                        prompt = prompt,
                        agentName = step.agentId.trim,
                      ),
                    )
                    .mapError(err => PersistenceError.QueryFailed("pipeline_parallel_assign", err.toString))
                    .map(run =>
                      PipelineExecutionRun(
                        stepIndex = index,
                        agentId = step.agentId.trim,
                        runId = run.id,
                        status = run.status.toString,
                      )
                    )
              }
      _    <- publishPipelineActivity(
                issueId = issueId,
                executionId = executionId,
                message = s"Pipeline '${pipeline.name}' started in parallel mode (${runs.size} runs).",
              )
    yield RunPipelineResponse(
      executionId = executionId,
      issueId = issueId,
      pipelineId = pipeline.id,
      mode = PipelineExecutionMode.Parallel,
      status = "running",
      runs = runs,
      message = Some("Parallel pipeline started."),
    )

  private def executeSequentialPipeline(
    issueId: String,
    issue: DomainIssue,
    pipeline: AgentPipeline,
    workspaceId: String,
    runRequest: RunPipelineRequest,
    executionId: String,
  ): IO[PersistenceError, RunPipelineResponse] =
    pipeline.steps match
      case Nil           =>
        ZIO.fail(PersistenceError.QueryFailed("pipeline", "Pipeline has no steps"))
      case first :: tail =>
        for
          firstRun <- workspaceRunService
                        .assign(
                          workspaceId,
                          AssignRunRequest(
                            issueRef = s"#$issueId",
                            prompt = pipelinePrompt(issue, first, runRequest.basePromptOverride),
                            agentName = first.agentId.trim,
                          ),
                        )
                        .mapError(err => PersistenceError.QueryFailed("pipeline_sequential_assign", err.toString))
          _        <- processSequentialPipeline(
                        issueId = issueId,
                        issue = issue,
                        executionId = executionId,
                        previousRunId = firstRun.id,
                        previousStep = first,
                        remaining = tail.zipWithIndex.map((step, idx) => (idx + 1, step)),
                        basePromptOverride = runRequest.basePromptOverride,
                      ).catchAll(err =>
                        publishPipelineActivity(
                          issueId = issueId,
                          executionId = executionId,
                          message = s"Sequential pipeline failed: ${err.toString}",
                        )
                      ).forkDaemon
          _        <- publishPipelineActivity(
                        issueId = issueId,
                        executionId = executionId,
                        message = s"Pipeline '${pipeline.name}' started sequentially with agent ${first.agentId}.",
                      )
        yield RunPipelineResponse(
          executionId = executionId,
          issueId = issueId,
          pipelineId = pipeline.id,
          mode = PipelineExecutionMode.Sequential,
          status = "running",
          runs =
            PipelineExecutionRun(0, first.agentId.trim, firstRun.id, firstRun.status.toString) ::
              tail.zipWithIndex.map {
                case (step, idx) =>
                  PipelineExecutionRun(stepIndex = idx + 1, agentId = step.agentId.trim, runId = "", status = "queued")
              },
          message = Some("Sequential pipeline started; next steps will continue automatically."),
        )

  private def processSequentialPipeline(
    issueId: String,
    issue: DomainIssue,
    executionId: String,
    previousRunId: String,
    previousStep: PipelineStep,
    remaining: List[(Int, PipelineStep)],
    basePromptOverride: Option[String],
  ): IO[PersistenceError, Unit] =
    remaining match
      case Nil                       =>
        publishPipelineActivity(
          issueId = issueId,
          executionId = executionId,
          message = "Sequential pipeline completed.",
        )
      case (index, nextStep) :: tail =>
        for
          previousRun <- waitForTerminalRun(previousRunId)
          canContinue  = previousRun.status == workspace.entity.RunStatus.Completed || previousStep.continueOnFailure
          _           <-
            if canContinue then ZIO.unit
            else
              publishPipelineActivity(
                issueId = issueId,
                executionId = executionId,
                message =
                  s"Sequential pipeline halted at step ${index} because previous run ${previousRun.id} ended with ${previousRun.status}.",
              )
          _           <-
            if canContinue then
              for
                continued <- workspaceRunService
                               .continueRun(
                                 previousRunId,
                                 pipelinePrompt(issue, nextStep, basePromptOverride),
                                 Some(nextStep.agentId.trim),
                               )
                               .mapError(err =>
                                 PersistenceError.QueryFailed("pipeline_sequential_continue", err.toString)
                               )
                _         <- publishPipelineActivity(
                               issueId = issueId,
                               executionId = executionId,
                               message =
                                 s"Sequential pipeline started step ${index + 1} with agent ${nextStep.agentId} (run ${continued.id}).",
                             )
                _         <- processSequentialPipeline(
                               issueId = issueId,
                               issue = issue,
                               executionId = executionId,
                               previousRunId = continued.id,
                               previousStep = nextStep,
                               remaining = tail,
                               basePromptOverride = basePromptOverride,
                             )
              yield ()
            else ZIO.unit
        yield ()

  private def waitForTerminalRun(runId: String): IO[PersistenceError, workspace.entity.WorkspaceRun] =
    def loop: IO[PersistenceError, workspace.entity.WorkspaceRun] =
      workspaceRepository
        .getRun(runId)
        .mapError(mapIssueRepoError)
        .flatMap(
          _.fold[IO[PersistenceError, workspace.entity.WorkspaceRun]](
            ZIO.fail(PersistenceError.QueryFailed("pipeline", s"Run not found: $runId"))
          )(ZIO.succeed)
        )
        .flatMap { run =>
          if run.status == workspace.entity.RunStatus.Completed ||
            run.status == workspace.entity.RunStatus.Failed ||
            run.status == workspace.entity.RunStatus.Cancelled
          then ZIO.succeed(run)
          else ZIO.sleep(2.seconds) *> loop
        }
    loop

  private def pipelinePrompt(
    issue: DomainIssue,
    step: PipelineStep,
    basePromptOverride: Option[String],
  ): String =
    step.promptOverride
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(basePromptOverride.map(_.trim).filter(_.nonEmpty))
      .getOrElse(executionPrompt(issue))

  private def publishPipelineActivity(
    issueId: String,
    executionId: String,
    message: String,
  ): UIO[Unit] =
    Clock.instant.flatMap(now =>
      activityHub.publish(
        ActivityEvent(
          id = EventId.generate,
          eventType = ActivityEventType.RunStateChanged,
          source = "pipeline",
          summary = s"[pipeline:$executionId issue:#$issueId] $message",
          createdAt = now,
        )
      )
    )

  private def loadMergeHistory(issueId: IssueId): IO[PersistenceError, List[MergeHistoryEntryView]] =
    issueRepository
      .history(issueId)
      .mapError(mapIssueRepoError)
      .map { history =>
        history.sortBy(_.occurredAt).flatMap {
          case e: IssueEvent.MergeAttempted       =>
            Some(
              MergeHistoryEntryView(
                eventType = "attempted",
                happenedAt = e.attemptedAt,
                sourceBranch = Some(e.sourceBranch),
                targetBranch = Some(e.targetBranch),
              )
            )
          case e: IssueEvent.MergeSucceeded       =>
            Some(
              MergeHistoryEntryView(
                eventType = "succeeded",
                happenedAt = e.mergedAt,
                commitSha = Some(e.commitSha),
                filesChanged = Some(e.filesChanged),
                insertions = Some(e.insertions),
                deletions = Some(e.deletions),
              )
            )
          case e: IssueEvent.MergeFailed          =>
            Some(
              MergeHistoryEntryView(
                eventType = "failed",
                happenedAt = e.failedAt,
                conflictFiles = e.conflictFiles,
              )
            )
          case e: IssueEvent.CiVerificationResult =>
            Some(
              MergeHistoryEntryView(
                eventType = "ci",
                happenedAt = e.checkedAt,
                ciPassed = Some(e.passed),
                details = Option(e.details).map(_.trim).filter(_.nonEmpty),
              )
            )
          case _                                  => None
        }
      }

  private def previewIssuesFromFolder(request: FolderImportRequest)
    : IO[PersistenceError, List[FolderImportPreviewItem]] =
    for
      files <- issueImportMarkdownFiles(request.folder)
      now   <- Clock.instant
      items <- ZIO.foreach(files) { file =>
                 for
                   markdown <- ZIO
                                 .attemptBlocking(Files.readString(file, StandardCharsets.UTF_8))
                                 .mapError(e => PersistenceError.QueryFailed(file.toString, e.getMessage))
                   parsed    = parseMarkdownIssue(file, markdown, now)
                 yield FolderImportPreviewItem(
                   fileName = file.getFileName.toString,
                   title = parsed.title,
                   issueType = parsed.issueType,
                   priority = parsed.priority,
                 )
               }
    yield items

  private def importIssuesFromFolderDetailed(
    request: FolderImportRequest
  ): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      files   <- issueImportMarkdownFiles(request.folder)
      results <- ZIO.foreach(files) { file =>
                   (for
                     now      <- Clock.instant
                     markdown <- ZIO
                                   .attemptBlocking(Files.readString(file, StandardCharsets.UTF_8))
                                   .mapError(e => PersistenceError.QueryFailed(file.toString, e.getMessage))
                     event     = parseMarkdownIssue(file, markdown, now)
                     _        <- issueRepository.append(event).mapError(mapIssueRepoError)
                   yield ()).either
                 }
    yield toBulkResponse(files.size, results)

  private def importIssuesFromConfiguredFolderDetailed: IO[PersistenceError, BulkIssueOperationResponse] =
    for
      configuredFolder <- issueImportFolderFromSettings
      result           <- importIssuesFromFolderDetailed(FolderImportRequest(configuredFolder))
    yield result

  private def issueImportFolderFromSettings: IO[PersistenceError, String] =
    for
      setting <-
        taskRepository
          .getSetting("issues.importFolder")
          .flatMap(opt =>
            ZIO
              .fromOption(opt.map(_.value.trim).filter(_.nonEmpty))
              .orElseFail(PersistenceError.QueryFailed("settings", "'issues.importFolder' is empty or missing"))
          )
    yield setting

  private def issueImportMarkdownFiles(folderSetting: String): IO[PersistenceError, List[Path]] =
    for
      folderPath <- ZIO
                      .fromOption(Option(folderSetting).map(_.trim).filter(_.nonEmpty))
                      .orElseFail(PersistenceError.QueryFailed("folder", "Folder path is required"))
      folder     <- ZIO
                      .attempt(Paths.get(folderPath))
                      .mapError(e => PersistenceError.QueryFailed("folder", e.getMessage))
      files      <- ZIO
                      .attemptBlocking {
                        if !Files.exists(folder) then List.empty[Path]
                        else
                          Files
                            .list(folder)
                            .iterator()
                            .asScala
                            .filter(path =>
                              Files.isRegularFile(path) && path.getFileName.toString.toLowerCase.endsWith(".md")
                            )
                            .toList
                      }
                      .mapError(e => PersistenceError.QueryFailed("folder", e.getMessage))
    yield files

  private def previewGitHubIssues(request: GitHubImportPreviewRequest)
    : IO[PersistenceError, List[GitHubImportPreviewItem]] =
    ghListIssues(request).map { raw =>
      raw.fromJson[List[GitHubImportPreviewItem]].getOrElse(Nil)
    }

  private def importGitHubIssues(request: GitHubImportPreviewRequest)
    : IO[PersistenceError, BulkIssueOperationResponse] =
    for
      items   <- previewGitHubIssues(request)
      results <- ZIO.foreach(items) { item =>
                   (for
                     now    <- Clock.instant
                     issueId = IssueId.generate
                     _      <- issueRepository
                                 .append(
                                   IssueEvent.Created(
                                     issueId = issueId,
                                     title = s"[GH#${item.number}] ${item.title}",
                                     description = item.body,
                                     issueType = "github",
                                     priority = "medium",
                                     occurredAt = now,
                                     requiredCapabilities = Nil,
                                   )
                                 )
                                 .mapError(mapIssueRepoError)
                     _      <- issueRepository
                                 .append(
                                   IssueEvent.ExternalRefLinked(
                                     issueId = issueId,
                                     externalRef = s"GH:${request.repo}#${item.number}",
                                     externalUrl = Some(item.url),
                                     occurredAt = now,
                                   )
                                 )
                                 .mapError(mapIssueRepoError)
                   yield ()).either
                 }
    yield toBulkResponse(items.size, results)

  private def ghListIssues(request: GitHubImportPreviewRequest): IO[PersistenceError, String] =
    val safeLimit = request.limit.max(1).min(200)
    val safeState = request.state.trim.toLowerCase match
      case "closed" => "closed"
      case "all"    => "all"
      case _        => "open"
    final case class GhIssueLabel(name: String) derives JsonCodec
    final case class GhIssueItem(
      number: Long,
      title: String,
      body: String,
      labels: List[GhIssueLabel] = Nil,
      state: String,
      url: String,
    ) derives JsonCodec
    ZIO
      .attemptBlocking {
        val args    = List(
          "gh",
          "issue",
          "list",
          "--repo",
          request.repo.trim,
          "--state",
          safeState,
          "--limit",
          safeLimit.toString,
          "--json",
          "number,title,body,labels,state,url",
        )
        val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
        val output  = scala.io.Source.fromInputStream(process.getInputStream, "UTF-8").mkString
        val exit    = process.waitFor()
        exit -> output
      }
      .mapError(err => PersistenceError.QueryFailed("github_import", Option(err.getMessage).getOrElse(err.toString)))
      .flatMap {
        case (exit, output) =>
          if exit == 0 then
            output.fromJson[List[GhIssueItem]] match
              case Right(values) =>
                ZIO.succeed(
                  values.map { item =>
                    GitHubImportPreviewItem(
                      number = item.number,
                      title = item.title,
                      body = item.body,
                      labels = item.labels.map(_.name),
                      state = item.state,
                      url = item.url,
                    )
                  }.toJson
                )
              case Left(_)       => ZIO.succeed("[]")
          else
            ZIO.fail(PersistenceError.QueryFailed("github_import", output.trim))
      }

  private def parseTagList(raw: Option[String]): List[String] =
    raw.toList.flatMap(_.split(",").toList).map(_.trim).filter(_.nonEmpty).distinct

  private def parseCapabilityList(raw: Option[String]): List[String] =
    raw.toList
      .flatMap(_.split(",").toList)
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .distinct

  private def rankedAgentSuggestions(requiredCapabilities: List[String])
    : IO[PersistenceError, List[AgentMatchSuggestion]] =
    for
      allAgents <- agentRepository.list().mapError(mapIssueRepoError)
      activeMap <- activeRunsByAgent.mapError(mapIssueRepoError)
      ranked     = AgentMatching
                     .rankAgents(allAgents, requiredCapabilities, activeMap)
                     .map(result =>
                       AgentMatchSuggestion(
                         agentId = result.agent.id.value,
                         agentName = result.agent.name,
                         capabilities = result.agent.capabilities,
                         score = result.score,
                         overlapCount = result.overlapCount,
                         requiredCount = result.requiredCount,
                         activeRuns = result.activeRuns,
                       )
                     )
    yield ranked

  private def activeRunsByAgent: IO[shared.errors.PersistenceError, Map[String, Int]] =
    for
      workspaces <- workspaceRepository.list
      runs       <- ZIO.foreach(workspaces)(ws => workspaceRepository.listRuns(ws.id)).map(_.flatten)
    yield AgentMatching.activeRunsByAgent(runs)

  private def loadDispatchStatuses(
    issues: List[AgentIssueView]
  ): IO[PersistenceError, Map[IssueId, DispatchStatusResponse]] =
    issueDispatchStatusService
      .statusesFor(
        issues.collect {
          case issue if issue.status == IssueStatus.Todo =>
            issue.id.map(IssueId.apply)
        }.flatten
      )
      .mapError(mapIssueRepoError)

  private def selectWorkReports(
    reports: Map[IssueId, IssueWorkReport],
    issues: List[AgentIssueView],
  ): Map[IssueId, IssueWorkReport] =
    val visibleIssueIds = issues.flatMap(_.id).map(IssueId.apply).toSet
    reports.filter { case (issueId, _) => visibleIssueIds.contains(issueId) }

  private def timed[A](label: String)(effect: IO[PersistenceError, A]): IO[PersistenceError, TimedResult[A]] =
    for
      startedAt   <- Clock.nanoTime
      value       <- effect
      completedAt <- Clock.nanoTime
      durationMs   = nanosToMillis(completedAt - startedAt)
      _           <- ZIO.logTrace(s"[board] $label completed in ${durationMs}ms")
    yield TimedResult(value, durationMs)

  private def timedUio[A](label: String)(effect: UIO[A]): UIO[TimedResult[A]] =
    for
      startedAt   <- Clock.nanoTime
      value       <- effect
      completedAt <- Clock.nanoTime
      durationMs   = nanosToMillis(completedAt - startedAt)
      _           <- ZIO.logTrace(s"[board] $label completed in ${durationMs}ms")
    yield TimedResult(value, durationMs)

  private def boardTimingHeaders(
    route: String,
    totalDurationMs: Long,
    workspacesDurationMs: Long,
    issuesDurationMs: Long,
    workReportsDurationMs: Long,
    dispatchDurationMs: Long,
    issueCount: Int,
  ): Headers =
    Headers(
      Header.Custom(
        "Server-Timing",
        s"""board_total;dur=$totalDurationMs,board_workspaces;dur=$workspacesDurationMs,board_issues;dur=$issuesDurationMs,board_work_reports;dur=$workReportsDurationMs,board_dispatch;dur=$dispatchDurationMs""",
      ),
      Header.Custom("X-Board-Route", route),
      Header.Custom("X-Board-Render-Ms", totalDurationMs.toString),
      Header.Custom("X-Board-Issue-Count", issueCount.toString),
    )

  private def logBoardTiming(
    route: String,
    totalDurationMs: Long,
    workspacesDurationMs: Long,
    issuesDurationMs: Long,
    workReportsDurationMs: Long,
    dispatchDurationMs: Long,
    issueCount: Int,
  ): UIO[Unit] =
    ZIO.logTrace(
      s"[board] route=$route totalMs=$totalDurationMs workspacesMs=$workspacesDurationMs issuesMs=$issuesDurationMs workReportsMs=$workReportsDurationMs dispatchMs=$dispatchDurationMs issueCount=$issueCount"
    )

  private def nanosToMillis(value: Long): Long =
    value / 1000000L

  private def registryAgentToAgentInfo(registryAgent: _root_.agent.entity.Agent): _root_.config.entity.AgentInfo =
    _root_.config.entity.AgentInfo(
      name = registryAgent.name,
      handle = registryAgent.name.trim.toLowerCase.replaceAll("[^a-z0-9_-]+", "-"),
      displayName = registryAgent.name,
      description = registryAgent.description,
      agentType = _root_.config.entity.AgentType.Custom,
      usesAI = true,
      tags = registryAgent.capabilities,
    )

  private def persistStructuredFields(
    issueId: IssueId,
    promptTemplate: Option[String],
    acceptanceCriteria: Option[String],
    estimate: Option[String],
    kaizenSkill: Option[String],
    proofOfWorkRequirements: List[String],
    now: Instant,
  ): IO[PersistenceError, Unit] =
    for
      _ <- promptTemplate.fold[IO[PersistenceError, Unit]](ZIO.unit) { template =>
             issueRepository
               .append(IssueEvent.PromptTemplateUpdated(issueId, template, now))
               .mapError(mapIssueRepoError)
           }
      _ <- acceptanceCriteria.fold[IO[PersistenceError, Unit]](ZIO.unit) { criteria =>
             issueRepository
               .append(IssueEvent.AcceptanceCriteriaUpdated(issueId, criteria, now))
               .mapError(mapIssueRepoError)
           }
      _ <- estimate.fold[IO[PersistenceError, Unit]](ZIO.unit) { value =>
             issueRepository
               .append(IssueEvent.EstimateUpdated(issueId, value, now))
               .mapError(mapIssueRepoError)
           }
      _ <- kaizenSkill.fold[IO[PersistenceError, Unit]](ZIO.unit) { skill =>
             issueRepository
               .append(IssueEvent.KaizenSkillUpdated(issueId, skill, now))
               .mapError(mapIssueRepoError)
           }
      _ <- ZIO.when(proofOfWorkRequirements.nonEmpty) {
             issueRepository
               .append(
                 IssueEvent.ProofOfWorkRequirementsUpdated(
                   issueId,
                   sanitizeProofRequirements(proofOfWorkRequirements),
                   now,
                 )
               )
               .mapError(mapIssueRepoError)
           }
    yield ()

  private def parseWorkspaceSelection(form: Map[String, String]): Option[String] =
    form.get("workspaceId").map(_.trim).filter(_.nonEmpty)

  private def ensureWorkspaceExists(workspaceId: String): IO[PersistenceError, Unit] =
    workspaceRepository
      .get(workspaceId)
      .mapError(mapIssueRepoError)
      .flatMap {
        case Some(_) => ZIO.unit
        case None    => ZIO.fail(PersistenceError.QueryFailed("workspace", s"Not found: $workspaceId"))
      }

  private def importIssuesFromConfiguredFolder: IO[PersistenceError, Int] =
    importIssuesFromConfiguredFolderDetailed.map(_.succeeded)

  private def settingBoolean(key: String, default: Boolean): IO[PersistenceError, Boolean] =
    configRepository
      .getSetting(key)
      .map(_.exists(v => Option(v.value).getOrElse("").trim.equalsIgnoreCase("true")))
      .orElseSucceed(default)
