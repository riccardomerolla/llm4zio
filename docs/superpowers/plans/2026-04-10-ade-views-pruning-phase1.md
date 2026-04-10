# ADE Views Pruning — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete dead code (checkpoint domain, Evolution/Plans/Specs/Decisions standalone pages) and update navigation, routes, and DI wiring.

**Architecture:** Pure deletion + surgical edits. Remove 5 standalone page UIs and the entire checkpoint domain module. Keep all domain modules except checkpoint (they're used by orchestration/sdlc/evolution). Keep DecisionsView side panel (used by run panels).

**Tech Stack:** Scala 3, sbt multi-module, ZIO 2, Scalatags, HTMX

---

### Task 1: Delete checkpoint domain module

**Files:**
- Delete: `modules/checkpoint-domain/` (entire directory)
- Delete: `src/main/scala/checkpoint/boundary/CheckpointsController.scala`
- Delete: `src/main/scala/checkpoint/control/CheckpointReviewService.scala`
- Delete: `src/main/scala/shared/web/CheckpointsView.scala`
- Delete: `src/test/scala/checkpoint/boundary/CheckpointsControllerSpec.scala`
- Delete: `src/test/scala/checkpoint/control/CheckpointReviewServiceSpec.scala`
- Delete: `src/test/scala/shared/web/CheckpointsViewSpec.scala`
- Modify: `build.sbt`

- [ ] **Step 1: Delete the checkpoint-domain module directory**

```bash
rm -rf modules/checkpoint-domain/
```

- [ ] **Step 2: Delete checkpoint controller, service, and view from root**

```bash
rm src/main/scala/checkpoint/boundary/CheckpointsController.scala
rm src/main/scala/checkpoint/control/CheckpointReviewService.scala
rm src/main/scala/shared/web/CheckpointsView.scala
```

- [ ] **Step 3: Delete checkpoint test files**

```bash
rm src/test/scala/checkpoint/boundary/CheckpointsControllerSpec.scala
rm src/test/scala/checkpoint/control/CheckpointReviewServiceSpec.scala
rm src/test/scala/shared/web/CheckpointsViewSpec.scala
```

- [ ] **Step 4: Remove checkpointDomain from build.sbt**

In `build.sbt`, remove the `checkpointDomain` module definition (lines 430-436):

```scala
// DELETE this entire block:
lazy val checkpointDomain = (project in file("modules/checkpoint-domain"))
  .dependsOn(sharedIds, sharedErrors, taskrunDomain, issuesDomain, workspaceDomain)
  .settings(foundationSettings)
  .settings(
    name := "checkpoint-domain",
    libraryDependencies ++= domainDeps,
  )
```

Remove `checkpointDomain` from the `sharedWeb.dependsOn(...)` call (line 383). Change:

```scala
    orchestrationDomain, checkpointDomain, sdlcDomain, llm4zio)
```

to:

```scala
    orchestrationDomain, sdlcDomain, llm4zio)
```

Remove `checkpointDomain` from `allModules` (line 487). Change:

```scala
  checkpointDomain, sdlcDomain,
```

to:

```scala
  sdlcDomain,
```

- [ ] **Step 5: Verify checkpoint deletion compiles**

Run: `sbt compile`
Expected: Compilation errors — checkpoint is still referenced in `AdeRouteModule.scala`, `ApplicationDI.scala`, and `NavBadgeController.scala`. These will be fixed in Tasks 6-8.

- [ ] **Step 6: Commit checkpoint domain deletion**

```bash
git add -A
git commit -m "chore: delete checkpoint domain module (dead code)"
```

---

### Task 2: Delete Evolution standalone UI

**Files:**
- Delete: `src/main/scala/evolution/boundary/EvolutionController.scala`
- Delete: `modules/shared-web/src/main/scala/shared/web/EvolutionView.scala`
- Delete: `src/test/scala/evolution/boundary/EvolutionControllerSpec.scala`
- Delete: `src/test/scala/shared/web/EvolutionViewSpec.scala`

- [ ] **Step 1: Delete Evolution controller, view, and tests**

```bash
rm src/main/scala/evolution/boundary/EvolutionController.scala
rm modules/shared-web/src/main/scala/shared/web/EvolutionView.scala
rm src/test/scala/evolution/boundary/EvolutionControllerSpec.scala
rm src/test/scala/shared/web/EvolutionViewSpec.scala
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "chore: delete Evolution standalone page UI"
```

---

### Task 3: Delete Plans standalone UI

**Files:**
- Delete: `src/main/scala/plan/boundary/PlansController.scala`
- Delete: `modules/shared-web/src/main/scala/shared/web/PlansView.scala`
- Delete: `modules/shared-web/src/main/scala/shared/web/PlanPreviewComponents.scala`
- Delete: `src/test/scala/plan/boundary/PlansControllerSpec.scala`
- Delete: `src/test/scala/shared/web/PlansViewSpec.scala`
- Delete: `src/test/scala/shared/web/PlanPreviewComponentsSpec.scala`

- [ ] **Step 1: Delete Plans controller, views, and tests**

```bash
rm src/main/scala/plan/boundary/PlansController.scala
rm modules/shared-web/src/main/scala/shared/web/PlansView.scala
rm modules/shared-web/src/main/scala/shared/web/PlanPreviewComponents.scala
rm src/test/scala/plan/boundary/PlansControllerSpec.scala
rm src/test/scala/shared/web/PlansViewSpec.scala
rm src/test/scala/shared/web/PlanPreviewComponentsSpec.scala
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "chore: delete Plans standalone page UI"
```

---

### Task 4: Delete Specifications standalone UI

**Files:**
- Delete: `modules/specification-domain/src/main/scala/specification/boundary/SpecificationsController.scala`
- Delete: `modules/specification-domain/src/main/scala/specification/boundary/SpecificationsView.scala`
- Delete: `src/test/scala/specification/boundary/SpecificationsControllerSpec.scala`
- Delete: `src/test/scala/shared/web/SpecificationsViewSpec.scala`

- [ ] **Step 1: Delete Specifications controller, view, and tests**

```bash
rm modules/specification-domain/src/main/scala/specification/boundary/SpecificationsController.scala
rm modules/specification-domain/src/main/scala/specification/boundary/SpecificationsView.scala
rm src/test/scala/specification/boundary/SpecificationsControllerSpec.scala
rm src/test/scala/shared/web/SpecificationsViewSpec.scala
```

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "chore: delete Specifications standalone page UI"
```

---

### Task 5: Trim DecisionsView and delete DecisionsController

**Files:**
- Delete: `src/main/scala/decision/boundary/DecisionsController.scala`
- Delete: `src/test/scala/decision/boundary/DecisionsControllerSpec.scala`
- Modify: `modules/decision-domain/src/main/scala/decision/boundary/DecisionsView.scala`

- [ ] **Step 1: Delete DecisionsController and its test**

```bash
rm src/main/scala/decision/boundary/DecisionsController.scala
rm src/test/scala/decision/boundary/DecisionsControllerSpec.scala
```

- [ ] **Step 2: Trim DecisionsView to side-panel-only**

Replace the entire content of `modules/decision-domain/src/main/scala/decision/boundary/DecisionsView.scala` with:

```scala
package decision.boundary

import decision.entity.*
import scalatags.Text.all.*

object DecisionsView:

  def sidePanelFragment(decisions: List[Decision], runId: String): String =
    div(cls := "space-y-3 p-1", attr("data-decision-panel") := runId)(
      if decisions.isEmpty then
        div(cls := "rounded-lg border border-dashed border-white/10 bg-slate-900/40 p-6 text-center")(
          p(cls := "text-xs text-gray-400")("No pending decisions for this run.")
        )
      else
        frag(
          p(cls := "text-xs text-gray-400 mb-2")(s"${decisions.size} decision(s) pending review"),
          decisions.map(d => sidePanelCard(d, runId)),
        )
    ).render

  private def sidePanelCard(decision: Decision, runId: String): Frag =
    val criticalCls =
      if decision.urgency == DecisionUrgency.Critical then " border-rose-500/45" else " border-white/10"
    div(cls := s"rounded-lg border bg-slate-900/60 p-3$criticalCls")(
      div(cls := "flex items-start justify-between gap-2")(
        h3(cls := "text-xs font-semibold text-white leading-snug")(decision.title),
        div(cls := "flex-shrink-0")(urgencyBadge(decision.urgency)),
      ),
      p(cls := "mt-1 text-[11px] text-slate-400 line-clamp-3")(decision.context),
      decision.source.issueId.map(issueId =>
        p(cls := "mt-1 text-[11px] text-indigo-300")(s"Issue: ${issueId.value}")
      ).getOrElse(frag()),
      decision.resolution.map { resolution =>
        div(cls := "mt-2 rounded border border-emerald-400/20 bg-emerald-500/5 px-2 py-2 text-[11px] text-emerald-100")(
          span(cls := "font-semibold")(resolution.kind.toString),
          span(cls := "ml-2 text-emerald-200/80")(s"by ${resolution.actor}"),
        )
      }.getOrElse(sidePanelActionBar(decision, runId)),
    )

  private def sidePanelActionBar(decision: Decision, runId: String): Frag =
    if decision.status != DecisionStatus.Pending then frag()
    else
      div(cls := "mt-2 space-y-2")(
        form(
          action            := s"/decisions/${decision.id.value}/resolve",
          method            := "post",
          attr("hx-post")   := s"/decisions/${decision.id.value}/resolve",
          attr("hx-target") := "closest [data-decision-panel]",
          attr("hx-swap")   := "outerHTML",
        )(
          input(`type` := "hidden", name := "_run_id", value := runId),
          textarea(
            name        := "summary",
            rows        := "2",
            placeholder := "Reviewer notes (optional)…",
            cls         := "w-full rounded border border-white/10 bg-black/20 px-2 py-1 text-[11px] text-slate-200 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-cyan-500/50",
          )(""),
          div(cls := "mt-1.5 flex flex-wrap gap-1.5")(
            button(
              `type`              := "submit",
              name                := "resolution",
              value               := DecisionResolutionKind.Approved.toString,
              attr("data-action") := "approve",
              cls                 := "rounded border border-emerald-400/30 bg-emerald-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-emerald-200 hover:bg-emerald-500/20",
            )("Approve"),
            button(
              `type`              := "submit",
              name                := "resolution",
              value               := DecisionResolutionKind.ReworkRequested.toString,
              attr("data-action") := "rework",
              cls                 := "rounded border border-amber-400/30 bg-amber-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-amber-200 hover:bg-amber-500/20",
            )("Rework"),
            button(
              `type`            := "button",
              cls               := "rounded border border-rose-400/30 bg-rose-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-rose-200 hover:bg-rose-500/20",
              attr("hx-post")   := s"/decisions/${decision.id.value}/escalate",
              attr("hx-target") := "closest [data-decision-panel]",
              attr("hx-swap")   := "outerHTML",
            )("Escalate"),
          ),
        )
      )

  private def urgencyBadge(urgency: DecisionUrgency): Frag =
    val tone = urgency match
      case DecisionUrgency.Critical => "border-rose-400/30 bg-rose-500/10 text-rose-200"
      case DecisionUrgency.High     => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case DecisionUrgency.Medium   => "border-cyan-400/30 bg-cyan-500/10 text-cyan-200"
      case DecisionUrgency.Low      => "border-slate-400/30 bg-slate-500/10 text-slate-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $tone")(urgency.toString)
