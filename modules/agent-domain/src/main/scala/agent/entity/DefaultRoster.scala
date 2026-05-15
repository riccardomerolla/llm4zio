package agent.entity

import java.time.{ Duration, Instant }

import shared.ids.Ids.AgentId

/** The default 5-employee cast for the agentic software house.
  *
  * Locked by the big-review (Phase 1, Q8 = α). One employee per role; each
  * has `maxConcurrentRuns = 1` (one-in-flight invariant). The persona is
  * stored in `systemPrompt`. Users can rename or extend the roster via
  * the custom-agent path; this object provides the seed values used at
  * onboarding (R10) and as documentation of the intended fleet shape.
  */
object DefaultRoster:

  private val epoch: Instant = Instant.EPOCH

  /** Pat — PM / Triage. Refiles the board, grooms the backlog. */
  val pat: Agent = template(
    name = "pat",
    description = "PM / Triage. Refiles new issues, grooms the backlog, sets ticket lane.",
    role = EmployeeRole.PM,
    systemPrompt = Some(
      "You are Pat, the PM. Your job is to triage incoming issues, set a clear title/description, " +
        "assign a TicketLane (Frontend, Backend, Testing, Review, or Custom), and groom the backlog. " +
        "Do not write code. Hand work off to the right employee."
    ),
    capabilities = List("triage", "planning", "backlog"),
  )

  /** Alex — Frontend Engineer. Owns frontend tickets end-to-end. */
  val alex: Agent = template(
    name = "alex",
    description = "Frontend Engineer. Walks frontend tickets end-to-end through the SPDD loop.",
    role = EmployeeRole.FrontendEng,
    systemPrompt = Some(
      "You are Alex, a frontend engineer. Take a frontend ticket from analysis through Canvas, " +
        "code generation, API tests, and review. Stay on the frontend lane — escalate non-frontend work to Pat."
    ),
    capabilities = List("frontend", "react", "lit", "scalatags", "htmx"),
  )

  /** Ben — Backend Engineer. Owns backend tickets end-to-end. */
  val ben: Agent = template(
    name = "ben",
    description = "Backend Engineer. Walks backend tickets end-to-end through the SPDD loop.",
    role = EmployeeRole.BackendEng,
    systemPrompt = Some(
      "You are Ben, a backend engineer. Take a backend ticket from analysis through Canvas, " +
        "code generation, API tests, and review. Stay on the backend lane — escalate non-backend work to Pat."
    ),
    capabilities = List("backend", "scala", "zio", "http"),
  )

  /** Dana — QA. Owns testing tickets and watches the build. */
  val dana: Agent = template(
    name = "dana",
    description = "QA. Walks testing tickets end-to-end; watches for broken tests and reports.",
    role = EmployeeRole.QA,
    systemPrompt = Some(
      "You are Dana, the QA engineer. Take a testing ticket from analysis through Canvas, " +
        "test generation, API tests, and review. Also watch for broken tests across the repo and surface them as issues."
    ),
    capabilities = List("testing", "zio-test", "integration-test", "api-test"),
  )

  /** Rex — Reviewer. Reviews completed tickets in the Review column. */
  val rex: Agent = template(
    name = "rex",
    description = "Reviewer. Reviews completed tickets in the Review column before they move to Done.",
    role = EmployeeRole.Reviewer,
    systemPrompt = Some(
      "You are Rex, the reviewer. Review completed work in the Review column. Check for correctness, " +
        "test coverage, governance-gate satisfaction, and clarity. Approve or request rework with concrete feedback."
    ),
    capabilities = List("review", "code-review", "approval"),
  )

  /** The full default roster in canonical order. */
  val all: List[Agent] = List(pat, alex, ben, dana, rex)

  /** Look up the default-roster employee for a role. Returns None for Custom. */
  def forRole(role: EmployeeRole): Option[Agent] = role match
    case EmployeeRole.PM          => Some(pat)
    case EmployeeRole.FrontendEng => Some(alex)
    case EmployeeRole.BackendEng  => Some(ben)
    case EmployeeRole.QA          => Some(dana)
    case EmployeeRole.Reviewer    => Some(rex)
    case EmployeeRole.Custom      => None

  private def template(
    name: String,
    description: String,
    role: EmployeeRole,
    systemPrompt: Option[String],
    capabilities: List[String],
  ): Agent =
    Agent(
      id = AgentId(name),
      name = name,
      description = description,
      cliTool = "claude-code",
      capabilities = capabilities,
      defaultModel = None,
      systemPrompt = systemPrompt,
      maxConcurrentRuns = 1,
      envVars = Map.empty,
      timeout = Duration.ofMinutes(30),
      enabled = true,
      createdAt = epoch,
      updatedAt = epoch,
      role = role,
    )
