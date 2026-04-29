package canvas.entity

import java.time.Instant

import zio.test.*

import shared.ids.Ids.{ CanvasId, TestScenarioDocId }

object TestScenarioDocSpec extends ZIOSpecDefault:

  private val now      = Instant.parse("2026-04-29T13:00:00Z")
  private val docId    = TestScenarioDocId("tsd-1")
  private val canvasId = CanvasId("canvas-1")

  private val withinQuota = TestScenario(
    id = "scenario-001",
    name = "within-quota",
    kind = TestScenarioKind.Normal,
    operationId = "op-001",
    acRefs = List("AC1"),
    given_ = "Standard customer with 100K quota, 90K used",
    when_ = "30,000 fast-model tokens submitted",
    then_ = "10,000 from quota, 20,000 overage, $0.20 charge",
  )

  private val exactBoundary = TestScenario(
    id = "scenario-002",
    name = "exact-quota",
    kind = TestScenarioKind.Boundary,
    operationId = "op-001",
    acRefs = List("AC2"),
    given_ = "Standard customer with 100K quota, 99,999 used",
    when_ = "1 fast-model token submitted",
    then_ = "1 from quota, 0 overage, $0.00 charge",
  )

  private val unknownModel = TestScenario(
    id = "scenario-003",
    name = "unknown-model",
    kind = TestScenarioKind.Error,
    operationId = "op-001",
    acRefs = List("AC3"),
    given_ = "Standard customer with quota remaining",
    when_ = "weird-model token submitted",
    then_ = "HTTP 400 with code UNKNOWN_MODEL, no ledger row",
  )

  private def created(scenarios: List[TestScenario] = List(withinQuota), path: Option[String] = None) =
    TestScenarioEvent.Created(
      testScenarioDocId = docId,
      canvasId = canvasId,
      scenarios = scenarios,
      generatedScriptPath = path,
      occurredAt = now,
    )

  private def passedRun(at: Instant): TestScenarioRunResult =
    TestScenarioRunResult(
      runAt = at,
      totalScenarios = 3,
      passed = 3,
      failed = 0,
      skipped = 0,
      outcome = TestScenarioRunOutcome.Passed,
      notes = None,
    )

  private def failedRun(at: Instant): TestScenarioRunResult =
    TestScenarioRunResult(
      runAt = at,
      totalScenarios = 3,
      passed = 2,
      failed = 1,
      skipped = 0,
      outcome = TestScenarioRunOutcome.Failed,
      notes = Some("scenario-001: charge mismatch"),
    )

  def spec: Spec[Any, Nothing] =
    suite("TestScenarioDoc")(
      test("fromEvents on empty stream returns Left") {
        assertTrue(TestScenarioDoc.fromEvents(Nil).isLeft)
      },
      test("Created produces a doc with the supplied scenarios and no run history") {
        val doc = TestScenarioDoc.fromEvents(List(created(List(withinQuota, exactBoundary, unknownModel))))
        assertTrue(
          doc.exists(_.id == docId),
          doc.exists(_.canvasId == canvasId),
          doc.exists(_.scenarios.map(_.id) == List("scenario-001", "scenario-002", "scenario-003")),
          doc.exists(_.history.isEmpty),
          doc.exists(_.lastResult.isEmpty),
        )
      },
      test("ScenarioAdded appends to scenarios in order") {
        val doc = TestScenarioDoc.fromEvents(
          List(
            created(List(withinQuota)),
            TestScenarioEvent.ScenarioAdded(docId, exactBoundary, now.plusSeconds(10)),
            TestScenarioEvent.ScenarioAdded(docId, unknownModel, now.plusSeconds(20)),
          )
        )
        assertTrue(
          doc.exists(_.scenarios.size == 3),
          doc.exists(_.scenarios.map(_.id) == List("scenario-001", "scenario-002", "scenario-003")),
          doc.exists(_.scenarios.map(_.kind) ==
            List(TestScenarioKind.Normal, TestScenarioKind.Boundary, TestScenarioKind.Error)),
        )
      },
      test("ScenarioAdded rejects duplicate scenario id") {
        val doc = TestScenarioDoc.fromEvents(
          List(
            created(List(withinQuota)),
            TestScenarioEvent.ScenarioAdded(docId, withinQuota.copy(name = "another"), now.plusSeconds(10)),
          )
        )
        assertTrue(doc.left.exists(_.contains("scenario-001")))
      },
      test("RunRecorded appends to history; lastResult returns the most recent") {
        val firstRun  = passedRun(now.plusSeconds(60))
        val secondRun = failedRun(now.plusSeconds(120))
        val doc       = TestScenarioDoc.fromEvents(
          List(
            created(),
            TestScenarioEvent.RunRecorded(docId, firstRun, now.plusSeconds(60)),
            TestScenarioEvent.RunRecorded(docId, secondRun, now.plusSeconds(120)),
          )
        )
        assertTrue(
          doc.exists(_.history == List(firstRun, secondRun)),
          doc.exists(_.lastResult.contains(secondRun)),
          doc.exists(_.lastResult.exists(_.outcome == TestScenarioRunOutcome.Failed)),
        )
      },
      test("ScriptPathUpdated overrides the generated script path") {
        val doc = TestScenarioDoc.fromEvents(
          List(
            created(path = Some("scripts/spdd/test-old.sh")),
            TestScenarioEvent.ScriptPathUpdated(docId, "scripts/spdd/test-canvas-1.sh", now.plusSeconds(30)),
          )
        )
        assertTrue(doc.exists(_.generatedScriptPath.contains("scripts/spdd/test-canvas-1.sh")))
      },
      test("RunRecorded before Created fails with descriptive error") {
        val doc = TestScenarioDoc.fromEvents(
          List(TestScenarioEvent.RunRecorded(docId, passedRun(now), now))
        )
        assertTrue(doc.left.exists(_.contains("not initialized")))
      },
      test("Created applied twice fails (re-initialization is illegal)") {
        val doc = TestScenarioDoc.fromEvents(List(created(), created()))
        assertTrue(doc.left.exists(_.contains("already initialized")))
      },
      test("a doc with three scenarios + two runs ends up self-consistent") {
        val firstRun  = passedRun(now.plusSeconds(60))
        val secondRun = failedRun(now.plusSeconds(120))
        val doc       = TestScenarioDoc.fromEvents(
          List(
            created(List(withinQuota, exactBoundary, unknownModel), Some("scripts/spdd/test-canvas-1.sh")),
            TestScenarioEvent.RunRecorded(docId, firstRun, now.plusSeconds(60)),
            TestScenarioEvent.RunRecorded(docId, secondRun, now.plusSeconds(120)),
          )
        )
        assertTrue(
          doc.exists(_.scenarios.size == 3),
          doc.exists(_.history.size == 2),
          doc.exists(_.generatedScriptPath.contains("scripts/spdd/test-canvas-1.sh")),
          doc.exists(_.updatedAt == now.plusSeconds(120)),
          doc.exists(_.canvasId == canvasId),
        )
      },
    )
