package triage.entity

import zio.json.*
import zio.json.ast.Json

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
  * Structural discrimination by field name — matches what the triage
  * prompt asks the agent to emit (flat JSON object, no wrapper):
  *   - if the object has a `lane` field → LaneAndNote
  *   - if the object has a `clarify` field → Clarify
  *
  * Default zio-json sealed-trait codecs require a `{"CaseName": {...}}`
  * wrapper, which neither the prompt nor the agents produce.
  */
sealed trait TriageOutcome
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
  ) extends TriageOutcome derives JsonCodec

  final case class Clarify(
    clarify: String,
    options: List[String] = Nil,
  ) extends TriageOutcome derives JsonCodec

  given JsonDecoder[TriageOutcome] = JsonDecoder[Json.Obj].mapOrFail { obj =>
    val fieldNames = obj.fields.map(_._1).toSet
    if fieldNames.contains("lane") then obj.toJson.fromJson[LaneAndNote]
    else if fieldNames.contains("clarify") then obj.toJson.fromJson[Clarify]
    else
      Left(
        "TriageOutcome JSON must have either a `lane` field (LaneAndNote) " +
          "or a `clarify` field (Clarify); got fields: " +
          fieldNames.mkString("[", ", ", "]")
      )
  }

  given JsonEncoder[TriageOutcome] = JsonEncoder[Json].contramap {
    case lane: LaneAndNote   => lane.toJsonAST.getOrElse(Json.Null)
    case clarify: Clarify    => clarify.toJsonAST.getOrElse(Json.Null)
  }

  given JsonCodec[TriageOutcome] = JsonCodec(summon[JsonEncoder[TriageOutcome]], summon[JsonDecoder[TriageOutcome]])

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
