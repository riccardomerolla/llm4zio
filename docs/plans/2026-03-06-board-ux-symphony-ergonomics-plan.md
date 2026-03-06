# Board UX — Symphony-Style Ergonomics Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Improve the issue board UX to match Symphony-style ergonomics: add a Board nav item, restyle cards with status identity, add an expandable evidence bar for proof-of-work, and improve the stats bar and filter bar visual design.

**Architecture:** Pure view-layer changes — no backend routes, no data model changes. All changes are in `shared/web/` Scalatags view objects. The evidence bar uses native HTML `<details>`/`<summary>` for zero-JS expand/collapse. All changes follow TDD (red-green-refactor).

**Tech Stack:** Scala 3, Scalatags, Tailwind CSS (browser CDN), HTMX, ZIO Test

---

## Conventions

- Run tests with: `sbt "testOnly shared.web.*"` or `sbt "testOnly shared.web.LayoutSpec"` etc.
- Run format/lint: `sbt fmt` — must pass cleanly before commit
- `sbt fmt` runs scalafix + scalafmt; it will auto-fix formatting issues
- No `Instant.now()` — tests use fixed instants like `Instant.parse("2026-03-05T10:00:00Z")`
- No unused imports — scalafix `-Werror` will reject them
- All test files live in `src/test/scala/shared/web/`
- All view files live in `src/main/scala/shared/web/`

---

## Task 1: Board Nav Item + `tableColumns` Icon

**Files:**
- Modify: `src/main/scala/shared/web/Layout.scala`
- Create: `src/test/scala/shared/web/LayoutSpec.scala`

### Step 1: Write the failing test

Create `src/test/scala/shared/web/LayoutSpec.scala`:

```scala
package shared.web

import zio.test.*

object LayoutSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("Layout")(
      test("sidebar contains a Board nav item linking to /issues/board") {
        val html = Layout.page("Test", "/issues/board")()
        assertTrue(
          html.contains("/issues/board"),
          html.contains("Board"),
        )
      },
      test("Board nav item is active when currentPath starts with /issues/board") {
        val html = Layout.page("Test", "/issues/board")()
        // Active nav items get the bg-white/5 class; inactive get text-gray-400
        assertTrue(html.contains("bg-white/5"))
      },
      test("Board nav item is not active for /issues list path") {
        val html = Layout.page("Test", "/issues")()
        // The board link should exist but not be active
        assertTrue(html.contains("/issues/board"))
      },
    )
```

### Step 2: Run test to verify it fails

```
sbt "testOnly shared.web.LayoutSpec"
```

Expected: FAIL — `html` does not contain `/issues/board` or `"Board"`.

### Step 3: Add `tableColumns` icon and Board nav item to Layout.scala

In `src/main/scala/shared/web/Layout.scala`:

**Add to `Icons` object** (after the `archive` val, before `cog`):

```scala
val tableColumns: Frag = icon(
  "M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5Zm2 4h6V7H5v2Zm0 4h6v-2H5v2Zm0 4h6v-2H5v2Zm8-8h6V7h-6v2Zm0 4h6v-2h-6v2Zm0 4h6v-2h-6v2Z"
)
```

**Add Board nav item** in `desktopSidebar`, in the "Workspace" `ul`, directly below the Issues item:

```scala
navItem("/issues", "Issues", Icons.flag, currentPath.startsWith("/issues") && !currentPath.startsWith("/issues/board")),
navItem("/issues/board", "Board", Icons.tableColumns, currentPath.startsWith("/issues/board")),
```

Note: the Issues item condition must exclude `/issues/board` so both aren't active at once.

### Step 4: Run test to verify it passes

```
sbt "testOnly shared.web.LayoutSpec"
```

Expected: All 3 tests PASS.

### Step 5: Run format

```
sbt fmt
```

Expected: Clean (may auto-reformat Layout.scala).

### Step 6: Commit

```bash
git add src/main/scala/shared/web/Layout.scala src/test/scala/shared/web/LayoutSpec.scala
git commit -m "feat: add Board nav item and tableColumns icon to sidebar"
```

---

## Task 2: Evidence Bar in ProofOfWorkView

