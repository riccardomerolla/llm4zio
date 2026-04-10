# ADE Views Pruning — Phase 4 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Fix all broken links and remove orphaned code left over from Phases 1-3.

**Spec:** `docs/superpowers/specs/2026-04-10-ade-views-pruning-phase4-design.md`

---

### Task 1: Fix CommandCenterView module grid

**Files:**
- Modify: `modules/shared-web/src/main/scala/shared/web/CommandCenterView.scala`

- [ ] **Step 1: Update adeModuleGrid**

Replace the current `adeModuleGrid()` method (lines 60-69):

```scala
  private def adeModuleGrid(): Frag =
    div(cls := "grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4")(
      moduleCard("/sdlc", "SDLC Dashboard", IconPulse, "text-cyan-400", "Metrics, lifecycle & delivery health"),
      moduleCard("/board", "Board", IconTableColumns, "text-blue-400", "Git-backed issue kanban"),
      moduleCard("/decisions", "Decisions", IconClock, "text-purple-400", "Human-in-the-loop review inbox"),
      moduleCard("/checkpoints", "Checkpoints", IconCheckCircle, "text-emerald-400", "Quality gates during agent runs"),
      moduleCard("/governance", "Governance", IconDocument, "text-amber-400", "Policy engine & transition rules"),
      moduleCard("/evolution", "Evolution", IconSparkles, "text-rose-400", "Structural change proposals"),
      moduleCard("/daemons", "Daemons", IconCpuChip, "text-sky-400", "Background agents & scheduled jobs"),
    )
```

With:

```scala
  private def adeModuleGrid(): Frag =
    div(cls := "grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4")(
      moduleCard("/sdlc", "SDLC Dashboard", IconPulse, "text-cyan-400", "Metrics, lifecycle & delivery health"),
      moduleCard("/board", "Board", IconTableColumns, "text-blue-400", "Git-backed issue kanban"),
      moduleCard("/settings/governance", "Governance", IconDocument, "text-amber-400", "Policy engine & transition rules"),
      moduleCard("/settings/daemons", "Daemons", IconCpuChip, "text-sky-400", "Background agents & scheduled jobs"),
    )
```

- [ ] **Step 2: Remove unused icon constants**

Remove the icon constants that are no longer used:

```scala
  private val IconClock        = "M12 6v6h4.5m4.5 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
  private val IconCheckCircle  = "M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z"
  private val IconSparkles     =
    "M9.813 15.904 9 18l-.813-2.096L6 15l2.187-.904L9 12l.813 2.096L12 15l-2.187.904ZM17.25 8.25 16.5 10.5l-2.25.75 2.25.75.75 2.25.75-2.25 2.25-.75-2.25-.75-.75-2.25ZM4.5 4.5 4 6l-1.5.5 1.5.5.5 1.5.5-1.5L6.5 6 5 5.5 4.5 4.5Z"
```

- [ ] **Step 3: Compile**

Run: `sbt compile`

- [ ] **Step 4: Commit**

```bash
git add modules/shared-web/src/main/scala/shared/web/CommandCenterView.scala
git commit -m "fix: remove dead module cards and update Settings links in CommandCenterView"
```

---

### Task 2: Remove broken lifecycle hrefs in SdlcDashboardService

**Files:**
- Modify: `modules/sdlc-domain/src/main/scala/sdlc/control/SdlcDashboardService.scala`

- [ ] **Step 1: Clear the three broken hrefs**

Change the three lifecycle stage definitions (lines ~159-181):

Change:
```scala
      LifecycleStage(
        key = "idea",
        label = "Idea",
        count = specifications.count(spec =>
          spec.status != SpecificationStatus.Approved && spec.status != SpecificationStatus.Superseded
        ),
        href = "/specifications",
        description = "Draft and refinement specs before approval.",
      ),
      LifecycleStage(
        key = "spec",
        label = "Spec",
        count = specifications.count(_.status == SpecificationStatus.Approved),
        href = "/specifications",
        description = "Approved specifications ready for planning.",
      ),
      LifecycleStage(
        key = "plan",
        label = "Plan",
        count = plans.count(plan => plan.status == PlanStatus.Draft || plan.status == PlanStatus.Validated),
        href = "/plans",
        description = "Plans waiting for or passing validation.",
      ),
```

To:
```scala
      LifecycleStage(
        key = "idea",
        label = "Idea",
        count = specifications.count(spec =>
          spec.status != SpecificationStatus.Approved && spec.status != SpecificationStatus.Superseded
        ),
        href = "",
        description = "Draft and refinement specs before approval.",
      ),
      LifecycleStage(
        key = "spec",
        label = "Spec",
        count = specifications.count(_.status == SpecificationStatus.Approved),
        href = "",
        description = "Approved specifications ready for planning.",
      ),
      LifecycleStage(
        key = "plan",
        label = "Plan",
        count = plans.count(plan => plan.status == PlanStatus.Draft || plan.status == PlanStatus.Validated),
        href = "",
        description = "Plans waiting for or passing validation.",
      ),
```

- [ ] **Step 2: Compile**

Run: `sbt sdlcDomain/compile`

- [ ] **Step 3: Commit**

```bash
git add modules/sdlc-domain/src/main/scala/sdlc/control/SdlcDashboardService.scala
git commit -m "fix: remove broken /specifications and /plans hrefs from SDLC lifecycle stages"
```

---

### Task 3: Remove checkpoint link from AgentMonitorView

**Files:**
- Modify: `modules/shared-web/src/main/scala/shared/web/AgentMonitorView.scala`
- Modify: `src/test/scala/shared/web/AgentMonitorViewSpec.scala`

