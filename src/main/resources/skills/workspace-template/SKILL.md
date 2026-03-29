---
name: workspace-template
description: Create a new ADE workspace from scratch — guided wizard that scaffolds project structure, registers the workspace, and generates initial board issues from requirements.
metadata:
  version: "1.0.0"
  domain: workspace
  triggers: new workspace, create workspace, scaffold project, bootstrap project, new project, workspace template, init workspace
  role: wizard
  scope: orchestration
  output-format: mixed
---

# Workspace Template Skill

Guided wizard for bootstrapping a new ADE workspace from scratch. Scaffolds the project, registers it with the gateway, and populates the board with initial issues derived from user requirements.

## Core Workflow

1. **Gather** — Collect project details via wizard questions
2. **Scaffold** — Create directory structure, build files, and CLAUDE.md
3. **Register** — Register as ADE workspace via gateway API
4. **Plan** — Generate and create initial board issues from requirements
5. **Verify** — Confirm workspace and board are operational

---

## Step 1: Gather — Wizard Questions

Ask the following questions **one at a time**, confirming each answer before proceeding. Show defaults in brackets.

| # | Question | Field | Required | Default | Validation |
|---|----------|-------|----------|---------|------------|
| 1 | What is the project name? | `name` | yes | — | lowercase, alphanumeric + hyphens, no spaces |
| 2 | Where should the project be created? | `path` | yes | `~/projects/{name}` | must not already exist |
| 3 | One-line description of the project | `description` | yes | — | non-empty |
| 4 | Tech stack? | `stack` | yes | `scala3-zio` | one of: `scala3-zio`, `spring-boot`, `react-ts`, `python`, `custom` |
| 5 | Describe the key features and goals | `features` | yes | — | free-text, drives issue generation |
| 6 | CLI tool for AI agents | `cliTool` | no | `claude` | one of: `claude`, `gemini`, `codex`, `copilot`, `opencode` |
| 7 | Run mode | `runMode` | no | `host` | one of: `host`, `docker`, `cloud` |

If the user chose `docker`, also ask:
- Docker image name
- Mount worktree? (default: yes)
- Network name (optional)

If the user chose `cloud`, also ask:
- Cloud provider
- Image name
- Region (optional)

After collecting all answers, **display a summary table** and ask for confirmation before proceeding.

---

## Step 2: Scaffold — Project Structure

### 2.1 Create the project directory

```bash
mkdir -p {path}
cd {path}
git init
```

### 2.2 Load the stack-specific reference

Based on the `stack` answer, load the appropriate reference file for detailed scaffolding instructions:

| Stack | Reference file |
|-------|---------------|
| `scala3-zio` | `references/scala3-zio.md` |
| `spring-boot` | `references/spring-boot.md` |
| `react-ts` | `references/react-ts.md` |
| `python` | Create: `pyproject.toml`, `src/{name}/__init__.py`, `tests/`, `.gitignore`, `CLAUDE.md` |
| `custom` | Ask the user what files/structure they want. At minimum create: `.gitignore`, `CLAUDE.md` |

Follow the reference file instructions to create all scaffolding files.

### 2.3 Always create these files

Regardless of stack, every workspace must have:

- **`.gitignore`** — Stack-appropriate ignores + `/.board/`
- **`CLAUDE.md`** — Project conventions (generated from reference template, customized with project name/description)

### 2.4 Initial commit

```bash
cd {path}
git add -A
git commit -m "Initial project scaffolding"
```

---

## Step 3: Register — ADE Gateway

Register the workspace with the ADE gateway running at `http://localhost:8080`.

### API Call

```bash
curl -s -X POST http://localhost:8080/api/workspaces \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "name={name}&localPath={path}&description={description}&cliTool={cliTool}&runModeType={runMode}"
```

For Docker run mode, add:
```
&runModeType=docker&dockerImage={image}&dockerMount=on&dockerNetwork={network}
```

For Cloud run mode, add:
```
&runModeType=cloud&cloudProvider={provider}&cloudImage={image}&cloudRegion={region}
```

### Required form fields

| Field | Description |
|-------|-------------|
| `name` | Workspace name (from wizard) |
| `localPath` | Absolute path to project directory |
| `description` | Project description |
| `cliTool` | AI tool: `claude`, `gemini`, `codex`, etc. |
| `runModeType` | `host`, `docker`, or `cloud` |

### Error handling

- If the gateway is not running, inform the user and offer to skip registration (they can register later via the UI)
- If the path validation fails, verify git was initialized correctly

