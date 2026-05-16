# Canvas: Pat auto-triages Backlog issues (CLI-driven)

- **Canvas ID:** `PAT-TRIAGE-202605161630`
- **Parent canvas:** `TG-INTAKE-202605161555` (intake produced Backlog issues; this canvas moves them out)
- **Status:** Draft
- **Authors:** Riccardo + Claude
- **Target deployable:** one PR
- **Operates via:** Pat's configured connector — whatever you set on her `/agents/pat/edit` page (CLI or API, system default or per-agent override). Resolved at run-time via `ConnectorConfigResolver.resolve(Some("pat"))`.

---

## 1. Story

> As a supervisor, when an issue lands in Backlog (via Telegram, the web, or
> any other intake), I want Pat to auto-triage it within a minute — set a
> `TicketLane`, optionally append a triage note as a tag, move it to Todo —
> so the `AutoDispatcher` can pick it up and route to Alex / Ben / Dana / Rex
> without me touching the board.

### INVEST checks

- **Independent**: doesn't depend on PR 1/PR 2 changes shipping together
- **Negotiable**: heuristic-fallback design lets us tune Pat's prompt without re-spec
- **Valuable**: closes the gap "issue in Backlog forever, nothing happens"
- **Estimable**: ~1 PR, ~300 LoC
- **Small**: a single daemon + JSON contract; no UI changes
- **Testable**: 8+ unit tests; integration test against `echo` CLI tool

### Acceptance scenarios

**A1 — Happy path, lane in Pat's output**

Given a Backlog issue `"Add README for the prototype"`,
when the `PatTriageDaemon` tick fires,
then it invokes Pat's CLI with the triage prompt, parses
`{"lane":"Custom","note":"docs"}`, appends `LaneSet(Custom, "pat")` +
`TagsUpdated([..., "triage:docs"])` + `MovedToTodo`, and the issue lands
in Todo with `lane == Custom`.

**A2 — Pat picks a typed lane**

Given a Backlog issue `"Fix login bug on Safari"`,
when Pat returns `{"lane":"Frontend","note":"safari-only"}`,
then `LaneSet(Frontend, "pat")` is appended and `AgentMatching.pickEmployeeForLaneStrict`
will pick Alex on the next AutoDispatcher tick.

**A3 — Pat asks for clarification**

Given a Backlog issue with ambiguous content (`"hmm"`),
when Pat returns `{"clarify":"What do you want me to do?","options":["..."]}`,
then no `LaneSet` / `MovedToTodo` events fire, the issue stays in Backlog,
and a `Decision` is escalated via `DecisionInbox` so the supervisor gets a
Telegram tap-to-resolve prompt.

**A4 — Pat returns malformed JSON**

Given Pat's CLI returns garbage or non-JSON,
when the daemon parses,
then the issue stays in Backlog, a `Decision` escalation fires with the raw
output as context, and the daemon logs a warning. Daemon loop keeps running.

**A5 — CLI exit code != 0**

Given Pat's CLI exits non-zero,
when the daemon catches the error,
then it logs, escalates a `Decision` ("Pat's CLI failed: <stderr tail>"),
and continues — no infinite retry loop. Backoff: 5 min before retrying the
same issue (tag-based fingerprint).

**A6 — Idempotency**

Given an issue already has a `LaneSet` event,
when the daemon ticks,
then it skips that issue (only acts on Backlog issues with no `LaneSet`).

### Out of scope (separate canvases)

- **Editing the title/description** — there's no `TitleEdited` event yet.
  Pat can suggest a re-title via the `clarify` path; we don't apply it.
- **Re-triage** — once `LaneSet`, Pat doesn't reconsider. Future canvas.
- **Cross-employee handoffs** — Pat doesn't escalate to Alex/Ben/Dana/Rex
  directly; that's still the AutoDispatcher's job once she sets the lane.
