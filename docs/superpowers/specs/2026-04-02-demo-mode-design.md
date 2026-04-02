# Demo Mode for llm4zio Gateway

## Context

Testing the ADE workflow end-to-end requires real AI provider calls, which is slow, expensive, and non-deterministic. A demo mode that mocks only the AI layer while keeping all other features fully working enables:

- Repeatable UX testing of the full board lifecycle
- Discovery of gaps and enhancement opportunities in the ADE workflow
- Stress testing with configurable issue counts (10, 25, 50, 100)
- Demonstrations without requiring API keys or token spend

The demo simulates a real workflow: user selects Mock AI provider, creates a project from the Spring Boot template, generates issue cards, agents work in parallel (committing markdown summaries), issues arrive at Review for human approval, and after approval they are merged and moved to Done.

---

## Architecture Overview

Three layers of mocking, each surgically replacing only the AI-dependent behavior:

1. **Mock LLM Provider** — new `Mock` variant in `LlmProvider`/`AIProvider` enums with a `MockProvider` returning pre-canned responses
2. **Mock Agent Runner** — replaces the injectable `runCliAgent` function in `WorkspaceRunServiceLive` with a mock that delays, commits a markdown file, and exits
3. **Demo Orchestrator** — automates the Quick Demo flow (create project, generate issues, dispatch)

All non-AI features remain fully real: git worktrees, board state management, governance evaluation, decision inbox, merge flow, activity feed.

---

## Components

### 1. Mock LLM Provider

**New file:** `llm4zio/src/main/scala/llm4zio/providers/MockProvider.scala`

Implements `LlmService` with deterministic responses:

- `executeStream` / `executeStreamWithHistory`: emit pre-canned chunks with small delays to simulate streaming
- `executeWithTools`: return empty `ToolCallResponse` (no tool calls needed for demo)
- `executeStructured[A]`: detect schema shape and return pre-canned JSON matching the expected type (critical for `IssueCreationWizard` which expects `GeneratedIssueBatch`)
- `isAvailable`: always returns `true`

The mock provider reads `demo.issueCount` from config to determine how many issues to include in structured responses.

**Enum additions:**
- `llm4zio/src/main/scala/llm4zio/core/Models.scala` — add `Mock` to `LlmProvider`
- `src/main/scala/config/entity/ProviderModels.scala` — add `Mock` to `AIProvider`

**Factory wiring:**
- `llm4zio/src/main/scala/llm4zio/core/LlmService.scala` — add `case LlmProvider.Mock => MockProvider.make(cfg)` to `buildProvider`
- `src/main/scala/config/SettingsApplier.scala` — add `case "Mock" => Some(AIProvider.Mock)` to `parseAIProvider`
- `src/main/scala/config/ConfigLoader.scala` — add `case "mock" => Right(AIProvider.Mock)` to `parseAIProvider`
- `src/main/scala/config/control/ModelService.scala` — add `Mock` entry to catalog (single "mock-model" entry) and handle in `probeRemote` (return instant success)
- `src/main/scala/config/boundary/SettingsValidator.scala` — allow `Mock` as valid provider value

### 2. Pre-Canned Issue Catalog

**New file:** `src/main/scala/demo/entity/MockIssueCatalog.scala`

~100 curated Spring Boot microservice issues organized by category:

| Category | Count | Examples |
|----------|-------|---------|
| Setup/Bootstrap | 5 | Maven setup, CI pipeline, Docker, CLAUDE.md, code formatting |
| REST Endpoints | 20 | CRUD controllers, validation, pagination, error handling, versioning |
| Database Layer | 15 | JPA entities, repositories, migrations, connection pooling |
| Auth/Security | 15 | Spring Security, JWT, OAuth2, CORS, rate limiting, RBAC |
| Testing | 15 | Unit tests, integration tests, test containers, contract tests |
| Observability | 10 | Actuator, custom metrics, structured logging, tracing |
| CI/CD | 10 | GitHub Actions, Docker builds, environment configs |
| Documentation | 10 | OpenAPI specs, README, architecture docs, runbooks |

