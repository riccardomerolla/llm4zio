# Markdown State Machine — Design Spec

**Date:** 2026-03-20
**Status:** Approved
**Milestone:** M15: Markdown State Machine

## Problem

The current issue management system uses EclipseStore (event-sourced with JSON snapshots) for persistence. While functional, it:
- Requires a running application and binary DB to view/edit board state
- Separates issue metadata from agent task instructions (translation layer needed)
- Cannot be versioned or diffed meaningfully (binary persistence)
- Ties workspace board state to an external store instead of the workspace repo itself

## Solution: Markdown State Machine

Replace EclipseStore-based issue persistence for workspace boards with a **git-native, filesystem-based approach**:

- **Folders = board columns** (physical directories in the workspace repo)
- **ISSUE.md files = issue cards** (YAML frontmatter + markdown agent instructions, following the SKILL.md pattern from openclaw)
- **Git = event log** (every state change is a commit)
- **The board IS the repo** — `git clone` = get the entire board state

The issue definition IS the agent's task instructions. No translation needed.

---

## Filesystem Layout

```
{workspace-repo}/
└── .board/
    ├── BOARD.md                    (board config: defaultAgent, ciCommand)
    ├── backlog/
    │   └── add-search-feature/
    │       ├── ISSUE.md
    │       └── references/
    │           └── search-api-spec.md
    ├── todo/
    │   ├── fix-auth-timeout/
    │   │   ├── ISSUE.md
    │   │   ├── references/
    │   │   │   └── session-flow.md
    │   │   └── scripts/
    │   │       └── validate-auth.sh
    │   └── refactor-api-layer/
    │       └── ISSUE.md
    ├── in-progress/
    ├── review/
    ├── done/
    └── archive/
```

**6 physical columns:** backlog/ | todo/ | in-progress/ | review/ | done/ | archive/

Transient states (Assigned, Merging, Rework) tracked in ISSUE.md frontmatter field, not as folder moves.

Moving an issue = `git mv .board/{from}/{id}/ .board/{to}/{id}/` + commit.

---

## ISSUE.md Format

```yaml
---
id: fix-auth-timeout
title: Fix authentication timeout on session refresh
priority: high
assignedAgent: coder-agent
requiredCapabilities: [scala, zio, auth]
blockedBy: []
tags: [bug, auth]
acceptanceCriteria:
  - Session refresh extends token by 30 min
  - Expired tokens return 401
estimate: medium
proofOfWork:
  - Tests pass for SessionService
  - No regression in auth flow
transientState: none
branchName: null
failureReason: null
completedAt: null
createdAt: 2026-03-20T10:00:00Z
---

# Fix Authentication Timeout

## Context
Users report session drops after 30 minutes of inactivity...

## Steps
1. Check SessionService.refresh() logic
2. Fix token expiry calculation
3. Add regression test

## References
See `references/session-flow.md` for auth architecture.
```

Each issue directory follows the SKILL.md pattern with optional subdirectories:
- `references/` — context documents, API specs, domain guides
- `scripts/` — validation scripts, setup scripts
- `assets/` — templates, config files, data files

---

## Scala 3 ADT

New package: `board/entity/`

### BoardModels.scala
- `BoardColumn` enum: `Backlog | Todo | InProgress | Review | Done | Archive`
- `TransientState` enum: `None | Assigned(agent, at) | Merging(at) | Rework(reason, at)`
- `IssuePriority` enum: `Critical | High | Medium | Low`
- `IssueEstimate` enum: `XS | S | M | L | XL`
- `IssueFrontmatter` case class: all YAML fields
- `BoardIssue`: frontmatter + markdown body + column + directoryPath
- `Board`: workspacePath + `Map[BoardColumn, List[BoardIssue]]`
- `BoardConfig`: defaultAgent, autoDispatch, ciCommand
- `BoardIssueId` opaque type (kebab-case slug = directory name)

### BoardError.scala
Error enum: `BoardNotFound | IssueNotFound | IssueAlreadyExists | InvalidColumn | ParseError | WriteError | GitOperationFailed | DependencyCycle | ConcurrencyConflict`

---

## Service Layer

