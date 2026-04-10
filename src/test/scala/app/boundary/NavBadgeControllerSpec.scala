package app.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*

object NavBadgeControllerSpec extends ZIOSpecDefault:

  private val inProgressIssue = AgentIssue(
    id = IssueId("issue-1"),
    runId = None,
    conversationId = None,
    title = "In progress",
    description = "desc",
    issueType = "task",
    priority = "high",
    requiredCapabilities = Nil,
    state = IssueState.InProgress(AgentId("agent-1"), Instant.parse("2026-03-27T09:00:00Z")),
    tags = Nil,
    blockedBy = Nil,
    contextPath = "",
    sourceFolder = "",
  )

  private val issueRepository = new IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                = ZIO.succeed(inProgressIssue)
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ZIO.succeed(
        if filter.states.contains(IssueStateTag.InProgress) then List(inProgressIssue) else Nil
      )
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

  private val routes = NavBadgeController.routes(issueRepository)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("NavBadgeControllerSpec")(
      test("board badge renders in-progress issue count") {
        for
          response <- routes.runZIO(Request.get(URL(Path.decode("/nav/badges/board"))))
          body     <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body.contains(">1<"))
      },
    )
