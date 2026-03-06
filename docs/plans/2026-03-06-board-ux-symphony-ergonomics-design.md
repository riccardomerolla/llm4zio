# Board UX — Symphony-Style Ergonomics Design

**Date:** 2026-03-06
**Status:** Approved

## Context

Issues #420–#423 (Milestone 1: Proof-of-Work Board) are implemented and all 35 tests pass.
The issue board is functional but visually flat and missing the ergonomic polish of Symphony's
agent-driven task management UX. This design covers the UI improvements.

## Goals

- Add missing "Board" nav item so the board is reachable from the sidebar
- Restyle board cards with clear visual hierarchy and status identity
- Replace collapsed POW panel on cards with a compact expandable evidence bar (Option B)
- Improve stats bar prominence
- Improve filter bar visual design

## Non-Goals

- No new data model changes
- No backend route changes
- No JavaScript beyond what already exists (CSS + `<details>` for evidence bar expansion)

---

## Section 1: Navigation

Add a "Board" nav item to the sidebar in `Layout.scala`, in the "Workspace" group, directly below "Issues".

- Path: `/issues/board`
- Active condition: `currentPath.startsWith("/issues/board")`
- Icon: new `tableColumns` icon (2×3 grid, Heroicons outline) added to `Icons` object
- "Issues" nav item retains `/issues` (list view)

**File:** `src/main/scala/shared/web/Layout.scala`

---

## Section 2: Board Cards

Replace the current flat gray card with a visually structured card.

### Structure

```
┌──────────────────────────────┐
│▌ [Title, 2-line clamp]       │  ← 3px left border, color = status
│  [status badge] [priority]   │
│  [tags]                      │
│  [agent chip]    [● pulse]   │  ← pulse only for InProgress
├──────────────────────────────┤
│ ▶ PR: Open  ✓ CI  Δ 3 files  │  ← evidence bar (Option B)
└──────────────────────────────┘
```

### Status border colors (Tailwind)

| Status     | Left border class           |
|------------|-----------------------------|
| Open       | `border-l-indigo-400`       |
| Assigned   | `border-l-amber-400`        |
| InProgress | `border-l-emerald-400`      |
| Completed  | `border-l-teal-400`         |
| Failed     | `border-l-rose-500`         |

### Agent chip

- Shown when `issue.assignedAgent` or `issue.preferredAgent` is non-empty
- Small pill: `rounded-full bg-white/10 px-2 py-0.5 text-[10px] text-slate-300`
- Text truncated to 12 chars max

### Status pulse

- Shown only for `InProgress`
- `h-2 w-2 rounded-full bg-emerald-400 animate-pulse`

**Files:** `src/main/scala/shared/web/IssuesView.scala`

---

## Section 3: Evidence Bar (Proof-of-Work, Option B)

Replace the collapsed `ProofOfWorkView.panel` on board cards with a slim evidence bar.

### Design

A `<details>` element at the bottom of each card (outside the `<a>` tag):

- `<summary>` shows up to 3 icon chips: PR status badge, CI status badge, diff count
- Clicking `<summary>` expands the full `ProofOfWorkView.panel` inline
- If no report exists, the evidence bar is not rendered
- No JavaScript needed — native `<details>`/`<summary>` semantics

### New method

`ProofOfWorkView.evidenceBar(report: IssueWorkReport): String`

- Returns empty string if `!hasAnySignal(report)`
- Renders a `<details>` with a `<summary>` chip row and the full panel as body

### Impact on IssuesView

`boardCard` calls `ProofOfWorkView.evidenceBar(report)` instead of `ProofOfWorkView.panel(..., collapsed=true)`.
`detailPage` continues to use `ProofOfWorkView.panel(..., collapsed=false)` (unchanged).

**Files:**
- `src/main/scala/shared/web/ProofOfWorkView.scala` — add `evidenceBar` method
- `src/main/scala/shared/web/IssuesView.scala` — update `boardCard`

---

## Section 4: Stats Bar

Replace the flat text bar with three metric tiles in a horizontal strip.

### Tiles

| Tile       | Icon style               | Color  |
|------------|--------------------------|--------|
| Running    | amber dot + count        | amber  |
| Completed  | emerald checkmark + count| emerald|
| Tokens     | purple lightning + "12.4k"| purple|

Each tile: `rounded-lg border border-white/10 bg-slate-900/60 px-4 py-2 flex items-center gap-2`

Token formatting: values ≥ 1000 displayed as `{n}k` (integer division), otherwise raw number.

**File:** `src/main/scala/shared/web/BoardStats.scala` — update `statsBar`

---

## Section 5: Filter Bar

Restyle the board filter bar from a 4-column grid to a compact horizontal pill-row.

- Filters displayed as compact labeled inputs/selects in a single flex row with `flex-wrap`
- Active filters show a subtle highlight border (`border-indigo-400/40`)
- "Has proof" checkbox becomes a toggle pill: `rounded-full border px-3 py-1 text-xs`
- "Filter" button becomes `rounded-full` style consistent with the pill row
- "Reset" link moved inline at the end

**File:** `src/main/scala/shared/web/IssuesView.scala` — update `boardFilterBar`

---

## Testing Strategy

Follow TDD (red-green-refactor) for each change:

1. **Nav item** — rendered HTML of `Layout.page` contains `/issues/board` link
2. **Board card restyling** — `boardCardFragment` HTML contains `border-l-emerald-400` for InProgress, `animate-pulse` for InProgress, agent chip text
3. **Evidence bar** — `ProofOfWorkView.evidenceBar` returns empty for no-signal reports; returns `<details>` with chip text for reports with signals; expanding shows full panel content
4. **Stats bar** — `BoardStats.statsBar` contains formatted token count (e.g. "12k"), amber/emerald/purple classes
5. **Filter bar restyling** — `boardFilterBar` HTML contains `rounded-full` on the proof toggle

All tests in `src/test/scala/shared/web/`.

---

## Files Changed

| File | Change |
|------|--------|
| `src/main/scala/shared/web/Layout.scala` | Add Board nav item + `tableColumns` icon |
| `src/main/scala/shared/web/IssuesView.scala` | Restyle `boardCard`, `boardFilterBar` |
| `src/main/scala/shared/web/ProofOfWorkView.scala` | Add `evidenceBar` method |
| `src/main/scala/shared/web/BoardStats.scala` | Restyle `statsBar` with metric tiles |