### IssueMarkdownParser (control/)
- `parse(raw)` → `(IssueFrontmatter, body)` — split on `---`, parse YAML
- `render(frontmatter, body)` → String — re-render YAML + body
- `updateFrontmatter(raw, fn)` → String — parse, apply, render
- Lightweight line-based YAML parsing (flat frontmatter structure)

### BoardRepository (entity/ trait, control/ impl)
Every mutation = git commit on workspace main branch.
- `initBoard`, `readBoard`, `readIssue`, `createIssue`, `moveIssue`, `updateIssue`, `deleteIssue`, `listIssues`
- Concurrency: per-workspace `Semaphore(1)` for git operations
- Atomicity: `git checkout .board/` rollback on failure via `ZIO.ensuring`

### BoardCache (control/)
- `Ref[Map[String, CachedBoard]]` — workspace path → (board, gitHeadSha, cachedAt)
- Write-through invalidation + git HEAD SHA check + 30s TTL
- Lazy column loading

### BoardOrchestrator (control/)
- `dispatchCycle(workspacePath)` → `DispatchResult(dispatched, blocked, skipped)`
- `completeIssue(workspacePath, issueId, success, details)` → merge + move

### IssueCreationWizard (control/)
- `fromNaturalLanguage` — LLM generates ISSUE.md from description
- `fromCodeAnalysis` — LLM suggests issues from source code analysis
- `fromTemplate` — interpolate `.board/templates/` with {{placeholders}}

---

## Batch Execution Flow

```
1. READ board state → extract todo/ issues
2. RESOLVE dependencies → filter ready issues → sort by priority
3. DISPATCH (parallel, respecting agent pool):
   - Update frontmatter: transientState = Assigned
   - git mv todo/{id}/ → in-progress/{id}/
   - WorkspaceRunService.assign() → worktree + branch
   - Agent receives ISSUE.md body as context (READ-ONLY)
4. ON SUCCESS:
   - git merge --no-ff branch into main
   - git mv in-progress/{id}/ → done/{id}/
   - Update frontmatter: completedAt
   - Cleanup worktree
5. ON FAILURE:
   - git mv in-progress/{id}/ → backlog/{id}/
   - Update frontmatter: failureReason
   - Cleanup worktree
6. LOOP
```

Board state changes happen ONLY on main by the orchestrator. Agents work on feature branches in worktrees and never touch `.board/`.

---

## Integration

### Reuse
- `WorkspaceRunService` — worktree lifecycle, fiber registry, agent pool
- `GitService` — extend with add/mv/commit/rm
- `DependencyResolver` — dependency graph + cycle detection
- `PlannerAgentService` — LLM-driven issue creation pattern
- `ActivityHub` + `GitWatcher` — real-time UI updates

### Replace (workspace boards only)
- `IssueRepositoryES` / `IssueEventStoreES` → `BoardRepositoryFS`
- `IssueEvent` ADT → git commits
- `AutoDispatcher` → `BoardOrchestrator`

### New files
```
src/main/scala/board/
├── entity/  (BoardModels, BoardError, BoardRepository trait)
├── control/ (BoardRepositoryFS, IssueMarkdownParser, BoardOrchestrator,
│             BoardDependencyResolver, IssueCreationWizard, BoardCache)
└── boundary/ (BoardController)
```

---

## Pros & Cons

| Pros | Cons & Mitigations |
|------|-------------------|
| Git-native — full audit trail | Concurrent mutations → Semaphore(1) |
| Human-readable — any text editor | Performance at scale → in-memory cache |
| Agent-native — ISSUE.md IS instructions | No SQL queries → cache-based filtering |
| Portable — git clone = board state | No real-time push → GitWatcher + ActivityHub |
| No binary DB dependency | Git repo bloat → markdown-only, external links for binaries |
| Diff-friendly — reviewable state changes | Partial failure → ZIO.ensuring rollback |
| Self-contained — refs/scripts/assets travel with issue | |
| Offline-first — no server needed | |

---

## Verification

- **Unit:** IssueMarkdownParser roundtrip, BoardDependencyResolver cycle detection, BoardCache invalidation
- **Integration:** BoardRepositoryFS CRUD with git log verification, BoardOrchestrator dispatch cycle
- **E2E:** Init board → create issues → dispatch → agents in worktrees → merge → done/ → verify `git log .board/`