**Files:**
- Modify: `src/main/scala/shared/web/ProofOfWorkView.scala`
- Modify: `src/test/scala/shared/web/ProofOfWorkViewSpec.scala`

### Step 1: Write the failing tests

Add these tests to the existing `ProofOfWorkViewSpec` suite (append inside the `suite(...)` call):

```scala
test("evidenceBar returns empty string when report has no signals") {
  val html = ProofOfWorkView.evidenceBar(emptyReport)
  assertTrue(html.isEmpty)
},
test("evidenceBar renders a details element with summary chips when signals present") {
  val report = emptyReport.copy(
    prLink   = Some("https://github.com/org/repo/pull/1"),
    prStatus = Some(PrStatus.Open),
    ciStatus = Some(CiStatus.Passed),
  )
  val html   = ProofOfWorkView.evidenceBar(report)
  assertTrue(
    html.contains("<details"),
    html.contains("<summary"),
    html.contains("Open"),
    html.contains("Passed"),
  )
},
test("evidenceBar includes diff count chip when diffStats present") {
  val report = emptyReport.copy(diffStats = Some(DiffStats(4, 30, 8)))
  val html   = ProofOfWorkView.evidenceBar(report)
  assertTrue(
    html.contains("4"),
    html.contains("<details"),
  )
},
test("evidenceBar body contains the full panel content") {
  val report = emptyReport.copy(walkthrough = Some("Refactored login."))
  val html   = ProofOfWorkView.evidenceBar(report)
  assertTrue(html.contains("Refactored login."))
},
```

Also add the `DiffStats` import to the top of `ProofOfWorkViewSpec.scala`:

```scala
import orchestration.entity.DiffStats
```

### Step 2: Run tests to verify they fail

```
sbt "testOnly shared.web.ProofOfWorkViewSpec"
```

Expected: 4 new tests FAIL — `evidenceBar` method does not exist yet.

### Step 3: Add `evidenceBar` to ProofOfWorkView.scala

Add this method to `ProofOfWorkView` object (after the `panel` method, before `hasAnySignal`):

```scala
/** Render a compact expandable evidence bar for use on board cards.
  *
  * Returns an empty string when the report has no signals. Uses a native HTML `<details>`/`<summary>`
  * element so no JavaScript is required for expand/collapse.
  */
def evidenceBar(report: IssueWorkReport): String =
  if !hasAnySignal(report) then ""
  else
    val chips: Seq[Frag] = Seq(
      report.prStatus.map(s => prStatusBadge(s)),
      report.ciStatus.map(ci => ciStatusBadge(ci)),
      report.diffStats.map(ds =>
        span(cls := "rounded-full bg-slate-700/60 px-2 py-0.5 text-[10px] text-slate-300")(
          s"${ds.filesChanged} files"
        )
      ),
    ).flatten

    tag("details")(
      cls := "mt-2 border-t border-white/10 pt-2"
    )(
      tag("summary")(
        cls := "flex cursor-pointer list-none flex-wrap items-center gap-1.5 text-[10px] text-slate-400 hover:text-slate-200"
      )(
        span(cls := "mr-1 text-slate-500")("Evidence"),
        chips,
      ),
      div(cls := "mt-2")(
        raw(panel(report, collapsed = false))
      ),
    ).render
```

### Step 4: Run tests to verify they pass

```
sbt "testOnly shared.web.ProofOfWorkViewSpec"
```

Expected: All tests PASS (including the 4 new ones).

### Step 5: Run format

```
sbt fmt
```

### Step 6: Commit

```bash
git add src/main/scala/shared/web/ProofOfWorkView.scala src/test/scala/shared/web/ProofOfWorkViewSpec.scala
git commit -m "feat: add evidenceBar method to ProofOfWorkView for board card option B"
```

---

## Task 3: Restyle Board Cards

**Files:**
- Modify: `src/main/scala/shared/web/IssuesView.scala`
- Modify: `src/test/scala/shared/web/IssuesBoardProofOfWorkSpec.scala`

### Step 1: Write failing tests

Add these tests to `IssuesBoardProofOfWorkSpec` (append to existing suite):