- [ ] **Step 1: Update fromInfo to set reviewHref = None**

In `AgentMonitorView.fromInfo` (line 66), change:

```scala
      reviewHref = info.runId.filter(_.trim.nonEmpty).map(runId => s"/checkpoints/$runId"),
```

To:

```scala
      reviewHref = None,
```

- [ ] **Step 2: Update AgentMonitorViewSpec**

The test at `src/test/scala/shared/web/AgentMonitorViewSpec.scala` asserts the checkpoint URL. Update the two assertions:

Change:
```scala
          rows.head.reviewHref.contains("/checkpoints/issue-abc-12345"),
```
To:
```scala
          rows.head.reviewHref.isEmpty,
```

Change:
```scala
          html.contains("window.location='/checkpoints/issue-abc-12345'"),
```
To:
```scala
          !html.contains("window.location="),
```

- [ ] **Step 3: Compile and run test**

Run: `sbt 'testOnly shared.web.AgentMonitorViewSpec'`

- [ ] **Step 4: Commit**

```bash
git add modules/shared-web/src/main/scala/shared/web/AgentMonitorView.scala src/test/scala/shared/web/AgentMonitorViewSpec.scala
git commit -m "fix: remove orphaned /checkpoints link from AgentMonitorView"
```

---

### Task 4: Remove orphaned decisions badge from NavBadgeController

**Files:**
- Modify: `src/main/scala/app/boundary/NavBadgeController.scala`
- Modify: `src/main/scala/app/boundary/AdeRouteModule.scala`
- Modify: `src/test/scala/app/boundary/NavBadgeControllerSpec.scala`

- [ ] **Step 1: Simplify NavBadgeController**

Replace the entire file `src/main/scala/app/boundary/NavBadgeController.scala` with:

```scala
package app.boundary

import zio.*
import zio.http.*

import issues.entity.{ IssueFilter, IssueRepository, IssueStateTag }
import shared.errors.PersistenceError

object NavBadgeController:

  def routes(
    issueRepository: IssueRepository,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "nav" / "badges" / "board" -> handler { (_: Request) =>
        inProgressBoardCount(issueRepository)
          .map(count => badgeResponse(count))
          .catchAll(error => ZIO.succeed(errorResponse(error.toString)))
      },
    )

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

- [ ] **Step 2: Update AdeRouteModule to remove DecisionInbox**

Replace the entire file `src/main/scala/app/boundary/AdeRouteModule.scala` with:

```scala
package app.boundary

import zio.*
import zio.http.*

import board.boundary.BoardController as BoardBoundaryController
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
        issueRepository <- ZIO.service[IssueRepository]
      yield new AdeRouteModule:
        override val routes: Routes[Any, Response] =
          board.routes ++
            projects.routes ++
            knowledge.routes ++
            demoController.routes ++
            NavBadgeController.routes(
              issueRepository,
            )
    }
```

- [ ] **Step 3: Update NavBadgeControllerSpec**

Replace the entire file `src/test/scala/app/boundary/NavBadgeControllerSpec.scala` with:

```scala
package app.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.*

object NavBadgeControllerSpec extends ZIOSpecDefault:

  private val inProgressIssue = AgentIssue(
    id = IssueId("issue-1"),
    runId = None,
    conversationId = None,
    title = "In progress",
    description = "desc",
    issueType = "task",
    priority = "high",
    requiredCapabilities = Nil,
    state = IssueState.InProgress(AgentId("agent-1"), Instant.parse("2026-03-27T09:00:00Z")),
    tags = Nil,
    blockedBy = Nil,
    contextPath = "",
    sourceFolder = "",
  )

  private val issueRepository = new IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.unit
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                = ZIO.succeed(inProgressIssue)
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      = ZIO.succeed(Nil)
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ZIO.succeed(
        if filter.states.contains(IssueStateTag.InProgress) then List(inProgressIssue) else Nil
      )
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.unit

  private val routes = NavBadgeController.routes(issueRepository)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("NavBadgeControllerSpec")(
      test("board badge renders in-progress issue count") {
        for
          response <- routes.runZIO(Request.get(URL(Path.decode("/nav/badges/board"))))
          body     <- response.body.asString
        yield assertTrue(response.status == Status.Ok, body.contains(">1<"))
      },
    )
```

- [ ] **Step 4: Compile and run tests**

Run: `sbt 'testOnly app.boundary.NavBadgeControllerSpec'`

- [ ] **Step 5: Commit**

```bash
git add src/main/scala/app/boundary/NavBadgeController.scala src/main/scala/app/boundary/AdeRouteModule.scala src/test/scala/app/boundary/NavBadgeControllerSpec.scala
git commit -m "refactor: remove orphaned decisions badge route from NavBadgeController"
```

---

### Task 5: Compile and test verification

- [ ] **Step 1: Full compile**

Run: `sbt compile`

- [ ] **Step 2: Run tests**

Run: `sbt test`

- [ ] **Step 3: Verify no remaining broken links**

Run a grep to confirm no view/controller code references deleted routes:

```bash
grep -rn '"/checkpoints' --include="*.scala" modules/ src/main/scala/ | grep -v target | grep -v docs
grep -rn '"/evolution' --include="*.scala" modules/ src/main/scala/ | grep -v target | grep -v docs
grep -rn '"/decisions' --include="*.scala" modules/ src/main/scala/ | grep -v target | grep -v docs
grep -rn '"/specifications' --include="*.scala" modules/ src/main/scala/ | grep -v target | grep -v docs
grep -rn 'href = "/plans' --include="*.scala" modules/ src/main/scala/ | grep -v target | grep -v docs
```

All should return empty.

- [ ] **Step 4: Fix any issues and commit**
