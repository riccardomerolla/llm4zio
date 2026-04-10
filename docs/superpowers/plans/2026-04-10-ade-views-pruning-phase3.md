# ADE Views Pruning — Phase 3 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Add Plan and Specification summary panels to the Issue detail view.

**Spec:** `docs/superpowers/specs/2026-04-10-ade-views-pruning-phase3-design.md`

---

### Task 1: Add LinkedPlan/LinkedSpec DTOs to board-domain

**Files:**
- Create: `modules/board-domain/src/main/scala/board/entity/LinkedContext.scala`

- [ ] **Step 1: Create the DTO file**

Create `modules/board-domain/src/main/scala/board/entity/LinkedContext.scala`:

```scala
package board.entity

import java.time.Instant

case class LinkedPlan(
  id: String,
  summary: String,
  status: String,
  taskCount: Int,
  validationStatus: Option[String],
  specificationId: Option[String],
  createdAt: Instant,
)

case class LinkedSpec(
  id: String,
  title: String,
  status: String,
  version: Int,
  author: String,
  contentPreview: String,
  reviewCommentCount: Int,
  createdAt: Instant,
)

case class IssueContext(
  timeline: List[TimelineEntry],
  linkedPlans: List[LinkedPlan],
  linkedSpecs: List[LinkedSpec],
)
```

- [ ] **Step 2: Compile board-domain**

Run: `sbt boardDomain/compile`

- [ ] **Step 3: Commit**

```bash
git add modules/board-domain/src/main/scala/board/entity/LinkedContext.scala
git commit -m "feat: add LinkedPlan, LinkedSpec, IssueContext DTOs to board-domain"
```

---

### Task 2: Extend IssueTimelineService to return IssueContext

**Files:**
- Modify: `src/main/scala/board/control/IssueTimelineService.scala`

- [ ] **Step 1: Change trait return type**

Change:
```scala
trait IssueTimelineService:
  def buildTimeline(workspaceId: String, issueId: BoardIssueId): IO[PersistenceError, List[TimelineEntry]]
```
to:
```scala
trait IssueTimelineService:
  def buildTimeline(workspaceId: String, issueId: BoardIssueId): IO[PersistenceError, IssueContext]
```

Update the companion object's accessor method return type similarly.

- [ ] **Step 2: Add PlanRepository and SpecificationRepository dependencies**

Add imports:
```scala
import plan.entity.{ Plan, PlanRepository }
import specification.entity.{ Specification, SpecificationRepository }
```

Update the `live` layer:
```scala
  val live: ZLayer[
    IssueRepository & WorkspaceRepository & DecisionInbox & ChatRepository & AnalysisRepository &
      PlanRepository & SpecificationRepository,
    Nothing,
    IssueTimelineService,
  ] =
    ZLayer.fromFunction(IssueTimelineServiceLive.apply)
```

Add fields to `IssueTimelineServiceLive`:
```scala
final case class IssueTimelineServiceLive(
  issueRepository: IssueRepository,
  workspaceRepository: WorkspaceRepository,
  decisionInbox: DecisionInbox,
  chatRepository: ChatRepository,
  analysisRepository: AnalysisRepository,
  planRepository: PlanRepository,
  specificationRepository: SpecificationRepository,
) extends IssueTimelineService:
```

- [ ] **Step 3: Implement plan/spec lookup and mapping**

At the end of `buildTimeline`, after building the timeline list, add plan/spec queries:

```scala
      plans          <- planRepository.list
      specs          <- specificationRepository.list
      linkedPlans     = plans
                          .filter(_.linkedIssueIds.exists(_.value == agentIssueId.value))
                          .map(toLinkedPlan)
      linkedSpecs     = specs
                          .filter(_.linkedIssueIds.exists(_.value == agentIssueId.value))
                          .map(toLinkedSpec)
    yield IssueContext(
      timeline = timeline.sortBy(_.occurredAt),
      linkedPlans = linkedPlans,
      linkedSpecs = linkedSpecs,
    )
```

Add mapping methods:
```scala
  private def toLinkedPlan(plan: Plan): LinkedPlan =
    LinkedPlan(
      id = plan.id.value,
      summary = plan.summary,
      status = plan.status.toString,
      taskCount = plan.drafts.size,
      validationStatus = plan.validation.map(_.status.toString),
      specificationId = plan.specificationId.map(_.value),
      createdAt = plan.createdAt,
    )

  private def toLinkedSpec(spec: Specification): LinkedSpec =
    LinkedSpec(
      id = spec.id.value,
      title = spec.title,
      status = spec.status.toString,
      version = spec.version,
      author = spec.author.displayName,
      contentPreview = spec.content.take(200),
      reviewCommentCount = spec.reviewComments.size,
      createdAt = spec.createdAt,
    )
```

- [ ] **Step 4: Commit**

