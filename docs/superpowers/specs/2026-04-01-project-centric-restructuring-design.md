# Project-Centric Restructuring

## Context

After using the llm4zio-gateway ADE, several UX pain points emerged:

1. **Commit noise** — `.llm4zio/analysis/` files are committed to workspace git repos, polluting code history with non-code changes. `.board/` is a separate nested git repo inside the workspace directory, which is awkward.
2. **No project-scoped filtering** — users must navigate between separate project and workspace pages; there's no global filter to scope all views by project.
3. **Loose workspace-project coupling** — workspaces can exist without a project, making the getting-started flow confusing and the data model inconsistent.
4. **Standalone chat** — conversations can exist without project/workspace context, which has no practical use case.

This restructuring makes **projects the primary organizational unit**, moves all gateway-managed files out of workspace repos, and adds a global project filter to the UI.

---

## New Directory Layout

All gateway-managed data moves out of workspace repos into `~/.llm4zio-gateway/projects/`:

```
~/.llm4zio-gateway/
├── data/                              # EclipseStore (unchanged)
│   ├── config-store/
│   └── data-store/
└── projects/
    └── {projectId}/                   # One git repo per project
        ├── .git/
        ├── .board/
        │   ├── BOARD.md
        │   ├── backlog/{issueId}/ISSUE.md
        │   ├── todo/...
        │   ├── in_progress/...
        │   ├── review/...
        │   ├── done/...
        │   └── archive/...
        └── workspaces/
            └── {workspaceId}/
                └── .llm4zio/
                    └── analysis/
                        ├── code-review.md
                        ├── architecture.md
                        └── security.md
```

**Key properties:**
- Each project directory is a single git repo (replaces the nested `.board` git repo)
- Board is per-project (one board shared across all workspaces in a project)
- Analysis results are per-workspace but stored under the project directory
- Workspace repos (`localPath`) contain only code — zero gateway artifacts

---

## Domain Model Changes

### Workspace — add `projectId`

```scala
case class Workspace(
  id: String,
  projectId: ProjectId,    // NEW — required, set at creation
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
  enabled: Boolean,
  runMode: RunMode,
  cliTool: String,
  createdAt: Instant,
  updatedAt: Instant,
  defaultBranch: String = "main",
)
```

`WorkspaceEvent.Created` also gains `projectId: ProjectId`.

`WorkspaceRepository` gains `listByProject(projectId: ProjectId): IO[PersistenceError, List[Workspace]]`.

### Project — remove `workspaceIds`

```scala
case class Project(
  id: ProjectId,
  name: String,
  description: Option[String],
  // workspaceIds removed — query WorkspaceRepository.listByProject instead
  settings: ProjectSettings,
  createdAt: Instant,
  updatedAt: Instant,
)
```

**Removed events:** `ProjectEvent.WorkspaceAdded`, `ProjectEvent.WorkspaceRemoved` — the link is now implicit via `workspace.projectId`.

`Project.fromEvents` no longer handles these events. The `applyEvent` cases for `WorkspaceAdded` and `WorkspaceRemoved` are removed.

### Conversation — proper project+workspace association

```scala
case class Conversation(
  id: ConversationId,
  projectId: ProjectId,       // NEW — required
  workspaceId: String,        // Now a proper field (was encoded in description marker)
  channel: ChannelInfo,
  state: ConversationState,
  title: String,
  description: String,        // No longer encodes workspace/mode markers
  messages: List[Message],
  runId: Option[TaskRunId],
  createdBy: Option[String],
)
```

`ConversationEvent.Created` also gains `projectId` and `workspaceId` as proper fields.

The description-marker parsing logic (`parseWorkspaceMarkerDescription`, `modeDescription`) is removed.

---

## Project Storage Service

New service: `ProjectStorageService` in `project/control/`.

```scala
trait ProjectStorageService:
  /** Initialize the project directory as a git repo with .board structure. */
  def initProjectStorage(projectId: ProjectId): IO[PersistenceError, Path]

  /** Resolve the project storage root path. */
  def projectRoot(projectId: ProjectId): UIO[Path]

  /** Resolve the board path for a project. */
  def boardPath(projectId: ProjectId): UIO[Path]

  /** Resolve the workspace analysis path within a project. */
  def workspaceAnalysisPath(projectId: ProjectId, workspaceId: String): UIO[Path]
```

Called automatically when a project is created. The gateway root (`~/.llm4zio-gateway`) is injected via config.

---

## Project Filter — Top Bar Dropdown

### UI

A dropdown in the top bar, left of core nav items:

```
┌──────────────────────────────────────────────────────────────┐
│ [▼ All Projects] │ Command Center Board Projects ... │ ADE ▼│
└──────────────────────────────────────────────────────────────┘
```

Options: "All Projects" (default) + one entry per project.

### Mechanism

1. **Client:** On selection, store `projectId` (or `"all"`) in `localStorage` and set a `project-filter` cookie with the same value (so the server can read it on full page loads). Trigger page reload.
2. **HTMX header:** Configure `htmx.config` to append `X-Project-Filter` header (read from localStorage) to every HTMX request. For full page loads (non-HTMX), the server reads the `project-filter` cookie instead.
3. **Server:** A helper extracts `X-Project-Filter` from the request header. Returns a `ProjectFilter` ADT:
   ```scala
   enum ProjectFilter:
     case All
     case Selected(projectId: ProjectId)
   ```
