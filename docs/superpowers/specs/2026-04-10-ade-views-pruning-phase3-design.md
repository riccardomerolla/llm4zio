# ADE Views Pruning Phase 3 — Plan + Spec Panels in Issue Detail

## Goal

Show linked Plan and Specification summaries in the Issue detail view as collapsible panels between the header and timeline. This surfaces contextual ADE data that was previously only on the now-deleted standalone pages.

## Architecture

**DTO approach:** Define `LinkedPlan` and `LinkedSpec` case classes in `board.entity` to keep `board-domain` decoupled from `plan-domain` and `specification-domain`. The `IssueTimelineService` (in root) maps full entities to these DTOs.

**Data flow:**
1. `IssueTimelineService` gains `PlanRepository` and `SpecificationRepository` dependencies
2. `buildTimeline` is extended to also return linked plans/specs (or a new method/return type)
3. `BoardController.renderIssueDetail` passes this data to `IssueTimelineView.page`
4. `IssueTimelineView` renders collapsible panels

## Changes

### New DTOs in board-domain

`modules/board-domain/src/main/scala/board/entity/LinkedContext.scala`:

```scala
case class LinkedPlan(
  id: String,
  summary: String,
  status: String,       // Draft, Validated, Executing, Completed, Abandoned
  taskCount: Int,
  validationStatus: Option[String],  // Passed, Failed, Pending
  specificationId: Option[String],
  createdAt: Instant,
)

case class LinkedSpec(
  id: String,
  title: String,
  status: String,       // Draft, InRefinement, Approved, Superseded
  version: Int,
  author: String,
  contentPreview: String,  // first ~200 chars
  reviewCommentCount: Int,
  createdAt: Instant,
)

case class IssueContext(
  timeline: List[TimelineEntry],
  linkedPlans: List[LinkedPlan],
  linkedSpecs: List[LinkedSpec],
)
```

### Extend IssueTimelineService

Change return type from `List[TimelineEntry]` to `IssueContext`:

```scala
trait IssueTimelineService:
  def buildTimeline(workspaceId: String, issueId: BoardIssueId): IO[PersistenceError, IssueContext]
```

Add `PlanRepository` and `SpecificationRepository` to dependencies. After building the timeline, query plans and specs by filtering `list` results for matching `linkedIssueIds`.

### Extend IssueTimelineView

Add collapsible panels between header and timeline in `page()`:

```
header
linkedPlanPanels (if any)    ← NEW
linkedSpecPanels (if any)    ← NEW
timelineBody
reviewActionForm
```

Each panel: collapsible `<details>` element with status badge, summary content, and metadata.

### Update BoardController

`renderIssueDetail` already calls `buildTimeline` — just pass the new `IssueContext` to the view.

## Panel Design

**Plan panel:** Indigo-tinted border, "Plan" label, status badge (color-coded), summary text truncated to 3 lines, task count chip, validation indicator.

**Spec panel:** Violet-tinted border, "Specification" label, status badge, title, author, version badge, content preview (first ~200 chars), review comment count.

Both panels use `<details>` for collapse/expand, open by default.

## Testing

- Unit test: `IssueTimelineView` renders plan/spec panels when data present, omits when empty
- Unit test: `IssueTimelineService` maps Plan/Spec entities to DTOs correctly
- Existing timeline tests updated for new return type
