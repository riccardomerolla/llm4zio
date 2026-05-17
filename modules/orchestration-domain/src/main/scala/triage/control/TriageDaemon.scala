package triage.control

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
import prompts.PromptLoader
import shared.errors.PersistenceError
import shared.ids.Ids.{ EventId, IssueId }
import triage.entity.{ TriageBatchOutcome, TriageError, TriageOutcome }

/** Triage worker, invoked by `DaemonAgentScheduler` on its tick.
  *
  * Scans Backlog issues that haven't been triaged yet (no `LaneSet`
  * since the most recent backlog entry), filtered to the workspaces
  * the daemon spec covers, and asks the configured triage agent to
  * triage them through whichever connector that agent is wired to
  * (CLI or API, per `/agents/<name>/edit`).
  *
  * On a successful triage, appends `LaneSet` + optional
  * `TagsUpdated(triage:<note>)` + `MovedToTodo`, making the issue
  * dispatchable to the lane-matched engineer by the existing
  * `AutoDispatcher`. On failure or ambiguity, opens a Decision so the
  * supervisor gets a Telegram tap-to-resolve prompt.
  *
  * This is a stateless service — the scheduler owns the tick cadence
  * (`DaemonAgentSpec.trigger`) and the enable flag
  * (`DaemonAgentScheduler.setEnabled`).
  */
trait TriageDaemon:
  /** Triage one batch.
    *
    * @param workspaceIds Backlog issues whose workspace is in this set
    *                     are picked up; empty set means "all workspaces".
    * @param agentName    Which agent does the triage call (resolved
    *                     against the connector + registry, used as the
    *                     `setBy` field on `LaneSet`, and stamped on the
    *                     activity events). Comes from
    *                     `DaemonAgentSpec.agentName`.
    *
    * Errors are recovered into the `skipped` counter; this method
    * never fails.
    */
  def triageBatch(workspaceIds: Set[String], agentName: String): UIO[TriageBatchOutcome]