```scala
test("board card for InProgress issue has emerald left border class") {
  val issue = baseIssue.copy(status = IssueStatus.InProgress)
  val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
  assertTrue(html.contains("border-l-emerald-400"))
},
test("board card for Open issue has indigo left border class") {
  val issue = baseIssue.copy(status = IssueStatus.Open)
  val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
  assertTrue(html.contains("border-l-indigo-400"))
},
test("board card for Failed issue has rose left border class") {
  val issue = baseIssue.copy(status = IssueStatus.Failed)
  val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
  assertTrue(html.contains("border-l-rose-500"))
},
test("board card for InProgress issue shows animate-pulse dot") {
  val issue = baseIssue.copy(status = IssueStatus.InProgress)
  val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
  assertTrue(html.contains("animate-pulse"))
},
test("board card for Completed issue does not show animate-pulse dot") {
  val issue = baseIssue.copy(status = IssueStatus.Completed)
  val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
  assertTrue(!html.contains("animate-pulse"))
},
test("board card shows agent chip when assignedAgent is present") {
  val issue = baseIssue.copy(assignedAgent = Some("claude-3-5-sonnet"))
  val html  = IssuesView.boardCardFragment(issue, Nil, workReport = None)
  assertTrue(html.contains("claude-3-5"))
},
test("board card uses evidenceBar (details element) instead of collapsed panel") {
  val report = IssueWorkReport.empty(issueId, now).copy(walkthrough = Some("Done."))
  val html   = IssuesView.boardCardFragment(baseIssue, Nil, workReport = Some(report))
  assertTrue(
    html.contains("<details"),
    !html.contains("data-pow-collapsed"),
  )
},
```

Also ensure `IssueStatus.Failed` is accessible — check the import at the top of `IssuesBoardProofOfWorkSpec`. It imports `IssueStatus` already.

### Step 2: Run tests to verify they fail

```
sbt "testOnly shared.web.IssuesBoardProofOfWorkSpec"
```

Expected: 7 new tests FAIL.

### Step 3: Update `boardCard` in IssuesView.scala

Replace the `boardCard` private method body (lines 733–786 in the saved file):

```scala
private def boardCard(
  issue: AgentIssueView,
  workspaces: List[(String, String)],
  workReport: Option[IssueWorkReport] = None,
): Frag =
  val issueId       = safe(issue.id, "-")
  val workspaceId   = safe(issue.workspaceId)
  val workspaceName = workspaceNameOf(workspaces, workspaceId).getOrElse(workspaceId)
  val titleText     = safeStr(issue.title, "Untitled")
  val updatedLabel  = prettyRelativeTime(issue.updatedAt)
  val isInProgress  = issue.status == IssueStatus.InProgress
  val agentName     =
    safe(issue.assignedAgent) match
      case v if v.nonEmpty => v
      case _               => safe(issue.preferredAgent)
  val borderCls     = issue.status match
    case IssueStatus.Open       => "border-l-4 border-l-indigo-400"
    case IssueStatus.Assigned   => "border-l-4 border-l-amber-400"
    case IssueStatus.InProgress => "border-l-4 border-l-emerald-400"
    case IssueStatus.Completed  => "border-l-4 border-l-teal-400"
    case IssueStatus.Failed     => "border-l-4 border-l-rose-500"
    case _                      => "border-l-4 border-l-slate-600"
  val powHtml       = workReport.map(r => ProofOfWorkView.evidenceBar(r)).getOrElse("")
  div(
    cls                         := s"block rounded-lg border border-white/10 bg-slate-800/80 p-3 hover:border-indigo-400/40 hover:bg-slate-800 $borderCls",
    attr("draggable")           := "true",
    attr("data-issue-id")       := issueId,
    attr("data-bulk-card")      := "true",
    attr("data-issue-status")   := issueStatusToken(issue.status),
    attr("data-assigned-agent") := safe(issue.assignedAgent),
    attr("data-priority")       := safeStr(issue.priority.toString).toLowerCase,
    attr("data-tags")           := safe(issue.tags),
    attr("data-workspace-id")   := workspaceId,
  )(
    a(href := s"/issues/$issueId", cls := "block")(
      div(cls := "mb-1 flex items-start justify-between gap-2")(
        p(cls := "text-sm font-semibold text-slate-100 line-clamp-2 flex-1")(titleText),
        if isInProgress then
          span(cls := "mt-1 h-2 w-2 flex-shrink-0 rounded-full bg-emerald-400 animate-pulse")
        else (),
      ),
      div(cls := "mt-1.5 flex flex-wrap items-center gap-1.5")(
        priorityBadge(safeStr(issue.priority.toString, "medium")),
        safeTags(issue.tags).take(2).map(tagBadge),
      ),
      div(cls := "mt-2 flex items-center justify-between")(
        if agentName.nonEmpty then
          span(cls := "rounded-full bg-white/10 px-2 py-0.5 text-[10px] text-slate-300")(
            agentName.take(12)
          )
        else
          span(),
        p(cls := "text-[11px] text-slate-500")(s"Updated $updatedLabel"),
      ),
    ),
    if powHtml.nonEmpty then raw(powHtml) else (),
  )
```

