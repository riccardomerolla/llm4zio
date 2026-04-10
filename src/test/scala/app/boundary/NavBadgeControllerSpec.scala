package app.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*

object NavBadgeControllerSpec extends ZIOSpecDefault:

  private val pendingDecision = Decision(
    id = DecisionId("decision-1"),
    title = "Review",
    context = "ctx",
    action = DecisionAction.ReviewIssue,
    source = DecisionSource(
      kind = DecisionSourceKind.IssueReview,
      referenceId = "issue-1",
      summary = "summary",
      workspaceId = Some("ws-1"),
      issueId = Some(IssueId("issue-1")),
    ),
    urgency = DecisionUrgency.High,
    status = DecisionStatus.Pending,
    deadlineAt = None,
    createdAt = Instant.parse("2026-03-27T09:00:00Z"),
    updatedAt = Instant.parse("2026-03-27T09:00:00Z"),
  )

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

  private val decisionInbox = new DecisionInbox:
    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] =
      ZIO.succeed(pendingDecision)
    override def resolve(id: DecisionId, resolutionKind: DecisionResolutionKind, actor: String, summary: String)
      : IO[PersistenceError, Decision] = ZIO.succeed(pendingDecision)
    override def syncOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def resolveOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] = ZIO.none
    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision]   = ZIO.succeed(pendingDecision)
    override def get(id: DecisionId): IO[PersistenceError, Decision]                        = ZIO.succeed(pendingDecision)
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]         = ZIO.succeed(List(pendingDecision))
    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]         = ZIO.succeed(Nil)

  private val issueRepository = new IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                = ZIO.succeed(inProgressIssue)
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ZIO.succeed(
        if filter.states.contains(IssueStateTag.InProgress) then List(inProgressIssue) else Nil
      )
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

  private val routes = NavBadgeController.routes(decisionInbox, issueRepository)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("NavBadgeControllerSpec")(
      test("decisions badge renders pending decision count") {
        for
          response <- routes.runZIO(Request.get(URL(Path.decode("/nav/badges/decisions"))))
          body     <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body.contains(">1<"))
      },
      test("board badge renders in-progress issue count") {
        for
          response <- routes.runZIO(Request.get(URL(Path.decode("/nav/badges/board"))))
          body     <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body.contains(">1<"))
      },
    )
