# ADE Views Pruning — Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move Governance and Daemons pages into Settings as tabs, remove the ADE dropdown from navigation entirely.

**Spec:** `docs/superpowers/specs/2026-04-10-ade-views-pruning-phase2-design.md`

---

### Task 1: Extract SettingsShell to shared-web-core

**Files:**
- Create: `modules/shared-web-core/src/main/scala/shared/web/SettingsShell.scala`

- [ ] **Step 1: Create SettingsShell.scala**

Create `modules/shared-web-core/src/main/scala/shared/web/SettingsShell.scala` with this content:

```scala
package shared.web

import scalatags.Text.all.*
import scalatags.Text.tags2.nav as navTag

object SettingsShell:

  val tabs: List[(String, String)] = List(
    ("ai", "AI Models"),
    ("channels", "Channels"),
    ("gateway", "Gateway"),
    ("issues-templates", "Issue Templates"),
    ("governance", "Governance"),
    ("daemons", "Daemons"),
    ("system", "System"),
    ("advanced", "Advanced Config"),
    ("demo", "Demo"),
  )

  def page(activeTab: String, pageTitle: String)(bodyContent: Frag*): String =
    Layout.page(pageTitle, s"/settings/$activeTab")(
      Components.pageHeader(title = "Settings"),
      div(cls := "border-b border-white/10 mb-6")(
        navTag(cls := "-mb-px flex space-x-6", attr("aria-label") := "Settings tabs")(
          tabs.map {
            case (tab, label) =>
              val isActive = tab == activeTab
              a(
                href := s"/settings/$tab",
                cls  :=
                  (if isActive then
                     "border-b-2 border-indigo-500 py-4 px-1 text-sm font-medium text-white whitespace-nowrap"
                   else
                     "border-b-2 border-transparent py-4 px-1 text-sm font-medium text-gray-400 hover:text-white hover:border-white/30 whitespace-nowrap"),
                if isActive then attr("aria-current") := "page" else (),
              )(label)
          }
        )
      ),
      div(bodyContent*),
    )
```

Note: Uses `scalatags.Text.tags2.nav as navTag` to avoid conflict with the `nav` HTML attribute helper. Check how SettingsView imports it — it uses `scalatags.Text.tags2.nav`.

- [ ] **Step 2: Compile shared-web-core**

Run: `sbt sharedWebCore/compile`

- [ ] **Step 3: Commit**

```bash
git add modules/shared-web-core/src/main/scala/shared/web/SettingsShell.scala
git commit -m "feat: extract SettingsShell to shared-web-core for cross-module reuse"
```

---

### Task 2: Migrate SettingsView to use SettingsShell

**Files:**
- Modify: `modules/shared-web/src/main/scala/shared/web/SettingsView.scala`

- [ ] **Step 1: Replace settingsShell and tabs with delegation to SettingsShell**

In `modules/shared-web/src/main/scala/shared/web/SettingsView.scala`, remove the `tabs` list (lines 24-32) and replace the `settingsShell` method (lines 34-55) with:

```scala
  def settingsShell(activeTab: String, pageTitle: String)(bodyContent: Frag*): String =
    SettingsShell.page(activeTab, pageTitle)(bodyContent*)
```

Also remove the `import scalatags.Text.tags2.nav` import if it's no longer needed (SettingsShell handles its own import). Check if `nav` is used elsewhere in SettingsView — if not, remove the import.

- [ ] **Step 2: Compile**

Run: `sbt sharedWeb/compile`

- [ ] **Step 3: Commit**

```bash
git add modules/shared-web/src/main/scala/shared/web/SettingsView.scala
git commit -m "refactor: delegate SettingsView.settingsShell to shared SettingsShell"
```

---

### Task 3: Migrate GovernanceView + Controller to Settings tab

**Files:**
- Modify: `modules/governance-domain/src/main/scala/governance/boundary/GovernanceView.scala`
- Modify: `modules/governance-domain/src/main/scala/governance/boundary/GovernanceController.scala`

