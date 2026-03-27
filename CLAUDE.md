# CLAUDE.md — llm4zio Project Conventions

This file documents the conventions, patterns, and structure of the llm4zio codebase for AI assistants and new contributors.

---

## Build Commands

```bash
sbt compile           # compile all sources
sbt test              # run unit tests (1126+)
sbt it:test           # run integration tests (18+, no external services required)
sbt run               # start the gateway (http://localhost:8080)
sbt fmt               # format: scalafmt + scalafix
sbt check             # check formatting without modifying
sbt assembly          # build fat JAR
sbt --client test     # faster: connect to running sbt server
```

---

## Architecture: BCE Pattern

All domain packages follow **Boundary / Control / Entity**:

```
domain/
  boundary/   HTTP controllers, SSE endpoints, WebSocket handlers
              — only routing and HTTP concern live here
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

**Cross-cutting packages** (used by multiple domains):

```
shared/
  errors/     PersistenceError and other shared error ADTs
  ids/        Typed ID wrappers (GovernancePolicyId, ProjectId, etc.)
  store/      EventStore trait, StoreConfig, DataStoreModule
  web/        Scalatags view helpers, layout, HTML components

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

**Unit tests** live in `src/test/scala/` matching the main package structure:

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

Common stubs are shared from `src/test/scala/integration/IntegrationFixtures.scala` for IT tests.

---

## Frontend Conventions

### Server-side rendering: Scalatags

Views are generated in Scala via Scalatags in `shared/web/`:

```scala
// src/main/scala/shared/web/MyView.scala
object MyView:
  def render(items: List[Item]): String =
    html(
      body(
        div(cls := "container",
          items.map(item => div(cls := "card", item.name))
        )
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
