package pat.control

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
import pat.entity.PatTriageOutcome
import prompts.{ PromptError, PromptLoader }
import shared.errors.PersistenceError
import shared.ids.Ids.{ DecisionId, IssueId }
import shared.testfixtures.{ StubActivityHub, StubConfigRepository }

/** Locks the contract from spdd/PAT-TRIAGE-202605161630. The daemon
  * stubs the entire DI graph so we can drive every branch in the
  * canvas's Operations section without spinning up real connectors.
  */
object PatTriageDaemonSpec extends ZIOSpecDefault:

  // ── Fixtures ────────────────────────────────────────────────────────

  private val now: Instant = Instant.parse("2026-05-16T10:00:00Z")

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
    outcome: Either[LlmError, PatTriageOutcome],
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
    daemon: PatTriageDaemonLive,
    issues: StubIssueRepo,
    decisions: CapturingDecisionInbox,
    cfg: ConnectorConfig,
    callCount: Ref[Int],
  )

  private def mkFixture(
    backlog: List[AgentIssue],
    settings: Map[String, String] = Map("pat.triage.enabled" -> "true"),
    cfg: ConnectorConfig = ApiConnectorConfig(ConnectorId.Mock),
    outcome: Either[LlmError, PatTriageOutcome] = Right(PatTriageOutcome.LaneAndNote(TicketLane.Custom, None)),
  ): UIO[Fixture] =
    for
      issues      <- StubIssueRepo.make(backlog)
      configRepo  <- StubConfigRepository.make(settings)
      decisions   <- CapturingDecisionInbox.make
      activityHub <- StubActivityHub.make
      callCount   <- Ref.make(0)
      connector    = new StubConnector(outcome, callCount)
      daemon       = PatTriageDaemonLive(
                       issueRepository = issues,
                       configRepository = configRepo,
                       promptLoader = new StubPromptLoader,
                       connectorConfigResolver = new StubResolver(cfg),
                       connectorRegistry = new StubRegistry(connector),
                       decisionInbox = decisions,
                       activityHub = activityHub,
                     )
    yield Fixture(daemon, issues, decisions, cfg, callCount)

  // ── Tests ───────────────────────────────────────────────────────────

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PatTriageDaemon")(
    test("happy path: Pat picks a typed lane → LaneSet + TagsUpdated(triage:<note>) + MovedToTodo") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-1", title = "Fix login bug on Safari")),
                      outcome = Right(PatTriageOutcome.LaneAndNote(TicketLane.Frontend, Some("safari"))),
                    )
        moved    <- fx.daemon.triageOnce
        state    <- fx.issues.state
        events    = state(IssueId("i-1"))._2
        sentToInbox <- fx.decisions.captured
      yield assertTrue(
        moved == 1,
        events.exists {
          case lane: IssueEvent.LaneSet => lane.lane == TicketLane.Frontend && lane.setBy == "pat"
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
                   outcome = Right(PatTriageOutcome.LaneAndNote(TicketLane.Custom, None)),
                 )
        _     <- fx.daemon.triageOnce
        state <- fx.issues.state
        events = state(IssueId("i-2"))._2
      yield assertTrue(
        events.count(_.isInstanceOf[IssueEvent.LaneSet]) == 1,
        events.count(_.isInstanceOf[IssueEvent.MovedToTodo]) == 1,
        // No TagsUpdated event when Pat returns no note
        events.collect { case t: IssueEvent.TagsUpdated => t }.isEmpty,
      )
    },
    test("clarify: no issue events, supervisor decision opened, awaiting-supervisor tag set") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-3", title = "hmm")),
                      outcome = Right(PatTriageOutcome.Clarify("What do you want?", List("A", "B"))),
                    )
        moved    <- fx.daemon.triageOnce
        state    <- fx.issues.state
        events    = state(IssueId("i-3"))._2
        captured <- fx.decisions.captured
      yield assertTrue(
        moved == 1,
        !events.exists(_.isInstanceOf[IssueEvent.LaneSet]),
        !events.exists(_.isInstanceOf[IssueEvent.MovedToTodo]),
        captured.size == 1,
        captured.head._2 == "What do you want?",
        events.collect { case t: IssueEvent.TagsUpdated => t }.exists(_.tags.contains("pat:awaiting-supervisor")),
      )
    },
    test("connector failure: no issue events, escalation opened, awaiting-supervisor tag set") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-4")),
                      outcome = Left(LlmError.ParseError("bad json", "raw")),
                    )
        moved    <- fx.daemon.triageOnce
        state    <- fx.issues.state
        events    = state(IssueId("i-4"))._2
        captured <- fx.decisions.captured
      yield assertTrue(
        moved == 0, // failure path returns false
        !events.exists(_.isInstanceOf[IssueEvent.LaneSet]),
        !events.exists(_.isInstanceOf[IssueEvent.MovedToTodo]),
        captured.size == 1,
        captured.head._1.startsWith("Pat couldn't triage:"),
        events.collect { case t: IssueEvent.TagsUpdated => t }.exists(_.tags.contains("pat:awaiting-supervisor")),
      )
    },
    test("auth failure surfaces as ConnectorFailure → escalation, not a thrown exception") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-5")),
                      outcome = Left(LlmError.AuthenticationError("missing API key")),
                    )
        moved    <- fx.daemon.triageOnce
        captured <- fx.decisions.captured
      yield assertTrue(
        moved == 0,
        captured.size == 1,
        captured.head._2.contains("AuthenticationError"),
        captured.head._2.contains("missing API key"),
      )
    },
    test("idempotency: an issue with a prior LaneSet is skipped") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-6")),
                    )
        // Pre-seed a LaneSet event so needsTriage returns false
        _        <- fx.issues.append(IssueEvent.LaneSet(IssueId("i-6"), TicketLane.Backend, "pat", now))
        moved    <- fx.daemon.triageOnce
        callCnt  <- fx.callCount.get
      yield assertTrue(
        moved == 0,
        callCnt == 0, // connector never invoked
      )
    },
    test("backoff: awaiting-supervisor tag blocks re-triage") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-7", tags = List("pat:awaiting-supervisor"))),
                    )
        moved    <- fx.daemon.triageOnce
        callCnt  <- fx.callCount.get
      yield assertTrue(
        moved == 0,
        callCnt == 0,
      )
    },
    test("disabled: pat.triage.enabled=false short-circuits with zero connector calls") {
      for
        fx       <- mkFixture(
                      backlog = List(backlogIssue("i-8")),
                      settings = Map("pat.triage.enabled" -> "false"),
                    )
        moved    <- fx.daemon.triageOnce
        callCnt  <- fx.callCount.get
      yield assertTrue(
        moved == 0,
        callCnt == 0,
      )
    },
    test("per-tick cap: at most PerTickLimit issues processed per call") {
      val many = (1 to (PatTriageDaemon.PerTickLimit + 3)).toList.map(n => backlogIssue(s"big-$n"))
      for
        fx    <- mkFixture(backlog = many)
        moved <- fx.daemon.triageOnce
      yield assertTrue(moved == PatTriageDaemon.PerTickLimit)
    },
    test("title and description suggestions append TitleEdited + DescriptionEdited events") {
      for
        fx     <- mkFixture(
                    backlog = List(backlogIssue("i-tt", title = "hmm", description = "vague")),
                    outcome = Right(
                      PatTriageOutcome.LaneAndNote(
                        TicketLane.Backend,
                        note = Some("clarified"),
                        titleSuggestion = Some("Refactor the auth middleware to drop session tokens"),
                        descriptionSuggestion = Some("Compliance requires we drop tokens from cookies before the audit."),
                      )
                    ),
                  )
        _      <- fx.daemon.triageOnce
        state  <- fx.issues.state
        events  = state(IssueId("i-tt"))._2
      yield assertTrue(
        events.exists {
          case e: IssueEvent.TitleEdited       => e.title.contains("Refactor the auth middleware") && e.editedBy == "pat"
          case _                               => false
        },
        events.exists {
          case e: IssueEvent.DescriptionEdited => e.description.contains("Compliance requires") && e.editedBy == "pat"
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
                      PatTriageOutcome.LaneAndNote(
                        TicketLane.Custom,
                        titleSuggestion = Some("  Add login bug fix  "), // same after trim
                        descriptionSuggestion = Some("   "),              // blank
                      )
                    ),
                  )
        _      <- fx.daemon.triageOnce
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
        fx     <- mkFixture(
                    backlog = List(backlogIssue("i-re")),
                    outcome = Right(PatTriageOutcome.LaneAndNote(TicketLane.Testing, Some("flaky"))),
                  )
        // Seed prior triage history: LaneSet, then a MovedToBacklog event
        // simulating the issue being kicked back to Backlog from a later
        // column (e.g. Rework, manual move).
        _      <- fx.issues.append(IssueEvent.LaneSet(IssueId("i-re"), TicketLane.Frontend, "pat", now))
        _      <- fx.issues.append(IssueEvent.MovedToBacklog(IssueId("i-re"), now, now))
        moved  <- fx.daemon.triageOnce
        state  <- fx.issues.state
        events  = state(IssueId("i-re"))._2
        laneSets = events.collect { case e: IssueEvent.LaneSet => e }
      yield assertTrue(
        moved == 1,
        laneSets.size == 2,                          // original + re-triage
        laneSets.last.lane == TicketLane.Testing,    // re-triage decision wins
      )
    },
    test("connector resolved per agent name (pat) — verifies the daemon doesn't fall back to global") {
      for
        called  <- Ref.make(List.empty[Option[String]])
        recordingResolver = new ConnectorConfigResolver:
          override def resolve(agentName: Option[String]): IO[PersistenceError, ConnectorConfig] =
            called.update(_ :+ agentName).as(ApiConnectorConfig(ConnectorId.Mock))
        issues      <- StubIssueRepo.make(List(backlogIssue("i-9")))
        configRepo  <- StubConfigRepository.make(Map("pat.triage.enabled" -> "true"))
        decisions   <- CapturingDecisionInbox.make
        activityHub <- StubActivityHub.make
        callCount   <- Ref.make(0)
        connector    = new StubConnector(
                         Right(PatTriageOutcome.LaneAndNote(TicketLane.Custom, None)),
                         callCount,
                       )
        daemon       = PatTriageDaemonLive(
                         issueRepository = issues,
                         configRepository = configRepo,
                         promptLoader = new StubPromptLoader,
                         connectorConfigResolver = recordingResolver,
                         connectorRegistry = new StubRegistry(connector),
                         decisionInbox = decisions,
                         activityHub = activityHub,
                       )
        _       <- daemon.triageOnce
        agents  <- called.get
      yield assertTrue(agents == List(Some("pat")))
    },
  )
