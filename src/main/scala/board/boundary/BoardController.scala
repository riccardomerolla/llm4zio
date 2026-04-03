package board.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

import zio.*
import zio.http.*
import zio.json.*

import board.control.{
  BoardOrchestrator,
  DispatchResult,
  IssueApprovalService,
  IssueMarkdownParser,
  IssueTimelineService,
}
import board.entity.*
import project.control.ProjectStorageService
import shared.ids.Ids.BoardIssueId
import shared.web.{ BoardView, IssueTimelineView }
import workspace.entity.WorkspaceRepository

trait BoardController:
  def routes: Routes[Any, Response]

object BoardController:
  val live
    : ZLayer[
      BoardRepository & BoardOrchestrator & WorkspaceRepository & IssueMarkdownParser & ProjectStorageService &
        IssueApprovalService & IssueTimelineService,
      Nothing,
      BoardController,
    ] =
    ZLayer.fromFunction(BoardControllerLive.apply)

  def routes: ZIO[BoardController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[BoardController](_.routes)

final case class BoardControllerLive(
  boardRepository: BoardRepository,
  boardOrchestrator: BoardOrchestrator,
  workspaceRepository: WorkspaceRepository,
  issueParser: IssueMarkdownParser,
  projectStorageService: ProjectStorageService,
  issueApprovalService: IssueApprovalService,
  issueTimelineService: IssueTimelineService,
) extends BoardController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "board" / string("workspaceId")                                                   -> handler { (workspaceId: String, _: Request) =>
      renderBoardPage(workspaceId)
    },
    Method.GET / "board" / string("workspaceId") / "fragment"                                      -> handler {
      (workspaceId: String, _: Request) =>
        renderBoardFragment(workspaceId)
    },
    Method.GET / "board" / string("workspaceId") / "issues" / string("issueId")                    -> handler {
      (workspaceId: String, issueId: String, _: Request) =>
        readBoardIssueId(issueId).flatMap(id => renderIssueDetail(workspaceId, id)).catchAll(boardErrorResponse)
    },
    Method.POST / "board" / string("workspaceId") / "issues"                                       -> handler { (workspaceId: String, req: Request) =>
      createIssue(workspaceId, req).catchAll(boardErrorResponse)
    },
    Method.PUT / "board" / string("workspaceId") / "issues" / string("issueId") / "move"           -> handler {
      (workspaceId: String, issueId: String, req: Request) =>
        moveIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
    },
    Method.PUT / "board" / string("workspaceId") / "issues" / string("issueId")                    -> handler {
      (workspaceId: String, issueId: String, req: Request) =>
        updateIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
    },
    Method.DELETE / "board" / string("workspaceId") / "issues" / string("issueId")                 -> handler {
      (workspaceId: String, issueId: String, req: Request) =>
        deleteIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
    },
    Method.POST / "board" / string("workspaceId") / "dispatch"                                     -> handler {
      (workspaceId: String, req: Request) =>
        dispatch(workspaceId, req).catchAll(boardErrorResponse)
    },
    Method.POST / "board" / string("workspaceId") / "issues" / string("issueId") / "approve"       -> handler {
      (workspaceId: String, issueId: String, req: Request) =>
        approveIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
    },
    Method.POST / "board" / string("workspaceId") / "issues" / string("issueId") / "quick-approve" -> handler {
      (workspaceId: String, issueId: String, req: Request) =>
        quickApproveIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
    },
    Method.POST / "board" / string("workspaceId") / "issues" / string("issueId") / "rework"        -> handler {
      (workspaceId: String, issueId: String, req: Request) =>
        reworkIssue(workspaceId, issueId, req).catchAll(boardErrorResponse)
    },
  )

  private def renderBoardPage(workspaceId: String): UIO[Response] =
    val effect =
      for
        (workspace, projectRoot) <- resolveBoardPath(workspaceId)
        _                        <- boardRepository.initBoard(projectRoot).ignore
        board                    <- boardRepository.readBoard(projectRoot)
      yield htmlResponse(BoardView.page(workspaceId, workspace.name, workspace.localPath, board))

    effect.catchAll(boardErrorResponse)

  private def renderBoardFragment(workspaceId: String): UIO[Response] =
    (for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      board            <- boardRepository.readBoard(projectRoot)
    yield htmlResponse(BoardView.columnsFragment(workspaceId, board))).catchAll(boardErrorResponse)

  private def renderIssueDetail(workspaceId: String, issueId: BoardIssueId): IO[BoardError, Response] =
    for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      issue            <- boardRepository.readIssue(projectRoot, issueId)
      timeline         <- issueTimelineService
                            .buildTimeline(workspaceId, issueId)
                            .mapError(err => BoardError.ParseError(s"timeline lookup failed: $err"))
    yield htmlResponse(IssueTimelineView.page(workspaceId, issue, timeline))

  private def createIssue(workspaceId: String, req: Request): IO[BoardError, Response] =
    for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      request          <- parseCreateIssueRequest(req)
      issueId          <- parseOrGenerateId(request.id)
      now              <- Clock.instant
      column           <- parseColumn(request.column.getOrElse("backlog"))
      priority         <- parsePriority(Some(request.priority))
      estimate         <- parseEstimate(request.estimate)
      blockedBy        <- parseIssueIds(Some(request.blockedBy))
      created          <- boardRepository.createIssue(
                            projectRoot,
                            column,
                            BoardIssue(
                              frontmatter = IssueFrontmatter(
                                id = issueId,
                                title = request.title.trim,
                                priority = priority,
                                assignedAgent = request.assignedAgent.map(_.trim).filter(_.nonEmpty),
                                requiredCapabilities = request.requiredCapabilities.map(_.trim).filter(_.nonEmpty).distinct,
                                blockedBy = blockedBy,
                                tags = request.tags.map(_.trim).filter(_.nonEmpty).distinct,
                                acceptanceCriteria = request.acceptanceCriteria.map(_.trim).filter(_.nonEmpty),
                                estimate = estimate,
                                proofOfWork = Nil,
                                transientState = TransientState.None,
                                branchName = None,
                                failureReason = None,
                                completedAt = None,
                                createdAt = now,
                              ),
                              body = request.body.trim,
                              column = column,
                              directoryPath = "",
                            ),
                          )
      response         <-
        if isHtmx(req) then renderBoardFragment(workspaceId)
        else ZIO.succeed(Response.json(created.toJson).copy(status = Status.Created))
    yield response

  private def moveIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
    for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      issueId          <- readBoardIssueId(issueIdRaw)
      request          <- parseMoveRequest(req)
      toColumn         <- parseColumn(request.toColumn)
      moved            <- boardRepository.moveIssue(projectRoot, issueId, toColumn)
      response         <- if isHtmx(req) then renderBoardFragment(workspaceId) else ZIO.succeed(Response.json(moved.toJson))
    yield response

  private def updateIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
    for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      issueId          <- readBoardIssueId(issueIdRaw)
      request          <- parseUpdateIssueRequest(req)
      blockedBy        <- request.blockedBy match
                            case Some(values) => parseIssueIds(Some(values)).map(Some(_))
                            case None         => ZIO.succeed(None)
      priority         <- request.priority match
                            case Some(value) => parsePriority(Some(value)).map(Some(_))
                            case None        => ZIO.succeed(None)
      estimate         <- request.estimate match
                            case Some(value) => parseEstimate(Some(value)).map(Some(_))
                            case None        => ZIO.succeed(None)
      updated          <- boardRepository.updateIssue(
                            projectRoot,
                            issueId,
                            fm =>
                              fm.copy(
                                title = request.title.map(_.trim).filter(_.nonEmpty).getOrElse(fm.title),
                                priority = priority.getOrElse(fm.priority),
                                assignedAgent = request.assignedAgent match
                                  case Some(agent) => Option(agent).map(_.trim).filter(_.nonEmpty)
                                  case None        => fm.assignedAgent,
                                requiredCapabilities = request.requiredCapabilities
                                  .map(_.map(_.trim).filter(_.nonEmpty).distinct)
                                  .getOrElse(fm.requiredCapabilities),
                                blockedBy = blockedBy.getOrElse(fm.blockedBy),
                                tags = request.tags.map(_.map(_.trim).filter(_.nonEmpty).distinct).getOrElse(fm.tags),
                                acceptanceCriteria = request.acceptanceCriteria
                                  .map(_.map(_.trim).filter(_.nonEmpty))
                                  .getOrElse(fm.acceptanceCriteria),
                                estimate = estimate.flatMap(identity).orElse(fm.estimate),
                                proofOfWork = request.proofOfWork.map(_.map(_.trim).filter(_.nonEmpty)).getOrElse(fm.proofOfWork),
                              ),
                          )
      response         <- if isHtmx(req) then renderBoardFragment(workspaceId) else ZIO.succeed(Response.json(updated.toJson))
    yield response

  private def deleteIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
    for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      issueId          <- readBoardIssueId(issueIdRaw)
      _                <- boardRepository.deleteIssue(projectRoot, issueId)
      response         <-
        if isHtmx(req) then renderBoardFragment(workspaceId) else ZIO.succeed(Response(status = Status.NoContent))
    yield response

  private def dispatch(workspaceId: String, req: Request): IO[BoardError, Response] =
    for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      result           <- boardOrchestrator.dispatchCycle(projectRoot)
      encoded           = encodeDispatchResult(result)
      response         <-
        if isHtmx(req) then
          renderBoardFragment(workspaceId).map(_.addHeaders(Headers(Header.Custom("X-Dispatch-Result", encoded))))
        else ZIO.succeed(Response.json(encoded))
    yield response

  private def approveIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
    for
      (_, projectRoot) <- resolveBoardPath(workspaceId)
      issueId          <- readBoardIssueId(issueIdRaw)
      _                <- boardOrchestrator.approveIssue(projectRoot, issueId)
      response         <-
        if isHtmx(req) then renderBoardFragment(workspaceId)
        else ZIO.succeed(Response.redirect(URL.decode(s"/board/$workspaceId").toOption.getOrElse(URL.root)))
    yield response

  private def quickApproveIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
    for
      issueId  <- readBoardIssueId(issueIdRaw)
      form     <- req.body.asString
                    .mapError(err => BoardError.ParseError(err.getMessage))
                    .flatMap(parseForm)
      notes     = form.get("notes").orElse(form.get("comment")).map(_.trim).getOrElse("")
      _        <- issueApprovalService.quickApprove(workspaceId, issueId, notes)
      response <- ZIO.succeed(htmxRedirect(req, "/board"))
    yield response

  private def reworkIssue(workspaceId: String, issueIdRaw: String, req: Request): IO[BoardError, Response] =
    for
      issueId  <- readBoardIssueId(issueIdRaw)
      form     <- req.body.asString
                    .mapError(err => BoardError.ParseError(err.getMessage))
                    .flatMap(parseForm)
      comment   =
        form.get("comment").orElse(form.get("notes")).map(_.trim).filter(_.nonEmpty).getOrElse("Rework requested")
      _        <- issueApprovalService.reworkIssue(workspaceId, issueId, comment, actor = "web")
      response <- ZIO.succeed(htmxRedirect(req, "/board"))
    yield response

  private def resolveBoardPath(workspaceId: String): IO[BoardError, (workspace.entity.Workspace, String)] =
    for
      ws          <- resolveWorkspace(workspaceId)
      projectRoot <- projectStorageService.projectRoot(ws.projectId).map(_.toString)
    yield (ws, projectRoot)

  private def resolveWorkspace(workspaceId: String): IO[BoardError, workspace.entity.Workspace] =
    workspaceRepository
      .get(workspaceId)
      .mapError(err => BoardError.ParseError(s"workspace lookup failed: $err"))
      .flatMap(value => ZIO.fromOption(value).orElseFail(BoardError.BoardNotFound(workspaceId)))

  private def parseCreateIssueRequest(req: Request): IO[BoardError, CreateBoardIssueRequest] =
    parseRequest(req, parseCreateIssueFromForm)

  private def parseMoveRequest(req: Request): IO[BoardError, MoveBoardIssueRequest] =
    parseRequest(req, parseMoveRequestFromForm)

  private def parseUpdateIssueRequest(req: Request): IO[BoardError, UpdateBoardIssueRequest] =
    parseRequest(req, parseUpdateRequestFromForm)

  private def parseRequest[A: JsonCodec](
    req: Request,
    formDecoder: Map[String, String] => IO[BoardError, A],
  ): IO[BoardError, A] =
    req.body.asString
      .mapError(err => BoardError.ParseError(err.getMessage))
      .flatMap { raw =>
        val trimmed = raw.trim
        if trimmed.startsWith("{") then
          ZIO.fromEither(trimmed.fromJson[A]).mapError(err => BoardError.ParseError(s"Invalid JSON payload: $err"))
        else parseForm(raw).flatMap(formDecoder)
      }

  private def parseCreateIssueFromForm(form: Map[String, String]): IO[BoardError, CreateBoardIssueRequest] =
    for
      title <- requiredField(form, "title")
      body   = form.get("body").orElse(form.get("description")).map(_.trim).getOrElse("")
      _     <- ZIO.fail(BoardError.ParseError("body is required")).when(body.isEmpty)
    yield CreateBoardIssueRequest(
      id = form.get("id").map(_.trim).filter(_.nonEmpty),
      title = title,
      body = body,
      column = form.get("column").map(_.trim).filter(_.nonEmpty),
      priority = form.get("priority").map(_.trim).filter(_.nonEmpty).getOrElse("medium"),
      assignedAgent = form.get("assignedAgent"),
      requiredCapabilities = parseCsv(form.get("requiredCapabilities")),
      blockedBy = parseCsv(form.get("blockedBy")),
      tags = parseCsv(form.get("tags")),
      acceptanceCriteria = parseCsv(form.get("acceptanceCriteria")),
      estimate = form.get("estimate").map(_.trim).filter(_.nonEmpty),
    )

  private def parseMoveRequestFromForm(form: Map[String, String]): IO[BoardError, MoveBoardIssueRequest] =
    requiredField(form, "toColumn").map(MoveBoardIssueRequest.apply)

  private def parseUpdateRequestFromForm(form: Map[String, String]): IO[BoardError, UpdateBoardIssueRequest] =
    ZIO.succeed(
      UpdateBoardIssueRequest(
        title = form.get("title").map(_.trim).filter(_.nonEmpty),
        priority = form.get("priority").map(_.trim).filter(_.nonEmpty),
        assignedAgent = form.get("assignedAgent"),
        requiredCapabilities = form.get("requiredCapabilities").map(value => parseCsv(Some(value))),
        blockedBy = form.get("blockedBy").map(value => parseCsv(Some(value))),
        tags = form.get("tags").map(value => parseCsv(Some(value))),
        acceptanceCriteria = form.get("acceptanceCriteria").map(value => parseCsv(Some(value))),
        estimate = form.get("estimate").map(_.trim).filter(_.nonEmpty),
        proofOfWork = form.get("proofOfWork").map(value => parseCsv(Some(value))),
      )
    )

  private def parseForm(rawBody: String): IO[BoardError, Map[String, String]] =
    ZIO.attempt {
      rawBody
        .split("&")
        .toList
        .flatMap { kv =>
          kv.split("=", 2).toList match
            case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
            case key :: Nil          => Some(urlDecode(key) -> "")
            case _                   => None
        }
        .toMap
    }.mapError(err => BoardError.ParseError(err.getMessage))

  private def requiredField(form: Map[String, String], name: String): IO[BoardError, String] =
    form.get(name).map(_.trim).filter(_.nonEmpty) match
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(BoardError.ParseError(s"$name is required"))

  private def parseOrGenerateId(id: Option[String]): IO[BoardError, BoardIssueId] =
    val candidate = id.map(_.trim).filter(_.nonEmpty).getOrElse(s"issue-${UUID.randomUUID().toString.take(8)}")
    readBoardIssueId(candidate)

  private def readBoardIssueId(raw: String): IO[BoardError, BoardIssueId] =
    ZIO.fromEither(BoardIssueId.fromString(raw)).mapError(err => BoardError.ParseError(err))

  private def parseIssueIds(raw: Option[List[String]]): IO[BoardError, List[BoardIssueId]] =
    raw match
      case None         => ZIO.succeed(Nil)
      case Some(values) => ZIO.foreach(values.filter(_.trim.nonEmpty))(readBoardIssueId)

  private def parseColumn(value: String): IO[BoardError, BoardColumn] =
    val normalized = value.trim.toLowerCase.replace('_', '-') match
      case "inprogress" => "in-progress"
      case other        => other

    BoardColumn.fromFolderName(normalized) match
      case Some(column) => ZIO.succeed(column)
      case None         => ZIO.fail(BoardError.InvalidColumn(value))

  private def parsePriority(raw: Option[String]): IO[BoardError, IssuePriority] =
    raw.map(_.trim.toLowerCase).filter(_.nonEmpty) match
      case Some("critical") => ZIO.succeed(IssuePriority.Critical)
      case Some("high")     => ZIO.succeed(IssuePriority.High)
      case Some("medium")   => ZIO.succeed(IssuePriority.Medium)
      case Some("low")      => ZIO.succeed(IssuePriority.Low)
      case Some(other)      => ZIO.fail(BoardError.ParseError(s"Unsupported priority '$other'"))
      case None             => ZIO.succeed(IssuePriority.Medium)

  private def parseEstimate(raw: Option[String]): IO[BoardError, Option[IssueEstimate]] =
    raw.map(_.trim.toUpperCase).filter(_.nonEmpty) match
      case None        => ZIO.succeed(None)
      case Some("XS")  => ZIO.succeed(Some(IssueEstimate.XS))
      case Some("S")   => ZIO.succeed(Some(IssueEstimate.S))
      case Some("M")   => ZIO.succeed(Some(IssueEstimate.M))
      case Some("L")   => ZIO.succeed(Some(IssueEstimate.L))
      case Some("XL")  => ZIO.succeed(Some(IssueEstimate.XL))
      case Some(other) => ZIO.fail(BoardError.ParseError(s"Unsupported estimate '$other'"))

  private def isHtmx(req: Request): Boolean =
    req.headers.headers.exists { header =>
      header.headerName.toString.equalsIgnoreCase("HX-Request") && header.renderedValue.equalsIgnoreCase("true")
    }

  private def parseCsv(value: Option[String]): List[String] =
    value
      .toList
      .flatMap(_.split(",").toList)
      .map(_.trim)
      .filter(_.nonEmpty)
      .distinct

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def encodeDispatchResult(result: DispatchResult): String =
    val dispatched = result.dispatchedIssueIds.map(_.value.toJson).mkString(",")
    val skipped    = result.skippedIssueIds.map(_.value.toJson).mkString(",")
    s"""{"dispatchedIssueIds":[$dispatched],"skippedIssueIds":[$skipped]}"""

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def htmxRedirect(req: Request, path: String): Response =
    if isHtmx(req) then Response.ok.addHeader(Header.Custom("HX-Redirect", path))
    else Response.redirect(URL.decode(path).toOption.getOrElse(URL.root))

  private def boardErrorResponse(error: BoardError): UIO[Response] =
    error match
      case BoardError.BoardNotFound(path)            =>
        ZIO.succeed(Response.text(s"Board not found: $path").status(Status.NotFound))
      case BoardError.IssueNotFound(issueId)         =>
        ZIO.succeed(Response.text(s"Issue not found: $issueId").status(Status.NotFound))
      case BoardError.IssueAlreadyExists(issueId)    =>
        ZIO.succeed(Response.text(s"Issue already exists: $issueId").status(Status.Conflict))
      case BoardError.InvalidColumn(value)           =>
        ZIO.succeed(Response.text(s"Invalid column: $value").status(Status.BadRequest))
      case BoardError.DependencyCycle(issueIds)      =>
        ZIO.succeed(Response.text(s"Dependency cycle: ${issueIds.mkString(",")}").status(Status.UnprocessableEntity))
      case BoardError.ConcurrencyConflict(message)   => ZIO.succeed(Response.text(message).status(Status.Conflict))
      case BoardError.ParseError(message)            => ZIO.succeed(Response.text(message).status(Status.BadRequest))
      case BoardError.WriteError(path, message)      =>
        ZIO.succeed(Response.text(s"Write error at $path: $message").status(Status.InternalServerError))
      case BoardError.GitOperationFailed(op, reason) =>
        ZIO.succeed(Response.text(s"$op failed: $reason").status(Status.InternalServerError))

final case class CreateBoardIssueRequest(
  id: Option[String] = None,
  title: String,
  body: String,
  column: Option[String] = None,
  priority: String = "medium",
  assignedAgent: Option[String] = None,
  requiredCapabilities: List[String] = Nil,
  blockedBy: List[String] = Nil,
  tags: List[String] = Nil,
  acceptanceCriteria: List[String] = Nil,
  estimate: Option[String] = None,
) derives JsonCodec

final case class MoveBoardIssueRequest(toColumn: String) derives JsonCodec

final case class UpdateBoardIssueRequest(
  title: Option[String] = None,
  priority: Option[String] = None,
  assignedAgent: Option[String] = None,
  requiredCapabilities: Option[List[String]] = None,
  blockedBy: Option[List[String]] = None,
  tags: Option[List[String]] = None,
  acceptanceCriteria: Option[List[String]] = None,
  estimate: Option[String] = None,
  proofOfWork: Option[List[String]] = None,
) derives JsonCodec