```

Key changes: Removed `page`, `cardsFragment`, `decisionsList`, `header`, `inlineSelect`, `card`, `actionBar`, `escalateForm`, `keyboardNavScript`, `chip`, `statusBadge`. Kept `sidePanelFragment`, `sidePanelCard`, `sidePanelActionBar`, `urgencyBadge`. Removed `import shared.web.Layout` (no longer needed).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: delete Decisions standalone page, keep side panel fragment"
```

---

### Task 6: Update AdeRouteModule

**Files:**
- Modify: `src/main/scala/app/boundary/AdeRouteModule.scala`

- [ ] **Step 1: Rewrite AdeRouteModule to remove deleted controllers**

Replace the entire content of `src/main/scala/app/boundary/AdeRouteModule.scala` with:

```scala
package app.boundary

import zio.*
import zio.http.*

import board.boundary.BoardController as BoardBoundaryController
import daemon.boundary.DaemonsController
import decision.control.DecisionInbox
import demo.boundary.DemoController
import governance.entity.GovernancePolicyRepository
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
        DaemonsController &
        DemoController &
        DecisionInbox &
        IssueRepository &
        GovernancePolicyRepository,
      Nothing,
      AdeRouteModule,
    ] =
    ZLayer {
      for
        board           <- ZIO.service[BoardBoundaryController]
        projects        <- ZIO.service[ProjectsController]
        knowledge       <- ZIO.service[KnowledgeController]
        daemons         <- ZIO.service[DaemonsController]
        demoController  <- ZIO.service[DemoController]
        decisionInbox   <- ZIO.service[DecisionInbox]
        issueRepository <- ZIO.service[IssueRepository]
        governancePolicyRepo <- ZIO.service[GovernancePolicyRepository]
      yield new AdeRouteModule:
        override val routes: Routes[Any, Response] =
          board.routes ++
            projects.routes ++
            knowledge.routes ++
            daemons.routes ++
            demoController.routes ++
            GovernanceController.routes(governancePolicyRepo) ++
            NavBadgeController.routes(
              decisionInbox,
              issueRepository,
            )
    }
```

