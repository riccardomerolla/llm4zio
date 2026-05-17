package pat.entity

import zio.json.*

import issues.entity.TicketLane
import llm4zio.core.LlmError
import shared.errors.PersistenceError

/** Structured output Pat must produce in response to the triage prompt.
  *
  * Two shapes:
  *   - [[PatTriageOutcome.LaneAndNote]] — Pat made a call. Daemon writes
  *     `LaneSet` + optional `TagsUpdated` + `MovedToTodo`.
  *   - [[PatTriageOutcome.Clarify]] — Pat needs the supervisor. Daemon
  *     opens a `Decision` via `DecisionInbox` and leaves the issue in
  *     Backlog.
  *
  * The discriminator is the presence of the `lane` field; zio-json
  * decodes the first matching shape.
  */
sealed trait PatTriageOutcome derives JsonCodec
object PatTriageOutcome:
  /** Pat made a triage call. Optional `titleSuggestion` and
    * `descriptionSuggestion` are applied if Pat thinks the original
    * needs sharpening. Empty / blank suggestions are ignored at apply
    * time (the daemon trims and filters).
    */
  final case class LaneAndNote(
    lane: TicketLane,
    note: Option[String] = None,
    titleSuggestion: Option[String] = None,
    descriptionSuggestion: Option[String] = None,
  ) extends PatTriageOutcome

  final case class Clarify(
    clarify: String,
    options: List[String] = Nil,
  ) extends PatTriageOutcome

sealed trait PatTriageError
object PatTriageError:
  final case class ConnectorFailure(cause: LlmError)   extends PatTriageError
  case object Timeout                                  extends PatTriageError
  final case class Storage(cause: PersistenceError)    extends PatTriageError

/** Aggregate result of a single triage tick.
  *
  *   - `triaged`   — issues fully resolved (LaneSet + MovedToTodo applied)
  *   - `escalated` — issues parked with a Decision (Pat asked for clarification, malformed output, etc.)
  *   - `skipped`   — picked-up issues that hit an unrecoverable error before any side-effect
  *
  * Total = number of issues the daemon acted on this tick. Used by the
  * `DaemonAgentScheduler` to fill `DaemonRunOutcome`.
  */
final case class PatTriageBatchOutcome(
  triaged: Int,
  escalated: Int,
  skipped: Int,
):
  def total: Int = triaged + escalated + skipped
