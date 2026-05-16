# Canvas: Telegram free-text intake + /agents employee filter

- **Canvas ID:** `TG-INTAKE-202605161555`
- **Status:** Draft
- **Authors:** Riccardo + Claude
- **Target deployable:** one PR

---

## 1. Story (INVEST + Given/When/Then)

### Story A — Telegram free-text → backlog issue

> As a supervisor on Telegram, I want my free-text messages to become backlog
> issues that Pat can triage, so I can capture work from my phone without
> hitting the legacy chat-router or burning LLM tokens.

**Given** Telegram polling is on and a default workspace exists,
**when** I send a message that does **not** start with `/`,
**then** the gateway creates an `IssueEvent.Created` in the default workspace's
backlog, replies on Telegram with the new issue's title + ID, and does **not**
invoke `IntentParser` or any legacy agent.

Numeric examples:

- Title taken from the **first line** of the message, trimmed to **80** chars
  (UTF-8 safe; ellipsis appended if truncated).
- Description = the **full message body** (including the first line),
  preserved verbatim, max **8000** chars (Telegram's own limit is 4096; we
  allow some headroom for caption + text concatenation).
- Reply latency: under **2 s** end-to-end at p95 (no LLM call in the hot path).

### Story B — /agents lists only the five employees by default

> As a supervisor, I want `/agents` to show only my five employees by default
> so the legacy built-ins from a previous iteration don't clutter the view.

**Given** the AgentRegistry still seeds `legacyBuiltIns` alongside the
`DefaultRoster`,
**when** I open `/agents`,
**then** I see only Pat, Alex, Ben, Dana, Rex — with a footer "**N** legacy
agents hidden · [show all](?show=all)" when legacy agents exist.

**Given** the `?show=all` query parameter is present,
**when** I open `/agents?show=all`,
**then** I see employees first (sorted by role: PM, FrontendEng, BackendEng,
QA, Reviewer) followed by legacy agents under a "Legacy" subheading.

### Out of scope

- Ripping out `IntentParser` / `legacyBuiltIns` seeding (that's Option 2 in
  the conversation — separate canvas).
- Telegram → board-issue from **web chat** (`ChatController` keeps its
  current behaviour; only the Telegram inbound path is rerouted).
- Renaming `/agents` to `/employees` (Phase 4 cosmetic).

---

## 2. Analysis

### Domain concepts

| Concept | Where it lives | Notes |
|---|---|---|
| `NormalizedMessage` | `gateway-domain` entity | Inbound payload. `channelName == "telegram"` for our trigger. |
| `IssueEvent.Created` | `issues-domain` entity | Issue lifecycle starts here; `MovedToBacklog` follows to place it in Backlog. |
| `Workspace` | `workspace-domain` entity | We need exactly one to host the new issue. |
| `Agent` + `EmployeeRole` + `DefaultRoster` | `agent-domain` entity | Source of truth for "who is an employee". |
| `CommandParser` | `gateway.boundary.telegram` | Keep authoritative for `/help`, `/tasks`, etc. |

### Strategy

A new `control` collaborator — call it `TelegramIntake` — sits between
`GatewayService.processInbound` and `handleIntentRouting`. For Telegram
inbound messages it short-circuits the legacy intent path:

```
Telegram inbound
  ├─ starts with "/" → CommandParser (unchanged)
  └─ free-text     → TelegramIntake.fileIssue → IssueRepository.append
                       └─ reply on Telegram
```

For non-Telegram channels (`web`, future), `handleIntentRouting` runs as
before — no regression on `ChatController` or any existing test.

For Story B, `AgentsController.list` partitions the registry into
`employees` (name ∈ `DefaultRoster.all.map(_.name.toLowerCase)`) and `legacy`
(everything else), and renders only employees unless `?show=all` is set.

### Risks

1. **Workspace ambiguity** — onboarding seeds one workspace per project,
   but nothing forbids multiple. Mitigation: pick the **most-recently-created**
   workspace; include its name in the reply ("filed to `prototype`") so the
   user notices.
