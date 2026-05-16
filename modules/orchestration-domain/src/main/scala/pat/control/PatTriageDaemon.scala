package pat.control

import java.time.Instant

import zio.*
import zio.json.ast.Json

import _root_.config.control.ConnectorConfigResolver
import _root_.config.entity.ConfigRepository
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import decision.control.DecisionInbox
import decision.entity.DecisionUrgency
import issues.entity.*
import llm4zio.core.{ ApiConnector, CliConnector, ConnectorRegistry, LlmError, LlmService }
import llm4zio.tools.JsonSchema
import pat.entity.{ PatTriageError, PatTriageOutcome }
import prompts.PromptLoader
import shared.errors.PersistenceError
import shared.ids.Ids.{ EventId, IssueId }

/** Background daemon: scans Backlog issues without a `LaneSet` and asks
  * Pat to triage them through whichever connector Pat is configured for
  * (CLI or API, per `/agents/pat/edit`). On a successful triage, appends
  * `LaneSet` + optional `TagsUpdated(triage:<note>)` + `MovedToTodo`,
  * making the issue dispatchable to Alex/Ben/Dana/Rex by the existing
  * `AutoDispatcher`. On failure or ambiguity, opens a Decision so the
  * supervisor gets a Telegram tap-to-resolve prompt.
  */
trait PatTriageDaemon:
  def triageOnce: UIO[Int]

object PatTriageDaemon:

  /** Settings keys. `enabled` defaults to off for back-compat; new
    * installs flip it on through onboarding.
    */
  val EnabledKey: String          = "pat.triage.enabled"
  val IntervalKey: String         = "pat.triage.intervalSeconds"
  val DefaultIntervalSec: Long    = 60L
  val PerTickLimit: Int           = 5
  val PerIssueTimeout: Duration   = 60.seconds

  // Tag suffix; `pat:awaiting-supervisor` blocks re-triage until the
  // supervisor resolves the decision (which clears the tag).
  val BackoffTag: String          = "pat:awaiting-supervisor"

  def triageOnce: ZIO[PatTriageDaemon, Nothing, Int] =
    ZIO.serviceWithZIO[PatTriageDaemon](_.triageOnce)

  val live
    : ZLayer[
      IssueRepository & ConfigRepository & PromptLoader & ConnectorConfigResolver
        & ConnectorRegistry & DecisionInbox & ActivityHub,
      Nothing,
      PatTriageDaemon,
    ] =
    ZLayer.scoped {
      for
        issueRepository <- ZIO.service[IssueRepository]
        configRepo      <- ZIO.service[ConfigRepository]
        promptLoader    <- ZIO.service[PromptLoader]
        resolver        <- ZIO.service[ConnectorConfigResolver]
        registry        <- ZIO.service[ConnectorRegistry]
        decisionInbox   <- ZIO.service[DecisionInbox]
        activityHub     <- ZIO.service[ActivityHub]
        daemon           = PatTriageDaemonLive(
                             issueRepository,
                             configRepo,
                             promptLoader,
                             resolver,
                             registry,
                             decisionInbox,
                             activityHub,
                           )
        _               <- daemon.runLoop.forkScoped
      yield daemon
    }

  /** JSON schema handed to `executeStructured`. `Json` is what
    * `llm4zio.tools.JsonSchema` aliases to.
    */
  private[control] val schema: JsonSchema =
    Json.Obj(
      "type"        -> Json.Str("object"),
      "description" -> Json.Str("Pat's triage decision for a single backlog issue."),
      "oneOf"       -> Json.Arr(
        Json.Obj(
          "type"                 -> Json.Str("object"),
          "required"             -> Json.Arr(Json.Str("lane")),
          "additionalProperties" -> Json.Bool(false),
          "properties"           -> Json.Obj(
            "lane"                  -> Json.Obj(
              "type" -> Json.Str("string"),
              "enum" -> Json.Arr(
                Json.Str("Frontend"),
                Json.Str("Backend"),
                Json.Str("Testing"),
                Json.Str("Triage"),
                Json.Str("Review"),
                Json.Str("Custom"),
              ),
            ),
            "note"                  -> Json.Obj("type" -> Json.Str("string")),
            "titleSuggestion"       -> Json.Obj("type" -> Json.Str("string")),
            "descriptionSuggestion" -> Json.Obj("type" -> Json.Str("string")),
          ),
        ),
        Json.Obj(
          "type"                 -> Json.Str("object"),
          "required"             -> Json.Arr(Json.Str("clarify")),
          "additionalProperties" -> Json.Bool(false),
          "properties"           -> Json.Obj(
            "clarify" -> Json.Obj("type" -> Json.Str("string")),
            "options" -> Json.Obj(
              "type"  -> Json.Str("array"),
              "items" -> Json.Obj("type" -> Json.Str("string")),
            ),
          ),
        ),
      ),
    )

  /** Filter Backlog issues that haven't been triaged for this stint in
    * the Backlog column. Re-triage support: an issue that was triaged,
    * worked on, then sent back to Backlog (via `MovedToBacklog`) becomes
    * a candidate again — its old `LaneSet` is "before" the most recent
    * backlog entry and is therefore stale.
    */
  private[control] def needsTriage(
    issue: AgentIssue,
    history: List[IssueEvent],
  ): Boolean =
    issue.state.isInstanceOf[IssueState.Backlog] &&
      !issue.tags.contains(BackoffTag) && {
        val lastLaneSet = history.lastIndexWhere(_.isInstanceOf[IssueEvent.LaneSet])
        if lastLaneSet < 0 then true // never triaged → always a candidate
        else
          // Re-triage if a fresh backlog entry (`Created` or
          // `MovedToBacklog`) followed the most recent `LaneSet`.
          val lastBacklogEntry = history.lastIndexWhere(event =>
            event.isInstanceOf[IssueEvent.Created] ||
              event.isInstanceOf[IssueEvent.MovedToBacklog]
          )
          lastLaneSet < lastBacklogEntry
      }

