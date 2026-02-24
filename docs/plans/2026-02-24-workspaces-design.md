# Workspaces Feature Design

**Date:** 2026-02-24
**Status:** Approved
**Approach:** A — Thin Shell (local git worktree, no GitHub dependency)

## Overview

Workspaces let users register a local git repository and assign issues (tasks) to CLI agents
(Gemini CLI, OpenCode, Claude, Codex, Copilot). Each assignment gets an isolated git worktree
branch. The agent runs in non-interactive mode, and its stdout/stderr streams line-by-line into
the linked chat conversation as `Status` messages. Multi-turn follow-ups are supported by sending
a new message to the conversation.

---

## Section 1: Data Model

Stored in EclipseStore using the same prefix-key pattern as `agent:` and `workflow:` entries.

### Workspace

Key: `workspace:<id>`

```scala
case class Workspace(
  id: String,
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
  enabled: Boolean = true,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema
```

### WorkspaceRun

Key: `workspace-run:<id>`

```scala
case class WorkspaceRun(
  id: String,
  workspaceId: String,
  issueRef: String,
  agentName: String,
  prompt: String,
  conversationId: String,
  worktreePath: String,
  branchName: String,
  status: RunStatus,
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec, Schema

enum RunStatus derives JsonCodec, Schema:
  case Pending, Running, Completed, Failed
```

---

## Section 2: Architecture & Component Layers

```
/settings/workspaces          WorkspacesController
       │                             │
       │  CRUD                       │  HTTP routes
       ▼                             ▼
WorkspaceRepository ◄──── ConfigRepositoryES (EclipseStore)
  workspace:<id>
  workspace-run:<id>

/api/workspaces/:id/runs      WorkspaceRunController (part of WorkspacesController)
       │
       │  POST (assign issue)
       ▼
WorkspaceRunService
  1. create WorkspaceRun record (Pending)
  2. git worktree add <path> <branch>
  3. create ChatConversation (linked to runId)
  4. spawn ZIO fiber → CliAgentRunner
  5. update WorkspaceRun status → Running

CliAgentRunner (per-run fiber)
  - streams stdout/stderr from CLI process
  - each line → ConversationEntry (MessageType.Status)
  - on exit 0 → RunStatus.Completed
  - on non-zero → RunStatus.Failed
  - always → git worktree remove --force (best-effort)
```

### New files (BCE structure)

| File | Purpose |
|---|---|
| `workspace/entity/WorkspaceModels.scala` | `Workspace`, `WorkspaceRun`, `RunStatus` |
| `workspace/entity/WorkspaceRepository.scala` | trait + EclipseStore impl |
| `workspace/control/WorkspaceRunService.scala` | worktree lifecycle + fiber spawn |
| `workspace/control/CliAgentRunner.scala` | process execution + line streaming |
| `workspace/boundary/WorkspacesController.scala` | HTTP routes |
| `shared/web/WorkspacesView.scala` | Scalatags UI |

### Existing files touched

| File | Change |
|---|---|
| `app/ApplicationDI.scala` | wire new layers |
| `shared/web/HtmlViews.scala` | add Workspaces nav entry |
| `config/entity/ConfigStoreModule.scala` | add `Workspace` and `WorkspaceRun` schema codecs |

---

## Section 3: Data Flow — Issue Assignment to Agent Run

### Happy path

```
POST /api/workspaces/:wsId/runs  { issueRef, prompt, agentName }
  │
  ▼
WorkspaceRunService.assign(wsId, req)
  ├─ load Workspace by id (fail if not found or disabled)
  ├─ runId = UUID
  ├─ branchName = s"agent/${req.issueRef}-${runId.take(8)}"
  ├─ worktreePath = s"~/.cache/agent-worktrees/${ws.name}/${runId}"
  ├─ git worktree add <worktreePath> -b <branchName>
  ├─ create ChatConversation(title = s"[${ws.name}] ${req.issueRef}", runId = runId)
  ├─ save WorkspaceRun(status = Pending)
  └─ fiber: CliAgentRunner.run(run, ws, conversation)
       │
       ▼
     update WorkspaceRun → Running
     build CLI argv (agent-specific, see CLI Argv Mapping below)
     ProcessBuilder.start() with cwd = worktreePath
       │
       ├─ stdout lines → ConversationEntry(senderType=Assistant, messageType=Status)
       ├─ stderr lines → ConversationEntry(senderType=System, messageType=Status)
       └─ process exits
            ├─ exit 0 → WorkspaceRun(status=Completed)
            └─ exit ≠ 0 → WorkspaceRun(status=Failed)
            └─ always: git worktree remove --force <worktreePath> (best-effort)
```

