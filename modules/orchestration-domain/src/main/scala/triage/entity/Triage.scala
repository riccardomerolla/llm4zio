package triage.entity

import zio.json.*

import issues.entity.TicketLane
import llm4zio.core.LlmError
import shared.errors.PersistenceError

/** Structured output the triage agent must produce in response to the
  * triage prompt. Two shapes:
  *
  *   - [[TriageOutcome.LaneAndNote]] — the agent made a call. The
  *     daemon writes `LaneSet` + optional `TagsUpdated` + `MovedToTodo`.
  *   - [[TriageOutcome.Clarify]] — the agent needs the supervisor. The
  *     daemon opens a `Decision` via `DecisionInbox` and leaves the
  *     issue in Backlog.
  *
  * The discriminator is the presence of the `lane` field; zio-json
  * decodes the first matching shape.
  */
sealed trait TriageOutcome derives JsonCodec
object TriageOutcome:
  /** A successful triage call. Optional `titleSuggestion` and
    * `descriptionSuggestion` are applied if the agent thinks the
    * original needs sharpening. Empty / blank suggestions are ignored
    * at apply time (the daemon trims and filters).
    */
  final case class LaneAndNote(
    lane: TicketLane,
    note: Option[String] = None,
    titleSuggestion: Option[String] = None,
    descriptionSuggestion: Option[String] = None,
  ) extends TriageOutcome

  final case class Clarify(
    clarify: String,
    options: List[String] = Nil,
  ) extends TriageOutcome

sealed trait TriageError
object TriageError:
  final case class ConnectorFailure(cause: LlmError)   extends TriageError
  case object Timeout                                  extends TriageError
  final case class Storage(cause: PersistenceError)    extends TriageError

/** Aggregate result of a single triage tick.
  *
  *   - `triaged`   — issues fully resolved (LaneSet + MovedToTodo applied)
  *   - `escalated` — issues parked with a Decision (the agent asked for
  *                   clarification, malformed output, etc.)
  *   - `skipped`   — picked-up issues that hit an unrecoverable error
  *                   before any side-effect
  *
  * Total = number of issues the daemon acted on this tick. Used by the
  * `DaemonAgentScheduler` to fill `DaemonRunOutcome`.
  */
final case class TriageBatchOutcome(
  triaged: Int,
  escalated: Int,
  skipped: Int,
):
  def total: Int = triaged + escalated + skipped