final case class PatTriageDaemonLive(
  issueRepository: IssueRepository,
  configRepository: ConfigRepository,
  promptLoader: PromptLoader,
  connectorConfigResolver: ConnectorConfigResolver,
  connectorRegistry: ConnectorRegistry,
  decisionInbox: DecisionInbox,
  activityHub: ActivityHub,
) extends PatTriageDaemon:

  // ── public API ──────────────────────────────────────────────────────

  override def triageOnce: UIO[Int] =
    isEnabled.flatMap {
      case false => ZIO.succeed(0)
      case true  =>
        pickCandidates
          .flatMap(candidates =>
            ZIO.foreach(candidates)(triageOne).map(_.count(identity))
          )
          .catchAll(err =>
            ZIO.logWarning(s"pat-triage tick failed at list step: $err").as(0)
          )
    }

  // ── loop ────────────────────────────────────────────────────────────

  private[control] def runLoop: UIO[Nothing] =
    val tick = triageOnce *> intervalDuration.flatMap(ZIO.sleep(_))
    tick.forever

  private def intervalDuration: UIO[Duration] =
    configRepository
      .getSetting(PatTriageDaemon.IntervalKey)
      .map(opt =>
        opt
          .flatMap(_.value.toLongOption)
          .filter(_ > 0L)
          .getOrElse(PatTriageDaemon.DefaultIntervalSec)
      )
      .catchAll(_ => ZIO.succeed(PatTriageDaemon.DefaultIntervalSec))
      .map(_.seconds)

  private def isEnabled: UIO[Boolean] =
    configRepository
      .getSetting(PatTriageDaemon.EnabledKey)
      .map(_.exists(_.value.equalsIgnoreCase("true")))
      .catchAll(_ => ZIO.succeed(false))

  // ── candidate selection ─────────────────────────────────────────────

  private def pickCandidates: IO[PersistenceError, List[AgentIssue]] =
    for
      all       <- issueRepository.list(
                     IssueFilter(states = Set(IssueStateTag.Backlog), limit = 100)
                   )
      withHist  <- ZIO.foreach(all)(issue =>
                     issueRepository.history(issue.id).map(history => issue -> history)
                   )
      filtered   = withHist.collect {
                     case (issue, history) if PatTriageDaemon.needsTriage(issue, history) => issue
                   }
      // Oldest first so the queue drains predictably under load.
      sorted     = filtered.sortBy(backlogEnteredAt)
      capped     = sorted.take(PatTriageDaemon.PerTickLimit)
    yield capped

  private def backlogEnteredAt(issue: AgentIssue): Instant =
    issue.state match
      case IssueState.Backlog(at) => at
      case _                      => Instant.EPOCH

  // ── per-issue triage ────────────────────────────────────────────────

  private def triageOne(issue: AgentIssue): UIO[Boolean] =
    publishStart(issue) *> attemptTriage(issue)
      .foldZIO(
        err     => handleFailure(issue, err).as(false),
        applied => publishCompleted(issue).as(applied),
      )

  private def attemptTriage(issue: AgentIssue): IO[PatTriageError, Boolean] =
    for
      cfg       <- connectorConfigResolver
                     .resolve(Some("pat"))
                     .mapError(PatTriageError.Storage.apply)
      connector <- connectorRegistry
                     .resolve(cfg)
                     .mapError(PatTriageError.ConnectorFailure.apply)
      llm       <- asLlmService(connector)
                     .mapError(PatTriageError.ConnectorFailure.apply)
      prompt    <- renderPrompt(issue).mapError(err =>
                     PatTriageError.Storage(PersistenceError.QueryFailed("pat-triage-prompt", err.toString))
                   )
      outcome   <- llm
                     .executeStructured[PatTriageOutcome](prompt, PatTriageDaemon.schema)
                     .mapError(PatTriageError.ConnectorFailure.apply)
                     .timeout(PatTriageDaemon.PerIssueTimeout)
                     .someOrFail(PatTriageError.Timeout)
      applied   <- applyOutcome(issue, outcome)
                     .mapError(PatTriageError.Storage.apply)
    yield applied

  // CLI connectors may or may not also extend LlmService — only the ones
  // that wire up `executeStructured` do. Mirrors the same downcast
  // ConfigAwareLlmService does, with a typed failure if the connector
  // can't produce structured output (e.g. a CLI tool wired for
  // continuation-only chat).
  private def asLlmService(connector: llm4zio.core.Connector): IO[LlmError, LlmService] =
    connector match
      case api: ApiConnector  => ZIO.succeed(api)
      case cli: CliConnector  =>
        cli match
          case svc: LlmService => ZIO.succeed(svc)
          case _               =>
            ZIO.fail(
              LlmError.ConfigError(
                s"CLI connector ${cli.id.value} doesn't support structured triage output — " +
                  "pick a CLI that implements LlmService (e.g. claude, gemini, opencode)."
              )
            )

  private def renderPrompt(issue: AgentIssue): IO[prompts.PromptError, String] =
    promptLoader.load(
      "pat-triage",
      Map(
        "issueTitle"       -> issue.title,
        "issueDescription" -> Option(issue.description).getOrElse(""),
      ),
    )

  // ── result application ──────────────────────────────────────────────

  private def applyOutcome(
    issue: AgentIssue,
    outcome: PatTriageOutcome,
  ): IO[PersistenceError, Boolean] =
    outcome match
      case PatTriageOutcome.LaneAndNote(lane, note, titleSuggestion, descriptionSuggestion) =>
        for
          now            <- Clock.instant
          // Optional refinements first so the lane/move events see the
          // updated title/description if downstream readers care about
          // event ordering.
          _              <- appendIfNewTitle(issue, titleSuggestion, now)
          _              <- appendIfNewDescription(issue, descriptionSuggestion, now)
          _              <- issueRepository.append(IssueEvent.LaneSet(issue.id, lane, "pat", now))
          _              <- note.map(_.trim).filter(_.nonEmpty) match
                              case None        => ZIO.unit
                              case Some(value) =>
                                issueRepository.append(
                                  IssueEvent.TagsUpdated(
                                    issue.id,
                                    (issue.tags :+ s"triage:$value").distinct,
                                    now,
                                  )
                                )
          _              <- issueRepository.append(IssueEvent.MovedToTodo(issue.id, movedAt = now, occurredAt = now))
        yield true

      case PatTriageOutcome.Clarify(question, options) =>
        openClarifyDecision(issue, question, options).as(true)

  private def appendIfNewTitle(
    issue: AgentIssue,
    suggestion: Option[String],
    now: Instant,
  ): IO[PersistenceError, Unit] =
    suggestion.map(_.trim).filter(_.nonEmpty).filter(_ != issue.title.trim) match
      case None        => ZIO.unit
      case Some(value) =>
        issueRepository.append(IssueEvent.TitleEdited(issue.id, value, "pat", now))

  private def appendIfNewDescription(
    issue: AgentIssue,
    suggestion: Option[String],
    now: Instant,
  ): IO[PersistenceError, Unit] =
    suggestion.map(_.trim).filter(_.nonEmpty).filter(_ != Option(issue.description).map(_.trim).getOrElse("")) match
      case None        => ZIO.unit
      case Some(value) =>
        issueRepository.append(IssueEvent.DescriptionEdited(issue.id, value, "pat", now))

  // ── failure handling ────────────────────────────────────────────────

  private def handleFailure(issue: AgentIssue, err: PatTriageError): UIO[Unit] =
    val message = renderError(err)
    (for
      _   <- ZIO.logWarning(s"pat-triage failed for ${issue.id.value}: $message")
      _   <- openFailureDecision(issue, message)
      _   <- markAwaitingSupervisor(issue)
      _   <- publishFailed(issue, message)
    yield ()).catchAll(persistErr =>
      ZIO.logWarning(s"pat-triage could not record failure for ${issue.id.value}: $persistErr")
    )

  private def openClarifyDecision(
    issue: AgentIssue,
    question: String,
    @scala.annotation.unused options: List[String],
  ): IO[PersistenceError, Unit] =
    decisionInbox
      .openManualDecision(
        title = s"Pat needs clarification: ${issue.title}",
        context = Option(issue.description).getOrElse(""),
        referenceId = issue.id.value,
        summary = question,
        urgency = DecisionUrgency.Medium,
        workspaceId = issue.workspaceId,
        issueId = Some(issue.id),
      )
      .unit
      .zipLeft(markAwaitingSupervisor(issue))

  private def openFailureDecision(issue: AgentIssue, reason: String): IO[PersistenceError, Unit] =
    decisionInbox
      .openManualDecision(
        title = s"Pat couldn't triage: ${issue.title}",
        context = Option(issue.description).getOrElse(""),
        referenceId = issue.id.value,
        summary = reason,
        urgency = DecisionUrgency.Medium,
        workspaceId = issue.workspaceId,
        issueId = Some(issue.id),
      )
      .unit

  private def markAwaitingSupervisor(issue: AgentIssue): IO[PersistenceError, Unit] =
    if issue.tags.contains(PatTriageDaemon.BackoffTag) then ZIO.unit
    else
      for
        now <- Clock.instant
        _   <- issueRepository.append(
                 IssueEvent.TagsUpdated(
                   issue.id,
                   (issue.tags :+ PatTriageDaemon.BackoffTag).distinct,
                   now,
                 )
               )
      yield ()

  // ── activity events ─────────────────────────────────────────────────

  private def publishStart(issue: AgentIssue): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- activityHub.publish(
               ActivityEvent(
                 id = EventId.generate,
                 eventType = ActivityEventType.RunStarted,
                 source = "pat-triage",
                 agentName = Some("pat"),
                 summary = s"Triaging issue ${issue.id.value}",
                 createdAt = now,
               )
             )
    yield ()

  private def publishCompleted(issue: AgentIssue): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- activityHub.publish(
               ActivityEvent(
                 id = EventId.generate,
                 eventType = ActivityEventType.RunCompleted,
                 source = "pat-triage",
                 agentName = Some("pat"),
                 summary = s"Triaged issue ${issue.id.value}",
                 createdAt = now,
               )
             )
    yield ()

  private def publishFailed(issue: AgentIssue, reason: String): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- activityHub.publish(
               ActivityEvent(
                 id = EventId.generate,
                 eventType = ActivityEventType.RunFailed,
                 source = "pat-triage",
                 agentName = Some("pat"),
                 summary = s"Pat-triage failed for ${issue.id.value}: $reason",
                 createdAt = now,
               )
             )
    yield ()

  private def renderError(err: PatTriageError): String =
    err match
      case PatTriageError.ConnectorFailure(cause) =>
        s"Connector failure: ${describeLlmError(cause)}"
      case PatTriageError.Timeout                 =>
        s"Pat's connector timed out after ${PatTriageDaemon.PerIssueTimeout.toMillis}ms"
      case PatTriageError.Storage(cause)          =>
        s"Storage failure: $cause"

  // `LlmError extends Throwable` but the case classes don't pass their
  // `message` field into the Throwable constructor, so `getMessage`
  // returns null. Pattern-match the variants to surface real context.
  private def describeLlmError(err: LlmError): String =
    err match
      case LlmError.ProviderError(message, _)      => s"ProviderError: $message"
      case LlmError.RateLimitError(retryAfter)     => s"RateLimitError(retryAfter=$retryAfter)"
      case LlmError.AuthenticationError(message)   => s"AuthenticationError: $message"
      case LlmError.InvalidRequestError(message)   => s"InvalidRequestError: $message"
      case LlmError.TimeoutError(duration)         => s"TimeoutError($duration)"
      case LlmError.ParseError(message, raw)       =>
        val rawTail = Option(raw).map(_.takeRight(200)).getOrElse("")
        s"ParseError: $message (raw tail: $rawTail)"
      case LlmError.ToolError(toolName, message)   => s"ToolError($toolName): $message"
      case LlmError.ConfigError(message)           => s"ConfigError: $message"
      case LlmError.TurnLimitError(limit)          => s"TurnLimitError(limit=$limit)"
