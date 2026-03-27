package decision.control

import java.time.Instant

import zio.*
import zio.test.*

import activity.entity.{ ActivityEvent, ActivityEventType }
import decision.entity.*
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, IssueId }
import shared.testfixtures.*

object DecisionInboxSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T10:00:00Z")

  // ── Stub: DecisionRepository ──────────────────────────────────────────────

  final private class StubDecisionRepository(ref: Ref[Map[DecisionId, List[DecisionEvent]]])
    extends DecisionRepository:
    override def append(event: DecisionEvent): IO[PersistenceError, Unit] =
      ref.update(m => m.updated(event.decisionId, m.getOrElse(event.decisionId, Nil) :+ event))

    override def get(id: DecisionId): IO[PersistenceError, Decision] =
      history(id).flatMap(events =>
        ZIO.fromEither(Decision.fromEvents(events)).mapError(msg => PersistenceError.SerializationFailed(id.value, msg))
      )

    override def history(id: DecisionId): IO[PersistenceError, List[DecisionEvent]] =
      ref.get.flatMap(m =>
        ZIO.fromOption(m.get(id)).orElseFail(PersistenceError.NotFound("decision", id.value))
      )

    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]] =
      ref.get.flatMap(m =>
        ZIO.foreach(m.keys.toList)(get).map(all =>
          all
            .filter(d => filter.statuses.isEmpty || filter.statuses.contains(d.status))
            .filter(d => filter.urgency.forall(_ == d.urgency))
            .filter(d => filter.workspaceId.forall(wid => d.source.workspaceId.contains(wid)))
            .filter(d => filter.sourceKind.forall(_ == d.source.kind))
            .filter(d => filter.issueId.forall(id => d.source.issueId.contains(id)))
        )
      )

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def makeInbox(
    settings: Map[String, String] = Map(
      "decisions.timeoutSeconds.default" -> "3600",
      "mergePolicy.autoMerge"            -> "true",
    ),
    seededIssues: Map[IssueId, List[IssueEvent]] = Map.empty,
  ): UIO[(DecisionInboxLive, Ref[Map[IssueId, List[IssueEvent]]], Ref[List[ActivityEvent]])] =
    for
      decisionRef <- Ref.make(Map.empty[DecisionId, List[DecisionEvent]])
      issueRef    <- Ref.make(seededIssues)
      activityRef <- Ref.make(List.empty[ActivityEvent])
    yield (
      DecisionInboxLive(
        decisionRepository = new StubDecisionRepository(decisionRef),
        issueRepository = new MutableIssueRepository(issueRef),
        activityHub = new StubActivityHub(activityRef),
        configRepository = new StubConfigRepository(settings),
      ),
      issueRef,
      activityRef,
    )

  private def seedIssueEvents(issueId: IssueId): List[IssueEvent] =
    List(
      IssueEvent.Created(issueId, "Fix the bug", "Critical failure", "bug", "high", now.minusSeconds(600)),
      IssueEvent.Assigned(issueId, shared.ids.Ids.AgentId("agent-1"), now.minusSeconds(500), now.minusSeconds(500)),
      IssueEvent.Started(issueId, shared.ids.Ids.AgentId("agent-1"), now.minusSeconds(400), now.minusSeconds(400)),
      IssueEvent.Completed(
        issueId,
        shared.ids.Ids.AgentId("agent-1"),
        now.minusSeconds(60),
        "done",
        now.minusSeconds(60),
      ),
      IssueEvent.MovedToHumanReview(issueId, now.minusSeconds(30), now.minusSeconds(30)),
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DecisionInboxSpec")(
      test("openIssueReviewDecision creates a Pending decision and publishes DecisionCreated activity") {
        val issueId = IssueId("issue-open-1")
        for
          (inbox, _, activityRef) <- makeInbox(seededIssues = Map(issueId -> seedIssueEvents(issueId)))
          issue                   <- ZIO.serviceWithZIO[issues.entity.IssueRepository](_.get(issueId)).provideLayer(
                                       ZLayer.fromZIO(Ref.make(Map(issueId -> seedIssueEvents(issueId))).map(r =>
                                         new MutableIssueRepository(r)
                                       ))
                                     )
          _                       <- TestClock.setTime(now)
          decision                <- inbox.openIssueReviewDecision(issue)
          events                  <- activityRef.get
        yield assertTrue(
          decision.status == DecisionStatus.Pending,
          decision.source.kind == DecisionSourceKind.IssueReview,
          decision.source.issueId.contains(issueId),
          decision.urgency == DecisionUrgency.High,
          events.exists(_.eventType == ActivityEventType.DecisionCreated),
        )
      },
      test("openManualDecision creates a Pending manual decision and publishes activity") {
        for
          (inbox, _, activityRef) <- makeInbox()
          _                       <- TestClock.setTime(now)
          decision                <- inbox.openManualDecision(
                                       "Deploy approval",
                                       "Need sign-off on deployment",
                                       "deploy-v2",
                                       "Deploy version 2",
                                       DecisionUrgency.Critical,
                                       Some("ws-1"),
                                     )
          events                  <- activityRef.get
        yield assertTrue(
          decision.status == DecisionStatus.Pending,
          decision.source.kind == DecisionSourceKind.Manual,
          decision.urgency == DecisionUrgency.Critical,
          decision.title == "Deploy approval",
          events.exists(_.eventType == ActivityEventType.DecisionCreated),
        )
      },
      test("resolve(Approved) transitions decision to Resolved and appends Approved+Done events to linked issue") {
        val issueId = IssueId("issue-resolve-approve")
        for
          (inbox, issueRef, activityRef) <- makeInbox(
                                              seededIssues = Map(issueId -> seedIssueEvents(issueId)),
                                              settings = Map(
                                                "decisions.timeoutSeconds.default" -> "3600",
                                                "mergePolicy.autoMerge"            -> "false",
                                              ),
                                            )
          issue                           = AgentIssue(
                                              id = issueId,
                                              runId = None,
                                              conversationId = None,
                                              title = "Fix the bug",
                                              description = "Critical failure",
                                              issueType = "bug",
                                              priority = "high",
                                              requiredCapabilities = Nil,
                                              state = IssueState.HumanReview(now.minusSeconds(30)),
                                              tags = Nil,
                                              blockedBy = Nil,
                                              contextPath = "",
                                              sourceFolder = "",
                                            )
          _                              <- TestClock.setTime(now)
          pending                        <- inbox.openIssueReviewDecision(issue)
          _                              <- TestClock.setTime(now.plusSeconds(10))
          resolved                       <- inbox.resolve(pending.id, DecisionResolutionKind.Approved, "reviewer", "LGTM")
          issueEvents                    <- issueRef.get.map(_.getOrElse(issueId, Nil))
          activities                     <- activityRef.get
        yield assertTrue(
          resolved.status == DecisionStatus.Resolved,
          resolved.resolution.map(_.kind).contains(DecisionResolutionKind.Approved),
          issueEvents.exists {
            case _: IssueEvent.Approved => true
            case _                      => false
          },
          activities.exists(_.eventType == ActivityEventType.DecisionResolved),
        )
      },
      test("resolve(ReworkRequested) appends MovedToRework to the linked issue") {
        val issueId = IssueId("issue-rework")
        val issue   = AgentIssue(
          id = issueId,
          runId = None,
          conversationId = None,
          title = "Needs changes",
          description = "desc",
          issueType = "task",
          priority = "medium",
          requiredCapabilities = Nil,
          state = IssueState.HumanReview(now.minusSeconds(30)),
          tags = Nil,
          blockedBy = Nil,
          contextPath = "",
          sourceFolder = "",
        )
        for
          (inbox, issueRef, _) <- makeInbox()
          _                    <- TestClock.setTime(now)
          pending              <- inbox.openIssueReviewDecision(issue)
          _                    <- TestClock.setTime(now.plusSeconds(5))
          resolved             <- inbox.resolve(pending.id, DecisionResolutionKind.ReworkRequested, "reviewer", "Needs work")
          issueEvents          <- issueRef.get.map(_.getOrElse(issueId, Nil))
        yield assertTrue(
          resolved.status == DecisionStatus.Resolved,
          resolved.resolution.map(_.kind).contains(DecisionResolutionKind.ReworkRequested),
          issueEvents.exists {
            case _: IssueEvent.MovedToRework => true
            case _                           => false
          },
        )
      },
      test("runMaintenance expires decisions past their deadline and publishes escalation activity") {
        for
          (inbox, _, activityRef) <- makeInbox(settings =
                                       Map(
                                         "decisions.timeoutSeconds.default" -> "5",
                                         "mergePolicy.autoMerge"            -> "true",
                                       )
                                     )
          _                       <- TestClock.setTime(now)
          decision                <- inbox.openManualDecision("Urgent", "Time-sensitive", "ref-1", "desc", DecisionUrgency.High)
          _                       <- TestClock.setTime(now.plusSeconds(10))
          expired                 <- inbox.runMaintenance(now.plusSeconds(10))
          activities              <- activityRef.get
        yield assertTrue(
          expired.size == 1,
          expired.head.id == decision.id,
          expired.head.status == DecisionStatus.Escalated,
          expired.head.expiredAt.isDefined,
          activities.exists(_.eventType == ActivityEventType.DecisionEscalated),
        )
      },
      test("runMaintenance does not expire resolved decisions") {
        for
          (inbox, _, _) <- makeInbox(settings =
                             Map(
                               "decisions.timeoutSeconds.default" -> "5",
                               "mergePolicy.autoMerge"            -> "true",
                             )
                           )
          _             <- TestClock.setTime(now)
          d1            <- inbox.openManualDecision("Resolved one", "ctx", "ref-1", "s", DecisionUrgency.Medium)
          _             <- TestClock.setTime(now.plusSeconds(2))
          _             <- inbox.resolve(d1.id, DecisionResolutionKind.Approved, "actor", "ok")
          _             <- TestClock.setTime(now.plusSeconds(10))
          expired       <- inbox.runMaintenance(now.plusSeconds(10))
        yield assertTrue(expired.isEmpty)
      },
      test("list filters by status") {
        for
          (inbox, _, _) <- makeInbox()
          _             <- TestClock.setTime(now)
          d1            <- inbox.openManualDecision("First", "ctx1", "ref-1", "s1", DecisionUrgency.Low)
          d2            <- inbox.openManualDecision("Second", "ctx2", "ref-2", "s2", DecisionUrgency.Medium)
          _             <- TestClock.setTime(now.plusSeconds(1))
          _             <- inbox.resolve(d1.id, DecisionResolutionKind.Acknowledged, "actor", "ack")
          pending       <- inbox.list(DecisionFilter(statuses = Set(DecisionStatus.Pending), limit = Int.MaxValue))
          resolved      <- inbox.list(DecisionFilter(statuses = Set(DecisionStatus.Resolved), limit = Int.MaxValue))
        yield assertTrue(
          pending.size == 1,
          pending.head.id == d2.id,
          resolved.size == 1,
          resolved.head.id == d1.id,
        )
      },
      test("list filters by urgency") {
        for
          (inbox, _, _) <- makeInbox()
          _             <- TestClock.setTime(now)
          _             <- inbox.openManualDecision("Critical one", "ctx", "ref-1", "s", DecisionUrgency.Critical)
          _             <- inbox.openManualDecision("Low one", "ctx", "ref-2", "s", DecisionUrgency.Low)
          critical      <- inbox.list(DecisionFilter(urgency = Some(DecisionUrgency.Critical), limit = Int.MaxValue))
          low           <- inbox.list(DecisionFilter(urgency = Some(DecisionUrgency.Low), limit = Int.MaxValue))
        yield assertTrue(
          critical.size == 1,
          critical.head.urgency == DecisionUrgency.Critical,
          low.size == 1,
          low.head.urgency == DecisionUrgency.Low,
        )
      },
    )
