package issues.entity

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

/** The routing lane of a board ticket — the bridge from issue to employee role.
  *
  * Locked by the big-review (Phase 2, R5). Pat (the PM) sets this at triage
  * time. `AgentMatching.pickEmployeeForLane(lane)` then picks the role-matched
  * employee with occupancy=0 (one-in-flight invariant from Phase 1 Q8).
  */
enum TicketLane derives JsonCodec, Schema:
  case Frontend
  case Backend
  case Testing
  case Triage
  case Review
  case Custom

object TicketLane:
  def fromString(raw: String): Option[TicketLane] =
    raw.trim.toLowerCase match
      case "frontend" | "ui" | "ux"      => Some(TicketLane.Frontend)
      case "backend" | "api" | "server"  => Some(TicketLane.Backend)
      case "testing" | "qa" | "test"     => Some(TicketLane.Testing)
      case "triage" | "intake" | "pm"    => Some(TicketLane.Triage)
      case "review"                      => Some(TicketLane.Review)
      case "custom" | ""                 => Some(TicketLane.Custom)
      case _                             => None

  extension (lane: TicketLane)
    def displayName: String =
      lane match
        case TicketLane.Frontend => "Frontend"
        case TicketLane.Backend  => "Backend"
        case TicketLane.Testing  => "Testing"
        case TicketLane.Triage   => "Triage"
        case TicketLane.Review   => "Review"
        case TicketLane.Custom   => "Custom"