- [ ] **Step 1: Update GovernanceView to use SettingsShell**

In `modules/governance-domain/src/main/scala/governance/boundary/GovernanceView.scala`:

Change the import from:
```scala
import shared.web.Layout
```
to:
```scala
import shared.web.SettingsShell
```

Change the `page` method's wrapper from:
```scala
  def page(activePolicies: List[GovernancePolicy], archivedPolicies: List[GovernancePolicy]): String =
    Layout.page("Governance", "/governance")(
```
to:
```scala
  def page(activePolicies: List[GovernancePolicy], archivedPolicies: List[GovernancePolicy]): String =
    SettingsShell.page("governance", "Settings — Governance")(
```

Remove the standalone page header (lines 12-16) since the settings shell already provides a "Settings" page header. Replace:
```scala
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Governance"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Active policies, lifecycle transition rules, gate requirements, and archive."
          ),
        ),
```
with:
```scala
      div(cls := "space-y-6")(
        p(cls := "text-sm text-slate-300 mb-4")(
          "Active policies, lifecycle transition rules, gate requirements, and archive."
        ),
```

- [ ] **Step 2: Update GovernanceController route**

In `modules/governance-domain/src/main/scala/governance/boundary/GovernanceController.scala`:

Change the route from:
```scala
      Method.GET / "governance" -> handler { (_: Request) =>
```
to:
```scala
      Method.GET / "settings" / "governance" -> handler { (_: Request) =>
```

- [ ] **Step 3: Compile**

Run: `sbt governanceDomain/compile`

- [ ] **Step 4: Commit**

```bash
git add modules/governance-domain/
git commit -m "feat: move Governance page to /settings/governance tab"
```

---

### Task 4: Migrate DaemonsView + Controller to Settings tab

**Files:**
- Modify: `modules/daemon-domain/src/main/scala/daemon/boundary/DaemonsView.scala`
- Modify: `src/main/scala/daemon/boundary/DaemonsController.scala`

- [ ] **Step 1: Update DaemonsView to use SettingsShell**

In `modules/daemon-domain/src/main/scala/daemon/boundary/DaemonsView.scala`:

Change the import from:
```scala
import shared.web.*
```
to:
```scala
import shared.web.SettingsShell
```

(Check if other shared.web imports are needed — if DaemonsView uses `Layout`, `Components`, or `Icons` elsewhere, keep relevant imports. Read the full file first.)

Change the `page` method wrapper from:
```scala
  def page(statuses: List[DaemonAgentStatus]): String =
    Layout.page("Daemons", "/daemons")(
```
to:
```scala
  def page(statuses: List[DaemonAgentStatus]): String =
    SettingsShell.page("daemons", "Settings — Daemons")(
```

Remove the standalone page header. Replace:
```scala
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Daemon Agents"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Continuous maintenance agents derived per project and governed through settings or governance policy."
          ),
        ),
```
with:
```scala
      div(cls := "space-y-6")(
        p(cls := "text-sm text-slate-300 mb-4")(
          "Continuous maintenance agents derived per project and governed through settings or governance policy."
        ),
```

- [ ] **Step 2: Update DaemonsController routes**

In `src/main/scala/daemon/boundary/DaemonsController.scala`, update ALL routes from `/daemons` to `/settings/daemons`:

```scala
    Method.GET / "settings" / "daemons"                             -> handler {
```

```scala
    Method.POST / "settings" / "daemons" / string("id") / "start"   -> handler { ... }
    Method.POST / "settings" / "daemons" / string("id") / "stop"    -> handler { ... }
    Method.POST / "settings" / "daemons" / string("id") / "restart" -> handler { ... }
    Method.POST / "settings" / "daemons" / string("id") / "enable"  -> handler { ... }
    Method.POST / "settings" / "daemons" / string("id") / "disable" -> handler { ... }
```

Also update ALL redirect targets from `redirect("/daemons")` to `redirect("/settings/daemons")`.

