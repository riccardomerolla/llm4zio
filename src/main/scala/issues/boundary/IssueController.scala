package issues.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.time.Instant

import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.*
import zio.json.*

import db.{ ChatRepository, PersistenceError, TaskRepository }
import issues.entity.{ IssueEvent, IssueFilter, IssueRepository, IssueState, IssueStateTag }
import issues.entity.{ AgentIssue as DomainIssue }
import issues.entity.api.{ AgentIssueCreateRequest, AgentIssueView, AssignIssueRequest, IssuePriority, IssueStatus }
import orchestration.control.{ AgentRegistry, IssueAssignmentOrchestrator }
import shared.ids.Ids.{ IssueId, TaskRunId }
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }

trait IssueController:
  def routes: Routes[Any, Response]

object IssueController:

  def routes: ZIO[IssueController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[IssueController](_.routes)

  val live: ZLayer[ChatRepository & TaskRepository & IssueAssignmentOrchestrator & IssueRepository, Nothing, IssueController] =
    ZLayer.fromFunction(IssueControllerLive.apply)

final case class IssueControllerLive(
  chatRepository: ChatRepository,
  taskRepository: TaskRepository,
  issueAssignmentOrchestrator: IssueAssignmentOrchestrator,
  issueRepository: IssueRepository,
) extends IssueController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "issues"                                          -> handler { (req: Request) =>
      val runId        = req.queryParam("run_id").map(_.trim).filter(_.nonEmpty)
      val statusFilter = req.queryParam("status").map(_.trim).filter(_.nonEmpty)
      val query        = req.queryParam("q").map(_.trim).filter(_.nonEmpty)
      val tagFilter    = req.queryParam("tag").map(_.trim).filter(_.nonEmpty)

      ErrorHandlingMiddleware.fromPersistence {
        for
          issues  <- loadIssues(runId, statusFilter)
          filtered = filterIssues(issues, query, tagFilter)
        yield html(HtmlViews.issuesView(runId, filtered.asInstanceOf, statusFilter, query, tagFilter))
      }
    },
    Method.GET / "issues" / "new"                                  -> handler { (req: Request) =>
      val runId = req.queryParam("run_id").map(_.trim).filter(_.nonEmpty)
      ZIO.succeed(html(HtmlViews.issueCreateForm(runId)))
    },
    Method.POST / "issues"                                         -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          form    <- parseForm(req)
          title   <- required(form, "title")
          content <- required(form, "description")
          now     <- Clock.instant
          issueId  = IssueId.generate
          event    = IssueEvent.Created(
                       issueId = issueId,
                       title = title,
                       description = content,
                       issueType = form.get("issueType").map(_.trim).filter(_.nonEmpty).getOrElse("task"),
                       priority = form.get("priority").getOrElse("medium"),
                       occurredAt = now,
                     )
          _       <- issueRepository.append(event).mapError(mapIssueRepoError)
          redirect = form.get("runId").map(id => s"/issues?run_id=$id").getOrElse("/issues")
        yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", redirect)))
      }
    },
    Method.POST / "issues" / "import"                              -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          imported <- importIssuesFromConfiguredFolder
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", s"/issues?imported=$imported")),
        )
      }
    },
    Method.GET / "issues" / string("id")                           -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          issue          <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          customAgents   <- taskRepository.listCustomAgents
          enabledCustom   = customAgents.filter(_.enabled)
          availableAgents = AgentRegistry.allAgents(enabledCustom).filter(_.usesAI)
        yield html(HtmlViews.issueDetail(domainToView(issue).asInstanceOf, List.empty, availableAgents))
      }
    },
    Method.POST / "issues" / string("id") / "assign"               -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          issueId   <- parseLongId("issue", id)
          form      <- parseForm(req)
          agentName <- required(form, "agentName")
          _         <- issueAssignmentOrchestrator.assignIssue(issueId, agentName)
        yield Response(status = Status.SeeOther, headers = Headers(Header.Custom("Location", s"/issues/$id")))
      }
    },
    Method.POST / "api" / "issues"                                 -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body         <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          issueRequest <- ZIO
                            .fromEither(body.fromJson[AgentIssueCreateRequest])
                            .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          now          <- Clock.instant
          issueId       = IssueId.generate
          event         = IssueEvent.Created(
                            issueId = issueId,
                            title = issueRequest.title,
                            description = issueRequest.description,
                            issueType = issueRequest.issueType,
                            priority = issueRequest.priority.toString,
                            occurredAt = now,
                          )
          _            <- issueRepository.append(event).mapError(mapIssueRepoError)
          created      <- issueRepository.get(issueId).mapError(mapIssueRepoError)
        yield Response.json(domainToView(created).toJson)
      }
    },
    Method.GET / "api" / "issues"                                  -> handler { (req: Request) =>
      val runIdStr = req.queryParam("run_id").map(_.trim).filter(_.nonEmpty)
      ErrorHandlingMiddleware.fromPersistence {
        val filter = IssueFilter(runId = runIdStr.map(TaskRunId.apply))
        issueRepository.list(filter).mapError(mapIssueRepoError).map(issues => Response.json(issues.map(domainToView).toJson))
      }
    },
    Method.GET / "api" / "issues" / string("id")                   -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
          .map(issue => Response.json(domainToView(issue).toJson))
      }
    },
    Method.PATCH / "api" / "issues" / string("id") / "assign"      -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          issueId       <- parseLongId("issue", id)
          body          <- req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))
          assignRequest <- ZIO
                             .fromEither(body.fromJson[AssignIssueRequest])
                             .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          _             <- issueAssignmentOrchestrator.assignIssue(issueId, assignRequest.agentName)
          updated       <- issueRepository.get(IssueId(id)).mapError(mapIssueRepoError)
        yield Response.json(domainToView(updated).toJson)
      }
    },
    Method.GET / "api" / "issues" / "unassigned" / string("runId") -> handler { (runId: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        val filter = IssueFilter(
          runId = Some(TaskRunId(runId)),
          states = Set(IssueStateTag.Open),
        )
        issueRepository.list(filter).mapError(mapIssueRepoError)
          .map(issues => Response.json(issues.map(domainToView).toJson))
      }
    },
    Method.DELETE / "api" / "issues" / string("id")                -> handler { (_: String, _: Request) =>
      ZIO.succeed(Response(status = Status.NotImplemented))
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def mapIssueRepoError(e: shared.errors.PersistenceError): PersistenceError =
    e match
      case shared.errors.PersistenceError.NotFound(entity, id) =>
        PersistenceError.QueryFailed(s"$entity", s"Not found: $id")
      case shared.errors.PersistenceError.QueryFailed(op, cause) =>
        PersistenceError.QueryFailed(op, cause)
      case shared.errors.PersistenceError.SerializationFailed(entity, cause) =>
        PersistenceError.QueryFailed(entity, cause)
      case shared.errors.PersistenceError.StoreUnavailable(msg) =>
        PersistenceError.QueryFailed("store", msg)

  private def domainToView(i: DomainIssue): AgentIssueView =
    val (status, assignedAgent, assignedAt, completedAt, errorMessage) = i.state match
      case IssueState.Open(_)                 => (IssueStatus.Open, None, None, None, None)
      case IssueState.Assigned(agent, at)     => (IssueStatus.Assigned, Some(agent.value), Some(at), None, None)
      case IssueState.InProgress(agent, at)   => (IssueStatus.InProgress, Some(agent.value), Some(at), None, None)
      case IssueState.Completed(agent, at, _) => (IssueStatus.Completed, Some(agent.value), None, Some(at), None)
      case IssueState.Failed(agent, at, msg)  => (IssueStatus.Failed, Some(agent.value), None, Some(at), Some(msg))
      case IssueState.Skipped(at, _)          => (IssueStatus.Skipped, None, None, Some(at), None)
    val priority = IssuePriority.values.find(_.toString.equalsIgnoreCase(i.priority)).getOrElse(IssuePriority.Medium)
    val createdAt = i.state match
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
      contextPath = Option(i.contextPath).filter(_.nonEmpty),
      sourceFolder = Option(i.sourceFolder).filter(_.nonEmpty),
      priority = priority,
      status = status,
      assignedAgent = assignedAgent,
      assignedAt = assignedAt,
      completedAt = completedAt,
      errorMessage = errorMessage,
      createdAt = createdAt,
      updatedAt = assignedAt.orElse(completedAt).getOrElse(createdAt),
    )

  private def loadIssues(runId: Option[String], statusFilter: Option[String]): IO[PersistenceError, List[AgentIssueView]] =
    val filter = IssueFilter(
      runId = runId.map(TaskRunId.apply),
      states = statusFilter.flatMap(parseIssueStateTag).map(Set(_)).getOrElse(Set.empty),
    )
    issueRepository.list(filter).mapError(mapIssueRepoError).map(_.map(domainToView))

  private def parseIssueStateTag(raw: String): Option[IssueStateTag] =
    raw.trim.toLowerCase match
      case "open"        => Some(IssueStateTag.Open)
      case "assigned"    => Some(IssueStateTag.Assigned)
      case "in_progress" => Some(IssueStateTag.InProgress)
      case "completed"   => Some(IssueStateTag.Completed)
      case "failed"      => Some(IssueStateTag.Failed)
      case "skipped"     => Some(IssueStateTag.Skipped)
      case _             => None

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

  private def required(form: Map[String, String], key: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(form.get(key).map(_.trim).filter(_.nonEmpty))
      .orElseFail(PersistenceError.QueryFailed("parseForm", s"Missing field '$key'"))

  private def parseLongId(entity: String, raw: String): IO[PersistenceError, Long] =
    ZIO
      .fromOption(raw.toLongOption)
      .orElseFail(PersistenceError.QueryFailed(s"parse_$entity", s"Invalid $entity id: '$raw'"))

  private def importIssuesFromConfiguredFolder: IO[PersistenceError, Int] =
    for
      setting <-
        taskRepository
          .getSetting("issues.importFolder")
          .flatMap(opt =>
            ZIO
              .fromOption(opt.map(_.value.trim).filter(_.nonEmpty))
              .orElseFail(PersistenceError.QueryFailed("settings", "'issues.importFolder' is empty or missing"))
          )
      folder  <- ZIO
                   .attempt(Paths.get(setting))
                   .mapError(e => PersistenceError.QueryFailed("issues.importFolder", e.getMessage))
      files   <- ZIO
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
                   .mapError(e => PersistenceError.QueryFailed("issues.importFolder", e.getMessage))
      now     <- Clock.instant
      created <- ZIO.foreach(files) { file =>
                   for
                     markdown <- ZIO
                                   .attemptBlocking(Files.readString(file, StandardCharsets.UTF_8))
                                   .mapError(e => PersistenceError.QueryFailed(file.toString, e.getMessage))
                     event     = parseMarkdownIssue(file, markdown, now)
                     _        <- issueRepository.append(event).mapError(mapIssueRepoError)
                   yield ()
                 }
    yield created.size

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