- **Triage for issues created by the `DebtDetector` daemon** — those already
  do `MovedToTodo` themselves; the filter `state.isBacklog` skips them.

---

## 2. Analysis

### Domain concepts

| Concept | Where | Notes |
|---|---|---|
| `AgentIssue` + `state` | `issues-domain` entity | Backlog filter: `state.isInstanceOf[IssueState.Backlog]` and no `LaneSet` in history |
| `IssueEvent.LaneSet` / `MovedToTodo` / `TagsUpdated` | `issues-domain` entity | The three writes Pat performs |
| `TicketLane` (Frontend / Backend / Testing / Triage / Review / Custom) | `issues-domain` entity | Pat's JSON `lane` field maps here |
| `Agent` ("pat") | `agent-domain` entity | The roster agent with `role = PM`, `cliTool = "claude-code"` (configurable) |
| `ConnectorConfigResolver` | `config-domain.control` | Walks the 5-level override chain and returns Pat's `ConnectorConfig` |
| `ConnectorRegistry` | `llm4zio.core` | Builds the `Connector` (API or CLI) — both implement `LlmService` |
| `LlmService.executeStructured[A]` | `llm4zio.core` | Single entry point for schema-validated JSON output, regardless of connector |
| `DecisionInbox.escalate` | `decision-domain` | Where we send "Pat needs help" decisions |
| `ActivityHub` | `activity-domain` | Where Pat's runs publish events for the dashboard |

### Strategy

A new background daemon — `PatTriageDaemon` — modeled on
`DaemonAgentScheduler` (proven pattern in the codebase). Each tick:

```
1. List Backlog issues without LaneSet (filter at the repository level)
2. For each issue (capped at N per tick, e.g. 5):
   a. Resolve Pat's ConnectorConfig (API or CLI, with overrides)
   b. Resolve the Connector via ConnectorRegistry — implements LlmService
   c. Render the triage prompt template (new file under prompts/)
   d. Call connector.executeStructured[PatTriageOutcome](prompt, schema)
       - LaneAndNote(lane, note)  → LaneSet + TagsUpdated + MovedToTodo
       - Clarify(question, opts)  → DecisionInbox.escalate
       - decode failure / LlmError → DecisionInbox.escalate(with context)
3. Publish ActivityHub events for the dashboard
```

The daemon is **opt-in** via a config setting (`pat.triage.enabled`). Off by
default until the user explicitly enables it from Settings or onboarding —
this avoids surprising behaviour on existing installs.

### Why connector-based?

Every agent on the platform picks its own connector via the edit page —
CLI or API, system default or per-agent override. The codebase already
has the plumbing: `ConnectorConfigResolver.resolve(Some("pat"))` walks
the five-level fallback chain (`agent.pat.connector.<mode>.*` → mode-
scoped global → flat agent → flat global → legacy `ai.*`) and returns a
`ConnectorConfig`. `ConnectorRegistry.resolve(cfg)` then produces the
right `Connector` (an `ApiConnector` or `CliConnector`, both
implementing `LlmService`).

Pat's daemon uses that path exactly. The supervisor sets Pat to CLI on
her edit page → no Anthropic API call ever happens. Sets her to Mock
(for testing) → mock. Sets the global to CLI and leaves Pat at default
→ CLI inherits. **No special-casing for Pat in the daemon code.**

This matters: the broken `ai.apiKey` from the Telegram debug session
only mattered because `GatewayService.executeRoutedAgentReply` calls
the global `LlmService` directly, ignoring per-agent overrides. Pat's
daemon doesn't repeat that mistake.

### JSON contract Pat must produce

```json
{
  "lane": "Frontend" | "Backend" | "Testing" | "Triage" | "Review" | "Custom",
  "note": "short tag-friendly string, optional"
}
```

OR

```json
{
  "clarify": "question for the supervisor",
  "options": ["1-3 inline-keyboard choices"]
}
```

