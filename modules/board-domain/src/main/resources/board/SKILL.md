# .board — Filesystem-Backed Board

This directory is a **standalone git repository** isolated from the workspace git repo.
The workspace `.gitignore` contains `/.board/` so board commits never pollute `git status`.

---

## Directory Layout

```
.board/
├── SKILL.md              ← this file (board structure reference)
├── .git/                 ← board-only git history
├── backlog/              ← column: Backlog
├── todo/                 ← column: Todo
├── in-progress/          ← column: InProgress
├── review/               ← column: Review  (awaiting human approval)
├── done/                 ← column: Done
└── archive/              ← column: Archive
```

Each column directory contains **issue directories** named by their issue ID:

```
.board/todo/ISSUE-42/
└── ISSUE.md
```

---

## Issue File Format

Every `ISSUE.md` starts with a YAML frontmatter block (delimited by `---`) followed by a free-form markdown body.

```markdown
---
id: ISSUE-42
title: Add retry logic to HTTP client
priority: high
assignedAgent: null
requiredCapabilities: [scala, zio]
blockedBy: []
tags: [http, resilience]
acceptanceCriteria: [Retries on 5xx, Exponential backoff with jitter]
estimate: m
proofOfWork: []
transientState: none
branchName: null
failureReason: null
completedAt: null
createdAt: 2026-03-20T10:00:00Z
---

## Description

Detailed description of the issue in markdown.
```

### Frontmatter Fields

| Field | Type | Description |
|---|---|---|
| `id` | string | Unique issue ID (e.g. `ISSUE-42`) |
| `title` | string | Human-readable title |
| `priority` | `critical` / `high` / `medium` / `low` | Issue priority |
| `assignedAgent` | string or `null` | Agent currently working on it |
| `requiredCapabilities` | list `[a, b]` | Skills needed to work on this issue |
| `blockedBy` | list `[ISSUE-x]` | IDs of blocking issues |
| `tags` | list `[a, b]` | Free-form labels |
| `acceptanceCriteria` | list `[a, b]` | Definition of done |
| `estimate` | `xs` / `s` / `m` / `l` / `xl` or `null` | Effort estimate |
| `proofOfWork` | list `[a, b]` | Evidence of work done (appended on completion) |
| `transientState` | see below | Current runtime state of the issue |
| `branchName` | string or `null` | Feature branch (preserved until Approve) |
| `failureReason` | string or `null` | Set on run failure |
| `completedAt` | ISO-8601 or `null` | Timestamp when moved to Done |
| `createdAt` | ISO-8601 | Creation timestamp |

### `transientState` Values

| Value | Meaning |
|---|---|
| `none` | Idle |
| `assigned(agentName,2026-03-20T10:00:00Z)` | Agent is actively running |
| `merging(2026-03-20T10:00:00Z)` | Merge in progress (set during Approve) |
| `rework(reason,2026-03-20T10:00:00Z)` | Sent back for rework |

---

## Column Workflow

```
Backlog → Todo → InProgress → Review → Done
                                ↘ (on failure) → InProgress (Rework)
                                                           → Archive
```

| Column | Folder | Meaning |
|---|---|---|
| Backlog | `backlog/` | Not yet scheduled |
| Todo | `todo/` | Scheduled, waiting to be picked up |
| InProgress | `in-progress/` | Agent is working on it |
| Review | `review/` | Run complete, awaiting human approval |
| Done | `done/` | Merged and complete |
| Archive | `archive/` | Closed without completion |

### Key Transitions

- **Run completes successfully** → issue moves to `review/`, branch preserved (`branchName` kept)
- **Human clicks Approve** → branch merged, issue moves to `done/`, `completedAt` set, `branchName` cleared
- **Run fails** → `failureReason` set, `transientState` reset to `none`

---

## Reading the Board

To find all issues in a column, list subdirectories of the column folder and read each `ISSUE.md`.

To find a specific issue, search all column folders for a directory whose name matches the issue ID — the highest-priority column wins if duplicates exist (priority order: `archive` < `done` < `review` < `in-progress` < `todo` < `backlog`, i.e. closer to Done = higher priority).

To inspect board git history:
```sh
git -C .board log --oneline
```