```bash
git add src/main/scala/board/control/IssueTimelineService.scala
git commit -m "feat: extend IssueTimelineService to return linked plans and specs"
```

---

### Task 3: Update BoardController to pass IssueContext

**Files:**
- Modify: `src/main/scala/board/boundary/BoardController.scala`

- [ ] **Step 1: Update renderIssueDetail**

The current code:
```scala
      timeline         <- issueTimelineService
                            .buildTimeline(workspaceId, issueId)
                            .mapError(err => BoardError.ParseError(s"timeline lookup failed: $err"))
    yield htmlResponse(IssueTimelineView.page(workspaceId, issue, timeline))
```

Change to:
```scala
      context          <- issueTimelineService
                            .buildTimeline(workspaceId, issueId)
                            .mapError(err => BoardError.ParseError(s"timeline lookup failed: $err"))
    yield htmlResponse(IssueTimelineView.page(workspaceId, issue, context))
```

- [ ] **Step 2: Commit**

```bash
git add src/main/scala/board/boundary/BoardController.scala
git commit -m "refactor: pass IssueContext to IssueTimelineView"
```

---

### Task 4: Update IssueTimelineView to render panels

**Files:**
- Modify: `modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala`

- [ ] **Step 1: Change page signature to accept IssueContext**

Change:
```scala
  def page(
    workspaceId: String,
    issue: BoardIssue,
    timeline: List[TimelineEntry],
  ): String =
```
to:
```scala
  def page(
    workspaceId: String,
    issue: BoardIssue,
    context: IssueContext,
  ): String =
```

Update internal references from `timeline` to `context.timeline`.

- [ ] **Step 2: Add panel rendering between header and timeline**

In the `page` method, add panels:
```scala
      div(cls := "space-y-4")(
        header(workspaceId, issue),
        linkedPlanPanels(context.linkedPlans),
        linkedSpecPanels(context.linkedSpecs),
        timelineBody(workspaceId, context.timeline.sortBy(_.occurredAt)),
        if issue.column == BoardColumn.Review then reviewActionForm(workspaceId, issue) else frag(),
      ),
```

- [ ] **Step 3: Implement linkedPlanPanels**

```scala
  private def linkedPlanPanels(plans: List[LinkedPlan]): Frag =
    if plans.isEmpty then frag()
    else frag(plans.map(planPanel)*)

  private def planPanel(plan: LinkedPlan): Frag =
    tag("details")(attr("open") := "open", cls := "rounded-xl border border-indigo-400/20 bg-indigo-500/5")(
      tag("summary")(cls := "cursor-pointer px-5 py-3 flex items-center gap-3")(
        span(cls := "text-sm font-semibold text-white")("Plan"),
        statusBadge(plan.status, planStatusColor(plan.status)),
        plan.validationStatus.map(vs =>
          span(cls := "text-[10px] text-slate-400")(s"Validation: $vs")
        ).getOrElse(frag()),
        span(cls := "ml-auto text-xs text-slate-500")(plan.id),
      ),
      div(cls := "px-5 pb-4 space-y-2")(
        p(cls := "text-sm text-slate-300 line-clamp-3")(plan.summary),
        div(cls := "flex flex-wrap gap-2 text-xs")(
          chip(s"${plan.taskCount} task(s)"),
          plan.specificationId.map(sid => chip(s"Spec: $sid")).getOrElse(frag()),
        ),
      ),
    )

  private def planStatusColor(status: String): String = status match
    case "Completed" => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
    case "Executing" => "border-cyan-400/30 bg-cyan-500/10 text-cyan-200"
    case "Validated" => "border-blue-400/30 bg-blue-500/10 text-blue-200"
    case "Abandoned" => "border-rose-400/30 bg-rose-500/10 text-rose-200"
    case _           => "border-slate-400/30 bg-slate-500/10 text-slate-200"
```

- [ ] **Step 4: Implement linkedSpecPanels**

```scala
  private def linkedSpecPanels(specs: List[LinkedSpec]): Frag =
    if specs.isEmpty then frag()
    else frag(specs.map(specPanel)*)

  private def specPanel(spec: LinkedSpec): Frag =
    tag("details")(attr("open") := "open", cls := "rounded-xl border border-violet-400/20 bg-violet-500/5")(
      tag("summary")(cls := "cursor-pointer px-5 py-3 flex items-center gap-3")(
        span(cls := "text-sm font-semibold text-white")("Specification"),
        statusBadge(spec.status, specStatusColor(spec.status)),
        span(cls := "text-xs text-slate-400")(s"v${spec.version}"),
        span(cls := "ml-auto text-xs text-slate-500")(spec.id),
      ),
      div(cls := "px-5 pb-4 space-y-2")(
        h3(cls := "text-sm font-semibold text-white")(spec.title),
        p(cls := "text-xs text-slate-400")(s"by ${spec.author}"),
        p(cls := "text-sm text-slate-300 line-clamp-3")(spec.contentPreview),
        if spec.reviewCommentCount > 0 then
          span(cls := "text-xs text-slate-500")(s"${spec.reviewCommentCount} review comment(s)")
        else frag(),
      ),
    )

  private def specStatusColor(status: String): String = status match
    case "Approved"     => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
    case "InRefinement" => "border-amber-400/30 bg-amber-500/10 text-amber-200"
    case "Superseded"   => "border-slate-400/30 bg-slate-500/10 text-slate-200"
    case _              => "border-slate-400/30 bg-slate-500/10 text-slate-200"
```

