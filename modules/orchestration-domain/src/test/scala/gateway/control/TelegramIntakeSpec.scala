package gateway.control

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import gateway.entity.{ GatewayMessageRole, MessageDirection, NormalizedMessage, SessionKey }
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.{ IssueId, ProjectId }
import workspace.entity.{ RunMode, Workspace, WorkspaceEvent, WorkspaceRepository, WorkspaceRun, WorkspaceRunEvent }

object TelegramIntakeSpec extends ZIOSpecDefault:

  // Stubs ──────────────────────────────────────────────────────────────────

  final class CapturingIssueRepo(ref: Ref[List[IssueEvent]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             =
      ref.update(_ :+ event)
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

  final class StubWorkspaceRepo(workspaces: List[Workspace]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                    = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]                                  = ZIO.succeed(workspaces)
    override def listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]   =
      ZIO.succeed(workspaces.filter(_.projectId == projectId))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                     =
      ZIO.succeed(workspaces.find(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                               = ZIO.unit
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]              = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]      = ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]               = ZIO.succeed(None)

  // Fixtures ───────────────────────────────────────────────────────────────

  private val now: Instant = Instant.parse("2026-05-16T10:00:00Z")

  private def workspace(id: String, name: String, createdAt: Instant): Workspace =
    Workspace(
      id = id,
      projectId = ProjectId("p1"),
      name = name,
      localPath = s"/tmp/$id",
      defaultAgent = None,
      description = None,
      enabled = true,
      runMode = RunMode.Host,
      cliTool = "claude-code",
      createdAt = createdAt,
      updatedAt = createdAt,
    )

  private def message(content: String, channelName: String = "telegram"): NormalizedMessage =
    NormalizedMessage(
      id = "msg-1",
      channelName = channelName,
      sessionKey = SessionKey(channelName, "chat-1"),
      direction = MessageDirection.Inbound,
      role = GatewayMessageRole.User,
      content = content,
      metadata = Map.empty,
      timestamp = now,
    )

  // Tests ──────────────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TelegramIntake")(
    test("fileIssue creates Created + MovedToBacklog events on the most recent workspace") {
      for
        events  <- Ref.make(List.empty[IssueEvent])
        intake   = TelegramIntakeLive(
                     workspaceRepository = StubWorkspaceRepo(
                       List(
                         workspace("old", "old", Instant.parse("2026-01-01T00:00:00Z")),
                         workspace("new", "prototype", Instant.parse("2026-05-15T00:00:00Z")),
                       )
                     ),
                     issueRepository = CapturingIssueRepo(events),
                   )
        outcome <- intake.fileIssue(message("Add README"))
        captured <- events.get
      yield assertTrue(
        outcome.title == "Add README",
        outcome.workspaceName == "prototype",
        outcome.workspaceId == "new",
        captured.size == 2,
        captured.head.isInstanceOf[IssueEvent.Created],
        captured(1).isInstanceOf[IssueEvent.MovedToBacklog],
      )
    },
    test("title is the first non-empty line; description preserves the full body") {
      for
        events <- Ref.make(List.empty[IssueEvent])
        intake  = TelegramIntakeLive(
                    StubWorkspaceRepo(List(workspace("w", "w", now))),
                    CapturingIssueRepo(events),
                  )
        _      <- intake.fileIssue(message("\n\nFix login bug\n\nIt fails for emails with +"))
        all    <- events.get
        created = all.collectFirst { case c: IssueEvent.Created => c }.get
      yield assertTrue(
        created.title == "Fix login bug",
        created.description.contains("Fix login bug"),
        created.description.contains("It fails for emails with +"),
      )
    },
    test("titles longer than MaxTitleLen are truncated with ellipsis") {
      val longLine = "a" * 200
      for
        events <- Ref.make(List.empty[IssueEvent])
        intake  = TelegramIntakeLive(
                    StubWorkspaceRepo(List(workspace("w", "w", now))),
                    CapturingIssueRepo(events),
                  )
        out    <- intake.fileIssue(message(longLine))
      yield assertTrue(
        out.title.length == TelegramIntake.MaxTitleLen,
        out.title.endsWith("…"),
      )
    },
    test("descriptions longer than MaxDescriptionLen are truncated") {
      val huge = "x" * (TelegramIntake.MaxDescriptionLen + 500)
      for
        events <- Ref.make(List.empty[IssueEvent])
        intake  = TelegramIntakeLive(
                    StubWorkspaceRepo(List(workspace("w", "w", now))),
                    CapturingIssueRepo(events),
                  )
        _      <- intake.fileIssue(message(huge))
        all    <- events.get
        created = all.collectFirst { case c: IssueEvent.Created => c }.get
      yield assertTrue(created.description.length == TelegramIntake.MaxDescriptionLen)
    },
    test("EmptyMessage error when content is blank") {
      for
        events <- Ref.make(List.empty[IssueEvent])
        intake  = TelegramIntakeLive(
                    StubWorkspaceRepo(List(workspace("w", "w", now))),
                    CapturingIssueRepo(events),
                  )
        exit   <- intake.fileIssue(message("   \n\t  ")).exit
        all    <- events.get
      yield assert(exit)(fails(equalTo(TelegramIntakeError.EmptyMessage))) &&
        assertTrue(all.isEmpty)
    },
    test("NoWorkspaceConfigured error when the workspace store is empty") {
      for
        events <- Ref.make(List.empty[IssueEvent])
        intake  = TelegramIntakeLive(StubWorkspaceRepo(Nil), CapturingIssueRepo(events))
        exit   <- intake.fileIssue(message("hello")).exit
        all    <- events.get
      yield assert(exit)(fails(equalTo(TelegramIntakeError.NoWorkspaceConfigured))) &&
        assertTrue(all.isEmpty)
    },
    test("Storage error propagates wrapped PersistenceError when append fails") {
      val boom = PersistenceError.QueryFailed("append", "store down")

      final class FailingIssueRepo extends IssueRepository:
        override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.fail(boom)
        override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
          ZIO.fail(PersistenceError.NotFound("issue", id.value))
        override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
        override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(Nil)
        override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

      for
        intake <- ZIO.succeed(
                    TelegramIntakeLive(
                      StubWorkspaceRepo(List(workspace("w", "w", now))),
                      new FailingIssueRepo,
                    )
                  )
        exit   <- intake.fileIssue(message("hi")).exit
      yield assert(exit)(fails(equalTo(TelegramIntakeError.Storage(boom))))
    },
  )