### CLI argv mapping

| Agent | Command |
|---|---|
| `gemini-cli` | `gemini -p <prompt> <worktreePath>` |
| `opencode` | `opencode run --prompt <prompt> <worktreePath>` |
| `claude` | `claude --print <prompt>` (cwd = worktreePath) |
| `codex` | `codex <prompt>` (cwd = worktreePath) |
| `copilot` | `gh copilot suggest -t shell <prompt>` (cwd = worktreePath) |

### Multi-turn follow-up

```
POST /api/chat/:conversationId/messages  { content: "also add tests" }
  │
  ▼
ChatController detects runId on conversation
  → WorkspaceRunService.continueRun(runId, followUpPrompt)
       ├─ check worktree still exists
       ├─ re-create if Completed and branch still present
       └─ spawn new CliAgentRunner fiber with follow-up prompt
```

---

## Section 4: UI — `/settings/workspaces`

### Workspace list

```
┌─────────────────────────────────────────────────────┐
│  Workspaces                          [+ New Workspace]│
├─────────────────────────────────────────────────────┤
│  my-api          /home/user/projects/my-api          │
│  Default agent: gemini-cli     [Runs] [Edit] [Delete] │
│                                                     │
│  frontend-app    /home/user/projects/frontend        │
│  Default agent: opencode       [Runs] [Edit] [Delete] │
└─────────────────────────────────────────────────────┘
```

### Runs panel (HTMX inline expand)

```
┌─────────────────────────────────────────────────────┐
│  Runs for my-api                                    │
├──────────┬───────────┬───────────┬──────────────────┤
│ Issue    │ Agent     │ Status    │ Actions          │
├──────────┼───────────┼───────────┼──────────────────┤
│ #42      │ gemini-cli│ Completed │ [View Chat]      │
│ #51      │ opencode  │ Running   │ [View Chat]      │
│ #60      │ claude    │ Failed    │ [View Chat]      │
└──────────┴───────────┴───────────┴──────────────────┘
                              [Assign New Issue ▼]
```

### HTTP routes

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/settings/workspaces` | Full page |
| `GET` | `/api/workspaces` | JSON list |
| `POST` | `/api/workspaces` | Create workspace |
| `PUT` | `/api/workspaces/:id` | Update workspace |
| `DELETE` | `/api/workspaces/:id` | Delete workspace |
| `GET` | `/api/workspaces/:id/runs` | HTMX runs table fragment |
| `POST` | `/api/workspaces/:id/runs` | Assign issue → trigger run |
| `GET` | `/api/workspaces/:id/runs/:runId` | Run status (polling) |

**[View Chat]** links to the existing chat conversation view — no new UI needed for log viewing.

**Nav:** Add "Workspaces" entry to the sidebar nav alongside Settings, after Channels.

---

## Section 5: Error Handling & Testing

### Error table

| Failure point | Behavior |
|---|---|
| Workspace not found | 404 JSON error |
| Workspace disabled | 409 with reason |
| `git worktree add` fails | 409, no run created |
| CLI binary not on PATH | Immediate `Failed` run, error as `Status` message |
| Process exceeds timeout (default 30 min) | SIGTERM → SIGKILL after 5s, run → `Failed` |
| App shutdown mid-run | ZIO `Scope` finalizer sends SIGTERM, logs worktree path |
| Worktree dir already exists | `git worktree add` errors → surfaced as 409 |
| Follow-up on gone worktree | Re-create from branch if branch exists, else 409 |

### Typed error channel

```scala
enum WorkspaceError:
  case NotFound(id: String)
  case Disabled(id: String)
  case WorktreeError(message: String)
  case AgentNotFound(name: String)
  case RunTimeout(runId: String)
  case PersistenceFailure(cause: Throwable)
```

### Test plan

| Spec | Coverage |
|---|---|
| `WorkspaceRepositorySpec` | CRUD round-trip, EclipseStore in-memory |
| `WorkspaceRunServiceSpec` | Run lifecycle state transitions, stubbed `git` and CLI |
| `CliAgentRunnerSpec` | Real `echo` as agent; lines appear as `Status` entries |
| `WorkspacesControllerSpec` | HTTP routes, stubs `WorkspaceRunService` |
| `WorkspacesViewSpec` | Scalatags rendering (workspace list, runs table, empty states) |