### Step 4: Run tests to verify they pass

```
sbt "testOnly shared.web.IssuesBoardProofOfWorkSpec"
```

Expected: All tests PASS.

### Step 5: Run full test suite

```
sbt test
```

Expected: All tests PASS.

### Step 6: Run format

```
sbt fmt
```

### Step 7: Commit

```bash
git add src/main/scala/shared/web/IssuesView.scala src/test/scala/shared/web/IssuesBoardProofOfWorkSpec.scala
git commit -m "feat: restyle board cards with status border, agent chip, pulse dot, and evidence bar"
```

---

## Task 4: Restyle Stats Bar

**Files:**
- Modify: `src/main/scala/shared/web/BoardStats.scala`
- Modify: `src/test/scala/shared/web/BoardStatsSpec.scala`

### Step 1: Write failing tests

Add these tests to `BoardStatsSpec` (append to existing suite):

```scala
test("statsBar contains amber class for running tile") {
  val stats = BoardStats.Stats(running = 2, completed = 5, tokensTotal = 3000L)
  val html  = BoardStats.statsBar(stats)
  assertTrue(html.contains("amber"))
},
test("statsBar contains emerald class for completed tile") {
  val stats = BoardStats.Stats(running = 0, completed = 4, tokensTotal = 0L)
  val html  = BoardStats.statsBar(stats)
  assertTrue(html.contains("emerald"))
},
test("statsBar contains purple class for tokens tile") {
  val stats = BoardStats.Stats(running = 0, completed = 0, tokensTotal = 5000L)
  val html  = BoardStats.statsBar(stats)
  assertTrue(html.contains("purple"))
},
test("statsBar formats tokens >= 1000 as Nk") {
  val stats = BoardStats.Stats(running = 0, completed = 0, tokensTotal = 12500L)
  val html  = BoardStats.statsBar(stats)
  assertTrue(html.contains("12k"))
},
test("statsBar shows raw count when tokens < 1000") {
  val stats = BoardStats.Stats(running = 0, completed = 0, tokensTotal = 800L)
  val html  = BoardStats.statsBar(stats)
  assertTrue(html.contains("800"))
},
```

### Step 2: Run tests to verify they fail

```
sbt "testOnly shared.web.BoardStatsSpec"
```

Expected: 5 new tests FAIL (current `statsBar` uses `text-emerald-300` for running, no amber/purple).

### Step 3: Update `statsBar` in BoardStats.scala

Replace the `statsBar` method body:

```scala
def statsBar(stats: Stats): String =
  val tokensLabel =
    if stats.tokensTotal >= 1000 then s"${stats.tokensTotal / 1000}k"
    else stats.tokensTotal.toString
  div(
    cls                      := "flex flex-wrap items-center gap-3 rounded-xl border border-white/10 bg-slate-900/60 px-4 py-2",
    attr("data-board-stats") := "true",
  )(
    div(cls := "flex items-center gap-2 rounded-lg border border-white/10 bg-slate-800/60 px-3 py-1.5")(
      span(cls := "h-2 w-2 rounded-full bg-amber-400"),
      span(cls := "text-xs font-semibold text-amber-300")(stats.running.toString),
      span(cls := "text-xs text-slate-400")("running"),
    ),
    div(cls := "flex items-center gap-2 rounded-lg border border-white/10 bg-slate-800/60 px-3 py-1.5")(
      span(cls := "text-xs font-semibold text-emerald-300")("✓"),
      span(cls := "text-xs font-semibold text-emerald-300")(stats.completed.toString),
      span(cls := "text-xs text-slate-400")("completed"),
    ),
    div(cls := "flex items-center gap-2 rounded-lg border border-white/10 bg-slate-800/60 px-3 py-1.5")(
      span(cls := "text-xs font-semibold text-purple-300")("⚡"),
      span(cls := "text-xs font-semibold text-purple-300")(tokensLabel),
      span(cls := "text-xs text-slate-400")("tokens"),
    ),
  ).render
```