- [ ] **Step 3: Compile**

Run: `sbt compile`

- [ ] **Step 4: Commit**

```bash
git add modules/daemon-domain/ src/main/scala/daemon/boundary/DaemonsController.scala
git commit -m "feat: move Daemons page to /settings/daemons tab"
```

---

### Task 5: Update AdeRouteModule — remove Governance + Daemons

**Files:**
- Modify: `src/main/scala/app/boundary/AdeRouteModule.scala`

- [ ] **Step 1: Remove Governance and Daemons from AdeRouteModule**

Replace the entire content of `src/main/scala/app/boundary/AdeRouteModule.scala` with:

```scala
package app.boundary

import zio.*
import zio.http.*

import board.boundary.BoardController as BoardBoundaryController
import decision.control.DecisionInbox
import demo.boundary.DemoController
import issues.entity.IssueRepository
import knowledge.boundary.KnowledgeController
import project.boundary.ProjectsController

trait AdeRouteModule:
  def routes: Routes[Any, Response]

object AdeRouteModule:
  val live
    : ZLayer[
      BoardBoundaryController &
        ProjectsController &
        KnowledgeController &
        DemoController &
        DecisionInbox &
        IssueRepository,
      Nothing,
      AdeRouteModule,
    ] =
    ZLayer {
      for
        board           <- ZIO.service[BoardBoundaryController]
        projects        <- ZIO.service[ProjectsController]
        knowledge       <- ZIO.service[KnowledgeController]
        demoController  <- ZIO.service[DemoController]
        decisionInbox   <- ZIO.service[DecisionInbox]
        issueRepository <- ZIO.service[IssueRepository]
      yield new AdeRouteModule:
        override val routes: Routes[Any, Response] =
          board.routes ++
            projects.routes ++
            knowledge.routes ++
            demoController.routes ++
            NavBadgeController.routes(
              decisionInbox,
              issueRepository,
            )
    }
```

Removed: `DaemonsController`, `GovernanceController`, `GovernancePolicyRepository` imports and dependencies. Governance and Daemons routes are now served by their own controllers directly (wired via ApplicationDI).

- [ ] **Step 2: Commit**

```bash
git add src/main/scala/app/boundary/AdeRouteModule.scala
git commit -m "refactor: remove Governance and Daemons from AdeRouteModule"
```

---

### Task 6: Update ApplicationDI — wire governance and daemons routes separately

**Files:**
- Modify: `src/main/scala/app/ApplicationDI.scala`

- [ ] **Step 1: Check how routes are aggregated in WebServer/ApplicationDI**

Read `src/main/scala/app/WebServer.scala` to understand how `AdeRouteModule.routes` is composed with other routes. We need to ensure GovernanceController and DaemonsController routes are still included in the HTTP app.

The key: GovernanceController.routes needs `GovernancePolicyRepository` (already in layers). DaemonsController.routes are wired via `DaemonsController.live` + `DaemonsController` trait (already in layers).

We need to add governance and daemons routes to the route aggregation, outside of AdeRouteModule. Read the WebServer to find where routes are composed, then add:
- `GovernanceController.routes(governancePolicyRepo)` 
- `daemons.routes`

This may require adding a new route module or extending the existing route composition. Approach depends on how WebServer aggregates routes — read the file first.

- [ ] **Step 2: Make the necessary wiring changes**

Ensure governance and daemons routes are included in the HTTP app route composition.

- [ ] **Step 3: Compile**

