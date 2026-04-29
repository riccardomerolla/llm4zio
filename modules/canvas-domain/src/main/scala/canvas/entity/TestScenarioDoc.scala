package canvas.entity

import java.time.Instant

import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import shared.ids.Ids.{ CanvasId, TestScenarioDocId }

/** SPDD API-test artefact derived from a REASONS Canvas.
  *
  * Each TestScenarioDoc owns the scenarios the gateway needs to verify a Canvas's Operations against the running
  * service: at minimum one normal + one error per Operation, with boundary scenarios for any numeric thresholds. The
  * doc is event-sourced so that re-running the script (RunRecorded) accumulates a result history without rewriting
  * the scenarios themselves.
  */

enum TestScenarioKind derives JsonCodec, Schema:
  case Normal
  case Boundary
  case Error

enum TestScenarioRunOutcome derives JsonCodec, Schema:
  case Passed
  case Failed
  case Skipped

final case class TestScenario(
  id: String,
  name: String,
  kind: TestScenarioKind,
  operationId: String,
  acRefs: List[String],
  given_ : String,
  when_ : String,
  then_ : String,
) derives JsonCodec,
    Schema

final case class TestScenarioRunResult(
  runAt: Instant,
  totalScenarios: Int,
  passed: Int,
  failed: Int,
  skipped: Int,
  outcome: TestScenarioRunOutcome,
  notes: Option[String],
) derives JsonCodec,
    Schema

final case class TestScenarioDoc(
  id: TestScenarioDocId,
  canvasId: CanvasId,
  scenarios: List[TestScenario],
  generatedScriptPath: Option[String],
  history: List[TestScenarioRunResult],
  createdAt: Instant,
  updatedAt: Instant,
) derives JsonCodec,
    Schema:
  def lastResult: Option[TestScenarioRunResult] = history.lastOption

object TestScenarioDoc:
  def fromEvents(events: List[TestScenarioEvent]): Either[String, TestScenarioDoc] =
    events match
      case Nil => Left("Cannot rebuild TestScenarioDoc from an empty event stream")
      case _   =>
        events
          .foldLeft[Either[String, Option[TestScenarioDoc]]](Right(None))((acc, e) => acc.flatMap(applyEvent(_, e)))
          .flatMap {
            case Some(doc) => Right(doc)
            case None      => Left("TestScenarioDoc event stream did not produce a state")
          }

  private def applyEvent(
    current: Option[TestScenarioDoc],
    event: TestScenarioEvent,
  ): Either[String, Option[TestScenarioDoc]] =
    event match
      case created: TestScenarioEvent.Created       =>
        current match
          case Some(_) => Left(s"TestScenarioDoc ${created.testScenarioDocId.value} already initialized")
          case None    =>
            Right(
              Some(
                TestScenarioDoc(
                  id = created.testScenarioDocId,
                  canvasId = created.canvasId,
                  scenarios = created.scenarios,
                  generatedScriptPath = created.generatedScriptPath,
                  history = Nil,
                  createdAt = created.occurredAt,
                  updatedAt = created.occurredAt,
                )
              )
            )

      case added: TestScenarioEvent.ScenarioAdded   =>
        current
          .toRight(s"TestScenarioDoc ${added.testScenarioDocId.value} not initialized before ScenarioAdded")
          .flatMap { doc =>
            if doc.scenarios.exists(_.id == added.scenario.id) then
              Left(s"TestScenarioDoc ${added.testScenarioDocId.value} already has scenario ${added.scenario.id}")
            else
              Right(
                Some(
                  doc.copy(
                    scenarios = doc.scenarios :+ added.scenario,
                    updatedAt = added.occurredAt,
                  )
                )
              )
          }

      case run: TestScenarioEvent.RunRecorded       =>
        current
          .toRight(s"TestScenarioDoc ${run.testScenarioDocId.value} not initialized before RunRecorded")
          .map(doc =>
            Some(
              doc.copy(
                history = doc.history :+ run.result,
                updatedAt = run.occurredAt,
              )
            )
          )

      case path: TestScenarioEvent.ScriptPathUpdated =>
        current
          .toRight(s"TestScenarioDoc ${path.testScenarioDocId.value} not initialized before ScriptPathUpdated")
          .map(doc =>
            Some(
              doc.copy(
                generatedScriptPath = Some(path.path),
                updatedAt = path.occurredAt,
              )
            )
          )
