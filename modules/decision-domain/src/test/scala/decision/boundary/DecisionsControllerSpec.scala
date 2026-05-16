package decision.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.AgentIssue
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, IssueId }

object DecisionsControllerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-05-15T16:00:00Z")

  private def decisionFor(id: String, status: DecisionStatus = DecisionStatus.Pending): Decision =
    Decision(
      id = DecisionId(id),
      title = "Spec needs approval",
      context = "Spec body ready for review",
      action = DecisionAction.ReviewIssue,
      source = DecisionSource(
        kind = DecisionSourceKind.IssueReview,
        referenceId = "issue-1",
        summary = "Review required",
        issueId = Some(IssueId("issue-1")),
      ),
      urgency = DecisionUrgency.High,
      status = status,
      deadlineAt = None,
      createdAt = now,
      updatedAt = now,
    )

  /** Stub inbox that records resolve calls so the controller test can assert on them. */
  final private class StubInbox(
    storedDecisions: Ref[Map[DecisionId, Decision]],
    resolvedRef: Ref[List[(DecisionId, DecisionResolutionKind, String, String)]],
  ) extends DecisionInbox:
    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] =
      ZIO.fail(PersistenceError.QueryFailed("open", "unused"))
    override def openManualDecision(
      title: String,
      context: String,
      referenceId: String,
      summary: String,
      urgency: DecisionUrgency,
      workspaceId: Option[String],
      issueId: Option[IssueId],
    ): IO[PersistenceError, Decision] = ZIO.fail(PersistenceError.QueryFailed("open", "unused"))
    override def resolve(
      id: DecisionId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Decision] =
      for
        _       <- resolvedRef.update(_ :+ (id, resolutionKind, actor, summary))
        current <- storedDecisions.get
        updated  = current.get(id) match
                     case Some(d) => d.copy(status = DecisionStatus.Resolved)
                     case None    => decisionFor(id.value, DecisionStatus.Resolved)
        _       <- storedDecisions.update(_.updated(id, updated))
      yield updated
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
    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision] =
      storedDecisions.get.flatMap { m =>
        m.get(id) match
          case Some(d) =>
            val updated = d.copy(status = DecisionStatus.Escalated)
            storedDecisions.update(_.updated(id, updated)).as(updated)
          case None    => ZIO.fail(PersistenceError.NotFound("decision", id.value))
      }
    override def get(id: DecisionId): IO[PersistenceError, Decision] =
      storedDecisions.get.flatMap(m =>
        ZIO.fromOption(m.get(id)).orElseFail(PersistenceError.NotFound("decision", id.value))
      )
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]] =
      storedDecisions.get.map(_.values.toList.filter(d =>
        filter.statuses.isEmpty || filter.statuses.contains(d.status)
      ))
    override def runMaintenance(at: Instant): IO[PersistenceError, List[Decision]] =
      ZIO.succeed(Nil)

  private def mkController(initial: Map[DecisionId, Decision])
    : UIO[(DecisionsController, Ref[List[(DecisionId, DecisionResolutionKind, String, String)]])] =
    for
      stored  <- Ref.make(initial)
      resolved <- Ref.make(List.empty[(DecisionId, DecisionResolutionKind, String, String)])
      inbox    = StubInbox(stored, resolved)
    yield (DecisionsControllerLive(inbox), resolved)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DecisionsController")(
    test("GET /decisions/inbox renders the supervisor inbox page") {
      val pending = decisionFor("decision-1")
      for
        tuple             <- mkController(Map(pending.id -> pending))
        (controller, _)    = tuple
        response          <- controller.routes.runZIO(Request.get(URL.decode("/decisions/inbox").toOption.get))
        body              <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("Supervisor Inbox"),
        body.contains("Spec needs approval"),
        body.contains("Approve"),
        body.contains("Request changes"),
        body.contains("/decisions/decision-1/resolve"),
        // Body must be raw HTML, not entity-escaped (regression guard: the
        // view used to call .render on Layout.page's String result, which
        // double-escaped the page so browsers showed literal &lt;!DOCTYPE…).
        body.startsWith("<!DOCTYPE html>"),
        !body.contains("&lt;!DOCTYPE"),
      )
    },
    test("POST /decisions/{id}/resolve calls inbox.resolve with the option's resolution") {
      val pending = decisionFor("decision-2")
      for
        tuple              <- mkController(Map(pending.id -> pending))
        (controller, calls) = tuple
        request             = Request
                                .post(URL.decode("/decisions/decision-2/resolve").toOption.get, Body.fromString("key=approve&actor=test-supervisor"))
        response           <- controller.routes.runZIO(request)
        recorded           <- calls.get
      yield assertTrue(
        response.status == Status.Ok,
        recorded.size == 1,
        recorded.head._1 == DecisionId("decision-2"),
        recorded.head._2 == DecisionResolutionKind.Approved,
        recorded.head._3 == "test-supervisor",
      )
    },
    test("POST /decisions/{id}/resolve rejects an unknown option key") {
      val pending = decisionFor("decision-3")
      for
        tuple             <- mkController(Map(pending.id -> pending))
        (controller, _)    = tuple
        request            = Request
                               .post(URL.decode("/decisions/decision-3/resolve").toOption.get, Body.fromString("key=bogus&actor=test"))
        response          <- controller.routes.runZIO(request)
      yield assertTrue(response.status == Status.BadRequest)
    },
  )
