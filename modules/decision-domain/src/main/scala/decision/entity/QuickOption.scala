package decision.entity

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

/** A single channel-agnostic quick-reply option attached to a [[Decision]].
  *
  * Locked by the big-review (Phase 3, R6) and (Q5a). When a supervisor sees
  * an escalation — in Telegram inline buttons, or the web `/decisions/inbox`,
  * or any future channel — they pick one of these. The channel renderer maps
  * `key` to a callback payload; on tap, the system calls
  * `DecisionInbox.resolve(decisionId, option.resolution, actor, option.rationale)`.
  *
  * Channels render only `key` and `label`. `resolution` and `rationale` live
  * server-side so the channel never needs to know the resolution semantics.
  */
final case class QuickOption(
  key: String,
  label: String,
  resolution: DecisionResolutionKind,
  rationale: String = "",
) derives JsonCodec, Schema

object QuickOption:

  val Approve: QuickOption =
    QuickOption("approve", "Approve", DecisionResolutionKind.Approved, "Approved via quick reply")

  val RequestRework: QuickOption =
    QuickOption("rework", "Request changes", DecisionResolutionKind.ReworkRequested, "Rework requested via quick reply")

  val Acknowledge: QuickOption =
    QuickOption("ack", "Acknowledge", DecisionResolutionKind.Acknowledged, "Acknowledged via quick reply")

  val Escalate: QuickOption =
    QuickOption("escalate", "Escalate", DecisionResolutionKind.Escalated, "Escalated via quick reply")

  /** Default option set used when a decision was created before
    * `quickReplyOptions` existed, or when the source doesn't customise.
    * Excludes Escalate from the default set — escalations are typically
    * channel-specific actions rather than the supervisor's choice.
    */
  val defaults: List[QuickOption] = List(Approve, RequestRework, Acknowledge)

  /** Find an option by `key` — used by channel renderers to map a
    * button-press callback back to the option that issued it.
    */
  def findByKey(options: List[QuickOption], key: String): Option[QuickOption] =
    options.find(_.key.trim.equalsIgnoreCase(key.trim))