Anything else → treated as malformed. Schema validated by a single
`PatTriageOutcome` ADT decoder using zio-json.

### Risks

1. **Pat's CLI hangs.** Mitigation: per-issue timeout, default 60 s
   (matches `Agent.timeout` default of 30 min would be too long here).
2. **Pat goes rogue and writes 50 issues.** Mitigation: this daemon
   **never creates issues**, only triages existing ones. The only writes
   are `LaneSet` / `TagsUpdated` / `MovedToTodo` for issues already in the
   store.
3. **Heuristic-fallback temptation.** It would be tempting to keyword-match
   the title when Pat is unavailable; explicitly forbidden — escalate
   instead so the user sees the system isn't lying to them.
4. **Concurrent triage.** Two daemon ticks racing. Mitigation: in-memory
   `Ref[Set[IssueId]]` of issues being triaged this tick; second tick skips
   them. Idempotency at the event level via the `no LaneSet exists` filter
   also catches it.
5. **The user wants to override Pat manually.** Already supported via the
   `set_issue_lane` MCP tool and the `/board` UI; nothing to change.

### AC coverage matrix

| AC | Approach element |
|---|---|
| A1 happy path | `PatTriage.parseOutcome` Right-LaneAndNote case |
| A2 typed lane | `PatTriage.parseOutcome` honours all `TicketLane` values |
| A3 clarify | `PatTriage.parseOutcome` Clarify case → `DecisionInbox.escalate` |
| A4 malformed JSON | `PatTriage.parseOutcome` Left case |
| A5 CLI exit != 0 | `runProcess` non-zero handling → escalate |
| A6 idempotency | `Backlog && no LaneSet` filter at issue listing |

---

## 3. REASONS Canvas

### R — Requirements

1. A background daemon ticks every **60 s** (configurable via
   `pat.triage.intervalSeconds`).
2. On each tick, the daemon lists Backlog issues without `LaneSet` and
   processes **up to 5** in parallel-bounded order (oldest first).
3. For each, it resolves Pat's connector
   (`ConnectorConfigResolver.resolve(Some("pat"))`), creates a
   `Connector` via the registry, and invokes
   `executeStructured[PatTriageOutcome](prompt, schema)` with a **60 s**
   per-issue timeout. Works identically for CLI and API connectors —
   they both implement `LlmService.executeStructured`.
4. Pat's JSON output is parsed into a `PatTriageOutcome` ADT.
5. On `LaneAndNote(lane, note)`: append `LaneSet(lane, "pat")`,
   `TagsUpdated(prev :+ s"triage:$note")` if note nonempty, `MovedToTodo`.
6. On `Clarify(question, options)`: append no issue events; raise a
   `Decision` via `DecisionInbox.escalate` with the supervisor's chat ID.
7. On `MalformedOutput(raw)`, `ConnectorFailure(cause)`, or `Timeout`:
   escalate a `Decision` with the failure context; do not retry for
   **5 min** (tag-based backoff via the `pat:awaiting-supervisor` tag).
8. Daemon is **disabled by default**: gated on `pat.triage.enabled == true`.
9. Every run publishes an `ActivityEvent.AgentInvocation` for Pat with
   success / failure status so it appears on the `/dashboard`.
10. The change touches no UI; observation is via existing dashboard +
    `/decisions/inbox` + Telegram escalations.

### E — Entities

```scala
// gateway / triage entity (new file: orchestration-domain/pat/entity/PatTriage.scala)
sealed trait PatTriageOutcome
object PatTriageOutcome:
  final case class LaneAndNote(lane: TicketLane, note: Option[String])  extends PatTriageOutcome
  final case class Clarify(question: String, options: List[String])     extends PatTriageOutcome

sealed trait PatTriageError
object PatTriageError:
  final case class MalformedOutput(raw: String)        extends PatTriageError
  final case class ConnectorFailure(cause: LlmError)   extends PatTriageError
  case object Timeout                                  extends PatTriageError
  final case class Storage(cause: PersistenceError)    extends PatTriageError
```