Each entry is a `MockIssueTemplate` case class:
```scala
case class MockIssueTemplate(
  id: String,
  title: String,
  priority: Priority,
  tags: List[String],
  requiredCapabilities: List[String],
  acceptanceCriteria: List[String],
  estimate: Option[String],
  blockedBy: List[String],
  body: String,
)
```

Sampling method: `sample(count: Int): List[MockIssueTemplate]` proportionally selects from each category. Issues include realistic `blockedBy` references (e.g., auth issues blocked by setup issues). References are validated to exist within the sampled batch.

### 3. Demo Configuration

**New file:** `src/main/scala/demo/entity/DemoConfig.scala`

```scala
case class DemoConfig(
  enabled: Boolean = false,
  issueCount: Int = 25,
  agentDelaySeconds: Int = 5,
)
```

Settings keys: `demo.enabled`, `demo.issueCount`, `demo.agentDelaySeconds`

### 4. Mock Agent Runner

**New file:** `src/main/scala/demo/control/MockAgentRunner.scala`

Replaces `CliAgentRunner.runProcessStreaming` when demo mode is active. Uses the existing injectable `runCliAgent` parameter of `WorkspaceRunServiceLive`.

Behavior:
1. Log `[mock] Starting mock agent execution...` via the `onLine` callback
2. Wait `demo.agentDelaySeconds` seconds
3. Write `docs/mock-implementation/{issueId}.md` to the worktree with:
   - Issue title and description
   - Acceptance criteria
   - "Mock implementation" note with timestamp
4. `git add . && git commit` in the worktree
5. Log `[mock] Mock implementation complete.` via `onLine`
6. Return exit code 0

This triggers the standard completion lifecycle: `RunCompleted` → `completeIssue(success=true)` → issue moves to Review → `DecisionInbox` creates review decision.

**Injection point:** `WorkspaceRunServiceLive` constructor parameter `runCliAgent`. At ZLayer construction time (in the app wiring module), if `demo.enabled=true`, provide `MockAgentRunner.run` instead of `CliAgentRunner.runProcessStreaming`.

### 5. Demo Orchestrator

**New file:** `src/main/scala/demo/control/DemoOrchestrator.scala`

```scala
trait DemoOrchestrator:
  def runQuickDemo(config: DemoConfig): IO[DemoError, DemoResult]
  def cleanup(projectId: String): IO[DemoError, Unit]
  def status: UIO[Option[DemoStatus]]
```

`runQuickDemo` flow:
1. Create project "demo-spring-boot" via `ProjectRepository`
2. Create workspace directory, `git init`, register via `WorkspaceRepository`
3. Sample `config.issueCount` issues from `MockIssueCatalog`
4. Write issues to `.board/backlog/` via `BoardRepositoryFS.createIssue`
5. Trigger `BoardOrchestrator.dispatchCycle(workspacePath)`
6. Return `DemoResult(projectId, workspaceId, issueCount, estimatedSeconds)`

`cleanup` removes demo project, workspace, and worktree artifacts.

`status` returns current demo progress: issues dispatched, in-progress, at review, done.

### 6. Demo Controller

**New file:** `src/main/scala/demo/boundary/DemoController.scala`

Routes:
- `POST /api/demo/quick-start` — triggers Quick Demo, returns HTML progress fragment
- `GET /api/demo/status` — returns demo progress (for HTMX polling)
- `POST /api/demo/cleanup` — removes demo artifacts

### 7. Settings UI — Demo Tab

**Modified file:** `src/main/scala/shared/web/SettingsView.scala`

Add "Demo" to the tabs list. The demo tab contains:

- **Demo mode toggle** — checkbox that enables/disables demo mode. When toggled ON, auto-sets `ai.provider=Mock`. When OFF, restores previous provider (stored in `demo.previousProvider`).
- **Issue count dropdown** — select with options: 10, 25, 50, 100
- **Agent delay selector** — select with options: 2s, 5s, 10s, 30s
- **Save button** — persists demo settings via `POST /settings/demo`
- **Quick Demo section** — description + "Start Quick Demo" button (`hx-post="/api/demo/quick-start"`)
- **Progress area** — `hx-get="/api/demo/status" hx-trigger="every 3s"` showing dispatched/in-progress/review/done counts
- **Cleanup button** — `hx-post="/api/demo/cleanup"` to remove demo artifacts