2. **Idempotency** — the Telegram poller already tracks `nextOffset` so the
   same update is never delivered twice. We don't add a second guard.
3. **Empty workspace state** — first run before onboarding finishes. Reply
   with a friendly "No workspace yet — finish onboarding at <link>" instead
   of crashing.
4. **Legacy callers** — `ChatController.processInbound` is the other caller.
   We must NOT change web-chat behaviour. Solution: the intake check is
   gated on `message.channelName == "telegram"`.
5. **`/agents` filter regression** — existing tests assert legacy agents
   appear in the list. Update assertions to include `?show=all`.

### AC coverage matrix

| AC | Approach element |
|---|---|
| Free-text → issue | `TelegramIntake.fileIssue` |
| Title trim to 80 chars | `TelegramIntake.deriveTitle` |
| Description verbatim | `TelegramIntake.deriveDescription` |
| Reply on Telegram | `TelegramIntake.fileIssue` last step |
| No LLM call | The path never enters `handleIntentRouting` |
| Employees-only by default | `AgentsController.partitionByRoster` |
| `?show=all` shows both | `AgentsController.list` accepts query param |
| Legacy footer count | `AgentsView.list` takes `legacyCount` arg |

---

## 3. REASONS Canvas

### R — Requirements

1. Telegram message **without leading `/`** becomes a backlog `Issue` in the
   most-recently-created workspace, with no LLM call.
2. Reply on Telegram within 2 s p95: `"Filed `{title}` (#{id}) to backlog of
   `{workspaceName}` — Pat will triage shortly."`
3. If no workspace exists, reply: `"No workspace configured yet. Finish
   onboarding at /onboarding."`
4. `/help`, `/tasks`, `/status`, `/logs`, `/cancel`, `/start` continue to
   work exactly as today.
5. `GET /agents` returns **only** Pat, Alex, Ben, Dana, Rex by default,
   sorted by `EmployeeRole` order (PM, FrontendEng, BackendEng, QA,
   Reviewer).
6. `GET /agents?show=all` returns employees first, then a "Legacy"
   subheading with the remaining built-ins.
7. When legacy agents are hidden, a footer reads `"{N} legacy agents hidden
   · show all"` where `show all` links to `?show=all`.
8. **Web chat** (`ChatController`) behaviour is unchanged.

### E — Entities

```scala
// agent-domain (existing — used unchanged)
agent.entity.DefaultRoster.all: List[Agent]   // Pat, Alex, Ben, Dana, Rex
agent.entity.EmployeeRole                     // PM | FrontendEng | BackendEng | QA | Reviewer | Custom

// gateway-domain (new)
final case class TelegramIntakeOutcome(
  issueId: IssueId,
  title: String,
  workspaceId: String,
  workspaceName: String,
)

sealed trait TelegramIntakeError
object TelegramIntakeError:
  case object NoWorkspaceConfigured                       extends TelegramIntakeError
  case object EmptyMessage                                extends TelegramIntakeError
  final case class Storage(cause: PersistenceError)       extends TelegramIntakeError
```

No new persisted types. We reuse `IssueEvent.Created` + `IssueEvent.MovedToBacklog`.

### A — Approach

- **Dispatch by channel name.** `GatewayService.processInbound` inspects
  `message.channelName`. For `"telegram"` + free-text, it delegates to
  `TelegramIntake`. For everything else, the existing `handleIntentRouting`
  runs unchanged.