### Step 4: Run tests to verify they pass

```
sbt "testOnly shared.web.BoardStatsSpec"
```

Expected: All tests PASS.

### Step 5: Run format

```
sbt fmt
```

### Step 6: Commit

```bash
git add src/main/scala/shared/web/BoardStats.scala src/test/scala/shared/web/BoardStatsSpec.scala
git commit -m "feat: restyle stats bar with amber/emerald/purple metric tiles"
```

---

## Task 5: Restyle Filter Bar

**Files:**
- Modify: `src/main/scala/shared/web/IssuesView.scala`
- Create: `src/test/scala/shared/web/WorkflowsViewSpec.scala` — wait, wrong spec. Create: `src/test/scala/shared/web/BoardFilterBarSpec.scala`

### Step 1: Write failing tests

Create `src/test/scala/shared/web/BoardFilterBarSpec.scala`:

```scala
package shared.web

import zio.test.*

import issues.entity.api.{ AgentIssueView, IssuePriority, IssueStatus }

object BoardFilterBarSpec extends ZIOSpecDefault:

  // We test the board method output which includes the filter bar
  private val emptyIssues: List[AgentIssueView] = Nil
  private val emptyWorkspaces: List[(String, String)] = Nil

  def spec: Spec[Any, Nothing] =
    suite("boardFilterBar")(
      test("filter bar renders rounded-full style for has-proof toggle") {
        val html = IssuesView.board(
          issues           = emptyIssues,
          workspaces       = emptyWorkspaces,
          workspaceFilter  = None,
          agentFilter      = None,
          priorityFilter   = None,
          tagFilter        = None,
          query            = None,
          hasProofFilter   = None,
        )
        assertTrue(html.contains("rounded-full"))
      },
      test("filter bar Apply button has rounded-full class") {
        val html = IssuesView.board(
          issues           = emptyIssues,
          workspaces       = emptyWorkspaces,
          workspaceFilter  = None,
          agentFilter      = None,
          priorityFilter   = None,
          tagFilter        = None,
          query            = None,
          hasProofFilter   = None,
        )
        // Apply button should be rounded-full
        assertTrue(html.contains("rounded-full"))
      },
      test("filter bar highlights active workspace filter") {
        val ws   = List(("ws-1", "My Workspace"))
        val html = IssuesView.board(
          issues           = emptyIssues,
          workspaces       = ws,
          workspaceFilter  = Some("ws-1"),
          agentFilter      = None,
          priorityFilter   = None,
          tagFilter        = None,
          query            = None,
          hasProofFilter   = None,
        )
        assertTrue(html.contains("border-indigo-400"))
      },
    )
```

### Step 2: Run tests to verify they fail

```
sbt "testOnly shared.web.BoardFilterBarSpec"
```

Expected: At least the `rounded-full` and `border-indigo-400` tests FAIL since the current filter bar uses `rounded-md`.

### Step 3: Update `boardFilterBar` in IssuesView.scala

Replace the `boardFilterBar` private method:

