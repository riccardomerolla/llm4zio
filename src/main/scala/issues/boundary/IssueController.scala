package issues.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.time.Instant
import java.util.UUID

import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

import zio.*
import zio.http.*
import zio.json.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import agent.control.AgentMatching
import agent.entity.AgentRepository
import agent.entity.api.AgentMatchSuggestion
import db.{ ChatRepository, ConfigRepository, PersistenceError, TaskRepository }
import issues.entity.api.*
import issues.entity.{ AgentIssue as DomainIssue, * }
import orchestration.control.IssueAssignmentOrchestrator
import shared.ids.Ids.{ AgentId, EventId, IssueId, TaskRunId }
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.WorkspaceRepository

trait IssueController:
  def routes: Routes[Any, Response]

object IssueController:

  def routes: ZIO[IssueController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[IssueController](_.routes)

  val live
    : ZLayer[
      ChatRepository & TaskRepository & ConfigRepository & AgentRepository & IssueAssignmentOrchestrator & IssueRepository & WorkspaceRepository & WorkspaceRunService & ActivityHub,
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
) extends IssueController:

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
          form    <- parseForm(req)
          title   <- required(form, "title")
          content <- required(form, "description")
          now     <- Clock.instant
          issueId  = IssueId.generate
          tags     = parseTagList(form.get("tags"))
          required = parseCapabilityList(form.get("requiredCapabilities"))
          event    = IssueEvent.Created(
                       issueId = issueId,
                       title = title,
                       description = content,
                       issueType = form.get("issueType").map(_.trim).filter(_.nonEmpty).getOrElse("task"),
                       priority = form.get("priority").getOrElse("medium"),
                       occurredAt = now,
                       requiredCapabilities = required,
                     )
          _       <- issueRepository.append(event).mapError(mapIssueRepoError)
          _       <- ZIO.when(tags.nonEmpty) {
                       issueRepository.append(IssueEvent.TagsUpdated(issueId, tags, now)).mapError(mapIssueRepoError)
                     }
          _       <- parseWorkspaceSelection(form).fold[IO[PersistenceError, Unit]](ZIO.unit) { workspaceId =>
                       for
                         _ <- ensureWorkspaceExists(workspaceId)
                         _ <- issueRepository
                                .append(
                                  IssueEvent.WorkspaceLinked(
                                    issueId = issueId,
                                    workspaceId = workspaceId,
                                    occurredAt = now,
                                  )
                                )
                                .mapError(mapIssueRepoError)
                       yield ()
                     }
          redirect = form.get("runId").map(id => s"/board?mode=list&run_id=$id").getOrElse("/board?mode=list")
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
    Method.GET / "issues" / string("id")                             -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          issue          <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          workspaces     <- workspaceRepository.list.mapError(mapIssueRepoError)
          allAgents      <- agentRepository.list().mapError(mapIssueRepoError)
          availableAgents = allAgents.filter(_.enabled).map(registryAgentToAgentInfo)
        yield html(
          HtmlViews.issueDetail(
            domainToView(issue),
            List.empty,
            availableAgents,
            workspaces.map(ws => ws.id -> ws.name),
          )
        )
      }
    },
    Method.GET / "issues" / string("id") / "edit"                    -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          issue      <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          workspaces <- workspaceRepository.list.mapError(mapIssueRepoError)
        yield html(HtmlViews.issueEditForm(domainToView(issue), workspaces.map(ws => ws.id -> ws.name)))
      }
    },
    Method.POST / "issues" / string("id") / "edit"                   -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form    <- parseForm(req)
          title   <- required(form, "title")
          content <- required(form, "description")
          now     <- Clock.instant
          issueId  = IssueId(id)
          caps     = parseCapabilityList(form.get("requiredCapabilities"))
          event    = IssueEvent.MetadataUpdated(
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
          _       <- issueRepository.append(event).mapError(mapIssueRepoError)
          tags     = parseTagList(form.get("tags"))
          _       <- issueRepository.append(IssueEvent.TagsUpdated(issueId, tags, now)).mapError(mapIssueRepoError)
          _       <- parseWorkspaceSelection(form) match
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
                            .fromOption(parseIssueStateTag(rawStatus).map {
                              case IssueStateTag.Open       => IssueStatus.Open
                              case IssueStateTag.Assigned   => IssueStatus.Assigned
                              case IssueStateTag.InProgress => IssueStatus.InProgress
                              case IssueStateTag.Completed  => IssueStatus.Completed
                              case IssueStateTag.Failed     => IssueStatus.Failed
                              case IssueStateTag.Skipped    => IssueStatus.Skipped
                            })
                            .orElseFail(PersistenceError.QueryFailed("status_parse", s"Unknown status: $rawStatus"))
          event        <- statusToEvent(issueId, IssueStatusUpdateRequest(status = status), agentFallback, now)
          _            <- issueRepository.append(event).mapError(mapIssueRepoError)
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
          _                  <- issueAssignmentOrchestrator.assignIssue(id, agentName)
          _                  <- workspaceForRun.fold[IO[PersistenceError, Unit]](ZIO.unit) { workspaceId =>
                                  workspaceRunService
                                    .assign(
                                      workspaceId,
                                      AssignRunRequest(
                                        issueRef = s"#$id",
                                        prompt = issue.description,
                                        agentName = agentName,
                                      ),
                                    )
                                    .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
                                    .unit
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
          now          <- Clock.instant
          issueId       = IssueId.generate
          tags          = parseTagList(issueRequest.tags)
          required      = issueRequest.requiredCapabilities.map(_.trim).filter(_.nonEmpty).distinct
          event         = IssueEvent.Created(
                            issueId = issueId,
                            title = issueRequest.title,
                            description = issueRequest.description,
                            issueType = issueRequest.issueType,
                            priority = issueRequest.priority.toString,
                            occurredAt = now,
                            requiredCapabilities = required,
                          )
          _            <- issueRepository.append(event).mapError(mapIssueRepoError)
          _            <- ZIO.when(tags.nonEmpty) {
                            issueRepository.append(IssueEvent.TagsUpdated(issueId, tags, now)).mapError(mapIssueRepoError)
                          }
          _            <- issueRequest.workspaceId.fold[IO[PersistenceError, Unit]](ZIO.unit) { workspaceId =>
                            for
                              _ <- ensureWorkspaceExists(workspaceId)
                              _ <- issueRepository
                                     .append(
                                       IssueEvent.WorkspaceLinked(
                                         issueId = issueId,
                                         workspaceId = workspaceId,
                                         occurredAt = now,
                                       )
                                     )
                                     .mapError(mapIssueRepoError)
                            yield ()
                          }
          created      <- issueRepository.get(issueId).mapError(mapIssueRepoError)
        yield Response.json(domainToView(created).toJson)
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
          now        <- Clock.instant
          issueId     = IssueId.generate
          event       = IssueEvent.Created(
                          issueId = issueId,
                          title = title,
                          description = description,
                          issueType = template.issueType,
                          priority = template.priority.toString,
                          occurredAt = now,
                          requiredCapabilities = Nil,
                        )
          _          <- issueRepository.append(event).mapError(mapIssueRepoError)
          _          <- ZIO.when(template.tags.nonEmpty) {
                          issueRepository
                            .append(IssueEvent.TagsUpdated(issueId, template.tags.distinct, now))
                            .mapError(mapIssueRepoError)
                        }
          _          <- createReq.workspaceId.map(_.trim).filter(_.nonEmpty).fold[IO[PersistenceError, Unit]](ZIO.unit) {
                          workspaceId =>
                            for
                              _ <- ensureWorkspaceExists(workspaceId)
                              _ <- issueRepository
                                     .append(
                                       IssueEvent.WorkspaceLinked(
                                         issueId = issueId,
                                         workspaceId = workspaceId,
                                         occurredAt = now,
                                       )
                                     )
                                     .mapError(mapIssueRepoError)
                            yield ()
                        }
          created    <- issueRepository.get(issueId).mapError(mapIssueRepoError)
        yield Response.json(domainToView(created).toJson)
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
        val filter = IssueFilter(runId = runIdStr.map(TaskRunId.apply))
        issueRepository.list(filter).mapError(mapIssueRepoError).map(issues =>
          Response.json(issues.map(domainToView).toJson)
        )
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
    Method.GET / "api" / "issues" / string("id")                     -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          .map(issue => Response.json(domainToView(issue).toJson))
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
          _              <- issueAssignmentOrchestrator.assignIssue(id, assignRequest.agentName)
          _              <- workspaceForRun.fold[IO[PersistenceError, Unit]](ZIO.unit) { workspaceId =>
                              workspaceRunService
                                .assign(
                                  workspaceId,
                                  AssignRunRequest(
                                    issueRef = s"#$id",
                                    prompt = issue.description,
                                    agentName = assignRequest.agentName,
                                  ),
                                )
                                .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
                                .unit
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
                                 _ <- issueAssignmentOrchestrator.assignIssue(id, best.agentName)
                                 _ <- selectedWorkspaceId.fold[IO[PersistenceError, Unit]](ZIO.unit) { workspaceId =>
                                        workspaceRunService
                                          .assign(
                                            workspaceId,
                                            AssignRunRequest(
                                              issueRef = s"#$id",
                                              prompt = issue.description,
                                              agentName = best.agentName,
                                            ),
                                          )
                                          .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
                                          .unit
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
          event         <- statusToEvent(issueId, updateRequest, fallbackAgent, now)
          _             <- issueRepository.append(event).mapError(mapIssueRepoError)
          _             <- activityHub.publish(
                             ActivityEvent(
                               id = EventId.generate,
                               eventType = ActivityEventType.RunStateChanged,
                               source = "issues-board",
                               runId = issue.runId.map(r => TaskRunId(r.value)),
                               agentName = Some(fallbackAgent),
                               summary = s"Issue #$id moved to ${updateRequest.status.toString}",
                               createdAt = now,
                             )
                           )
          updated       <- issueRepository.get(issueId).mapError(mapIssueRepoError)
        yield Response.json(domainToView(updated).toJson)
      }
    },
    Method.GET / "api" / "issues" / "unassigned" / string("runId")   -> handler { (runId: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val filter = IssueFilter(
          runId = Some(TaskRunId(runId)),
          states = Set(IssueStateTag.Open),
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
    val hasProofFilter  = req.queryParam("hasProof").exists(_.trim.equalsIgnoreCase("true"))
    ErrorHandlingMiddleware.fromPersistence {
      for
        workspaces          <- workspaceRepository.list.mapError(mapIssueRepoError)
        issues              <- loadBoardIssues(query, tagFilter, workspaceFilter, agentFilter, priorityFilter, statusFilter)
        allAgents           <- agentRepository.list().mapError(mapIssueRepoError)
        availableAgents      = allAgents.filter(_.enabled).map(registryAgentToAgentInfo)
        autoDispatchEnabled <- settingBoolean("issues.autoDispatch.enabled", default = false)
        syncStatus          <- loadTrackerSyncStatus
        activeByAgent       <- activeRunsByAgent.mapError(mapIssueRepoError)
        activeAgents         = activeByAgent.values.count(_ > 0)
        enabledAgentsTotal   = math.max(availableAgents.size, 1)
        rendered            <- mode match
                                 case "list" =>
                                   ZIO.succeed(
                                     HtmlViews.issuesBoardList(
                                       issues = issues,
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
                                       issues = issues,
                                       workspaces = workspaces.map(ws => ws.id -> ws.name),
                                       workspaceFilter = workspaceFilter,
                                       agentFilter = agentFilter,
                                       priorityFilter = priorityFilter,
                                       tagFilter = tagFilter,
                                       query = query,
                                       statusFilter = statusFilter,
                                       availableAgents = availableAgents,
                                       autoDispatchEnabled = autoDispatchEnabled,
                                       syncStatus = syncStatus,
                                       agentUsage = Some(activeAgents -> enabledAgentsTotal),
                                       hasProofFilter = if hasProofFilter then Some(true) else None,
                                     )
                                   )
      yield html(rendered)
    }

  private def boardFragment(req: Request): UIO[Response] =
    val query           = req.queryParam("q").map(_.trim).filter(_.nonEmpty)
    val tagFilter       = req.queryParam("tag").map(_.trim).filter(_.nonEmpty)
    val workspaceFilter = req.queryParam("workspace").map(_.trim).filter(_.nonEmpty)
    val agentFilter     = req.queryParam("agent").map(_.trim).filter(_.nonEmpty)
    val priorityFilter  = req.queryParam("priority").map(_.trim.toLowerCase).filter(_.nonEmpty)
    val statusFilter    = req.queryParam("status").map(_.trim.toLowerCase).filter(_.nonEmpty)
    val hasProofFilter  = req.queryParam("hasProof").exists(_.trim.equalsIgnoreCase("true"))
    ErrorHandlingMiddleware.fromPersistence {
      for
        workspaces     <- workspaceRepository.list.mapError(mapIssueRepoError)
        issues         <- loadBoardIssues(query, tagFilter, workspaceFilter, agentFilter, priorityFilter, statusFilter)
        allAgents      <- agentRepository.list().mapError(mapIssueRepoError)
        availableAgents = allAgents.filter(_.enabled).map(registryAgentToAgentInfo)
      yield html(
        HtmlViews.issuesBoardColumns(
          issues = issues,
          workspaces = workspaces.map(ws => ws.id -> ws.name),
          availableAgents = availableAgents,
          hasProofFilter = if hasProofFilter then Some(true) else None,
        )
      )
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

  private def domainToView(i: DomainIssue): AgentIssueView =
    val (status, assignedAgent, assignedAt, completedAt, errorMessage) = i.state match
      case IssueState.Open(_)                 => (IssueStatus.Open, None, None, None, None)
      case IssueState.Assigned(agent, at)     => (IssueStatus.Assigned, Some(agent.value), Some(at), None, None)
      case IssueState.InProgress(agent, at)   => (IssueStatus.InProgress, Some(agent.value), Some(at), None, None)
      case IssueState.Completed(agent, at, _) => (IssueStatus.Completed, Some(agent.value), None, Some(at), None)
      case IssueState.Failed(agent, at, msg)  => (IssueStatus.Failed, Some(agent.value), None, Some(at), Some(msg))
      case IssueState.Skipped(at, _)          => (IssueStatus.Skipped, None, None, Some(at), None)
    val priority                                                       = IssuePriority.values.find(_.toString.equalsIgnoreCase(i.priority)).getOrElse(IssuePriority.Medium)
    val createdAt                                                      = i.state match
      case IssueState.Open(at) => at
      case _                   => java.time.Instant.EPOCH
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
      contextPath = Option(i.contextPath).filter(_.nonEmpty),
      sourceFolder = Option(i.sourceFolder).filter(_.nonEmpty),
      workspaceId = i.workspaceId,
      externalRef = i.externalRef,
      externalUrl = i.externalUrl,
      priority = priority,
      status = status,
      assignedAgent = assignedAgent,
      assignedAt = assignedAt,
      completedAt = completedAt,
      errorMessage = errorMessage,
      createdAt = createdAt,
      updatedAt = assignedAt.orElse(completedAt).getOrElse(createdAt),
    )

  private def loadBoardIssues(
    query: Option[String],
    tagFilter: Option[String],
    workspaceFilter: Option[String],
    agentFilter: Option[String],
    priorityFilter: Option[String],
    statusFilter: Option[String],
  ): IO[PersistenceError, List[AgentIssueView]] =
    issueRepository
      .list(IssueFilter())
      .mapError(mapIssueRepoError)
      .map(_.map(domainToView))
      .map(filterIssues(_, query, tagFilter))
      .map(_.filter(issue =>
        workspaceFilter.forall(_.equalsIgnoreCase(issue.workspaceId.getOrElse(""))) &&
        agentFilter.forall(agent =>
          issue.assignedAgent.exists(_.equalsIgnoreCase(agent)) || issue.preferredAgent.exists(
            _.equalsIgnoreCase(agent)
          )
        ) &&
        statusFilter.forall(status => statusMatches(issue.status, status)) &&
        priorityFilter.forall(p => issue.priority.toString.equalsIgnoreCase(p))
      ))
      .map(_.filter(issue =>
        issue.status == IssueStatus.Open ||
        issue.status == IssueStatus.Assigned ||
        issue.status == IssueStatus.InProgress ||
        issue.status == IssueStatus.Completed ||
        issue.status == IssueStatus.Failed
      ))

  private def statusMatches(status: IssueStatus, token: String): Boolean =
    (status, token.trim.toLowerCase) match
      case (IssueStatus.Open, "open")              => true
      case (IssueStatus.Assigned, "assigned")      => true
      case (IssueStatus.InProgress, "in_progress") => true
      case (IssueStatus.InProgress, "inprogress")  => true
      case (IssueStatus.Completed, "completed")    => true
      case (IssueStatus.Failed, "failed")          => true
      case (IssueStatus.Skipped, "skipped")        => true
      case _                                       => false

  private def parseIssueStateTag(raw: String): Option[IssueStateTag] =
    raw.trim.toLowerCase match
      case "open"        => Some(IssueStateTag.Open)
      case "assigned"    => Some(IssueStateTag.Assigned)
      case "in_progress" => Some(IssueStateTag.InProgress)
      case "completed"   => Some(IssueStateTag.Completed)
      case "failed"      => Some(IssueStateTag.Failed)
      case "skipped"     => Some(IssueStateTag.Skipped)
      case _             => None

  private def statusToEvent(
    issueId: IssueId,
    request: IssueStatusUpdateRequest,
    fallbackAgent: String,
    now: Instant,
  ): IO[PersistenceError, IssueEvent] =
    request.status match
      case IssueStatus.Open       =>
        ZIO.succeed(IssueEvent.Reopened(issueId = issueId, reopenedAt = now, occurredAt = now))
      case IssueStatus.Assigned   =>
        ZIO.succeed(
          IssueEvent.Assigned(
            issueId = issueId,
            agent = AgentId(fallbackAgent),
            assignedAt = now,
            occurredAt = now,
          )
        )
      case IssueStatus.InProgress =>
        ZIO.succeed(
          IssueEvent.Started(
            issueId = issueId,
            agent = AgentId(fallbackAgent),
            startedAt = now,
            occurredAt = now,
          )
        )
      case IssueStatus.Completed  =>
        ZIO.succeed(
          IssueEvent.Completed(
            issueId = issueId,
            agent = AgentId(fallbackAgent),
            completedAt = now,
            result = request.resultData.map(_.trim).filter(_.nonEmpty).getOrElse("Marked completed from board"),
            occurredAt = now,
          )
        )
      case IssueStatus.Failed     =>
        ZIO.succeed(
          IssueEvent.Failed(
            issueId = issueId,
            agent = AgentId(fallbackAgent),
            failedAt = now,
            errorMessage = request.reason.map(_.trim).filter(_.nonEmpty).getOrElse("Marked failed from board"),
            occurredAt = now,
          )
        )
      case IssueStatus.Skipped    =>
        ZIO.succeed(
          IssueEvent.Skipped(
            issueId = issueId,
            skippedAt = now,
            reason = request.reason.map(_.trim).filter(_.nonEmpty).getOrElse("Skipped from board"),
            occurredAt = now,
          )
        )

  private def filterIssues(
    issues: List[AgentIssueView],
    query: Option[String],
    tag: Option[String],
  ): List[AgentIssueView] =
    val byQuery = query match
      case Some(term) =>
        val needle = term.toLowerCase
        issues.filter(issue =>
          issue.title.toLowerCase.contains(needle) ||
          issue.description.toLowerCase.contains(needle) ||
          issue.issueType.toLowerCase.contains(needle)
        )
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
                      _     <- issueAssignmentOrchestrator.assignIssue(issueId, request.agentId)
                      _     <- workspaceRunService
                                 .assign(
                                   request.workspaceId,
                                   AssignRunRequest(
                                     issueRef = s"#$issueId",
                                     prompt = issue.description,
                                     agentName = request.agentId,
                                   ),
                                 )
                                 .mapError(err => PersistenceError.QueryFailed("workspace_assign", err.toString))
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
                      event        <- statusToEvent(
                                        IssueId(issueId),
                                        IssueStatusUpdateRequest(
                                          status = request.status,
                                          agentName = request.agentName,
                                          reason = request.reason,
                                          resultData = request.resultData,
                                        ),
                                        fallbackAgent,
                                        now,
                                      )
                      _            <- issueRepository.append(event).mapError(mapIssueRepoError)
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
      .getOrElse(issue.description)

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

  private def required(form: Map[String, String], key: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(form.get(key).map(_.trim).filter(_.nonEmpty))
      .orElseFail(PersistenceError.QueryFailed("parseForm", s"Missing field '$key'"))

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

  private def parseMarkdownIssue(file: Path, markdown: String, now: Instant): IssueEvent.Created =
    val lines = markdown.linesIterator.toList
    val title =
      lines
        .find(_.trim.startsWith("#"))
        .map(_.replaceFirst("^#+\\s*", "").trim)
        .filter(_.nonEmpty)
        .getOrElse(file.getFileName.toString.stripSuffix(".md"))

    def metadata(key: String): Option[String] =
      lines
        .find(_.toLowerCase.startsWith(s"$key:"))
        .flatMap(_.split(":", 2).lift(1).map(_.trim).filter(_.nonEmpty))

    IssueEvent.Created(
      issueId = IssueId.generate,
      title = title,
      description = markdown,
      issueType = metadata("type").getOrElse("task"),
      priority = metadata("priority").getOrElse("medium"),
      occurredAt = now,
      requiredCapabilities =
        parseCapabilityList(metadata("required_capabilities").orElse(metadata("required-capabilities"))),
    )

  private def settingBoolean(key: String, default: Boolean): IO[PersistenceError, Boolean] =
    configRepository
      .getSetting(key)
      .map(_.exists(v => Option(v.value).getOrElse("").trim.equalsIgnoreCase("true")))
      .orElseSucceed(default)

  private def settingLong(key: String): IO[PersistenceError, Option[Long]] =
    configRepository
      .getSetting(key)
      .map(_.flatMap(v => Option(v.value).map(_.trim).filter(_.nonEmpty).flatMap(_.toLongOption)))
      .orElseSucceed(None)

  private def settingString(key: String): IO[PersistenceError, Option[String]] =
    configRepository
      .getSetting(key)
      .map(_.flatMap(v => Option(v.value).map(_.trim).filter(_.nonEmpty)))
      .orElseSucceed(None)

  private def loadTrackerSyncStatus: IO[PersistenceError, shared.web.IssuesView.SyncStatus] =
    for
      lastSync <- settingString("trackers.sync.lastAt")
                    .flatMap {
                      case some @ Some(_) => ZIO.succeed(some)
                      case None           => settingString("trackers.lastSyncAt")
                    }
      synced   <- settingLong("trackers.sync.syncedCount")
                    .flatMap {
                      case some @ Some(_) => ZIO.succeed(some)
                      case None           => settingLong("trackers.syncedCount")
                    }
      errors   <- settingLong("trackers.sync.errorCount")
                    .flatMap {
                      case some @ Some(_) => ZIO.succeed(some)
                      case None           => settingLong("trackers.errorCount")
                    }
    yield shared.web.IssuesView.SyncStatus(lastSync, synced.getOrElse(0L).toInt, errors.getOrElse(0L).toInt)

  private def withQuery(basePath: String, req: Request): String =
    val knownKeys = List("mode", "run_id", "status", "q", "tag", "workspace", "agent", "priority", "hasProof")
    knownKeys.flatMap(key => req.queryParam(key).map(v => s"${urlEncode(key)}=${urlEncode(v)}")) match
      case Nil    => basePath
      case params =>
        val sep = if basePath.contains("?") then "&" else "?"
        s"$basePath$sep${params.mkString("&")}"

  private def redirectPermanent(path: String): Response =
    Response(
      status = Status.MovedPermanently,
      headers = Headers(Header.Location(URL.decode(path).getOrElse(URL.root))),
    )

  private def parseForm(req: Request): IO[PersistenceError, Map[String, String]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap { kv =>
            kv.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .toMap
      }
      .mapError(err => PersistenceError.QueryFailed("parseForm", err.getMessage))

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