Reused: `IssueEvent.LaneSet`, `IssueEvent.MovedToTodo`,
`IssueEvent.TagsUpdated`, `TicketLane`, `AgentIssue`, `DecisionInbox`,
`ActivityHub`, `ConnectorConfigResolver`, `ConnectorRegistry`,
`LlmService.executeStructured`.

### A — Approach

- **Daemon shape.** Mirror `DaemonAgentScheduler`: a scoped `ZLayer` that
  `forkScoped`s a tick loop. Tick reads config (enabled, interval),
  short-circuits if disabled, otherwise runs `triageOnce`.
- **Backlog filter.** Use existing `IssueFilter` with
  `states = Set(IssueStateTag.Backlog)`; the daemon then post-filters
  by inspecting history for `LaneSet` absence (`history(id)` exists).
- **Prompt template.** New file `src/main/resources/prompts/pat-triage.md`
  with placeholders `{{issueTitle}}`, `{{issueDescription}}`,
  `{{availableLanes}}`. Loaded via existing `PromptLoader`.
- **Connector resolution + invocation.** For each tick:

  ```scala
  for
    cfg       <- connectorConfigResolver.resolve(Some("pat"))
    connector <- connectorRegistry.resolve(cfg)
    prompt    <- promptLoader.render("pat-triage", placeholders)
    outcome   <- connector.executeStructured[PatTriageOutcome](prompt, schema)
                   .timeout(60.seconds)
  yield outcome
  ```

  CLI vs API is opaque at this layer — both implement
  `LlmService.executeStructured[A]`.
- **Schema generation.** `JsonSchema.derive[PatTriageOutcome]` if such a
  helper exists in `llm4zio.tools`; otherwise hand-write a minimal
  schema. For providers that don't natively support structured output
  (some CLI connectors), `executeStructured` wraps the prompt with
  schema instructions and parses the raw output — already the contract.
- **Escalation.** Reuse `DecisionInbox.escalate(workspaceId, issueId,
  question, quickReplyOptions)`. The Telegram pipeline is already wired
  from Phase 3.
- **Activity events.** Wrap each per-issue run with
  `ActivityHub.publishStart` / `publishCompletion` so the dashboard's
  recent-activity row updates.

### S — Structure

| Module | File | Change |
|---|---|---|
| `orchestration-domain` | `pat/entity/PatTriage.scala` | **NEW** outcome + error ADTs |
| `orchestration-domain` | `pat/control/PatTriageDaemon.scala` | **NEW** daemon trait + Live + layer |
| `orchestration-domain` | `pat/control/PatTriagePromptRenderer.scala` | **NEW** prompt rendering (small) |
| (resources) | `src/main/resources/prompts/pat-triage.md` | **NEW** triage prompt template |
| `app` | `app/ApplicationDI.scala` | Wire `PatTriageDaemon.live`; provide `ConnectorRegistry` + `ConnectorConfigResolver` deps (already in the graph for other consumers) |
| `config-domain` | `config/boundary/SettingsValidator.scala` | Recognise `pat.triage.enabled` (boolean) + `pat.triage.intervalSeconds` (int) |
| `app` | `app/boundary/OnboardingController.scala` | Add `"pat.triage.enabled" -> "true"` to default-saved settings so new installs get the triage loop on first run |
| `shared-web` | `shared/web/SettingsView.scala` | New form section "Pat (PM)" with the enabled toggle + interval slider |

Module deps unchanged: `orchestration-domain` already depends on
`issues-domain`, `decision-domain`, `activity-domain`, `agent-domain`,
`workspace-domain`, `config-domain`, and (via `llm4zio`) has access to
`ConnectorRegistry` and `LlmService`.

### O — Operations

