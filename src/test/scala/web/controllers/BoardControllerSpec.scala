package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import board.boundary.*
import board.control.*
import board.entity.*
import conversation.control.AgentDialogueCoordinator
import conversation.entity.*
import project.control.ProjectStorageService
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, ConversationId }
import shared.testfixtures.*
import workspace.entity.*

object BoardControllerSpec extends ZIOSpecDefault:

  private val workspace = Workspace(
    id = "ws-1",
    projectId = shared.ids.Ids.ProjectId("test-project"),
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

    override def assignIssue(workspacePath: String, issueId: BoardIssueId, agentName: String): IO[BoardError, Unit] =
      ZIO.unit

    override def markIssueStarted(
      workspacePath: String,
      issueId: BoardIssueId,
      agentName: String,
      branchName: String,
    ): IO[BoardError, Unit] =
      ZIO.unit

    override def completeIssue(workspacePath: String, issueId: BoardIssueId, success: Boolean, details: String)
      : IO[BoardError, Unit] = ZIO.unit

    override def approveIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] = ZIO.unit

  final private case class StubIssueApprovalService(
    quickApproveRef: Ref[List[(String, BoardIssueId, String)]],
    reworkRef: Ref[List[(String, BoardIssueId, String, String)]],
  ) extends IssueApprovalService:
    override def quickApprove(workspaceId: String, issueId: BoardIssueId, reviewerNotes: String): IO[BoardError, Unit] =
      quickApproveRef.update(_ :+ ((workspaceId, issueId, reviewerNotes)))

    override def reworkIssue(
      workspaceId: String,
      issueId: BoardIssueId,
      reworkComment: String,
      actor: String,
    ): IO[BoardError, Unit] =
      reworkRef.update(_ :+ ((workspaceId, issueId, reworkComment, actor)))

  final private case class StubIssueTimelineService(entries: List[board.entity.TimelineEntry])
    extends IssueTimelineService:
    override def buildTimeline(
      workspaceId: String,
      issueId: BoardIssueId,
    ): IO[shared.errors.PersistenceError, board.entity.IssueContext] =
      ZIO.succeed(board.entity.IssueContext(entries, Nil, Nil))

  private object StubProjectStorageService extends ProjectStorageService:
    override def initProjectStorage(projectId: shared.ids.Ids.ProjectId)
      : IO[shared.errors.PersistenceError, java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get("/tmp/test-project"))
    override def projectRoot(projectId: shared.ids.Ids.ProjectId): UIO[java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get("/tmp/test-project"))
    override def boardPath(projectId: shared.ids.Ids.ProjectId): UIO[java.nio.file.Path]   =
      ZIO.succeed(java.nio.file.Paths.get("/tmp/test-project/.board"))
    override def workspaceAnalysisPath(projectId: shared.ids.Ids.ProjectId, workspaceId: String)
      : UIO[java.nio.file.Path] =
      ZIO.succeed(java.nio.file.Paths.get(s"/tmp/test-project/workspaces/$workspaceId/.llm4zio/analysis"))

  private object NoOpAgentDialogueCoordinator extends AgentDialogueCoordinator:
    def startDialogue(
      issueId: BoardIssueId,
      initiator: AgentParticipant,
      respondent: AgentParticipant,
      topic: String,
      openingMessage: String,
    ): IO[PersistenceError, ConversationId] = ZIO.succeed(ConversationId("conv-stub"))
    def respondInDialogue(conversationId: ConversationId, agentName: String, message: String)
      : IO[PersistenceError, Unit] = ZIO.unit
    def humanIntervene(conversationId: ConversationId, userId: String, message: String)
      : IO[PersistenceError, Unit] = ZIO.unit
    def concludeDialogue(conversationId: ConversationId, outcome: DialogueOutcome)
      : IO[PersistenceError, Unit] = ZIO.unit
    def currentTurn(conversationId: ConversationId): IO[PersistenceError, TurnState]                =
      ZIO.fail(PersistenceError.NotFound("TurnState", conversationId.value))
    def awaitTurn(conversationId: ConversationId, agentName: String): IO[PersistenceError, Message] =
      ZIO.fail(PersistenceError.NotFound("TurnState", conversationId.value))

  private def controller(
    repo: BoardRepository,
    issueApprovalService: IssueApprovalService,
    issueTimelineService: IssueTimelineService,
  ): BoardControllerLive =
    BoardControllerLive(
      repo,
      StubBoardOrchestrator,
      StubWorkspaceRepository.single(workspace),
      IssueMarkdownParserLive(),
      StubProjectStorageService,
      issueApprovalService,
      issueTimelineService,
      NoOpAgentDialogueCoordinator,
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("BoardControllerSpec")(
    test("supports create, move, update and delete endpoints") {
      for
        state           <- Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]])
        quickApproveRef <- Ref.make(List.empty[(String, BoardIssueId, String)])
        reworkRef       <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
        ctrl             = controller(
                             InMemoryBoardRepo(state),
                             StubIssueApprovalService(quickApproveRef, reworkRef),
                             StubIssueTimelineService(Nil),
                           )
        create           =
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
        createReq        = Request.post("/board/ws-1/issues", Body.fromString(create))
        createRes       <- ctrl.routes.runZIO(createReq)
        _               <- TestClock.adjust(1.millis)
        moveReq          = Request.put("/board/ws-1/issues/test-issue-1/move", Body.fromString("""{"toColumn":"todo"}"""))
        moveRes         <- ctrl.routes.runZIO(moveReq)
        updateReq        = Request.put(
                             "/board/ws-1/issues/test-issue-1",
                             Body.fromString("""{"title":"Renamed issue","priority":"medium"}"""),
                           )
        updateRes       <- ctrl.routes.runZIO(updateReq)
        getRes          <- ctrl.routes.runZIO(Request.get("/board/ws-1/issues/test-issue-1"))
        delRes          <- ctrl.routes.runZIO(Request.delete("/board/ws-1/issues/test-issue-1"))
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
        state           <- Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]])
        quickApproveRef <- Ref.make(List.empty[(String, BoardIssueId, String)])
        reworkRef       <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
        ctrl             = controller(
                             InMemoryBoardRepo(state),
                             StubIssueApprovalService(quickApproveRef, reworkRef),
                             StubIssueTimelineService(Nil),
                           )
        resp            <- ctrl.routes.runZIO(Request.post("/board/ws-1/dispatch", Body.empty))
        body            <- ZIO.scoped(resp.body.asString)
      yield assertTrue(
        resp.status == Status.Ok,
        body.contains("dispatchedIssueIds"),
        body.contains("issue-a"),
      )
    },
    test("HTMX mutation returns board fragment HTML") {
      for
        state           <- Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]])
        quickApproveRef <- Ref.make(List.empty[(String, BoardIssueId, String)])
        reworkRef       <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
        ctrl             = controller(
                             InMemoryBoardRepo(state),
                             StubIssueApprovalService(quickApproveRef, reworkRef),
                             StubIssueTimelineService(Nil),
                           )
        req              = Request(
                             method = Method.POST,
                             url = URL.decode("/board/ws-1/issues").toOption.get,
                             headers = Headers(Header.Custom("HX-Request", "true")),
                             body = Body.fromString("title=From+Form&body=Body+From+Form"),
                           )
        resp            <- ctrl.routes.runZIO(req)
        body            <- ZIO.scoped(resp.body.asString)
      yield assertTrue(
        resp.status == Status.Ok,
        body.contains("ab-board-column"), // Fizzy layout: columns are custom elements
      )
    },
    test("issue detail route renders timeline view instead of markdown detail page") {
      val issue    = BoardIssue(
        frontmatter = IssueFrontmatter(
          id = BoardIssueId("timeline-1"),
          title = "Timeline issue",
          priority = IssuePriority.High,
          assignedAgent = Some("codex"),
          requiredCapabilities = Nil,
          blockedBy = Nil,
          tags = List("timeline"),
          acceptanceCriteria = Nil,
          estimate = None,
          proofOfWork = Nil,
          transientState = TransientState.None,
          branchName = Some("agent/timeline-1"),
          failureReason = None,
          completedAt = None,
          createdAt = Instant.parse("2026-03-20T10:00:00Z"),
        ),
        body = "Render the new timeline page",
        column = BoardColumn.Review,
        directoryPath = "/tmp/test-project/.board/review/timeline-1",
      )
      val timeline = List(
        board.entity.TimelineEntry.IssueCreated(
          BoardIssueId("timeline-1"),
          "Timeline issue",
          "Render the new timeline page",
          IssuePriority.High,
          List("timeline"),
          Instant.parse("2026-03-20T10:00:00Z"),
        )
      )
      for
        state           <- Ref.make(Map("/tmp/test-project" -> Map(BoardIssueId("timeline-1") -> issue)))
        quickApproveRef <- Ref.make(List.empty[(String, BoardIssueId, String)])
        reworkRef       <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
        ctrl             = controller(
                             InMemoryBoardRepo(state),
                             StubIssueApprovalService(quickApproveRef, reworkRef),
                             StubIssueTimelineService(timeline),
                           )
        resp            <- ctrl.routes.runZIO(Request.get("/board/ws-1/issues/timeline-1"))
        body            <- ZIO.scoped(resp.body.asString)
      yield assertTrue(
        resp.status == Status.Ok,
        body.contains("Timeline issue"),
        body.contains("Review action"),
        body.contains("Open"),
      )
    },
    test("quick-approve route delegates to IssueApprovalService") {
      for
        state           <- Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]])
        quickApproveRef <- Ref.make(List.empty[(String, BoardIssueId, String)])
        reworkRef       <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
        ctrl             = controller(
                             InMemoryBoardRepo(state),
                             StubIssueApprovalService(quickApproveRef, reworkRef),
                             StubIssueTimelineService(Nil),
                           )
        req              = Request.post(
                             "/board/ws-1/issues/timeline-1/quick-approve",
                             Body.fromString("notes=Looks+good"),
                           )
        resp            <- ctrl.routes.runZIO(req)
        recorded        <- quickApproveRef.get
      yield assertTrue(
        resp.status == Status.TemporaryRedirect,
        recorded == List(("ws-1", BoardIssueId("timeline-1"), "Looks good")),
      )
    },
    test("rework route delegates to IssueApprovalService with web actor") {
      for
        state           <- Ref.make(Map.empty[String, Map[BoardIssueId, BoardIssue]])
        quickApproveRef <- Ref.make(List.empty[(String, BoardIssueId, String)])
        reworkRef       <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
        ctrl             = controller(
                             InMemoryBoardRepo(state),
                             StubIssueApprovalService(quickApproveRef, reworkRef),
                             StubIssueTimelineService(Nil),
                           )
        req              = Request.post(
                             "/board/ws-1/issues/timeline-1/rework",
                             Body.fromString("comment=Needs+another+pass"),
                           )
        resp            <- ctrl.routes.runZIO(req)
        recorded        <- reworkRef.get
      yield assertTrue(
        resp.status == Status.TemporaryRedirect,
        recorded == List(("ws-1", BoardIssueId("timeline-1"), "Needs another pass", "web")),
      )
    },
  )
