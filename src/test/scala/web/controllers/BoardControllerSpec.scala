package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import board.boundary.*
import board.control.*
import board.entity.*
import shared.ids.Ids.BoardIssueId
import workspace.entity.*

object BoardControllerSpec extends ZIOSpecDefault:

  private val workspace = Workspace(
    id = "ws-1",
    name = "Gateway",
    localPath = "/tmp/ws-1",
    defaultAgent = Some("codex"),
    description = None,
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "codex",
    createdAt = Instant.parse("2026-03-20T10:00:00Z"),
    updatedAt = Instant.parse("2026-03-20T10:00:00Z"),
  )

  final private case class InMemoryBoardRepo(ref: Ref[Map[String, Map[BoardIssueId, BoardIssue]]])
    extends BoardRepository:
    override def initBoard(workspacePath: String): IO[BoardError, Unit] =
      ref.update(current => current.updatedWith(workspacePath)(existing => Some(existing.getOrElse(Map.empty)))).unit

    override def readBoard(workspacePath: String): IO[BoardError, Board] =
      ref.get.map { state =>
        val byId = state.getOrElse(workspacePath, Map.empty)
        val all  = byId.values.toList
        Board(workspacePath, BoardColumn.values.map(column => column -> all.filter(_.column == column)).toMap)
      }

    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue] =
      ref.get.flatMap { state =>
        ZIO
          .fromOption(state.getOrElse(workspacePath, Map.empty).get(issueId))
          .orElseFail(BoardError.IssueNotFound(issueId.value))
      }

    override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue)
      : IO[BoardError, BoardIssue] =
      ref.modify { state =>
        val byId = state.getOrElse(workspacePath, Map.empty)
        if byId.contains(issue.frontmatter.id) then
          (Left(BoardError.IssueAlreadyExists(issue.frontmatter.id.value)), state)
        else
          val created = issue.copy(column = column)
          (Right(created), state.updated(workspacePath, byId.updated(issue.frontmatter.id, created)))
      }.flatMap(ZIO.fromEither(_))

    override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
      : IO[BoardError, BoardIssue] =
      ref.modify { state =>
        val byId = state.getOrElse(workspacePath, Map.empty)
        byId.get(issueId) match
          case None        => (Left(BoardError.IssueNotFound(issueId.value)), state)
          case Some(issue) =>
            val moved = issue.copy(column = toColumn)
            (Right(moved), state.updated(workspacePath, byId.updated(issueId, moved)))
      }.flatMap(ZIO.fromEither(_))

    override def updateIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      update: IssueFrontmatter => IssueFrontmatter,
    ): IO[BoardError, BoardIssue] =
      ref.modify { state =>
        val byId = state.getOrElse(workspacePath, Map.empty)
        byId.get(issueId) match
          case None        => (Left(BoardError.IssueNotFound(issueId.value)), state)
          case Some(issue) =>
            val updated = issue.copy(frontmatter = update(issue.frontmatter))
            (Right(updated), state.updated(workspacePath, byId.updated(issueId, updated)))
      }.flatMap(ZIO.fromEither(_))

    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
      ref.update(state => state.updated(workspacePath, state.getOrElse(workspacePath, Map.empty) - issueId)).unit

    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      readBoard(workspacePath).map(_.columns.getOrElse(column, Nil))

    override def invalidateWorkspace(workspacePath: String): UIO[Unit] = ZIO.unit

  private object StubBoardOrchestrator extends BoardOrchestrator:
    override def dispatchCycle(workspacePath: String): IO[BoardError, DispatchResult] =
      ZIO.succeed(DispatchResult(List(BoardIssueId("issue-a")), List(BoardIssueId("issue-b"))))

    override def completeIssue(workspacePath: String, issueId: BoardIssueId, success: Boolean, details: String)
      : IO[BoardError, Unit] = ZIO.unit

  private object StubWorkspaceRepository extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[shared.errors.PersistenceError, Unit]                      = ZIO.dieMessage("unused")
    override def list: IO[shared.errors.PersistenceError, List[Workspace]]                                    = ZIO.succeed(List(workspace))
    override def get(id: String): IO[shared.errors.PersistenceError, Option[Workspace]]                       =
      ZIO.succeed(Option.when(id == workspace.id)(workspace))
    override def delete(id: String): IO[shared.errors.PersistenceError, Unit]                                 = ZIO.dieMessage("unused")
    override def appendRun(event: WorkspaceRunEvent): IO[shared.errors.PersistenceError, Unit]                =
      ZIO.dieMessage("unused")
    override def listRuns(workspaceId: String): IO[shared.errors.PersistenceError, List[WorkspaceRun]]        =
      ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[shared.errors.PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[shared.errors.PersistenceError, Option[WorkspaceRun]]                 = ZIO.succeed(None)

  private def controller(repo: BoardRepository): BoardControllerLive =
    BoardControllerLive(repo, StubBoardOrchestrator, StubWorkspaceRepository, IssueMarkdownParserLive())

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("BoardControllerSpec")(
    test("supports create, move, update and delete endpoints") {
      for
        state     <- Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]])
        ctrl       = controller(InMemoryBoardRepo(state))
        create     =
          """
            |{
            |  "id":"test-issue-1",
            |  "title":"First issue",
            |  "body":"Implement feature",
            |  "column":"backlog",
            |  "priority":"high",
            |  "tags":["feat"]
            |}
            |""".stripMargin
        createReq  = Request.post("/board/ws-1/issues", Body.fromString(create))
        createRes <- ctrl.routes.runZIO(createReq)
        _         <- TestClock.adjust(1.millis)
        moveReq    = Request.put("/board/ws-1/issues/test-issue-1/move", Body.fromString("""{"toColumn":"todo"}"""))
        moveRes   <- ctrl.routes.runZIO(moveReq)
        updateReq  = Request.put(
                       "/board/ws-1/issues/test-issue-1",
                       Body.fromString("""{"title":"Renamed issue","priority":"medium"}"""),
                     )
        updateRes <- ctrl.routes.runZIO(updateReq)
        getRes    <- ctrl.routes.runZIO(Request.get("/board/ws-1/issues/test-issue-1"))
        delRes    <- ctrl.routes.runZIO(Request.delete("/board/ws-1/issues/test-issue-1"))
      yield assertTrue(
        createRes.status == Status.Created,
        moveRes.status == Status.Ok,
        updateRes.status == Status.Ok,
        getRes.status == Status.Ok,
        delRes.status == Status.NoContent,
      )
    },
    test("dispatch endpoint returns DispatchResult") {
      for
        state <- Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]])
        ctrl   = controller(InMemoryBoardRepo(state))
        resp  <- ctrl.routes.runZIO(Request.post("/board/ws-1/dispatch", Body.empty))
        body  <- ZIO.scoped(resp.body.asString)
      yield assertTrue(
        resp.status == Status.Ok,
        body.contains("dispatchedIssueIds"),
        body.contains("issue-a"),
      )
    },
    test("HTMX mutation returns board fragment HTML") {
      for
        state <- Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]])
        ctrl   = controller(InMemoryBoardRepo(state))
        req    = Request(
                   method = Method.POST,
                   url = URL.decode("/board/ws-1/issues").toOption.get,
                   headers = Headers(Header.Custom("HX-Request", "true")),
                   body = Body.fromString("title=From+Form&body=Body+From+Form"),
                 )
        resp  <- ctrl.routes.runZIO(req)
        body  <- ZIO.scoped(resp.body.asString)
      yield assertTrue(
        resp.status == Status.Ok,
        body.contains("data-board-column"),
      )
    },
  )