- **Command vs. free-text** is decided by `CommandParser.parse` returning
  `Right(_)` (it's a command) vs. `Left(CommandParseError.NotACommand)`
  (it's free-text). We don't introduce a second parser.
- **Workspace resolution.** `WorkspaceRepository.list` ordered by
  `createdAt` descending; pick `.headOption`. (Single workspace = single
  result.)
- **Issue creation.** Two events appended atomically per the repo's existing
  pattern: `Created` then `MovedToBacklog`. The board picks the issue up on
  next read.
- **Reply.** Use the existing Telegram channel's `sendMessage` (same path
  `DecisionEscalationNotifier` uses).
- **Filtering.** New helper `AgentsController.partitionByRoster(all)`
  returns `(employees, legacy)`. `list` accepts `showAll: Boolean` (from
  query string) and passes both lists + a flag to the view.

### S — Structure

| Module | File | Change |
|---|---|---|
| `gateway-domain` | `gateway/control/TelegramIntake.scala` | **NEW** trait + `Live` impl. |
| `gateway-domain` | `gateway/control/GatewayService.scala` | Branch in `processInbound` on `channelName == "telegram"` + non-command. |
| `gateway-domain` | `gateway/control/GatewayService.scala` | Constructor gains `telegramIntake: TelegramIntake`. |
| `app` | `app/ApplicationDI.scala` | Wire `TelegramIntake.live` into the `GatewayService` layer. |
| `config-domain` | `config/boundary/AgentsController.scala` | Read `?show=all`, partition by roster, pass to view. |
| `agent-domain` | `agent/boundary/AgentsView.scala` | `list` gains `legacyCount: Int` + `showAll: Boolean`; render footer / "Legacy" subheading. |

No circular dependencies introduced: `gateway-domain` already `dependsOn`
`issues-domain`, `workspace-domain`, and (via orchestration) `agent-domain`.

### O — Operations

```scala
// gateway-domain
trait TelegramIntake:
  /** File a free-text Telegram message as a Backlog issue.
    *  Returns the created issue's metadata for the reply. */
  def fileIssue(message: NormalizedMessage): IO[TelegramIntakeError, TelegramIntakeOutcome]

final case class TelegramIntakeLive(
  workspaceRepository: WorkspaceRepository,
  issueRepository: IssueRepository,
  clock: Clock,
) extends TelegramIntake:
  override def fileIssue(message: NormalizedMessage): IO[TelegramIntakeError, TelegramIntakeOutcome] =
    for
      body       <- ZIO.fromOption(Option(message.content).map(_.trim).filter(_.nonEmpty))
                       .orElseFail(TelegramIntakeError.EmptyMessage)
      title       = deriveTitle(body)             // first line, trim 80, "…" if truncated
      description = deriveDescription(body)       // verbatim, capped at 8000
      ws         <- workspaceRepository.list.mapError(TelegramIntakeError.Storage.apply)
      workspace  <- ZIO.fromOption(ws.sortBy(_.createdAt).reverse.headOption)
                       .orElseFail(TelegramIntakeError.NoWorkspaceConfigured)
      now        <- clock.instant
      issueId     = IssueId.generate
      created     = IssueEvent.Created(issueId, title, description, "task", "normal", now)
      backlogged  = IssueEvent.MovedToBacklog(issueId, now, now)
      _          <- issueRepository.append(created).mapError(TelegramIntakeError.Storage.apply)
      _          <- issueRepository.append(backlogged).mapError(TelegramIntakeError.Storage.apply)
    yield TelegramIntakeOutcome(issueId, title, workspace.id, workspace.name)

// helpers (private[control], unit-tested)
def deriveTitle(body: String): String
def deriveDescription(body: String): String
```

Branch in `GatewayService.processInbound`:

```scala
override def processInbound(message: NormalizedMessage): IO[GatewayServiceError, Unit] =
  for
    _ <- forwardSteering(message)
    _ <- router.routeInbound(message).mapError(GatewayServiceError.Router.apply)
    _ <- if isTelegramFreeText(message) then handleTelegramIntake(message)
         else handleIntentRouting(message)
    _ <- bumpMetrics(message)
  yield ()

private def isTelegramFreeText(m: NormalizedMessage): Boolean =
  m.channelName == "telegram" && !m.content.trim.startsWith("/")

private def handleTelegramIntake(m: NormalizedMessage): IO[GatewayServiceError, Unit] =
  telegramIntake.fileIssue(m).foldZIO(
    {
      case TelegramIntakeError.NoWorkspaceConfigured =>
        sendAssistantReply(m, "No workspace configured yet. Finish onboarding at /onboarding.", None)
      case TelegramIntakeError.EmptyMessage          => ZIO.unit
      case TelegramIntakeError.Storage(cause)        =>
        ZIO.logError(s"telegram intake storage failure: $cause") *>
          sendAssistantReply(m, "Couldn't file your message — check the server logs.", None)
    },
    outcome =>
      sendAssistantReply(
        m,
        s"Filed `${outcome.title}` (#${outcome.issueId.value}) to backlog of `${outcome.workspaceName}` — Pat will triage shortly.",
        None,
      ),
  )
```

`AgentsController.list` (sketch):

```scala
def list(showAll: Boolean): UIO[Response] =
  for
    all                  <- agentService.listAll
    (employees, legacy)   = partitionByRoster(all)
    sortedEmployees       = employees.sortBy(a => roleOrder(a.role))
    body                  = AgentsView.list(
                              employees = sortedEmployees,
                              legacy    = if showAll then legacy else Nil,
                              legacyCount = legacy.size,
                              showAll   = showAll,
                            )
  yield htmlResponse(body)

private def partitionByRoster(all: List[Agent]): (List[Agent], List[Agent]) =
  val rosterNames = DefaultRoster.all.map(_.name.toLowerCase).toSet
  all.partition(a => rosterNames.contains(a.name.toLowerCase))
```

### N — Norms

Inherit project Norms (`CLAUDE.md`):

- BCE: `TelegramIntake` lives in `gateway.control`; view changes in
  `agent.boundary` / `config.boundary`; entities in `gateway.entity`.
- Typed errors only — no `Throwable` leaks; `PersistenceError` wraps store
  faults.
- Event sourcing — `IssueRepository.append` is the only mutation path.
- No `var` in new code; `Clock` from ZIO env, not `Instant.now`.
- `-Werror`-clean: no unused imports; same-package types across sbt modules
  go unimported.

### S — Safeguards (non-negotiable)

1. **Web-chat path must remain bit-for-bit identical.** Verified by
   `ChatController` existing unit tests + a new test asserting
   `handleIntentRouting` still fires for `channelName == "web"`.
2. **No LLM invocation in the Telegram intake hot path.** Verified by
   constructing `TelegramIntakeLive` without an `LlmService` dependency —
   the type system enforces it.
3. **Idempotency** — relies on Telegram polling's existing `nextOffset`
   guard; we add no new state. A regression test in
   `TelegramPollingServiceSpec` already covers double-poll.
4. **No issue created from empty body** — `EmptyMessage` short-circuits
   before any append.
5. **Failure path replies once, never crashes the loop.** Any
   `TelegramIntakeError` is recovered inside `processInbound`; the polling
   loop continues.
6. **Backwards compatibility for `/agents`**: `?show=all` recovers the
   pre-change list verbatim.

---

## API test scenarios (preview — formalised in Stage 5)

| # | Scenario | Channel input | Expected outcome |
|---|---|---|---|
| 1 | Free-text → issue | telegram, `"add README"` | 1 `IssueEvent.Created` + `MovedToBacklog`; reply with title + ID |
| 2 | Multi-line | telegram, `"Add README\n\nWith status section"` | title = `"Add README"`, description full body |
| 3 | Title truncation | telegram, 200-char first line | title = first 79 chars + `"…"`; description full body |
| 4 | Command passthrough | telegram, `"/help"` | CommandParser handles; no `IssueEvent` written |
| 5 | No workspace | telegram, `"foo"`, empty workspace store | reply mentions `/onboarding`; no issue written |
| 6 | Web chat regression | web, `"hi"` | `handleIntentRouting` fires; no `TelegramIntake` call |
| 7 | `/agents` default | `GET /agents` | 5 employee rows, sorted by role; footer if N legacy > 0 |
| 8 | `/agents?show=all` | `GET /agents?show=all` | employees + "Legacy" section with the rest |

---

## Open questions

None blocking. Two clarifying notes — flag if you disagree:

- **Workspace tie-breaker.** I'm picking the most-recently-created
  workspace. If you'd prefer "most recently *touched*" (issue activity),
  say so and we'll add a `lastActivityAt` field; otherwise this lands as
  drafted.
- **Issue type / priority.** I'm defaulting to `"task"` / `"normal"` to
  match what the board view already renders. Easy to change.