Key changes:
- Removed imports: `CheckpointsController`, `CheckpointReviewService`, `DecisionsController`, `EvolutionController`, `EvolutionProposalRepository`, `PlansController`, `SpecificationsController`
- Removed from ZLayer type: `SpecificationsController`, `PlansController`, `DecisionsController`, `CheckpointsController`, `CheckpointReviewService`, `EvolutionProposalRepository`
- Removed service injections: `specifications`, `plans`, `decisions`, `checkpoints`, `checkpointReview`, `evolutionRepo`
- Removed route aggregations: `specifications.routes`, `plans.routes`, `decisions.routes`, `checkpoints.routes`, `EvolutionController.routes(evolutionRepo)`
- Updated `NavBadgeController.routes` call to remove `checkpointReview` parameter (see Task 7)

- [ ] **Step 2: Commit**

```bash
git add src/main/scala/app/boundary/AdeRouteModule.scala
git commit -m "chore: remove deleted controller routes from AdeRouteModule"
```

---

### Task 7: Update NavBadgeController

**Files:**
- Modify: `src/main/scala/app/boundary/NavBadgeController.scala`
- Modify: `src/test/scala/app/boundary/NavBadgeControllerSpec.scala` (if it exists — remove checkpoint-related tests)

- [ ] **Step 1: Remove checkpoint badge route from NavBadgeController**

