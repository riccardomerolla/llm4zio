package decision.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import board.control.IssueApprovalService
import board.entity.BoardError
import decision.control.DecisionInbox
import decision.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ BoardIssueId, DecisionId, IssueId }

object DecisionsControllerSpec extends ZIOSpecDefault:

  private val now        = Instant.parse("2026-04-03T10:00:00Z")
  private val decisionId = DecisionId("decision-1")

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DecisionsControllerSpec")(
      test("IssueReview approved delegates to quickApprove instead of direct inbox resolution") {
        for
          resolvedRef <- Ref.make(List.empty[(DecisionId, DecisionResolutionKind, String, String)])
          quickRef    <- Ref.make(List.empty[(String, BoardIssueId, String)])
          reworkRef   <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
          routes       = controllerRoutes(
                           decision = issueReviewDecision,
                           resolvedRef = resolvedRef,
                           quickRef = quickRef,
                           reworkRef = reworkRef,
                         )
          resp        <- routes.runZIO(resolveRequest("approved", "Looks+good"))
          resolved    <- resolvedRef.get
          quickCalls  <- quickRef.get
          reworkCalls <- reworkRef.get
        yield assertTrue(
          resp.status == Status.SeeOther,
          resolved.isEmpty,
          quickCalls == List(("ws-1", BoardIssueId("issue-1"), "Looks good")),
          reworkCalls.isEmpty,
        )
      },
      test("IssueReview rework delegates to reworkIssue with web actor") {
        for
          resolvedRef <- Ref.make(List.empty[(DecisionId, DecisionResolutionKind, String, String)])
          quickRef    <- Ref.make(List.empty[(String, BoardIssueId, String)])
          reworkRef   <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
          routes       = controllerRoutes(
                           decision = issueReviewDecision,
                           resolvedRef = resolvedRef,
                           quickRef = quickRef,
                           reworkRef = reworkRef,
                         )
          resp        <- routes.runZIO(resolveRequest("reworkrequested", "Needs+another+pass"))
          resolved    <- resolvedRef.get
          quickCalls  <- quickRef.get
          reworkCalls <- reworkRef.get
        yield assertTrue(
          resp.status == Status.SeeOther,
          resolved.isEmpty,
          quickCalls.isEmpty,
          reworkCalls == List(("ws-1", BoardIssueId("issue-1"), "Needs another pass", "web")),
        )
      },
      test("non-IssueReview resolutions keep the existing inbox resolve behavior") {
        for
          resolvedRef <- Ref.make(List.empty[(DecisionId, DecisionResolutionKind, String, String)])
          quickRef    <- Ref.make(List.empty[(String, BoardIssueId, String)])
          reworkRef   <- Ref.make(List.empty[(String, BoardIssueId, String, String)])
          routes       = controllerRoutes(
                           decision = manualDecision,
                           resolvedRef = resolvedRef,
                           quickRef = quickRef,
                           reworkRef = reworkRef,
                         )
          resp        <- routes.runZIO(resolveRequest("acknowledged", "Handled+manually"))
          resolved    <- resolvedRef.get
          quickCalls  <- quickRef.get
          reworkCalls <- reworkRef.get
        yield assertTrue(
          resp.status == Status.SeeOther,
          resolved == List((decisionId, DecisionResolutionKind.Acknowledged, "web", "Handled manually")),
          quickCalls.isEmpty,
          reworkCalls.isEmpty,
        )
      },
    )

  private def controllerRoutes(
    decision: Decision,
    resolvedRef: Ref[List[(DecisionId, DecisionResolutionKind, String, String)]],
    quickRef: Ref[List[(String, BoardIssueId, String)]],
    reworkRef: Ref[List[(String, BoardIssueId, String, String)]],
  ): Routes[Any, Response] =
    DecisionsController
      .make(
        StubDecisionInbox(decision, resolvedRef),
        StubIssueApprovalService(quickRef, reworkRef),
      )
      .routes

  private def resolveRequest(resolution: String, summary: String): Request =
    Request(
      method = Method.POST,
      url = URL(Path.decode(s"/decisions/${decisionId.value}/resolve")),
      body = Body.fromString(s"resolution=$resolution&summary=$summary"),
    )

  private val issueReviewDecision = Decision(
    id = decisionId,
    title = "Review issue #issue-1",
    context = "Review implementation",
    action = DecisionAction.ReviewIssue,
    source = DecisionSource(
      kind = DecisionSourceKind.IssueReview,
      referenceId = "issue-1",
      summary = "Issue review",
      workspaceId = Some("ws-1"),
      issueId = Some(IssueId("issue-1")),
    ),
    urgency = DecisionUrgency.High,
    status = DecisionStatus.Pending,
    deadlineAt = None,
    resolution = None,
    escalatedAt = None,
    expiredAt = None,
    createdAt = now,
    updatedAt = now,
  )

  private val manualDecision = Decision(
    id = decisionId,
    title = "Manual escalation",
    context = "Manual review",
    action = DecisionAction.ManualEscalation,
    source = DecisionSource(
      kind = DecisionSourceKind.Manual,
      referenceId = "manual-1",
      summary = "Manual summary",
      workspaceId = Some("ws-1"),
      issueId = None,
    ),
    urgency = DecisionUrgency.Medium,
    status = DecisionStatus.Pending,
    deadlineAt = None,
    resolution = None,
    escalatedAt = None,
    expiredAt = None,
    createdAt = now,
    updatedAt = now,
  )

  final private case class StubDecisionInbox(
    decision: Decision,
    resolvedRef: Ref[List[(DecisionId, DecisionResolutionKind, String, String)]],
  ) extends DecisionInbox:
    override def openIssueReviewDecision(issue: issues.entity.AgentIssue): IO[PersistenceError, Decision] =
      ZIO.fail(PersistenceError.QueryFailed("open_issue_review_decision", "unused"))

    override def resolve(
      id: DecisionId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Decision] =
      resolvedRef.update(_ :+ ((id, resolutionKind, actor, summary))).as(
        decision.copy(
          status = DecisionStatus.Resolved,
          resolution = Some(DecisionResolution(resolutionKind, actor, summary, now.plusSeconds(1))),
          updatedAt = now.plusSeconds(1),
        )
      )

    override def syncOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] =
      ZIO.none

    override def resolveOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]] =
      ZIO.none

    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision] =
      ZIO.fail(PersistenceError.QueryFailed("escalate", "unused"))

    override def get(id: DecisionId): IO[PersistenceError, Decision] =
      if id == decision.id then ZIO.succeed(decision)
      else ZIO.fail(PersistenceError.NotFound("decision", id.value))

    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]] =
      ZIO.succeed(List(decision))

    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]] =
      ZIO.succeed(Nil)

  final private case class StubIssueApprovalService(
    quickRef: Ref[List[(String, BoardIssueId, String)]],
    reworkRef: Ref[List[(String, BoardIssueId, String, String)]],
  ) extends IssueApprovalService:
    override def quickApprove(workspaceId: String, issueId: BoardIssueId, reviewerNotes: String): IO[BoardError, Unit] =
      quickRef.update(_ :+ ((workspaceId, issueId, reviewerNotes)))

    override def reworkIssue(
      workspaceId: String,
      issueId: BoardIssueId,
      reworkComment: String,
      actor: String,
    ): IO[BoardError, Unit] =
      reworkRef.update(_ :+ ((workspaceId, issueId, reworkComment, actor)))