```scala
trait PatTriageDaemon:
  def triageOnce: UIO[Int]    // returns issues acted on (Lane/Todo OR escalated)

object PatTriageDaemon:
  val live: ZLayer[
    IssueRepository & ConfigRepository & WorkspaceRepository & PromptLoader
      & ConnectorConfigResolver & ConnectorRegistry & DecisionInbox & ActivityHub,
    Nothing,
    PatTriageDaemon,
  ] =
    ZLayer.scoped {
      for
        ...
        _ <- runLoop.forkScoped       // background tick; bails when disabled
      yield PatTriageDaemonLive(...)
    }

final case class PatTriageDaemonLive(
  issueRepository: IssueRepository,
  configRepository: ConfigRepository,
  workspaceRepository: WorkspaceRepository,
  promptLoader: PromptLoader,
  connectorConfigResolver: ConnectorConfigResolver,
  connectorRegistry: ConnectorRegistry,
  decisionInbox: DecisionInbox,
  activityHub: ActivityHub,
) extends PatTriageDaemon:

  def triageOnce: UIO[Int] =
    for
      enabled <- isEnabled
      n       <- if !enabled then ZIO.succeed(0) else triageBatch
    yield n

  private def triageBatch: UIO[Int] =
    for
      candidates <- pickCandidates(limit = 5)    // Backlog && no LaneSet, oldest first
      results    <- ZIO.foreach(candidates)(triageOne)
    yield results.count(_._1)                    // count of "did something"

  private def triageOne(issue: AgentIssue): UIO[(Boolean, Option[PatTriageError])] =
    val effect =
      for
        cfg       <- connectorConfigResolver.resolve(Some("pat"))
                       .mapError(PatTriageError.Storage.apply)
        connector <- connectorRegistry.resolve(cfg)
                       .mapError(PatTriageError.ConnectorFailure.apply)
        prompt    <- renderPrompt(issue)
        outcome   <- connector
                       .executeStructured[PatTriageOutcome](prompt, schema)
                       .mapError(PatTriageError.ConnectorFailure.apply)
                       .timeout(60.seconds)
                       .someOrFail(PatTriageError.Timeout)
        applied   <- apply(issue, outcome)         // LaneSet/MovedToTodo OR escalate
      yield applied
    activityHub.aroundRun("pat", issue.id, effect.exit).map {
      case Exit.Success(true)  => (true, None)
      case Exit.Success(false) => (false, None)
      case Exit.Failure(cause) =>
        (false, cause.failureOption.collect { case e: PatTriageError => e })
    }

    // The schema we hand to executeStructured. Pure, unit-tested.
  private[control] val schema: JsonSchema = patTriageOutcomeSchema
```

Helpers (pure, unit-tested independently):

```scala
def pickCandidates(allBacklog: List[AgentIssue], histories: Map[IssueId, List[IssueEvent]], limit: Int): List[AgentIssue]
val patTriageOutcomeSchema: JsonSchema    // JSON schema for executeStructured
```

> **Note on parsing.** `LlmService.executeStructured` does decode + retry
> internally — the daemon never sees raw text. A decode failure surfaces
> as `LlmError.SchemaError` which the daemon wraps in
> `ConnectorFailure(cause)` and treats as "malformed → escalate". One
> path, no duplicate JSON parser to maintain.

### N — Norms

- BCE: daemon + parser live in `orchestration-domain.pat.control`; ADTs
  in `orchestration-domain.pat.entity`; no boundary code.
