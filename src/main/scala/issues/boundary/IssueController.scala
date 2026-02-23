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
import models.*
import orchestration.control.{ AgentRegistry, IssueAssignmentOrchestrator }
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }

trait IssueController:
  def routes: Routes[Any, Response]

object IssueController:

  def routes: ZIO[IssueController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[IssueController](_.routes)

  val live: ZLayer[ChatRepository & TaskRepository & IssueAssignmentOrchestrator, Nothing, IssueController] =
    ZLayer.fromFunction(IssueControllerLive.apply)

final case class IssueControllerLive(
  chatRepository: ChatRepository,
  taskRepository: TaskRepository,
  issueAssignmentOrchestrator: IssueAssignmentOrchestrator,
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
        yield html(HtmlViews.issuesView(runId, filtered, statusFilter, query, tagFilter))
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
          issue    = AgentIssue(
                       runId = form.get("runId").map(_.trim).filter(_.nonEmpty),
                       title = title,
                       description = content,
                       issueType = form.get("issueType").map(_.trim).filter(_.nonEmpty).getOrElse("task"),
                       tags = form.get("tags").map(_.trim).filter(_.nonEmpty),
                       preferredAgent = form.get("preferredAgent").map(_.trim).filter(_.nonEmpty),
                       contextPath = form.get("contextPath").map(_.trim).filter(_.nonEmpty),
                       sourceFolder = form.get("sourceFolder").map(_.trim).filter(_.nonEmpty),
                       priority = parsePriority(form.get("priority").getOrElse("medium")),
                       createdAt = now,
                       updatedAt = now,
                     )
          _       <- chatRepository.createIssue(issue)
          redirect = issue.runId.map(id => s"/issues?run_id=$id").getOrElse("/issues")
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
          issueId        <- parseLongId("issue", id)
          issue          <- chatRepository
                              .getIssue(issueId)
                              .someOrFail(PersistenceError.NotFound("issue", issueId))
          assignments    <- chatRepository.listAssignmentsByIssue(issueId)
          customAgents   <- taskRepository.listCustomAgents
          enabledCustom   = customAgents.filter(_.enabled)
          availableAgents = AgentRegistry.allAgents(enabledCustom).filter(_.usesAI)
        yield html(HtmlViews.issueDetail(issue, assignments, availableAgents))
      }
    },
    Method.POST / "issues" / string("id") / "assign"               -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          issueId   <- parseLongId("issue", id)
          form      <- parseForm(req)
          agentName <- required(form, "agentName")
          updated   <- issueAssignmentOrchestrator.assignIssue(issueId, agentName)
          redirectTo = updated.conversationId.map(cid => s"/chat/$cid").getOrElse(s"/issues/$issueId")
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", redirectTo)),
        )
      }
    },
    Method.POST / "api" / "issues"                                 -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body         <- req.body.asString.mapError(err =>
                            PersistenceError.QueryFailed("request_body", err.getMessage)
                          )
          issueRequest <- ZIO
                            .fromEither(body.fromJson[AgentIssueCreateRequest])
                            .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          now          <- Clock.instant
          issue         = AgentIssue(
                            runId = issueRequest.runId,
                            conversationId = issueRequest.conversationId,
                            title = issueRequest.title,
                            description = issueRequest.description,
                            issueType = issueRequest.issueType,
                            tags = issueRequest.tags,
                            preferredAgent = issueRequest.preferredAgent,
                            contextPath = issueRequest.contextPath,
                            sourceFolder = issueRequest.sourceFolder,
                            priority = issueRequest.priority,
                            createdAt = now,
                            updatedAt = now,
                          )
          issueId      <- chatRepository.createIssue(issue)
          created      <- chatRepository
                            .getIssue(issueId)
                            .someOrFail(PersistenceError.NotFound("issue", issueId))
        yield Response.json(created.toJson)
      }
    },
    Method.GET / "api" / "issues"                                  -> handler { (req: Request) =>
      val runId = req.queryParam("run_id").map(_.trim).filter(_.nonEmpty)
      ErrorHandlingMiddleware.fromPersistence {
        runId match
          case Some(value) =>
            for
              parsed <- parseLongId("run", value)
              issues <- chatRepository.listIssuesByRun(parsed)
            yield Response.json(issues.toJson)
          case None        => chatRepository.listIssues(0, 500).map(issues => Response.json(issues.toJson))
      }
    },
    Method.GET / "api" / "issues" / string("id")                   -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          issueId     <- parseLongId("issue", id)
          issue       <- chatRepository
                           .getIssue(issueId)
                           .someOrFail(PersistenceError.NotFound("issue", issueId))
          assignments <- chatRepository.listAssignmentsByIssue(issueId)
        yield Response.json((issue, assignments).toJson)
      }
    },
    Method.PATCH / "api" / "issues" / string("id") / "assign"      -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          issueId       <- parseLongId("issue", id)
          body          <- req.body.asString.mapError(err =>
                             PersistenceError.QueryFailed("request_body", err.getMessage)
                           )
          assignRequest <- ZIO
                             .fromEither(body.fromJson[AssignIssueRequest])
                             .mapError(err => PersistenceError.QueryFailed("json_parse", err))
          updated       <- issueAssignmentOrchestrator.assignIssue(issueId, assignRequest.agentName)
        yield Response.json(updated.toJson)
      }
    },
    Method.GET / "api" / "issues" / "unassigned" / string("runId") -> handler { (runId: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          parsedRunId <- parseLongId("run", runId)
          issues      <- chatRepository.listUnassignedIssues(parsedRunId)
        yield Response.json(issues.toJson)
      }
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def loadIssues(runId: Option[String], statusFilter: Option[String]): IO[PersistenceError, List[AgentIssue]] =
    runId match
      case Some(value) =>
        for
          parsed <- parseLongId("run", value)
          issues <- chatRepository.listIssuesByRun(parsed)
        yield statusFilter match
          case Some(raw) => issues.filter(matchesStatus(_, raw))
          case None      => issues
      case None        =>
        statusFilter match
          case Some(raw) =>
            parseIssueStatus(raw) match
              case Some(status) => chatRepository.listIssuesByStatus(status)
              case None         => chatRepository.listIssues(0, 500)
          case None      => chatRepository.listIssues(0, 500)

  private def filterIssues(issues: List[AgentIssue], query: Option[String], tag: Option[String]): List[AgentIssue] =
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

  private def matchesStatus(issue: AgentIssue, statusRaw: String): Boolean =
    parseIssueStatus(statusRaw).contains(issue.status)

  private def parseIssueStatus(raw: String): Option[IssueStatus] =
    raw.trim.toLowerCase match
      case "open"        => Some(IssueStatus.Open)
      case "assigned"    => Some(IssueStatus.Assigned)
      case "in_progress" => Some(IssueStatus.InProgress)
      case "completed"   => Some(IssueStatus.Completed)
      case "failed"      => Some(IssueStatus.Failed)
      case "skipped"     => Some(IssueStatus.Skipped)
      case _             => None

  private def parsePriority(raw: String): IssuePriority =
    raw.trim.toLowerCase match
      case "low"      => IssuePriority.Low
      case "high"     => IssuePriority.High
      case "critical" => IssuePriority.Critical
      case _          => IssuePriority.Medium

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
                     issue     = parseMarkdownIssue(file, markdown, now)
                     _        <- chatRepository.createIssue(issue)
                   yield ()
                 }
    yield created.size

  private def parseMarkdownIssue(file: Path, markdown: String, now: Instant): AgentIssue =
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

    AgentIssue(
      title = title,
      description = markdown,
      issueType = metadata("type").getOrElse("task"),
      tags = metadata("tags"),
      preferredAgent = metadata("agent"),
      contextPath = metadata("context"),
      sourceFolder = metadata("source"),
      runId = metadata("run"),
      priority = parsePriority(metadata("priority").getOrElse("medium")),
      createdAt = now,
      updatedAt = now,
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
