# CLAUDE.md — llm4zio Project Conventions

This file documents the conventions, patterns, and structure of the llm4zio codebase for AI assistants and new contributors.

---

## Build Commands

```bash
sbt compile           # compile all sources
sbt test              # run unit tests (1121+)
sbt it:test           # run integration tests (18+, no external services required)
sbt run               # start the gateway (http://localhost:8080)
sbt fmt               # format: scalafmt + scalafix
sbt check             # check formatting without modifying
sbt assembly          # build fat JAR
sbt --client test     # faster: connect to running sbt server

# Per-module commands (faster feedback loops):
sbt boardDomain/compile       # compile only board-domain + its deps
sbt boardDomain/test          # run only board-domain tests
sbt 'testOnly board.control.BoardCacheSpec'  # run a single test class
```

---

## Architecture: BCE Pattern

All domain packages follow **Boundary / Control / Entity** ([bce.design](https://bce.design/)):

```
domain/
  boundary/   HTTP controllers, views, SSE endpoints, WebSocket handlers
              — only routing, rendering, and HTTP concern live here
  control/    Services, repositories, orchestrators, use-case logic
              — business logic, state machines, event sourcing
  entity/     Domain types: case classes, enums, event ADTs, errors
              — pure data, no ZIO effects
```

**Rules:**
- `boundary` may depend on `control` and `entity`
- `control` may depend on `entity` only (never `boundary`)
- `entity` has zero dependencies on other layers
- Service logic must NOT live in `boundary/` — BCE violation
- Views (Scalatags) belong in `boundary/`, not in a separate view package

---

## Multi-Module Structure

The codebase uses **sbt multi-module builds** to enforce BCE cohesion. Each domain is a self-contained module combining all three layers together, named after its domain.

### Module Layout

```
modules/
  shared-json/          # JSON codec support
  shared-ids/           # Typed ID wrappers (GovernancePolicyId, ProjectId, etc.)
  shared-errors/        # PersistenceError and shared error ADTs
  shared-store-core/    # EventStore trait, EclipseStore integration
  shared-web-core/      # Domain-independent view infra: Layout, Components, JsResources
  shared-services/      # Cross-cutting services: FileService, RateLimiter, HttpAIClient, StateService

  # Domain modules — each is a BCE unit:
  activity-domain/      agent-domain/        analysis-domain/
  board-domain/         checkpoint-domain/   config-domain/
  conversation-domain/  daemon-domain/       decision-domain/
  demo-domain/          evolution-domain/    gateway-domain/
  governance-domain/    issues-domain/       knowledge-domain/
  memory-domain/        orchestration-domain/ plan-domain/
  project-domain/       sdlc-domain/         specification-domain/
  taskrun-domain/       workspace-domain/

  shared-web/           # Views with multi-domain deps (being distributed to domain modules)
```

### What Lives Where

**Domain modules** (`modules/*-domain/`) contain:
- `entity/` — all domain modules have this (models, events, repository traits)
- `control/` — most modules now have services colocated (e.g., `AgentMatching`, `BoardCache`, `DaemonAgentScheduler`, `WorkflowEngine`)
- `boundary/` — views that only depend on their own domain (e.g., `BoardView`, `IssueTimelineView`, `DaemonsView`, `AgentsView`, `ChannelView`)

**Root `src/main/scala/`** retains files that depend on multiple domains or have circular dependency constraints:
- `app/` — `ApplicationDI`, `WebServer`, DI wiring (depends on everything)
- `mcp/` — cross-cutting MCP tool support
- `db/` — legacy database layer
- Domain `boundary/` controllers (HTTP routing that cross-cuts multiple domains)
- Domain `control/` services with unresolved cross-domain dependencies (e.g., `BoardOrchestrator`, `OrchestratorControlPlane`)

**`shared-web`** contains ~20 view files with multi-domain dependencies that cannot yet move to a single domain module (e.g., `SettingsView` depends on config + gateway + agent). These are being distributed to domain `boundary/` packages as dependencies are untangled.

### Module Dependency Rules

```scala
// In build.sbt:
val domainDeps    = Seq(zioCoreDep, zioStreamsDep, zioJsonDep)       // entity-only modules
val domainBceDeps = domainDeps ++ Seq(zioHttpDep, scalatags)          // modules with boundary layer

// Domain modules depend on foundation + other domain modules:
lazy val boardDomain = project.in(file("modules/board-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, workspaceDomain)
  .settings(libraryDependencies ++= domainBceDeps)
```

**Key constraints:**
- Domain modules MUST NOT depend on root `src/main/scala/`
- Circular `dependsOn` is illegal in sbt — break cycles by extracting trait interfaces to entity packages
- `orchestration-domain` holds trait interfaces (e.g., `AgentPoolManager`, `TaskExecutor`) that other modules depend on; `*Live` implementations live in root or in the module itself
- Use `_root_.config.entity.ConfigRepository` when `zio.config` shadows the `config` package

### Import Conventions for Modules

When a domain module and the root project share the same package (e.g., `board.boundary`), Scala allows same-package access across sbt module boundaries — **do NOT add explicit imports** for same-package types (causes unused import errors with `-Werror`).

When `config` package is shadowed by `zio.config`:
```scala
import _root_.config.entity.ConfigRepository  // use _root_ prefix
```

When a local variable name shadows a package:
```scala
// In AgentsController, `agent` is a local val of type Agent
// agent.boundary.AgentsView would be parsed as accessing .boundary on the val
import _root_.agent.boundary.AgentsView  // use _root_ prefix, then unqualified name
```

---

## Package Naming

All packages use **singular** names matching the domain:

```
agent/        board/        checkpoint/   config/
conversation/ daemon/       decision/     evolution/
gateway/      governance/   issues/       knowledge/
mcp/          memory/       orchestration/ plan/
project/      sdlc/         specification/ taskrun/
workspace/    activity/
```

**Foundation packages** (shared infrastructure):

```
shared/
  errors/     PersistenceError and other shared error ADTs
  ids/        Typed ID wrappers (GovernancePolicyId, ProjectId, etc.)
  store/      EventStore trait, StoreConfig, DataStoreModule
  web/        Scalatags view helpers, layout, HTML components
  services/   Cross-cutting services (FileService, RateLimiter, etc.)

db/           Legacy database layer — avoid adding new code here
app/          Application entry point, WebServer, DI wiring
```

---

## ADE Domain Packages

The 12 ADE feature packages:

| Package | Domain |
|---------|--------|
| `board/` | Kanban board, issue lifecycle, git-backed storage |
| `specification/` | Spec documents, approval workflow |
| `plan/` | Implementation plans, validation |
| `decision/` | Human-in-the-loop decision inbox |
| `checkpoint/` | Quality gates during agent runs |
| `knowledge/` | Persistent knowledge base, fact extraction |
| `governance/` | Policy engine, transition rules, gate evaluation |
| `daemon/` | Background services, scheduled triggers |
| `evolution/` | Structural change proposals and rollback |
| `project/` | Workspace grouping, project-policy linking |
| `sdlc/` | SDLC metrics dashboard |
| `activity/` | Activity feed, audit events |

---

## Event Sourcing

All ADE aggregates are fully event-sourced:

```scala
// 1. Events — sealed trait in entity/
sealed trait GovernancePolicyEvent
object GovernancePolicyEvent:
  case class PolicyCreated(policyId: GovernancePolicyId, ...) extends GovernancePolicyEvent
  case class PolicyArchived(policyId: GovernancePolicyId, ...) extends GovernancePolicyEvent

// 2. Aggregate — built by replaying events
case class GovernancePolicy(id: GovernancePolicyId, ...)
object GovernancePolicy:
  def fromEvents(events: List[GovernancePolicyEvent]): Option[GovernancePolicy] = ...
  val noOp: GovernancePolicy = ...  // default pass-through policy

// 3. Repository — append + replay via EventStore
trait GovernancePolicyRepository:
  def append(event: GovernancePolicyEvent): IO[PersistenceError, Unit]
  def get(id: GovernancePolicyId): IO[PersistenceError, Option[GovernancePolicy]]
  def list: IO[PersistenceError, List[GovernancePolicy]]
```

**EventStore** is the generic persistence abstraction:

```scala
trait EventStore[Id, Event]:
  def append(id: Id, event: Event): IO[PersistenceError, Unit]
  def getEvents(id: Id): IO[PersistenceError, List[Event]]
  def getAllEvents: IO[PersistenceError, List[Event]]
```

Real backend: `EclipseStore` (`DataStoreModule.live`). Test backend: in-memory `Ref`-based stubs.

---

## Error Handling

**Always use `shared.errors.PersistenceError`** — not `db.PersistenceError` (legacy, being removed):

```scala
import shared.errors.PersistenceError

// PersistenceError variants:
PersistenceError.NotFound(entity: String, id: String)
PersistenceError.StorageError(message: String, cause: Option[Throwable])
PersistenceError.SerializationError(message: String)
```

All service methods use typed error channels — no `Throwable` in business logic:

```scala
def get(id: WorkspaceId): IO[PersistenceError, Option[Workspace]]
def evaluateTransition(...): IO[PersistenceError, GovernanceTransitionDecision]
```

---

## Dependency Injection: ZLayer

Standard service definition pattern:

```scala
trait MyService:
  def doThing(id: String): IO[PersistenceError, Result]

object MyService:
  val live: ZLayer[Dependency1 & Dependency2, Nothing, MyService] =
    ZLayer.fromFunction(MyServiceLive.apply)

final case class MyServiceLive(dep1: Dependency1, dep2: Dependency2) extends MyService:
  def doThing(id: String): IO[PersistenceError, Result] = ...
```

**Rules:**
- Services accessed via `ZIO.serviceWithZIO[MyService](_.doThing(id))`
- No layer construction inside effect bodies
- Compose the full dependency graph at application startup in `app/`

---

## Testing Patterns

**Unit tests** live alongside their module in `src/test/scala/`:
- Module-specific tests: `modules/*-domain/src/test/scala/` (when the module has tests)
- Root tests: `src/test/scala/` (for code still in root, or cross-domain tests)

```scala
object MyServiceSpec extends ZIOSpecDefault:
  def spec = suite("MyService")(
    test("describes success case") {
      for
        result <- MyService.doThing("id")
      yield assertTrue(result == expected)
    },
    test("describes failure case") {
      for
        result <- MyService.doThing("bad-id").exit
      yield assert(result)(fails(equalTo(PersistenceError.NotFound("entity", "bad-id"))))
    },
  )
```

**Integration tests** live in `src/it/scala/integration/` and use real EclipseStore:

```scala
object MyIntegrationSpec extends ZIOSpecDefault:
  def spec = suite("MyIntegrationSpec")(
    test("...") {
      withTempDir { path =>
        (for
          repo <- ZIO.service[MyRepository]
          ...
        yield assertTrue(...)).provideLayer(esLayer(path))
      }
    }
  ) @@ sequential
```

**Stub naming convention** — prefix with `Stub` or `NoOp`:

```scala
final class StubActivityHub extends ActivityHub:         // configurable stub
object NoOpGovernancePolicyService extends GovernancePolicyService:  // always passes
```

Common stubs are shared from:
- `src/test/scala/shared/testfixtures/` — reusable stubs (StubConfigRepository, StubActivityHub, etc.)
- `src/test/scala/integration/IntegrationFixtures.scala` — IT test helpers

---

## Frontend Conventions

### Server-side rendering: Scalatags

Views are generated in Scala via Scalatags. Domain-specific views live in their domain module's `boundary/` package; shared view infrastructure (Layout, Components) lives in `shared-web-core`:

```scala
// modules/board-domain/src/main/scala/board/boundary/BoardView.scala
package board.boundary

import shared.web.*  // Layout, Components from shared-web-core

object BoardView:
  def page(workspaceId: String, issues: List[BoardIssue]): String =
    Layout.page("Board", "/board")(
      div(cls := "container",
        issues.map(issue => Components.card(issue.title))
      )
    ).render
```

### Client-side: Lit 3 web components

Custom elements live in `src/main/resources/static/client/components/` with `ab-` prefix:

```javascript
// ab-my-component.js
import { LitElement, html, css } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbMyComponent extends LitElement {
  // Render into light DOM for Tailwind CSS compatibility
  createRenderRoot() { return this; }

  static properties = {
    title: { type: String },
    count: { type: Number },
  };

  render() {
    return html`<div class="ab-my-component">${this.title}: ${this.count}</div>`;
  }
}
customElements.define('ab-my-component', AbMyComponent);
```

**Rules:**
- All custom elements use `ab-` prefix
- Render into light DOM (`createRenderRoot() { return this; }`) for Tailwind compatibility
- Import Lit from CDN (`cdn.jsdelivr.net/npm/lit@3/+esm`)
- Events: `CustomEvent` with `bubbles: true, composed: true`

### HTMX for server interactions

Use HTMX attributes for dynamic UI without writing JavaScript:

```html
<button hx-post="/decisions/123/approve"
        hx-target="#decision-123"
        hx-swap="outerHTML">
  Approve
</button>
```

---

## Board Workflow

The board uses git-backed Markdown files in `.board/` within each workspace repo:

```
.board/
  backlog/   issue-*.md
  todo/      issue-*.md
  in-progress/  issue-*.md
  review/    issue-*.md
  done/      issue-*.md
```

Lifecycle: `Backlog → Todo → InProgress → Review → Done`

- **Dispatch** (Todo → InProgress): subject to governance policy evaluation
- **Complete** (InProgress → Review): agent completes, goes to human review
- **Approve** (Review → Done): human approves, triggers git merge
- **Rework** (Review → InProgress): human requests changes

---

## Typed IDs

Use typed ID wrappers from `shared.ids.Ids` — never raw `String` for entity IDs:

```scala
import shared.ids.Ids.*

val policyId: GovernancePolicyId = GovernancePolicyId("policy-123")
val projectId: ProjectId         = ProjectId("project-abc")
val workspaceId: String          = "ws-1"  // workspaces use String by convention
```

---

## Modularization Strategy

The codebase is being modularized per [bce.design](https://bce.design/) principles. The plan lives at `.claude/plans/snoopy-tinkering-hejlsberg.md`.

### Current State (Phases 0–3 complete, Phase 4 in progress)

**Completed:**
- Foundation modules extracted (`shared-json`, `shared-ids`, `shared-errors`, `shared-store-core`, `shared-web-core`, `shared-services`)
- All 23 domain modules have `entity/` layers
- Most domain modules have `control/` services colocated (agent, board, daemon, evolution, issues, knowledge, orchestration, sdlc)
- Leaf domain views moved to `boundary/` (BoardView, IssueTimelineView, DaemonsView, AgentsView, ChannelView, ModelsView, BoardStats, RunSessionUiMeta)

**Remaining:**
- ~20 views in `shared-web` with multi-domain dependencies → distribute to domain `boundary/` packages
- ~80 root files (controllers, services) blocked by circular or cross-domain dependencies
- Cross-domain integration tests → stay in root or move to domain modules

### How to Move Code to a Module

1. **Check dependencies** — run `grep -r "import.*the.package" src/` to find all import sites
2. **Move the file** — preserve the package declaration (same package across sbt modules is fine in Scala)
3. **Update `build.sbt`** — add required `dependsOn` and `libraryDependencies`
4. **Remove unused imports** — `-Werror` makes unused imports fatal
5. **Compile** — `sbt compile` after every file move
6. **Verify circular deps** — if `A dependsOn B` and `B dependsOn A`, extract trait interfaces to break the cycle

### Circular Dependency Resolution

The orchestration ↔ gateway ↔ workspace cluster has circular control-layer dependencies. The pattern used:
- **Trait interfaces** live in `orchestration-domain` entity package (e.g., `AgentPoolManager`, `TaskExecutor`, `WorkReportEventBus`)
- **`*Live` implementations** live in the module with the richest dependency set, or remain in root
- Other modules `dependsOn(orchestrationDomain)` for the trait, not the implementation

---

## Key Conventions Summary

- **No `var`** — use `Ref`, `Queue`, `Hub` for mutable state
- **No `Throwable`** — typed error ADTs everywhere
- **No side effects outside ZIO** — wrap with `ZIO.attempt` / `ZIO.attemptBlocking`
- **Singular package names** — `agent/` not `agents/`
- **`shared.errors.PersistenceError`** — not `db.PersistenceError`
- **ZLayer for DI** — no manual wiring inside effects
- **Event sourcing** — `*Event` sealed traits, `*.fromEvents` projections
- **SSR + Lit 3** — Scalatags for HTML, `ab-` prefix for web components
- **HTMX** — server-driven interactivity, no custom JS for CRUD
- **`-Werror`** — unused imports are fatal; same-package access across sbt modules needs no import
- **`_root_` prefix** — use when `zio.config` shadows the `config` package or local variables shadow package names
- **One domain per commit** — when moving code to modules, commit after each domain for safe rollback
- **Views in `boundary/`** — domain-specific views belong in their domain module's boundary package, not in `shared-web`