4. **Controllers** accept `ProjectFilter` and filter their data accordingly.

### Affected views

All views filter by the selected project:

- **Board** — shows the selected project's board. When "All Projects" is selected, aggregates boards from all projects (reads each project's `.board/` directory and merges issues into a unified view, tagging each issue with its project name)
- **Tasks/Runs** — filter by workspaces belonging to the project
- **Chat list** — filter conversations by project
- **Specifications, Plans, Knowledge** — filter by project context
- **SDLC Dashboard, Decisions, Checkpoints** — filter by project
- **Command Center** — summary scoped to project

---

## Workspace Creation Flow

### Before

1. Go to `/workspaces`, fill form, create workspace
2. Go to `/projects/{id}`, add workspace from dropdown

### After

1. Go to `/projects/{id}`, Workspaces tab
2. Click "Add Workspace", fill inline form (name, localPath, cliTool, runMode, defaultBranch)
3. Submit to `POST /projects/{id}/workspaces/create`
4. Workspace created with `projectId` set automatically

### Removed routes

- `POST /workspaces` (standalone creation)
- `GET /workspaces` (standalone list page)
- `POST /projects/{id}/workspaces` (add existing workspace)
- `POST /projects/{id}/workspaces/remove` (remove workspace from project)

### Retained routes

- `GET /workspaces/{id}` — workspace detail page (accessed from project view)
- `PATCH /workspaces/{id}` — update workspace settings
- Run-related workspace routes remain unchanged

---

## Chat Changes

- `ChatController` requires `projectId` and `workspaceId` when creating a conversation
- The `/chat/new` route reads the project filter and only shows workspaces from that project
- The chats dropdown in the top bar filters by the selected project
- Remove the description-marker encoding (`"mode:plan|workspace:ws-123"`)
- Conversations without project+workspace context cannot be created

---

## Getting Started Flow

The intended user journey:

1. **Create a project** — `/projects` page, fill name/description
2. **Add workspace(s)** — within the project detail page, inline creation form
3. **Select project in dropdown** — top bar filter scopes everything
4. **Board, chat, analysis** — all scoped to the selected project

---

## Files to Modify

### New files
| File | Purpose |
|------|---------|
| `project/control/ProjectStorageService.scala` | Manages project directory git repos, path resolution |

### Modified files (key)
| File | Change |
|------|--------|
| `workspace/entity/WorkspaceModels.scala` | Add `projectId: ProjectId` to `Workspace`, update `fromEvents` |
| `workspace/entity/WorkspaceEvent.scala` | Add `projectId` to `Created` event |
| `workspace/entity/WorkspaceRepository.scala` | Add `listByProject` method |
| `workspace/entity/WorkspaceRepositoryES.scala` | Implement `listByProject` |
| `project/entity/ProjectModels.scala` | Remove `workspaceIds`, update `fromEvents` |
| `project/entity/ProjectEvent.scala` | Remove `WorkspaceAdded`/`WorkspaceRemoved` |
| `project/boundary/ProjectsController.scala` | Inline workspace creation, remove add/remove routes, read project filter |
| `workspace/boundary/WorkspacesController.scala` | Remove creation route and list page |
| `board/control/BoardRepositoryFS.scala` | Change path resolution to use `ProjectStorageService` |
| `analysis/control/AnalysisAgentRunner.scala` | Change output paths to use `ProjectStorageService` |
| `conversation/entity/Conversation.scala` | Add `projectId`, `workspaceId` as proper fields |
| `conversation/entity/ConversationRepository.scala` | Update for new fields |
| `conversation/boundary/ChatController.scala` | Require project+workspace, remove marker encoding |
| `shared/web/Layout.scala` | Add project filter dropdown, remove Workspaces nav item |
| `shared/web/ProjectsView.scala` | Add inline workspace creation form |
| `shared/web/ChatView.scala` | Update for project-scoped filtering |
| `app/boundary/WebServer.scala` | Update route composition |
| `app/boundary/WorkspaceRouteModule.scala` | Remove workspace list/creation routes |
| `app/ApplicationDI.scala` | Wire `ProjectStorageService` |

### Test files
All tests referencing `workspaceIds` on `Project`, standalone workspace creation, or standalone chat creation need updating.

---

## Verification

1. **Create project** — verify project directory is initialized as a git repo at `~/.llm4zio-gateway/projects/{id}/`
2. **Create workspace in project** — verify workspace has `projectId`, appears in project detail
3. **Board operations** — create/move/delete issues, verify they operate on `~/.llm4zio-gateway/projects/{id}/.board/`
4. **Analysis** — trigger workspace analysis, verify output goes to `~/.llm4zio-gateway/projects/{id}/workspaces/{wsId}/.llm4zio/analysis/`
5. **Project filter** — select a project in dropdown, verify all views filter correctly. Select "All", verify aggregation.
6. **Chat** — create conversation, verify it requires project+workspace. Verify chat list filters by project.
7. **Workspace repo** — verify no `.board/` or `.llm4zio/` directories exist in the workspace's `localPath` after operations
8. **Run all unit tests** (`sbt test`) and integration tests (`sbt it:test`)