Replace the entire content of `src/main/scala/app/boundary/NavBadgeController.scala` with:

```scala
package app.boundary

import zio.*
import zio.http.*

import decision.control.DecisionInbox
import decision.entity.{ DecisionFilter, DecisionStatus }
import issues.entity.{ IssueFilter, IssueRepository, IssueStateTag }
import shared.errors.PersistenceError

object NavBadgeController:

  def routes(
    decisionInbox: DecisionInbox,
    issueRepository: IssueRepository,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "nav" / "badges" / "decisions" -> handler { (_: Request) =>
        pendingDecisionCount(decisionInbox)
          .map(count => badgeResponse(count))
          .catchAll(error => ZIO.succeed(errorResponse(error.toString)))
      },
      Method.GET / "nav" / "badges" / "board"     -> handler { (_: Request) =>
        inProgressBoardCount(issueRepository)
          .map(count => badgeResponse(count))
          .catchAll(error => ZIO.succeed(errorResponse(error.toString)))
      },
    )

  private def pendingDecisionCount(decisionInbox: DecisionInbox): IO[PersistenceError, Int] =
    decisionInbox
      .list(DecisionFilter(limit = Int.MaxValue))
      .map(_.count(_.status == DecisionStatus.Pending))

  private def inProgressBoardCount(issueRepository: IssueRepository): IO[PersistenceError, Int] =
    issueRepository
      .list(IssueFilter(states = Set(IssueStateTag.InProgress), limit = Int.MaxValue))
      .map(_.size)

  private def badgeResponse(count: Int): Response =
    val body =
      if count > 0 then
        s"""<span class="ml-auto inline-flex min-w-[1.25rem] items-center justify-center rounded-full bg-amber-500/20 px-1.5 py-0.5 text-[10px] font-semibold text-amber-200">$count</span>"""
      else ""
    Response.text(body).contentType(MediaType.text.html)

  private def errorResponse(message: String): Response =
    Response.text(
      s"""<span class="hidden" data-sidebar-badge-error="$message"></span>"""
    ).contentType(MediaType.text.html)
```