- [ ] **Step 5: Add shared helpers**

```scala
  private def statusBadge(text: String, colorCls: String): Frag =
    span(cls := s"rounded-full border px-2.5 py-0.5 text-[10px] font-semibold $colorCls")(text)

  private def chip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-2.5 py-0.5 text-xs text-slate-300")(value)
```

- [ ] **Step 6: Commit**

```bash
git add modules/board-domain/src/main/scala/board/boundary/IssueTimelineView.scala
git commit -m "feat: render linked Plan and Spec panels in issue detail"
```

---

### Task 5: Update ApplicationDI

**Files:**
- Modify: `src/main/scala/app/ApplicationDI.scala`

- [ ] **Step 1: Update IssueTimelineService.live layer**

The `IssueTimelineService.live` layer in `webServerLayer` needs `PlanRepository` and `SpecificationRepository` in its environment. These are already provided earlier in the layer list (`PlanRepositoryES.live`, `SpecificationRepositoryES.live`), so the layer graph should resolve automatically. Just verify `IssueTimelineService.live` is present — it should be.

If there's an explicit layer wiring override, update it. Otherwise, ZIO's automatic layer resolution should handle the new dependencies.

- [ ] **Step 2: Compile**

Run: `sbt compile`

- [ ] **Step 3: Commit if changes needed**

---

### Task 6: Update tests

**Files:**
- Modify: `src/test/scala/board/control/IssueTimelineServiceSpec.scala`
- Modify: `src/test/scala/shared/web/IssueTimelineViewSpec.scala`

- [ ] **Step 1: Update IssueTimelineServiceSpec**

The service now returns `IssueContext` instead of `List[TimelineEntry]`. Update test assertions:

Change `service.buildTimeline(...)` result handling from:
```scala
for entries <- service.buildTimeline(workspaceId, boardIssueId)
yield assertTrue(
  entries.map(_.occurredAt) == ...
```
to:
```scala
for context <- service.buildTimeline(workspaceId, boardIssueId)
yield assertTrue(
  context.timeline.map(_.occurredAt) == ...
```

Replace all `entries` references with `context.timeline` in assertions.

Add stub repositories for plans and specs to `IssueTimelineServiceLive` constructor:
```scala
planRepository = StubPlanRepository(Nil),
specificationRepository = StubSpecRepository(Nil),
```

Add stub implementations:
```scala
final private case class StubPlanRepository(plans: List[Plan]) extends PlanRepository:
  override def append(event: PlanEvent): IO[PersistenceError, Unit]        = ZIO.unit
  override def get(id: PlanId): IO[PersistenceError, Option[Plan]]         = ZIO.none
  override def history(id: PlanId): IO[PersistenceError, List[PlanEvent]]  = ZIO.succeed(Nil)
  override def list: IO[PersistenceError, List[Plan]]                      = ZIO.succeed(plans)

final private case class StubSpecRepository(specs: List[Specification]) extends SpecificationRepository:
  override def append(event: SpecificationEvent): IO[PersistenceError, Unit]             = ZIO.unit
  override def get(id: SpecificationId): IO[PersistenceError, Option[Specification]]     = ZIO.none
  override def history(id: SpecificationId): IO[PersistenceError, List[SpecificationEvent]] = ZIO.succeed(Nil)
  override def list: IO[PersistenceError, List[Specification]]                           = ZIO.succeed(specs)
  override def diff(id: SpecificationId, fromVersion: Int, toVersion: Int): IO[PersistenceError, String] = ZIO.succeed("")
```

- [ ] **Step 2: Update IssueTimelineViewSpec**

Update `IssueTimelineView.page` calls to pass `IssueContext` instead of timeline list:

Change:
```scala
IssueTimelineView.page(workspaceId, issue, timeline)
```
to:
```scala
IssueTimelineView.page(workspaceId, issue, IssueContext(timeline, Nil, Nil))
```

- [ ] **Step 3: Commit**

```bash
git add src/test/scala/
git commit -m "test: update timeline tests for IssueContext return type"
```

---

### Task 7: Compile and test verification

- [ ] **Step 1: Full compile**

Run: `sbt compile`

- [ ] **Step 2: Run tests**

Run: `sbt test`

- [ ] **Step 3: Fix any issues and commit**