### Get the workspace ID

After registration, retrieve the workspace ID for issue creation:

```bash
curl -s http://localhost:8080/api/workspaces | jq -r '.[] | select(.name=="{name}") | .id'
```

---

## Step 4: Plan — Generate Board Issues

### 4.1 Generate issues from requirements

Using the collected wizard answers, generate **5-15 actionable board issues** for the initial backlog. Think through the project requirements and break them into concrete tasks.

**Issue categories to cover:**

1. **Setup** (1-2 issues) — Build configuration, CI/CD, linting, formatting
2. **Core features** (1 issue per feature/goal) — Implementation tasks from the user's feature list
3. **Testing** (1-2 issues) — Test framework setup, initial test coverage
4. **Documentation** (0-1 issues) — README, API docs if applicable

**For each issue, determine:**

| Field | Description |
|-------|-------------|
| `title` | Clear, actionable title (e.g., "Add user authentication with JWT") |
| `description` | Markdown body with context, approach hints, and scope |
| `priority` | `critical`, `high`, `medium`, or `low` |
| `tags` | Relevant labels (e.g., `setup`, `feature`, `testing`, `docs`) |
| `acceptanceCriteria` | Newline-separated definition of done |
| `estimate` | `XS` (< 1h), `S` (1-4h), `M` (4-8h), `L` (1-3d), `XL` (3d+) |

### 4.2 Preview and confirm

Present the generated issues as a numbered list:

```
## Generated Issues

1. [high] Set up build configuration and CI pipeline (S)
   Tags: setup, ci
   AC: Build compiles, tests run in CI, linting configured

2. [high] Implement user registration endpoint (M)
   Tags: feature, auth
   AC: POST /api/users creates user, validates input, returns 201

3. ...
```

Ask the user:
- Are there issues to **remove**? (specify numbers)
- Are there issues to **add**?
- Any **edits** to existing issues?

Iterate until the user confirms.

### 4.3 Create issues via API

For each confirmed issue, create it via the gateway API:

```bash
curl -s -X POST http://localhost:8080/issues \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "title={title}&description={description}&priority={priority}&tags={tags}&workspaceId={workspaceId}&acceptanceCriteria={acceptanceCriteria}&estimate={estimate}"
```

**Field encoding:**
- `tags`: comma-separated (e.g., `setup,ci`)
- `acceptanceCriteria`: newline-separated
- `workspaceId`: the workspace ID obtained after registration
- All values must be URL-encoded

If the gateway is not running, **create issues as Markdown files directly** in `.board/backlog/`:

```markdown
---
id: {slug-from-title}
title: {title}
priority: {priority}
assignedAgent: null
requiredCapabilities: []
blockedBy: []
tags: [{tags}]
acceptanceCriteria: [{acceptance criteria items}]
estimate: {estimate}
proofOfWork: []
transientState: none
branchName: null
failureReason: null
completedAt: null
createdAt: {ISO-8601 now}
---

{description}
```

---

## Step 5: Verify

After all steps complete, verify:

1. **Directory exists** — `ls {path}` shows expected structure
2. **Git initialized** — `git -C {path} log --oneline` shows initial commit
3. **Build works** (if applicable):
   - Scala: `cd {path} && sbt compile`
   - Spring Boot: `cd {path} && ./mvnw compile`
   - React: `cd {path} && npm install && npm run build`
   - Python: `cd {path} && pip install -e .`
4. **Workspace registered** — `curl -s http://localhost:8080/api/workspaces | jq '.[] | select(.name=="{name}")'`
5. **Board populated** — `ls {path}/.board/backlog/` shows issue directories

Report the results to the user. If any step failed, explain what went wrong and how to fix it.

---

## Constraints

### MUST DO
- Initialize git before registering the workspace (gateway validates git repo)
- Create a CLAUDE.md with project-specific conventions
- Add `/.board/` to `.gitignore`
- URL-encode all form values when calling APIs
- Present issue preview before creating them
- Use absolute paths for `localPath` when registering

### MUST NOT DO
- Create the project inside an existing git repository (unless explicitly asked)
- Skip the confirmation step before creating issues
- Register a workspace without a valid git repo
- Hardcode workspace IDs — always look them up after registration
- Create more than 20 issues in the initial batch (keep it focused)

### SHOULD DO
- Suggest a sensible package/module name derived from the project name
- Include a basic README.md in the scaffolding
- Order issues by priority (critical/high first) in the preview
- Tag setup issues separately from feature issues
- Set reasonable estimates (prefer smaller, more focused issues)
