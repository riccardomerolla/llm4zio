package agent.entity

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

/** The typed role of an employee in the agentic software house.
  *
  * Locked by the big-review (Phase 2, R2). Each role has a default workload lane
  * — see `TicketLane.defaultLaneFor` in issues-domain for the mapping used at
  * routing time. Custom is the back-compat escape hatch for existing agents
  * that haven't yet been re-typed.
  */
enum EmployeeRole derives JsonCodec, Schema:
  case PM           // Pat — backlog triage, intake support
  case FrontendEng  // Alex — frontend tickets E2E
  case BackendEng   // Ben — backend tickets E2E
  case QA           // Dana — testing tickets E2E
  case Reviewer     // Rex — review pass before Done
  case Custom       // user-defined employee, free-form persona

object EmployeeRole:
  def fromString(raw: String): Option[EmployeeRole] =
    raw.trim.toLowerCase match
      case "pm" | "pat"                              => Some(EmployeeRole.PM)
      case "frontend" | "frontendeng" | "alex"       => Some(EmployeeRole.FrontendEng)
      case "backend" | "backendeng" | "ben"          => Some(EmployeeRole.BackendEng)
      case "qa" | "dana"                             => Some(EmployeeRole.QA)
      case "reviewer" | "review" | "rex"             => Some(EmployeeRole.Reviewer)
      case "custom" | ""                             => Some(EmployeeRole.Custom)
      case _                                         => None

  extension (role: EmployeeRole)
    def displayName: String =
      role match
        case EmployeeRole.PM          => "PM / Triage"
        case EmployeeRole.FrontendEng => "Frontend Engineer"
        case EmployeeRole.BackendEng  => "Backend Engineer"
        case EmployeeRole.QA          => "QA"
        case EmployeeRole.Reviewer    => "Reviewer"
        case EmployeeRole.Custom      => "Custom"