**Modified file:** `src/main/scala/config/boundary/SettingsController.scala`

Add routes:
- `GET /settings/demo` — renders demo tab
- `POST /settings/demo` — saves demo settings keys

Add demo keys to the managed settings list.

---

## Two Trigger Modes

### Quick Demo (automated)
One click starts the full cycle. The user watches issues flow through the board and then approves them at Review. Minimal interaction required.

### Manual Mode
User enables Mock provider in settings, then manually:
1. Creates a project from the Spring Boot template
2. Uses IssueCreationWizard to generate issues (mock `executeStructured` returns pre-canned batch)
3. Dispatches issues from the board UI
4. Mock agents work with configured delay
5. Approves issues at Review

This mode tests each step of the real UX individually.

---

## Files Summary

### New Files (6)
| File | Package Layer |
|------|--------------|
| `llm4zio/src/main/scala/llm4zio/providers/MockProvider.scala` | llm4zio lib |
| `src/main/scala/demo/entity/DemoConfig.scala` | demo/entity |
| `src/main/scala/demo/entity/MockIssueCatalog.scala` | demo/entity |
| `src/main/scala/demo/control/MockAgentRunner.scala` | demo/control |
| `src/main/scala/demo/control/DemoOrchestrator.scala` | demo/control |
| `src/main/scala/demo/boundary/DemoController.scala` | demo/boundary |

### Modified Files (9)
| File | Change |
|------|--------|
| `llm4zio/.../core/Models.scala` | Add `Mock` to `LlmProvider` |
| `llm4zio/.../core/LlmService.scala` | Add `Mock` case to factory |
| `src/.../config/entity/ProviderModels.scala` | Add `Mock` to `AIProvider` |
| `src/.../config/SettingsApplier.scala` | Add `Mock` parse + `toDemoConfig` |
| `src/.../config/ConfigLoader.scala` | Add `mock` parse case |
| `src/.../config/control/ModelService.scala` | Add `Mock` to catalog/probe |
| `src/.../config/boundary/SettingsController.scala` | Add demo routes + keys |
| `src/.../config/boundary/SettingsValidator.scala` | Allow `Mock` provider |
| `src/.../shared/web/SettingsView.scala` | Add Demo tab |
| `src/.../app/ApplicationDI.scala` | Conditional `runCliAgent` injection |

---

## Testing Strategy

### Unit Tests
- `MockProviderSpec` — all `LlmService` methods return valid responses; `executeStructured` returns parseable `GeneratedIssueBatch`
- `MockIssueCatalogSpec` — sampling returns correct count, all categories represented, `blockedBy` references valid within batch, no duplicate IDs
- `MockAgentRunnerSpec` — markdown file written to correct path, git commit succeeds, delay respected

### Integration Tests
- `DemoOrchestratorSpec` — full Quick Demo flow creates project, workspace, issues, dispatches; mock agents complete; issues arrive at Review

### Verification
- Start gateway with `sbt run`
- Navigate to Settings → Demo tab
- Enable demo mode, select 10 issues, 2s delay
- Click "Quick Demo"
- Verify issues appear on board, flow through lifecycle, arrive at Review
- Approve an issue, verify it moves to Done with merge
- Test manual mode: create project from template, generate issues via wizard, dispatch manually

---

## Potential Challenges

1. **Concurrent mock agents**: 100 issues dispatched simultaneously = 100 fibers. The `AgentPoolManager` may throttle. Mitigation: set generous pool capacity for mock agent, or dispatch in batches.

2. **Worktree creation needs real git repo**: Quick Demo must `git init` before dispatch. Handled in step 2 of the orchestrator.

3. **Two provider enum hierarchies**: `LlmProvider` (llm4zio lib) and `AIProvider` (gateway config) both need `Mock`. This follows the established pattern for all other providers.

4. **Demo cleanup**: Worktrees, branches, and project artifacts must be cleanable. The `cleanup` endpoint handles this.