Key changes: Removed `CheckpointReviewService` import, removed `checkpointReviewService` parameter, removed `/nav/badges/checkpoints` route, removed `pendingCheckpointCount` method, removed `CheckpointReviewError` import.

- [ ] **Step 2: Update NavBadgeControllerSpec if it exists**

Check `src/test/scala/app/boundary/NavBadgeControllerSpec.scala`. If it references `CheckpointReviewService`, remove the checkpoint-related test cases and the stub/import.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: remove checkpoint badge from NavBadgeController"
```

---

### Task 8: Update ApplicationDI

**Files:**
- Modify: `src/main/scala/app/ApplicationDI.scala`

- [ ] **Step 1: Remove controller imports**

In `src/main/scala/app/ApplicationDI.scala`, remove these import lines:

```scala
// Remove (line 33):
import checkpoint.boundary.CheckpointsController
// Remove (line 34):
import checkpoint.control.CheckpointReviewService
// Remove (line 44):
import decision.boundary.DecisionsController
// Remove (line 80):
import plan.boundary.PlansController
// Remove (line 91):
import specification.boundary.SpecificationsController
```

Keep these imports (they're for domain layers, not controllers):
- `import decision.control.DecisionInbox` (line 45)
- `import decision.entity.{ DecisionEventStoreES, DecisionRepositoryES }` (line 46)
- `import evolution.control.EvolutionEngine` (line 50)
- `import evolution.entity.{ EvolutionProposalEventStoreES, EvolutionProposalRepositoryES }` (line 51)
- `import plan.entity.{ PlanEventStoreES, PlanRepositoryES }` (line 81)
- `import specification.entity.{ SpecificationEventStoreES, SpecificationRepositoryES }` (line 92)

- [ ] **Step 2: Remove controller layers from webServerLayer**

In the `webServerLayer` method's layer list, remove these 5 lines:

```scala
// Remove (line 332):
CheckpointReviewService.live,
// Remove (line 355):
SpecificationsController.live,
// Remove (line 356):
PlansController.live,
// Remove (line 357):
DecisionsController.live,
// Remove (line 358):
CheckpointsController.live,
```

Keep all domain repository/service layers (they're used by other modules):
- `SpecificationEventStoreES.live` (line 315)
- `SpecificationRepositoryES.live` (line 316)
- `PlanEventStoreES.live` (line 317)
- `PlanRepositoryES.live` (line 318)
- `DecisionLogEventStoreES.live` (line 319)
- `DecisionLogRepositoryES.live` (line 320)
- `EvolutionProposalEventStoreES.live` (line 321)
- `EvolutionProposalRepositoryES.live` (line 322)
- `GovernancePolicyEventStoreES.live` (line 323)
- `GovernancePolicyRepositoryES.live` (line 324)
- `GovernancePolicyEngine.live` (line 325)
- `GovernancePolicyService.live` (line 326)
- `DecisionInbox.live` (line 366)
- `EvolutionEngine.live` (line 367)

- [ ] **Step 3: Compile to verify**

Run: `sbt compile`
Expected: SUCCESS — all deleted controllers are removed from both routes and DI.

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/app/ApplicationDI.scala
git commit -m "chore: remove dead controller layers from ApplicationDI"
```

