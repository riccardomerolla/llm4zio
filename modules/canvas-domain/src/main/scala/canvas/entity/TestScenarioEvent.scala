package canvas.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ CanvasId, TestScenarioDocId }

sealed trait TestScenarioEvent derives JsonCodec, Schema:
  def testScenarioDocId: TestScenarioDocId
  def occurredAt: Instant

object TestScenarioEvent:
  final case class Created(
    testScenarioDocId: TestScenarioDocId,
    canvasId: CanvasId,
    scenarios: List[TestScenario],
    generatedScriptPath: Option[String],
    occurredAt: Instant,
  ) extends TestScenarioEvent

  final case class ScenarioAdded(
    testScenarioDocId: TestScenarioDocId,
    scenario: TestScenario,
    occurredAt: Instant,
  ) extends TestScenarioEvent

  final case class RunRecorded(
    testScenarioDocId: TestScenarioDocId,
    result: TestScenarioRunResult,
    occurredAt: Instant,
  ) extends TestScenarioEvent

  final case class ScriptPathUpdated(
    testScenarioDocId: TestScenarioDocId,
    path: String,
    occurredAt: Instant,
  ) extends TestScenarioEvent