- Typed errors throughout — `PatTriageError` is the only failure shape
  surfaced to the daemon loop; `triageOnce` returns `UIO[Int]` and never
  propagates failures (they're recovered into escalations or logged).
- Event sourcing only — never mutate `AgentIssue` directly.
- All clocks via ZIO's `Clock.instant`, not `Instant.now`.
- Prompt template lives in `resources/prompts/`, loaded via `PromptLoader`
  (already in place for other agents).
- Use `_root_.config.entity.ConfigRepository` because `zio.config` shadows.

### S — Safeguards (non-negotiable)

1. **No issue creation.** This daemon never appends `IssueEvent.Created`.
   The only writes are `LaneSet`, `TagsUpdated`, `MovedToTodo`. Verified
   by absence of `Created` references in `PatTriageDaemon.scala`.
2. **No heuristic fallback.** If Pat doesn't produce a valid outcome, the
   daemon **escalates**, never guesses. The user always knows when the
   system can't decide.
3. **Disabled by default.** `pat.triage.enabled` defaults to `false` for
   existing installs (the migration adds the key but leaves it
   unticked). New installs from onboarding get it `true`.
4. **Per-issue timeout.** `runProcess` is wrapped in `.timeout(60.seconds)`;
   a timed-out issue gets an escalation, no event side-effects.
5. **Backoff on failure.** After a `Decision` escalation, the issue is
   tagged `pat:awaiting-supervisor` so the next tick filters it out until
   the decision is resolved (which clears the tag).
6. **Connector resolution is per-agent.** The daemon resolves Pat's
   `ConnectorConfig` via `ConnectorConfigResolver.resolve(Some("pat"))`
   — never the global `LlmService`. Verified by the type signature of
   `PatTriageDaemonLive` (no `LlmService` field; only
   `ConnectorConfigResolver` and `ConnectorRegistry`). When Pat is set
   to a CLI connector, no API key is ever read.
7. **Existing legacy issues survive.** The daemon only touches Backlog
   issues with no `LaneSet`. Issues already in Todo / InProgress / Done
   are invisible to it.

---

## API test scenarios (formal in Stage 5)

Tests stub the `Connector` directly (returning pre-baked
`PatTriageOutcome` or `LlmError` values), so the same scenarios cover
CLI and API modes uniformly.

| # | Scenario | Stub connector returns | Expected events |
|---|---|---|---|
| 1 | Lane + note | `LaneAndNote(Frontend, Some("safari"))` | `LaneSet(Frontend,"pat")` + `TagsUpdated([..., "triage:safari"])` + `MovedToTodo` |
| 2 | Lane no note | `LaneAndNote(Custom, None)` | `LaneSet(Custom,"pat")` + `MovedToTodo` (no TagsUpdated) |
| 3 | Clarify | `Clarify("What?", List("A","B"))` | No issue events; `DecisionInbox.escalate` called once |
| 4 | Schema error | `LlmError.SchemaError("bad output")` | No events; escalation with last raw output |
| 5 | Auth error | `LlmError.AuthenticationError("missing key")` | No events; escalation including the connector kind ("Pat's API connector failed: …") |
| 6 | Already triaged | issue with prior `LaneSet` | Skipped entirely; tick returns 0 |
| 7 | Disabled | `pat.triage.enabled=false` | `triageOnce` returns 0 immediately; no connector resolution |
| 8 | Pat configured to CLI | `connectorConfigResolver` returns `CliConnectorConfig(ClaudeCli)` | Daemon invokes a CLI connector; verify the stub registry returned a CLI service |
| 9 | Pat configured to API | `connectorConfigResolver` returns `ApiConnectorConfig(Anthropic)` | Daemon invokes an API connector; verify the stub registry returned an API service |

End-to-end fixture: a stub `Connector` (implementing `LlmService`) that
returns `PatTriageOutcome.LaneAndNote(Custom, None)` from
`executeStructured`. Verifies the full daemon loop reads → resolves →
invokes → writes — connector kind irrelevant to the daemon code.

---

## Open questions

Two — I'll default to the conservative answer unless you disagree:

- **Default `pat.triage.enabled` for new installs.** I'd default it to
  `true` so onboarding "just works". You could argue for `false` so the
  user opts in. Default-on means the supervisor sees Pat acting within
  minutes of first issue.
- **Per-issue timeout (60 s).** CLI runs can take longer for big repos.
  60 s for v1; we can ratchet up after we see real durations.
