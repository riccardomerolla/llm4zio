package canvas.entity

import java.time.Instant

import zio.test.*

import shared.ids.Ids.{ AnalysisDocId, CanvasId, IssueId, NormProfileId, ProjectId, SafeguardProfileId, SpecificationId, TaskRunId }

object ReasonsCanvasSpec extends ZIOSpecDefault:

  private val author = CanvasAuthor(CanvasAuthorKind.Human, "user-1", "User 1")
  private val agent  = CanvasAuthor(CanvasAuthorKind.Agent, "spdd-architect", "SPDD Architect")
  private val now    = Instant.parse("2026-04-29T10:00:00Z")

  private def section(content: String, by: CanvasAuthor = author, at: Instant = now): CanvasSection =
    CanvasSection(content, by, at)

  private def initialSections: ReasonsSections =
    ReasonsSections(
      requirements = section("R: charge correctly per plan"),
      entities = section("E: Plan, UsageEvent, UsageLedger"),
      approach = section("A: pure pricing function"),
      structure = section("S: billing-domain BCE module"),
      operations = section("O: op-001 calculate(usage, plan, ledger)"),
      norms = section("N: BigDecimal HALF_UP @ 4dp"),
      safeguards = section("S: SG-1 idempotency, SG-2 financial accuracy"),
    )

  private def created(id: CanvasId, sections: ReasonsSections = initialSections): CanvasEvent.Created =
    CanvasEvent.Created(
      canvasId = id,
      projectId = ProjectId("proj-1"),
      title = "Multi-plan billing",
      sections = sections,
      storyIssueId = Some(IssueId("BILL-1")),
      analysisId = Some(AnalysisDocId("an-1")),
      specificationId = Some(SpecificationId("spec-1")),
      normProfileId = Some(NormProfileId("norms-default")),
      safeguardProfileId = Some(SafeguardProfileId("safeguards-billing")),
      author = author,
      occurredAt = now,
    )

  def spec: Spec[Any, Nothing] =
    suite("ReasonsCanvas")(
      test("fromEvents on empty stream returns Left") {
        val result = ReasonsCanvas.fromEvents(Nil)
        assertTrue(result.isLeft)
      },
      test("fromEvents Created produces Draft canvas at version 1 with all 7 sections") {
        val id     = CanvasId("canvas-1")
        val events = List[CanvasEvent](created(id))

        val rebuilt = ReasonsCanvas.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.id == id),
          rebuilt.exists(_.status == CanvasStatus.Draft),
          rebuilt.exists(_.version == 1),
          rebuilt.exists(_.revisions.size == 1),
          rebuilt.exists(_.sections.requirements.content.startsWith("R:")),
          rebuilt.exists(_.sections.safeguards.content.contains("idempotency")),
          rebuilt.exists(_.author == author),
        )
      },
      test("SectionUpdated bumps version, applies updates, keeps Draft when not yet Approved") {
        val id     = CanvasId("canvas-2")
        val events = List[CanvasEvent](
          created(id),
          CanvasEvent.SectionUpdated(
            canvasId = id,
            updates = List(
              CanvasSectionUpdate(CanvasSectionId.Operations, "O: op-001 + op-002 quotaRemaining(...)"),
              CanvasSectionUpdate(CanvasSectionId.Norms, "N: BigDecimal HALF_UP @ 4dp; structured logging on every charge"),
            ),
            author = agent,
            rationale = Some("expand operations + norms after analysis"),
            occurredAt = now.plusSeconds(60),
          ),
        )

        val rebuilt = ReasonsCanvas.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.version == 2),
          rebuilt.exists(_.status == CanvasStatus.Draft),
          rebuilt.exists(_.revisions.size == 2),
          rebuilt.exists(_.sections.operations.content.contains("op-002")),
          rebuilt.exists(_.sections.norms.content.contains("structured logging")),
          rebuilt.exists(_.sections.entities.content == "E: Plan, UsageEvent, UsageLedger"),
          rebuilt.exists(_.sections.operations.lastUpdatedBy == agent),
        )
      },
      test("Approved transitions Draft -> Approved without bumping version") {
        val id     = CanvasId("canvas-3")
        val events = List[CanvasEvent](
          created(id),
          CanvasEvent.Approved(canvasId = id, approvedBy = author, occurredAt = now.plusSeconds(120)),
        )

        val rebuilt = ReasonsCanvas.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.status == CanvasStatus.Approved),
          rebuilt.exists(_.version == 1),
          rebuilt.exists(_.updatedAt == now.plusSeconds(120)),
        )
      },
      test("SectionUpdated after Approved knocks status back to InReview (golden rule)") {
        val id     = CanvasId("canvas-4")
        val events = List[CanvasEvent](
          created(id),
          CanvasEvent.Approved(id, author, now.plusSeconds(60)),
          CanvasEvent.SectionUpdated(
            canvasId = id,
            updates = List(CanvasSectionUpdate(CanvasSectionId.Approach, "A: switch to Strategy pattern")),
            author = agent,
            rationale = Some("review feedback: clearer for new plans"),
            occurredAt = now.plusSeconds(120),
          ),
        )

        val rebuilt = ReasonsCanvas.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.status == CanvasStatus.InReview),
          rebuilt.exists(_.version == 2),
          rebuilt.exists(_.sections.approach.content.contains("Strategy")),
        )
      },
      test("MarkedStale flips status to Stale and records the reason on the latest revision") {
        val id     = CanvasId("canvas-5")
        val events = List[CanvasEvent](
          created(id),
          CanvasEvent.MarkedStale(
            canvasId = id,
            reason = "downstream code refactored; canvas Operations no longer match",
            markedBy = agent,
            occurredAt = now.plusSeconds(300),
          ),
        )

        val rebuilt = ReasonsCanvas.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.status == CanvasStatus.Stale),
          rebuilt.exists(_.revisions.last.staleReason.exists(_.contains("refactored"))),
        )
      },
      test("LinkedToTaskRun appends taskRunIds and dedupes on repeat") {
        val id      = CanvasId("canvas-6")
        val runId   = TaskRunId("run-1")
        val events  = List[CanvasEvent](
          created(id),
          CanvasEvent.LinkedToTaskRun(id, runId, now.plusSeconds(30)),
          CanvasEvent.LinkedToTaskRun(id, runId, now.plusSeconds(60)),
          CanvasEvent.LinkedToTaskRun(id, TaskRunId("run-2"), now.plusSeconds(90)),
        )

        val rebuilt = ReasonsCanvas.fromEvents(events)

        assertTrue(
          rebuilt.exists(_.linkedTaskRunIds == List(TaskRunId("run-1"), TaskRunId("run-2"))),
        )
      },
      test("Superseded transitions to Superseded status") {
        val id      = CanvasId("canvas-7")
        val events  = List[CanvasEvent](
          created(id),
          CanvasEvent.Superseded(id, supersededBy = Some(CanvasId("canvas-7-v2")), occurredAt = now.plusSeconds(500)),
        )

        val rebuilt = ReasonsCanvas.fromEvents(events)

        assertTrue(rebuilt.exists(_.status == CanvasStatus.Superseded))
      },
      test("SectionUpdated before Created fails with descriptive error") {
        val id     = CanvasId("canvas-8")
        val events = List[CanvasEvent](
          CanvasEvent.SectionUpdated(
            canvasId = id,
            updates = List(CanvasSectionUpdate(CanvasSectionId.Approach, "A: ...")),
            author = author,
            rationale = None,
            occurredAt = now,
          )
        )

        val rebuilt = ReasonsCanvas.fromEvents(events)

        assertTrue(rebuilt.left.exists(_.contains("not initialized")))
      },
      test("ReasonsSections.updated targets only the requested section") {
        val sections = initialSections
        val updated  = sections.updated(CanvasSectionId.Norms, section("N: NEW", agent, now.plusSeconds(10)))

        assertTrue(
          updated.norms.content == "N: NEW",
          updated.norms.lastUpdatedBy == agent,
          updated.requirements == sections.requirements,
          updated.entities == sections.entities,
          updated.approach == sections.approach,
          updated.structure == sections.structure,
          updated.operations == sections.operations,
          updated.safeguards == sections.safeguards,
        )
      },
    )
