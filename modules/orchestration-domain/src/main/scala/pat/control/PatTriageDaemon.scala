package pat.control

import java.time.Instant

import zio.*
import zio.json.ast.Json

import _root_.config.control.ConnectorConfigResolver
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import decision.control.DecisionInbox
import decision.entity.DecisionUrgency
import issues.entity.*
import llm4zio.core.{ ApiConnector, CliConnector, ConnectorRegistry, LlmError, LlmService }
import llm4zio.tools.JsonSchema
import pat.entity.{ PatTriageBatchOutcome, PatTriageError, PatTriageOutcome }
import prompts.PromptLoader
import shared.errors.PersistenceError
import shared.ids.Ids.{ EventId, IssueId }

/** Pat's triage logic, invoked by `DaemonAgentScheduler` on its tick.
  *
  * Scans Backlog issues that haven't been triaged yet (no `LaneSet`
  * since the most recent backlog entry), filtered to the workspaces
  * the daemon spec covers, and asks Pat to triage them through whichever
  * connector Pat is configured for (CLI or API, per `/agents/pat/edit`).
  *
  * On a successful triage, appends `LaneSet` + optional
  * `TagsUpdated(triage:<note>)` + `MovedToTodo`, making the issue
  * dispatchable to Alex/Ben/Dana/Rex by the existing `AutoDispatcher`.
  * On failure or ambiguity, opens a Decision so the supervisor gets a
  * Telegram tap-to-resolve prompt.
  *
  * This is a stateless service — the scheduler owns the tick cadence
  * (`DaemonAgentSpec.trigger`) and the enable flag
  * (`DaemonAgentScheduler.setEnabled`).
  */
trait PatTriageDaemon:
  /** Triage one batch. Filters Backlog issues to the workspaces given;
    * empty set means "all workspaces". Errors are recovered into the
    * `skipped` counter; this method never fails.
    */
  def triageBatch(workspaceIds: Set[String]): UIO[PatTriageBatchOutcome]

object PatTriageDaemon:

  val PerTickLimit: Int           = 5
  val PerIssueTimeout: Duration   = 60.seconds

  // Tag suffix; `pat:awaiting-supervisor` blocks re-triage until the
  // supervisor resolves the decision (which clears the tag).
  val BackoffTag: String          = "pat:awaiting-supervisor"

  def triageBatch(workspaceIds: Set[String]): ZIO[PatTriageDaemon, Nothing, PatTriageBatchOutcome] =
    ZIO.serviceWithZIO[PatTriageDaemon](_.triageBatch(workspaceIds))

  val live
    : ZLayer[
      IssueRepository & PromptLoader & ConnectorConfigResolver & ConnectorRegistry
        & DecisionInbox & ActivityHub,
      Nothing,
      PatTriageDaemon,
    ] =
    ZLayer.fromFunction(PatTriageDaemonLive.apply)

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
  promptLoader: PromptLoader,
  connectorConfigResolver: ConnectorConfigResolver,
  connectorRegistry: ConnectorRegistry,
  decisionInbox: DecisionInbox,
  activityHub: ActivityHub,
) extends PatTriageDaemon:

  // ── public API ──────────────────────────────────────────────────────

  override def triageBatch(workspaceIds: Set[String]): UIO[PatTriageBatchOutcome] =
    pickCandidates(workspaceIds)
      .flatMap { candidates =>
        ZIO.foreach(candidates)(triageOne).map { results =>
          PatTriageBatchOutcome(
            triaged = results.count(_ == TriageResult.Triaged),
            escalated = results.count(_ == TriageResult.Escalated),
            skipped = results.count(_ == TriageResult.Skipped),
          )
        }
      }
      .catchAll(err =>
        ZIO
          .logWarning(s"pat-triage batch failed at list step: $err")
          .as(PatTriageBatchOutcome(0, 0, 0))
      )

  // ── candidate selection ─────────────────────────────────────────────

  private def pickCandidates(workspaceIds: Set[String]): IO[PersistenceError, List[AgentIssue]] =
    for
      all       <- issueRepository.list(
                     IssueFilter(states = Set(IssueStateTag.Backlog), limit = 100)
                   )
      scoped     = if workspaceIds.isEmpty then all
                   else all.filter(_.workspaceId.exists(workspaceIds.contains))
      withHist  <- ZIO.foreach(scoped)(issue =>
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

  private enum TriageResult:
    case Triaged, Escalated, Skipped

  private def triageOne(issue: AgentIssue): UIO[TriageResult] =
    publishStart(issue) *> attemptTriage(issue)
      .foldZIO(
        err     => handleFailure(issue, err).as(TriageResult.Skipped),
        result  => publishCompleted(issue).as(result),
      )

  private def attemptTriage(issue: AgentIssue): IO[PatTriageError, TriageResult] =
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
      result    <- applyOutcome(issue, outcome)
                     .mapError(PatTriageError.Storage.apply)
    yield result

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
  ): IO[PersistenceError, TriageResult] =
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
        yield TriageResult.Triaged

      case PatTriageOutcome.Clarify(question, options) =>
        openClarifyDecision(issue, question, options).as(TriageResult.Escalated)

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