object TriageDaemon:

  val PerTickLimit: Int           = 5
  val PerIssueTimeout: Duration   = 60.seconds

  /** Backlog issues with this tag are parked until the supervisor
    * resolves the open Decision. Tag is generic (not agent-specific)
    * so it survives a rename of the triage agent.
    */
  val BackoffTag: String          = "triage:awaiting-supervisor"

  def triageBatch(workspaceIds: Set[String], agentName: String)
    : ZIO[TriageDaemon, Nothing, TriageBatchOutcome] =
    ZIO.serviceWithZIO[TriageDaemon](_.triageBatch(workspaceIds, agentName))

  val live
    : ZLayer[
      IssueRepository & PromptLoader & ConnectorConfigResolver & ConnectorRegistry
        & DecisionInbox & ActivityHub,
      Nothing,
      TriageDaemon,
    ] =
    ZLayer.fromFunction(TriageDaemonLive.apply)

  /** JSON schema handed to `executeStructured`. `Json` is what
    * `llm4zio.tools.JsonSchema` aliases to.
    */
  private[control] val schema: JsonSchema =
    Json.Obj(
      "type"        -> Json.Str("object"),
      "description" -> Json.Str("Triage decision for a single backlog issue."),
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
    * worked on, then sent back to Backlog (via `MovedToBacklog`)
    * becomes a candidate again — its old `LaneSet` is "before" the
    * most recent backlog entry and is therefore stale.
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

final case class TriageDaemonLive(
  issueRepository: IssueRepository,
  promptLoader: PromptLoader,
  connectorConfigResolver: ConnectorConfigResolver,
  connectorRegistry: ConnectorRegistry,
  decisionInbox: DecisionInbox,
  activityHub: ActivityHub,
) extends TriageDaemon:

  // ── public API ──────────────────────────────────────────────────────

  override def triageBatch(workspaceIds: Set[String], agentName: String): UIO[TriageBatchOutcome] =
    pickCandidates(workspaceIds)
      .flatMap { candidates =>
        ZIO.foreach(candidates)(issue => triageOne(issue, agentName)).map { results =>
          TriageBatchOutcome(
            triaged = results.count(_ == TriageResult.Triaged),
            escalated = results.count(_ == TriageResult.Escalated),
            skipped = results.count(_ == TriageResult.Skipped),
          )
        }
      }
      .catchAll(err =>
        ZIO
          .logWarning(s"triage batch ($agentName) failed at list step: $err")
          .as(TriageBatchOutcome(0, 0, 0))
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
                     case (issue, history) if TriageDaemon.needsTriage(issue, history) => issue
                   }
      // Oldest first so the queue drains predictably under load.
      sorted     = filtered.sortBy(backlogEnteredAt)
      capped     = sorted.take(TriageDaemon.PerTickLimit)
    yield capped

  private def backlogEnteredAt(issue: AgentIssue): Instant =
    issue.state match
      case IssueState.Backlog(at) => at
      case _                      => Instant.EPOCH

  // ── per-issue triage ────────────────────────────────────────────────

  private enum TriageResult:
    case Triaged, Escalated, Skipped

  private def triageOne(issue: AgentIssue, agentName: String): UIO[TriageResult] =
    publishStart(issue, agentName) *> attemptTriage(issue, agentName)
      .foldZIO(
        err     => handleFailure(issue, agentName, err).as(TriageResult.Skipped),
        result  => publishCompleted(issue, agentName).as(result),
      )

  private def attemptTriage(issue: AgentIssue, agentName: String): IO[TriageError, TriageResult] =
    for
      cfg       <- connectorConfigResolver
                     .resolve(Some(agentName))
                     .mapError(TriageError.Storage.apply)
      connector <- connectorRegistry
                     .resolve(cfg)
                     .mapError(TriageError.ConnectorFailure.apply)
      llm       <- asLlmService(connector)
                     .mapError(TriageError.ConnectorFailure.apply)
      prompt    <- renderPrompt(issue).mapError(err =>
                     TriageError.Storage(PersistenceError.QueryFailed("triage-prompt", err.toString))
                   )
      outcome   <- llm
                     .executeStructured[TriageOutcome](prompt, TriageDaemon.schema)
                     .mapError(TriageError.ConnectorFailure.apply)
                     .timeout(TriageDaemon.PerIssueTimeout)
                     .someOrFail(TriageError.Timeout)
      result    <- applyOutcome(issue, agentName, outcome)
                     .mapError(TriageError.Storage.apply)
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
      "triage",
      Map(
        "issueTitle"       -> issue.title,
        "issueDescription" -> Option(issue.description).getOrElse(""),
      ),
    )

  // ── result application ──────────────────────────────────────────────

  private def applyOutcome(
    issue: AgentIssue,
    agentName: String,
    outcome: TriageOutcome,
  ): IO[PersistenceError, TriageResult] =
    outcome match
      case TriageOutcome.LaneAndNote(lane, note, titleSuggestion, descriptionSuggestion) =>
        for
          now            <- Clock.instant
          // Optional refinements first so the lane/move events see the
          // updated title/description if downstream readers care about
          // event ordering.
          _              <- appendIfNewTitle(issue, agentName, titleSuggestion, now)
          _              <- appendIfNewDescription(issue, agentName, descriptionSuggestion, now)
          _              <- issueRepository.append(IssueEvent.LaneSet(issue.id, lane, agentName, now))
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

      case TriageOutcome.Clarify(question, options) =>
        openClarifyDecision(issue, agentName, question, options).as(TriageResult.Escalated)

  private def appendIfNewTitle(
    issue: AgentIssue,
    agentName: String,
    suggestion: Option[String],
    now: Instant,
  ): IO[PersistenceError, Unit] =
    suggestion.map(_.trim).filter(_.nonEmpty).filter(_ != issue.title.trim) match
      case None        => ZIO.unit
      case Some(value) =>
        issueRepository.append(IssueEvent.TitleEdited(issue.id, value, agentName, now))

  private def appendIfNewDescription(
    issue: AgentIssue,
    agentName: String,
    suggestion: Option[String],
    now: Instant,
  ): IO[PersistenceError, Unit] =
    suggestion.map(_.trim).filter(_.nonEmpty).filter(_ != Option(issue.description).map(_.trim).getOrElse("")) match
      case None        => ZIO.unit
      case Some(value) =>
        issueRepository.append(IssueEvent.DescriptionEdited(issue.id, value, agentName, now))

  // ── failure handling ────────────────────────────────────────────────

  private def handleFailure(issue: AgentIssue, agentName: String, err: TriageError): UIO[Unit] =
    val message = renderError(err)
    (for
      _   <- ZIO.logWarning(s"triage ($agentName) failed for ${issue.id.value}: $message")
      _   <- openFailureDecision(issue, agentName, message)
      _   <- markAwaitingSupervisor(issue)
      _   <- publishFailed(issue, agentName, message)
    yield ()).catchAll(persistErr =>
      ZIO.logWarning(s"triage could not record failure for ${issue.id.value}: $persistErr")
    )

  private def openClarifyDecision(
    issue: AgentIssue,
    agentName: String,
    question: String,
    @scala.annotation.unused options: List[String],
  ): IO[PersistenceError, Unit] =
    decisionInbox
      .openManualDecision(
        title = s"$agentName needs clarification: ${issue.title}",
        context = Option(issue.description).getOrElse(""),
        referenceId = issue.id.value,
        summary = question,
        urgency = DecisionUrgency.Medium,
        workspaceId = issue.workspaceId,
        issueId = Some(issue.id),
      )
      .unit
      .zipLeft(markAwaitingSupervisor(issue))

  private def openFailureDecision(
    issue: AgentIssue,
    agentName: String,
    reason: String,
  ): IO[PersistenceError, Unit] =
    decisionInbox
      .openManualDecision(
        title = s"$agentName couldn't triage: ${issue.title}",
        context = Option(issue.description).getOrElse(""),
        referenceId = issue.id.value,
        summary = reason,
        urgency = DecisionUrgency.Medium,
        workspaceId = issue.workspaceId,
        issueId = Some(issue.id),
      )
      .unit

  private def markAwaitingSupervisor(issue: AgentIssue): IO[PersistenceError, Unit] =
    if issue.tags.contains(TriageDaemon.BackoffTag) then ZIO.unit
    else
      for
        now <- Clock.instant
        _   <- issueRepository.append(
                 IssueEvent.TagsUpdated(
                   issue.id,
                   (issue.tags :+ TriageDaemon.BackoffTag).distinct,
                   now,
                 )
               )
      yield ()

  // ── activity events ─────────────────────────────────────────────────

  private def publishStart(issue: AgentIssue, agentName: String): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- activityHub.publish(
               ActivityEvent(
                 id = EventId.generate,
                 eventType = ActivityEventType.RunStarted,
                 source = "triage",
                 agentName = Some(agentName),
                 summary = s"Triaging issue ${issue.id.value}",
                 createdAt = now,
               )
             )
    yield ()

  private def publishCompleted(issue: AgentIssue, agentName: String): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- activityHub.publish(
               ActivityEvent(
                 id = EventId.generate,
                 eventType = ActivityEventType.RunCompleted,
                 source = "triage",
                 agentName = Some(agentName),
                 summary = s"Triaged issue ${issue.id.value}",
                 createdAt = now,
               )
             )
    yield ()

  private def publishFailed(issue: AgentIssue, agentName: String, reason: String): UIO[Unit] =
    for
      now <- Clock.instant
      _   <- activityHub.publish(
               ActivityEvent(
                 id = EventId.generate,
                 eventType = ActivityEventType.RunFailed,
                 source = "triage",
                 agentName = Some(agentName),
                 summary = s"Triage failed for ${issue.id.value}: $reason",
                 createdAt = now,
               )
             )
    yield ()

  private def renderError(err: TriageError): String =
    err match
      case TriageError.ConnectorFailure(cause) =>
        s"Connector failure: ${describeLlmError(cause)}"
      case TriageError.Timeout                 =>
        s"Connector timed out after ${TriageDaemon.PerIssueTimeout.toMillis}ms"
      case TriageError.Storage(cause)          =>
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