Run: `sbt compile`

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/app/
git commit -m "refactor: wire governance and daemons routes independently of AdeRouteModule"
```

---

### Task 7: Remove ADE dropdown from Layout.scala

**Files:**
- Modify: `modules/shared-web-core/src/main/scala/shared/web/Layout.scala`

- [ ] **Step 1: Remove adeGroup definition**

Delete the `adeGroup` val (lines 266-280):
```scala
  private lazy val adeGroup: NavGroup = NavGroup(
    label = "ADE",
    items = List(
      NavItem("/sdlc", "SDLC Dashboard", Icons.activity, _.startsWith("/sdlc")),
      NavItem(
        "/board",
        "Board",
        Icons.tableColumns,
        p => p.startsWith("/board") || p.startsWith("/issues/board"),
        liveBadgePath = Some("/nav/badges/board"),
      ),
      NavItem("/governance", "Governance", Icons.documentText, _.startsWith("/governance")),
      NavItem("/daemons", "Daemons", Icons.cpuChip, _.startsWith("/daemons")),
    ),
  )
```

- [ ] **Step 2: Remove ADE dropdown from topNavBar**

In the `topNavBar` method, remove the entire ADE dropdown block (lines ~149-180):

Replace:
```scala
          // Right: ADE dropdown + optional Chats + ⌘K
          div(cls := "flex items-center gap-2 flex-1 justify-end")(
            // ADE dropdown — pure HTML, toggled by inline script
            div(cls := "relative", attr("data-nav-dropdown") := "")(
              button(
                ...
              )("ADE ", span(cls := "text-[9px] opacity-60")("▼")),
              div(
                ...
              )(
                adeGroup.items.map { item =>
                  ...
                }*
              ),
            ),
            // Chats dropdown (only when chatWorkspaceNav is present)
```
with:
```scala
          // Right: optional Chats + ⌘K
          div(cls := "flex items-center gap-2 flex-1 justify-end")(
            // Chats dropdown (only when chatWorkspaceNav is present)
```

Keep everything after the Chats dropdown comment unchanged.

- [ ] **Step 3: Add SDLC Dashboard to coreGatewayGroup**

SDLC Dashboard was only in the ADE dropdown. Add it to coreGatewayGroup, before Settings:

In `coreGatewayGroup.items`, add before the Settings NavItem:
```scala
      NavItem("/sdlc", "SDLC", Icons.activity, _.startsWith("/sdlc")),
```

- [ ] **Step 4: Commit**

```bash
git add modules/shared-web-core/src/main/scala/shared/web/Layout.scala
git commit -m "refactor: remove ADE dropdown, add SDLC to core nav"
```

---

### Task 8: Update tests

**Files:**
- Modify: `src/test/scala/shared/web/LayoutSpec.scala`
- Modify: `src/test/scala/daemon/boundary/DaemonsControllerSpec.scala`

- [ ] **Step 1: Update LayoutSpec**

Update the nav test to:
- Remove assertions for `/governance` and `/daemons` in nav (they're now under `/settings/*`)
- Remove ADE dropdown assertions (`data-nav-dropdown` for ADE)
- Add assertion for `/sdlc` in core nav
- The `!html.contains("/governance")` assertion stays true (direct nav link removed)
- The `!html.contains("/daemons")` assertion stays true (direct nav link removed)

- [ ] **Step 2: Update DaemonsControllerSpec**

Update URL paths in tests from `/daemons` to `/settings/daemons`:
- `Request.get(URL(Path.decode("/daemons")))` → `Request.get(URL(Path.decode("/settings/daemons")))`
- `Path.decode("/daemons/project-1__test-guardian/start")` → `Path.decode("/settings/daemons/project-1__test-guardian/start")`

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/
git commit -m "test: update tests for Governance/Daemons Settings migration"
```

---

### Task 9: Compile and test verification

- [ ] **Step 1: Full compile**

Run: `sbt compile`

- [ ] **Step 2: Run tests**

Run: `sbt test`
Expected: All tests pass.

- [ ] **Step 3: Fix any remaining issues**

Watch for:
- Unused imports (`-Werror`) — especially removed `GovernanceController`, `DaemonsController` imports
- Missing route wiring — governance/daemons routes not reachable
- Redirect URLs still pointing to old `/daemons` path

- [ ] **Step 4: Commit fixes if needed**

```bash
git add -A
git commit -m "fix: resolve compilation/test errors from phase 2 migration"
```