---

### Task 9: Update Layout.scala navigation

**Files:**
- Modify: `modules/shared-web-core/src/main/scala/shared/web/Layout.scala`

- [ ] **Step 1: Remove nav items from coreGatewayGroup**

In `Layout.scala`, find the `coreGatewayGroup` definition (around line 247). Remove these two lines:

```scala
// Remove (line 259):
      NavItem("/specifications", "Specifications", Icons.documentText, _.startsWith("/specifications")),
// Remove (line 260):
      NavItem("/plans", "Plans", Icons.chart, _.startsWith("/plans")),
```

After removal, `coreGatewayGroup` items should be: Command Center, Board, Projects, Knowledge, Agents, Settings.

- [ ] **Step 2: Remove nav items from adeGroup**

In the `adeGroup` definition (around line 275). Remove these three items:

```scala
// Remove (lines 286-292):
      NavItem(
        "/checkpoints",
        "Checkpoints",
        Icons.documentText,
        _.startsWith("/checkpoints"),
        liveBadgePath = Some("/nav/badges/checkpoints"),
      ),
// Remove (lines 293-299):
      NavItem(
        "/decisions",
        "Decisions",
        Icons.activity,
        _.startsWith("/decisions"),
        liveBadgePath = Some("/nav/badges/decisions"),
      ),
// Remove (line 301):
      NavItem("/evolution", "Evolution", Icons.sparkles, _.startsWith("/evolution")),
```

After removal, `adeGroup` items should be: SDLC Dashboard, Board, Governance, Daemons.

- [ ] **Step 3: Commit**

```bash
git add modules/shared-web-core/src/main/scala/shared/web/Layout.scala
git commit -m "chore: remove pruned pages from navigation sidebar"
```

---

### Task 10: Compile and test

- [ ] **Step 1: Full compile**

Run: `sbt compile`
Expected: SUCCESS with zero errors. If there are unused import errors (`-Werror`), fix them — likely in files that imported deleted controllers or views.

- [ ] **Step 2: Run tests**

Run: `sbt test`
Expected: All remaining tests pass. Deleted test files are gone so they won't run.

- [ ] **Step 3: Fix any compilation or test errors**

Common issues to watch for:
- Unused imports in files that referenced deleted controllers/views — remove the imports
- `EvolutionController` referenced from `AdeRouteModule` — already handled in Task 6
- `CheckpointReviewService` referenced from `NavBadgeController` — already handled in Task 7
- Any other file importing `CheckpointsController`, `PlansController`, `SpecificationsController`, `DecisionsController`, or `EvolutionController` — remove those imports
- `shared.web.Layout` import in `DecisionsView` — already removed in Task 5

- [ ] **Step 4: Final commit if fixes were needed**

```bash
git add -A
git commit -m "fix: resolve compilation errors from phase 1 pruning"
```

---

### Task 11: Manual verification (optional)

- [ ] **Step 1: Start gateway**

Run: `sbt run`
Expected: Gateway starts on http://localhost:8080

- [ ] **Step 2: Verify remaining pages work**

Visit these URLs — all should render correctly:
- http://localhost:8080/ (Dashboard)
- http://localhost:8080/board (Board)
- http://localhost:8080/projects (Projects)
- http://localhost:8080/knowledge (Knowledge)
- http://localhost:8080/agents (Agents)
- http://localhost:8080/settings (Settings)
- http://localhost:8080/governance (Governance — still exists in Phase 1)
- http://localhost:8080/daemons (Daemons — still exists in Phase 1)

- [ ] **Step 3: Verify removed pages return 404**

Visit these URLs — all should return 404:
- http://localhost:8080/plans
- http://localhost:8080/specifications
- http://localhost:8080/checkpoints
- http://localhost:8080/decisions
- http://localhost:8080/evolution

- [ ] **Step 4: Verify navigation**

The sidebar should NOT show links to: Plans, Specifications, Checkpoints, Decisions, Evolution.

- [ ] **Step 5: Verify decision side panel**

Navigate to a board issue with an active agent run. The decision side panel should still render correctly in the run detail view.
