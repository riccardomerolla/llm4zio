package integration

import java.time.Instant

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import _root_.config.entity.{ ConfigRepository, CustomAgentRow, SettingRow, WorkflowRow }
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType, ActivityRepository }
import decision.control.{ DecisionInbox, DecisionInboxLive }
import decision.entity.*
import gateway.boundary.telegram.DecisionEscalationNotifier
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository, IssueState }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, DecisionId, IssueId }

/** Phase 7 integration test: stitches together the supervisor round-trip
  * end-to-end at the wire level.
  *
  * Verifies that:
  *   1. `DecisionInbox.escalate` publishes a `DecisionEscalated` activity
  *      event carrying the right decisionId + reason in its payload.
  *   2. The activity payload is parseable by `DecisionEscalationNotifier`'s
  *      decisionId extractor (so the live notifier can wake up, fetch the
  *      decision, and format a Telegram message).
  *   3. A Telegram callback payload `decision:resolve:{id}:approve` round-
  *      trips through `parseCallbackData` and lets us resolve via
  *      `DecisionInbox.resolve` with the Approve QuickOption's resolution.
  *   4. The resolution fires the Issue side-effects (Approved + MovedToMerging
  *      for IssueReview decisions when auto-merge is on).
  *
  * This is the wire from "governance gate fails" all the way to "issue moves
  * forward on the board". Real components, in-memory repos.
  */
object SupervisorRoundTripSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-05-16T10:00:00Z")

  // ── In-memory repositories ────────────────────────────────────────────────

  final private class InMemDecisionRepo(ref: Ref[Map[DecisionId, List[DecisionEvent]]]) extends DecisionRepository:
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
      ref.get.flatMap(m => ZIO.foreach(m.keys.toList)(get)).map { all =>
        all
          .filter(d => filter.statuses.isEmpty || filter.statuses.contains(d.status))
          .filter(d => filter.issueId.forall(id => d.source.issueId.contains(id)))
      }

  final private class InMemIssueRepo(ref: Ref[Map[IssueId, List[IssueEvent]]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit] =
      ref.update(m => m.updated(event.issueId, m.getOrElse(event.issueId, Nil) :+ event))
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                 =
      history(id).flatMap(events =>
        ZIO.fromEither(AgentIssue.fromEvents(events)).mapError(msg => PersistenceError.SerializationFailed(id.value, msg))
      )
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]       =
      ref.get.map(_.getOrElse(id, Nil))
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]]  = ZIO.succeed(Nil)
    override def delete(id: IssueId): IO[PersistenceError, Unit]                    = ref.update(_ - id)

  final private class InMemActivityRepo(ref: Ref[List[ActivityEvent]]) extends ActivityRepository:
    override def createEvent(event: ActivityEvent): IO[PersistenceError, shared.ids.Ids.EventId] =
      ref.update(_ :+ event).as(event.id)
    override def listEvents(
      eventType: Option[ActivityEventType],
      since: Option[Instant],
      limit: Int,
    ): IO[PersistenceError, List[ActivityEvent]] =
      ref.get.map(_.filter(e => eventType.forall(_ == e.eventType)).takeRight(limit))

  /** Minimal config: auto-merge on so an Approved IssueReview triggers
    * IssueEvent.Approved + IssueEvent.MovedToMerging. */
  final private class TestConfigRepo extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]] = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] = key match
      case "decisions.timeoutSeconds.default" =>
        ZIO.succeed(Some(SettingRow(key, "3600", Instant.EPOCH)))
      case "mergePolicy.autoMerge"            =>
        ZIO.succeed(Some(SettingRow(key, "true", Instant.EPOCH)))
      case _                                  => ZIO.succeed(None)
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit] = ZIO.unit
    override def deleteSetting(key: String): IO[PersistenceError, Unit]                = ZIO.unit
    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]    = ZIO.unit
    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]              =
      ZIO.fail(PersistenceError.QueryFailed("createWorkflow", "unused"))
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]               =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflow", "unused"))
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]     =
      ZIO.fail(PersistenceError.QueryFailed("getWorkflowByName", "unused"))
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                         =
      ZIO.fail(PersistenceError.QueryFailed("listWorkflows", "unused"))
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]              =
      ZIO.fail(PersistenceError.QueryFailed("updateWorkflow", "unused"))
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                           =
      ZIO.fail(PersistenceError.QueryFailed("deleteWorkflow", "unused"))
    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             =
      ZIO.fail(PersistenceError.QueryFailed("createCustomAgent", "unused"))
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgent", "unused"))
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.fail(PersistenceError.QueryFailed("getCustomAgentByName", "unused"))
    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     =
      ZIO.fail(PersistenceError.QueryFailed("listCustomAgents", "unused"))
    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             =
      ZIO.fail(PersistenceError.QueryFailed("updateCustomAgent", "unused"))
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          =
      ZIO.fail(PersistenceError.QueryFailed("deleteCustomAgent", "unused"))

  // ── Fixtures ──────────────────────────────────────────────────────────────

  private val issueId    = IssueId("issue-rt-1")
  private val agentId    = AgentId("alex")

  private def seedIssueAtReview(repo: IssueRepository): IO[PersistenceError, AgentIssue] =
    val initialEvents = List(
      IssueEvent.Created(
        issueId = issueId,
        title = "Add login form",
        description = "Frontend ticket",
        issueType = "feature",
        priority = "medium",
        occurredAt = now,
      ),
      IssueEvent.WorkspaceLinked(issueId, "ws-1", now.plusSeconds(1)),
      IssueEvent.Assigned(issueId, agentId, now.plusSeconds(2), now.plusSeconds(2)),
      IssueEvent.Started(issueId, agentId, now.plusSeconds(3), now.plusSeconds(3)),
      IssueEvent.MovedToHumanReview(issueId, now.plusSeconds(10), now.plusSeconds(10)),
    )
    ZIO.foreachDiscard(initialEvents)(repo.append) *> repo.get(issueId)

  private def setup: UIO[(DecisionInbox, IssueRepository, ActivityHub, Ref[List[ActivityEvent]])] =
    for
      decisionRef  <- Ref.make(Map.empty[DecisionId, List[DecisionEvent]])
      issueRef     <- Ref.make(Map.empty[IssueId, List[IssueEvent]])
      activityRef  <- Ref.make(List.empty[ActivityEvent])
      subscribers  <- Ref.make(Set.empty[Queue[ActivityEvent]])
      decisionRepo  = InMemDecisionRepo(decisionRef)
      issueRepo     = InMemIssueRepo(issueRef)
      activityRepo  = InMemActivityRepo(activityRef)
      hub           = activity.control.ActivityHubLive(activityRepo, subscribers)
      inbox         = DecisionInboxLive(decisionRepo, issueRepo, hub, TestConfigRepo())
    yield (inbox, issueRepo, hub, activityRef)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SupervisorRoundTripSpec")(
    test("inbox.escalate publishes DecisionEscalated with parseable payload + the notifier can extract the decisionId") {
      for
        tuple                  <- setup
        (inbox, issueRepo, hub, _) = tuple
        issue                  <- seedIssueAtReview(issueRepo)
        decision               <- inbox.openIssueReviewDecision(issue)
        subscription           <- hub.subscribe
        _                      <- inbox.escalate(decision.id, "Deadline exceeded")
        captured               <- subscription.takeAll
        escalation              = captured.find(_.eventType == ActivityEventType.DecisionEscalated)
      yield assertTrue(
        escalation.isDefined,
        escalation.flatMap(_.payload).exists(_.contains(decision.id.value)),
        // The notifier's payload parser must successfully extract the decisionId
        // from whatever publishEscalation emits.
        escalation.flatMap(_.payload).flatMap(p =>
          p.fromJson[Json.Obj].toOption.flatMap(_.fields.collectFirst {
            case ("decisionId", Json.Str(value)) => DecisionId(value)
          })
        ).contains(decision.id),
      )
    },
    test("a supervisor 'Approve' tap (decision:resolve:{id}:approve) walks the issue to Merging") {
      for
        tuple                  <- setup
        (inbox, issueRepo, _, _) = tuple
        issue                  <- seedIssueAtReview(issueRepo)
        decision               <- inbox.openIssueReviewDecision(issue)
        // Simulate the inbound Telegram callback round-trip:
        callback                = DecisionEscalationNotifier.callbackData(decision.id, "approve")
        parsed                  = DecisionEscalationNotifier.parseCallbackData(callback)
        // … the channel handler resolves the matching QuickOption and calls inbox.resolve
        option                  = QuickOption.findByKey(decision.renderableQuickOptions, "approve").get
        resolved               <- inbox.resolve(decision.id, option.resolution, "telegram:42", option.rationale)
        // Side-effects: IssueReview Approved decision fires Approved + MovedToMerging events
        issueAfter             <- issueRepo.get(issueId)
      yield assertTrue(
        parsed.contains(decision.id -> "approve"),
        option.resolution == DecisionResolutionKind.Approved,
        resolved.status == DecisionStatus.Resolved,
        resolved.resolution.map(_.kind).contains(DecisionResolutionKind.Approved),
        issueAfter.state.isInstanceOf[IssueState.Merging],
      )
    },
    test("a 'Request changes' tap moves the issue to Rework") {
      for
        tuple                  <- setup
        (inbox, issueRepo, _, _) = tuple
        issue                  <- seedIssueAtReview(issueRepo)
        decision               <- inbox.openIssueReviewDecision(issue)
        option                  = QuickOption.findByKey(decision.renderableQuickOptions, "rework").get
        _                      <- inbox.resolve(decision.id, option.resolution, "telegram:42", option.rationale)
        issueAfter             <- issueRepo.get(issueId)
      yield assertTrue(
        option.resolution == DecisionResolutionKind.ReworkRequested,
        issueAfter.state.isInstanceOf[IssueState.Rework],
      )
    },
  )
