package triage.control

import java.time.Instant

import zio.*
import zio.json.JsonCodec
import zio.test.*

import _root_.config.control.ConnectorConfigResolver
import decision.control.DecisionInbox
import decision.entity.*
import issues.entity.*
import llm4zio.core.*
import llm4zio.tools.JsonSchema
import prompts.{ PromptError, PromptLoader }
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, IssueId }
import shared.testfixtures.StubActivityHub
import triage.entity.{ TriageBatchOutcome, TriageOutcome }

/** Locks the contract from spdd/PAT-TRIAGE-202605161630 (v3 refold).
  * The daemon stubs the entire DI graph so we can drive every branch
  * in the canvas's Operations section without spinning up real
  * connectors. The triage agent name is parametric — these tests use
  * `"pat"` because that's the canonical PM in DefaultRoster, but
  * everything else works the same with any agent name.
  */
object TriageDaemonSpec extends ZIOSpecDefault:

  // ── Fixtures ────────────────────────────────────────────────────────

  private val now: Instant = Instant.parse("2026-05-17T10:00:00Z")
  private val pmAgent: String = "pat"

  private def backlogIssue(
    id: String,
    title: String = "do something",
    tags: List[String] = Nil,
    description: String = "context",
  ): AgentIssue =
    AgentIssue(
      id = IssueId(id),
      runId = None,
      conversationId = None,
      title = title,
      description = description,
      issueType = "task",
      priority = "normal",
      requiredCapabilities = Nil,
      state = IssueState.Backlog(now),
      tags = tags,
      blockedBy = Nil,
      blocking = Nil,
      contextPath = "",
      sourceFolder = "",
      workspaceId = Some("ws-1"),
    )

  // ── Stubs ───────────────────────────────────────────────────────────

  final class StubIssueRepo(ref: Ref[Map[IssueId, (AgentIssue, List[IssueEvent])]]) extends IssueRepository:
    def state: UIO[Map[IssueId, (AgentIssue, List[IssueEvent])]] = ref.get

    override def append(event: IssueEvent): IO[PersistenceError, Unit] =
      ref.update { current =>
        current.get(event.issueId) match
          case None                  => current
          case Some((issue, events)) =>
            val updated = event match
              case e: IssueEvent.TagsUpdated => issue.copy(tags = e.tags)
              case _                          => issue
            current + (event.issueId -> (updated, events :+ event))
      }

    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
      ref.get.map(_.get(id).map(_._2).getOrElse(Nil))

    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ref.get.map { m =>
        m.values
          .map(_._1)
          .filter(issue =>
            filter.states.isEmpty || filter.states.contains(IssueStateTag.fromState(issue.state))
          )
          .toList
      }

    override def get(id: IssueId): IO[PersistenceError, AgentIssue]            =
      ZIO.fail(PersistenceError.NotFound("issue", id.value))
    override def delete(id: IssueId): IO[PersistenceError, Unit]               = ZIO.unit

  object StubIssueRepo:
    def make(issues: List[AgentIssue]): UIO[StubIssueRepo] =
      Ref
        .make(issues.map(i => i.id -> (i, List.empty[IssueEvent])).toMap)
        .map(new StubIssueRepo(_))

  final class StubPromptLoader extends PromptLoader:
    override def load(name: String, context: Map[String, String] = Map.empty): IO[PromptError, String] =
      ZIO.succeed(s"[$name]\nTitle: ${context.getOrElse("issueTitle", "")}")

  final class StubResolver(cfg: ConnectorConfig) extends ConnectorConfigResolver:
    override def resolve(agentName: Option[String]): IO[PersistenceError, ConnectorConfig] =
      ZIO.succeed(cfg)

  /** A Connector whose `executeStructured` returns whatever it's primed
    * with. Implements both `ApiConnector` and `LlmService` so the daemon
    * accepts it through the same path API connectors use.
    */
  final class StubConnector(
    outcome: Either[LlmError, TriageOutcome],
    callCountRef: Ref[Int],
  ) extends ApiConnector:
    override val id: ConnectorId = ConnectorId.Mock

    override def healthCheck: IO[LlmError, HealthStatus] =
      ZIO.succeed(HealthStatus(Availability.Healthy, AuthStatus.Valid, None))
    override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      zio.stream.ZStream.empty
    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      zio.stream.ZStream.empty
    override def executeWithTools(
      prompt: String,
      tools: List[llm4zio.tools.AnyTool],
    ): IO[LlmError, ToolCallResponse] =
      ZIO.fail(LlmError.ConfigError("not used in tests"))

    override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
      callCountRef.update(_ + 1) *>
        ZIO.fromEither(outcome).map(_.asInstanceOf[A])

  final class StubRegistry(connector: Connector) extends ConnectorRegistry:
    override def resolve(config: ConnectorConfig): IO[LlmError, Connector]                    = ZIO.succeed(connector)
    override def resolveApi(config: ApiConnectorConfig): IO[LlmError, ApiConnector]           =
      connector match
        case api: ApiConnector => ZIO.succeed(api)
        case _                 => ZIO.fail(LlmError.ConfigError("not an api connector"))
    override def resolveCli(config: CliConnectorConfig): IO[LlmError, CliConnector]           =
      ZIO.fail(LlmError.ConfigError("not used in tests"))
    override def available: UIO[List[ConnectorId]]                                            = ZIO.succeed(List(ConnectorId.Mock))
    override def healthCheckAll: IO[LlmError, Map[ConnectorId, HealthStatus]]                 = ZIO.succeed(Map.empty)

  final class CapturingDecisionInbox(ref: Ref[List[(String, String)]]) extends DecisionInbox:
    def captured: UIO[List[(String, String)]] = ref.get

    override def openIssueReviewDecision(issue: AgentIssue): IO[PersistenceError, Decision] =
      ZIO.fail(PersistenceError.QueryFailed("not-used", "not-used"))

    override def openManualDecision(
      title: String,
      context: String,
      referenceId: String,
      summary: String,
      urgency: DecisionUrgency = DecisionUrgency.Medium,
      workspaceId: Option[String] = None,
      issueId: Option[IssueId] = None,
    ): IO[PersistenceError, Decision] =
      ref.update(_ :+ (title, summary)) *>
        ZIO.succeed(
          Decision(
            id = DecisionId("d-1"),
            title = title,
            context = context,
            action = DecisionAction.ManualEscalation,
            source = DecisionSource(DecisionSourceKind.Manual, referenceId, summary, workspaceId, issueId),
            urgency = urgency,
            status = DecisionStatus.Pending,
            deadlineAt = None,
            createdAt = now,
            updatedAt = now,
          )
        )

    override def resolve(
      id: DecisionId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Decision]                                                          =
      ZIO.fail(PersistenceError.QueryFailed("not-used", "not-used"))
    override def syncOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]]                                                  = ZIO.succeed(None)
    override def resolveOpenIssueReviewDecision(
      issueId: IssueId,
      resolutionKind: DecisionResolutionKind,
      actor: String,
      summary: String,
    ): IO[PersistenceError, Option[Decision]]                                                  = ZIO.succeed(None)
    override def escalate(id: DecisionId, reason: String): IO[PersistenceError, Decision]      =
      ZIO.fail(PersistenceError.QueryFailed("not-used", "not-used"))
    override def get(id: DecisionId): IO[PersistenceError, Decision]                           =
      ZIO.fail(PersistenceError.NotFound("decision", id.value))
    override def list(filter: DecisionFilter): IO[PersistenceError, List[Decision]]            = ZIO.succeed(Nil)
    override def runMaintenance(now: Instant): IO[PersistenceError, List[Decision]]            = ZIO.succeed(Nil)

  object CapturingDecisionInbox:
    def make: UIO[CapturingDecisionInbox] =
      Ref.make(List.empty[(String, String)]).map(new CapturingDecisionInbox(_))

  // ── Test fixture builder ────────────────────────────────────────────

  private final case class Fixture(
    daemon: TriageDaemonLive,
    issues: StubIssueRepo,
    decisions: CapturingDecisionInbox,
    cfg: ConnectorConfig,
    callCount: Ref[Int],
  )

  private def mkFixture(
    backlog: List[AgentIssue],
    cfg: ConnectorConfig = ApiConnectorConfig(ConnectorId.Mock),
    outcome: Either[LlmError, TriageOutcome] = Right(TriageOutcome.LaneAndNote(TicketLane.Custom, None)),
  ): UIO[Fixture] =
    for
      issues      <- StubIssueRepo.make(backlog)
      decisions   <- CapturingDecisionInbox.make
      activityHub <- StubActivityHub.make
      callCount   <- Ref.make(0)
      connector    = new StubConnector(outcome, callCount)
      daemon       = TriageDaemonLive(
                       issueRepository = issues,
                       promptLoader = new StubPromptLoader,
                       connectorConfigResolver = new StubResolver(cfg),
                       connectorRegistry = new StubRegistry(connector),
                       decisionInbox = decisions,
                       activityHub = activityHub,
                     )
    yield Fixture(daemon, issues, decisions, cfg, callCount)

  // ── Tests ───────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TriageDaemon")(
    test("happy path: triage agent picks a typed lane → LaneSet + TagsUpdated(triage:<note>) + MovedToTodo") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-1", title = "Fix login bug on Safari")),
                      outcome = Right(TriageOutcome.LaneAndNote(TicketLane.Frontend, Some("safari"))),
                    )
        outcome  <- fx.daemon.triageBatch(Set.empty, pmAgent)
        state    <- fx.issues.state
        events    = state(IssueId("i-1"))._2
        sentToInbox <- fx.decisions.captured
      yield assertTrue(
        outcome == TriageBatchOutcome(triaged = 1, escalated = 0, skipped = 0),
        events.exists {
          case lane: IssueEvent.LaneSet => lane.lane == TicketLane.Frontend && lane.setBy == pmAgent
          case _                        => false
        },
        events.exists {
          case tags: IssueEvent.TagsUpdated => tags.tags.contains("triage:safari")
          case _                            => false
        },
        events.exists(_.isInstanceOf[IssueEvent.MovedToTodo]),
        sentToInbox.isEmpty,
      )
    },
    test("lane without note: no TagsUpdated, but LaneSet + MovedToTodo still fire") {
      for
        fx    <- mkFixture(
                   backlog = List(backlogIssue("i-2")),
                   outcome = Right(TriageOutcome.LaneAndNote(TicketLane.Custom, None)),
                 )
        outcome <- fx.daemon.triageBatch(Set.empty, pmAgent)
        state   <- fx.issues.state
        events   = state(IssueId("i-2"))._2
      yield assertTrue(
        outcome.triaged == 1,
        events.count(_.isInstanceOf[IssueEvent.LaneSet]) == 1,
        events.count(_.isInstanceOf[IssueEvent.MovedToTodo]) == 1,
        events.collect { case t: IssueEvent.TagsUpdated => t }.isEmpty,
      )
    },
    test("clarify: no issue events, supervisor decision opened, awaiting-supervisor tag set") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-3", title = "hmm")),
                      outcome = Right(TriageOutcome.Clarify("What do you want?", List("A", "B"))),
                    )
        outcome  <- fx.daemon.triageBatch(Set.empty, pmAgent)
        state    <- fx.issues.state
        events    = state(IssueId("i-3"))._2
        captured <- fx.decisions.captured
      yield assertTrue(
        outcome == TriageBatchOutcome(triaged = 0, escalated = 1, skipped = 0),
        !events.exists(_.isInstanceOf[IssueEvent.LaneSet]),
        !events.exists(_.isInstanceOf[IssueEvent.MovedToTodo]),
        captured.size == 1,
        captured.head._2 == "What do you want?",
        captured.head._1.startsWith(s"$pmAgent needs clarification:"),
        events.collect { case t: IssueEvent.TagsUpdated => t }.exists(_.tags.contains(TriageDaemon.BackoffTag)),
      )
    },
    test("connector failure: no issue events, escalation opened, awaiting-supervisor tag set") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-4")),
                      outcome = Left(LlmError.ParseError("bad json", "raw")),
                    )
        outcome  <- fx.daemon.triageBatch(Set.empty, pmAgent)
        state    <- fx.issues.state
        events    = state(IssueId("i-4"))._2
        captured <- fx.decisions.captured
      yield assertTrue(
        outcome == TriageBatchOutcome(triaged = 0, escalated = 0, skipped = 1),
        !events.exists(_.isInstanceOf[IssueEvent.LaneSet]),
        !events.exists(_.isInstanceOf[IssueEvent.MovedToTodo]),
        captured.size == 1,
        captured.head._1.startsWith(s"$pmAgent couldn't triage:"),
        events.collect { case t: IssueEvent.TagsUpdated => t }.exists(_.tags.contains(TriageDaemon.BackoffTag)),
      )
    },
    test("auth failure surfaces as ConnectorFailure → escalation, not a thrown exception") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-5")),
                      outcome = Left(LlmError.AuthenticationError("missing API key")),
                    )
        outcome  <- fx.daemon.triageBatch(Set.empty, pmAgent)
        captured <- fx.decisions.captured
      yield assertTrue(
        outcome.skipped == 1,
        outcome.triaged == 0,
        captured.size == 1,
        captured.head._2.contains("AuthenticationError"),
        captured.head._2.contains("missing API key"),
      )
    },
    test("idempotency: an issue with a prior LaneSet is skipped") {
      for
        fx       <- mkFixture(backlog = List(backlogIssue("i-6")))
        _        <- fx.issues.append(IssueEvent.LaneSet(IssueId("i-6"), TicketLane.Backend, pmAgent, now))
        outcome  <- fx.daemon.triageBatch(Set.empty, pmAgent)
        callCnt  <- fx.callCount.get
      yield assertTrue(outcome.total == 0, callCnt == 0)
    },
    test("backoff: triage:awaiting-supervisor tag blocks re-triage") {
      for
        fx       <- mkFixture(backlog = List(backlogIssue("i-7", tags = List(TriageDaemon.BackoffTag))))
        outcome  <- fx.daemon.triageBatch(Set.empty, pmAgent)
        callCnt  <- fx.callCount.get
      yield assertTrue(outcome.total == 0, callCnt == 0)
    },
    test("workspace filter: only Backlog issues in the given workspace set are picked up") {
      val ws1   = backlogIssue("ws1-only").copy(workspaceId = Some("ws-1"))
      val ws2   = backlogIssue("ws2-only").copy(workspaceId = Some("ws-2"))
      val noWs  = backlogIssue("no-ws").copy(workspaceId = None)
      for
        fx       <- mkFixture(backlog = List(ws1, ws2, noWs))
        outcome  <- fx.daemon.triageBatch(Set("ws-1"), pmAgent)
        state    <- fx.issues.state
        triagedWs1 = state(IssueId("ws1-only"))._2.exists(_.isInstanceOf[IssueEvent.LaneSet])
        triagedWs2 = state(IssueId("ws2-only"))._2.exists(_.isInstanceOf[IssueEvent.LaneSet])
        triagedNoWs = state(IssueId("no-ws"))._2.exists(_.isInstanceOf[IssueEvent.LaneSet])
      yield assertTrue(outcome.triaged == 1, triagedWs1, !triagedWs2, !triagedNoWs)
    },
    test("empty workspace filter (Set.empty) picks up all Backlog issues regardless of workspace") {
      val ws1  = backlogIssue("a").copy(workspaceId = Some("ws-1"))
      val ws2  = backlogIssue("b").copy(workspaceId = Some("ws-2"))
      for
        fx      <- mkFixture(backlog = List(ws1, ws2))
        outcome <- fx.daemon.triageBatch(Set.empty, pmAgent)
      yield assertTrue(outcome.triaged == 2)
    },
    test("per-tick cap: at most PerTickLimit issues processed per call") {
      val many = (1 to (TriageDaemon.PerTickLimit + 3)).toList.map(n => backlogIssue(s"big-$n"))
      for
        fx      <- mkFixture(backlog = many)
        outcome <- fx.daemon.triageBatch(Set.empty, pmAgent)
      yield assertTrue(outcome.triaged == TriageDaemon.PerTickLimit)
    },
    test("title and description suggestions append TitleEdited + DescriptionEdited events stamped with agentName") {
      for
        fx     <- mkFixture(
                    backlog = List(backlogIssue("i-tt", title = "hmm", description = "vague")),
                    outcome = Right(
                      TriageOutcome.LaneAndNote(
                        TicketLane.Backend,
                        note = Some("clarified"),
                        titleSuggestion = Some("Refactor the auth middleware to drop session tokens"),
                        descriptionSuggestion = Some("Compliance requires we drop tokens from cookies before the audit."),
                      )
                    ),
                  )
        _      <- fx.daemon.triageBatch(Set.empty, pmAgent)
        state  <- fx.issues.state
        events  = state(IssueId("i-tt"))._2
      yield assertTrue(
        events.exists {
          case e: IssueEvent.TitleEdited       => e.title.contains("Refactor the auth middleware") && e.editedBy == pmAgent
          case _                               => false
        },
        events.exists {
          case e: IssueEvent.DescriptionEdited => e.description.contains("Compliance requires") && e.editedBy == pmAgent
          case _                               => false
        },
        events.exists(_.isInstanceOf[IssueEvent.LaneSet]),
        events.exists(_.isInstanceOf[IssueEvent.MovedToTodo]),
      )
    },
    test("blank or unchanged title/description suggestions do not emit edit events") {
      for
        fx     <- mkFixture(
                    backlog = List(backlogIssue("i-skip", title = "Add login bug fix", description = "details")),
                    outcome = Right(
                      TriageOutcome.LaneAndNote(
                        TicketLane.Custom,
                        titleSuggestion = Some("  Add login bug fix  "),
                        descriptionSuggestion = Some("   "),
                      )
                    ),
                  )
        _      <- fx.daemon.triageBatch(Set.empty, pmAgent)
        state  <- fx.issues.state
        events  = state(IssueId("i-skip"))._2
      yield assertTrue(
        !events.exists(_.isInstanceOf[IssueEvent.TitleEdited]),
        !events.exists(_.isInstanceOf[IssueEvent.DescriptionEdited]),
        events.exists(_.isInstanceOf[IssueEvent.LaneSet]),
      )
    },
    test("re-triage: an issue moved BACK to Backlog after a prior LaneSet gets re-triaged") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-re")),
                      outcome = Right(TriageOutcome.LaneAndNote(TicketLane.Testing, Some("flaky"))),
                    )
        _        <- fx.issues.append(IssueEvent.LaneSet(IssueId("i-re"), TicketLane.Frontend, pmAgent, now))
        _        <- fx.issues.append(IssueEvent.MovedToBacklog(IssueId("i-re"), now, now))
        outcome  <- fx.daemon.triageBatch(Set.empty, pmAgent)
        state    <- fx.issues.state
        events    = state(IssueId("i-re"))._2
        laneSets  = events.collect { case e: IssueEvent.LaneSet => e }
      yield assertTrue(
        outcome.triaged == 1,
        laneSets.size == 2,
        laneSets.last.lane == TicketLane.Testing,
      )
    },
    test("connector resolved per agent name passed to triageBatch") {
      val customAgent = "sarah" // simulating a renamed PM
      for
        called   <- Ref.make(List.empty[Option[String]])
        recordingResolver = new ConnectorConfigResolver:
          override def resolve(agentName: Option[String]): IO[PersistenceError, ConnectorConfig] =
            called.update(_ :+ agentName).as(ApiConnectorConfig(ConnectorId.Mock))
        issues      <- StubIssueRepo.make(List(backlogIssue("i-9")))
        decisions   <- CapturingDecisionInbox.make
        activityHub <- StubActivityHub.make
        callCount   <- Ref.make(0)
        connector    = new StubConnector(
                         Right(TriageOutcome.LaneAndNote(TicketLane.Custom, None)),
                         callCount,
                       )
        daemon       = TriageDaemonLive(
                         issueRepository = issues,
                         promptLoader = new StubPromptLoader,
                         connectorConfigResolver = recordingResolver,
                         connectorRegistry = new StubRegistry(connector),
                         decisionInbox = decisions,
                         activityHub = activityHub,
                       )
        _       <- daemon.triageBatch(Set.empty, customAgent)
        agents  <- called.get
      yield assertTrue(agents == List(Some(customAgent)))
    },
  )