```scala
private def boardFilterBar(
  workspaces: List[(String, String)],
  workspaceFilter: Option[String],
  agentFilter: Option[String],
  priorityFilter: Option[String],
  tagFilter: Option[String],
  query: Option[String],
  hasProofFilter: Option[Boolean] = None,
): Frag =
  val activeInput = "rounded-full border border-indigo-400/40 bg-slate-800/70 px-3 py-1.5 text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none"
  val idleInput   = "rounded-full border border-white/15 bg-slate-800/70 px-3 py-1.5 text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none"
  form(method := "get", action := "/issues/board", cls := "rounded-xl border border-white/10 bg-slate-900/60 px-4 py-3")(
    div(cls := "flex flex-wrap items-center gap-2")(
      input(
        `type`      := "text",
        name        := "q",
        value       := query.getOrElse(""),
        placeholder := "Search",
        cls         := (if query.exists(_.nonEmpty) then activeInput else idleInput),
      ),
      select(
        name := "workspace",
        cls  := (if workspaceFilter.exists(_.nonEmpty) then
                   s"$activeInput border-indigo-400"
                 else idleInput),
      )(
        option(value := "")("Any workspace"),
        workspaces.sortBy(_._2.toLowerCase).map { (id, name) =>
          option(value := id, if workspaceFilter.contains(id) then selected := "selected" else ())(
            s"$name"
          )
        },
      ),
      input(
        `type`      := "text",
        name        := "agent",
        value       := agentFilter.getOrElse(""),
        placeholder := "Agent",
        cls         := (if agentFilter.exists(_.nonEmpty) then activeInput else idleInput),
      ),
      select(
        name := "priority",
        cls  := (if priorityFilter.exists(_.nonEmpty) then activeInput else idleInput),
      )(
        option(value := "")("Any priority"),
        option(value := "critical", if priorityFilter.contains("critical") then selected := "selected" else ())("Critical"),
        option(value := "high", if priorityFilter.contains("high") then selected := "selected" else ())("High"),
        option(value := "medium", if priorityFilter.contains("medium") then selected := "selected" else ())("Medium"),
        option(value := "low", if priorityFilter.contains("low") then selected := "selected" else ())("Low"),
      ),
      input(
        `type`      := "text",
        name        := "tag",
        value       := tagFilter.getOrElse(""),
        placeholder := "Tag",
        cls         := (if tagFilter.exists(_.nonEmpty) then activeInput else idleInput),
      ),
      label(
        cls := s"flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs ${if hasProofFilter.contains(true) then "border-indigo-400/60 bg-indigo-500/20 text-indigo-200" else "border-white/15 bg-slate-800/70 text-slate-300"}"
      )(
        input(
          `type` := "checkbox",
          name   := "hasProof",
          value  := "true",
          cls    := "sr-only",
          if hasProofFilter.contains(true) then checked := "checked" else (),
        ),
        span("Has proof"),
      ),
      button(
        `type` := "submit",
        cls    := "rounded-full bg-indigo-500 px-4 py-1.5 text-xs font-semibold text-white hover:bg-indigo-400",
      )("Apply"),
      a(
        href := "/issues/board",
        cls  := "rounded-full border border-white/20 px-3 py-1.5 text-xs text-slate-300 hover:bg-white/5",
      )("Reset"),
    )
  )
```

### Step 4: Run tests to verify they pass

```
sbt "testOnly shared.web.BoardFilterBarSpec"
```

Expected: All 3 tests PASS.

### Step 5: Run full test suite

```
sbt test
```

Expected: All tests PASS.

### Step 6: Run format

```
sbt fmt
```

### Step 7: Commit

```bash
git add src/main/scala/shared/web/IssuesView.scala src/test/scala/shared/web/BoardFilterBarSpec.scala
git commit -m "feat: restyle board filter bar as compact pill-row with active filter highlighting"
```

---

## Task 6: Final Verification

### Step 1: Run full test suite

```
sbt test
```

Expected: All tests PASS.

### Step 2: Run format check

```
sbt fmt
```

Expected: Clean — no changes needed.

### Step 3: Review changed files

Changed files in this milestone:
- `src/main/scala/shared/web/Layout.scala`
- `src/main/scala/shared/web/ProofOfWorkView.scala`
- `src/main/scala/shared/web/IssuesView.scala`
- `src/main/scala/shared/web/BoardStats.scala`
- `src/test/scala/shared/web/LayoutSpec.scala` (new)
- `src/test/scala/shared/web/ProofOfWorkViewSpec.scala` (extended)
- `src/test/scala/shared/web/IssuesBoardProofOfWorkSpec.scala` (extended)
- `src/test/scala/shared/web/BoardStatsSpec.scala` (extended)
- `src/test/scala/shared/web/BoardFilterBarSpec.scala` (new)
