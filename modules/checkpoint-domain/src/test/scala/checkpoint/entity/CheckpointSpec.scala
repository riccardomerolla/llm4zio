package checkpoint.entity

import java.time.Instant

import zio.test.*

import shared.ids.Ids.{ CanvasId, CheckpointId, TaskRunId }

object CheckpointSpec extends ZIOSpecDefault:

  private val now      = Instant.parse("2026-04-29T12:00:00Z")
  private val cpId     = CheckpointId("cp-1")
  private val runId    = TaskRunId("run-1")
  private val canvasId = CanvasId("canvas-1")

  private def created(kind: CheckpointKind = CheckpointKind.CanvasComplete): CheckpointEvent.Created =
    CheckpointEvent.Created(
      checkpointId = cpId,
      runId = runId,
      canvasId = Some(canvasId),
      kind = kind,
      occurredAt = now,
    )

  def spec: Spec[Any, Nothing] =
    suite("Checkpoint")(
      test("fromEvents on empty stream returns Left") {
        assertTrue(Checkpoint.fromEvents(Nil).isLeft)
      },
      test("Created produces a Pending checkpoint") {
        val rebuilt = Checkpoint.fromEvents(List(created()))
        assertTrue(
          rebuilt.exists(_.id == cpId),
          rebuilt.exists(_.runId == runId),
          rebuilt.exists(_.canvasId.contains(canvasId)),
          rebuilt.exists(_.status == CheckpointStatus.Pending),
          rebuilt.exists(_.kind == CheckpointKind.CanvasComplete),
          rebuilt.exists(_.findings.isEmpty),
          rebuilt.exists(_.startedAt.isEmpty),
        )
      },
      test("Started transitions Pending -> Running and stamps startedAt") {
        val rebuilt = Checkpoint.fromEvents(
          List(
            created(),
            CheckpointEvent.Started(cpId, now.plusSeconds(5)),
          )
        )
        assertTrue(
          rebuilt.exists(_.status == CheckpointStatus.Running),
          rebuilt.exists(_.startedAt.contains(now.plusSeconds(5))),
          rebuilt.exists(_.completedAt.isEmpty),
        )
      },
      test("Passed transitions Running -> Passed with evidence and stamps completedAt") {
        val rebuilt = Checkpoint.fromEvents(
          List(
            created(),
            CheckpointEvent.Started(cpId, now.plusSeconds(5)),
            CheckpointEvent.Passed(cpId, evidence = Some("all 7 sections present"), occurredAt = now.plusSeconds(10)),
          )
        )
        assertTrue(
          rebuilt.exists(_.status == CheckpointStatus.Passed),
          rebuilt.exists(_.evidence.contains("all 7 sections present")),
          rebuilt.exists(_.completedAt.contains(now.plusSeconds(10))),
        )
      },
      test("Failed records findings and transitions Running -> Failed") {
        val findings = List(
          CheckpointFinding("CANVAS-OPS-EMPTY", "Operations section is empty", CheckpointFindingSeverity.Error),
          CheckpointFinding("CANVAS-N-WEAK", "Norms profile not linked", CheckpointFindingSeverity.Warning),
        )
        val rebuilt = Checkpoint.fromEvents(
          List(
            created(),
            CheckpointEvent.Started(cpId, now.plusSeconds(5)),
            CheckpointEvent.Failed(cpId, evidence = Some("canvas review failed"), findings = findings, occurredAt = now.plusSeconds(10)),
          )
        )
        assertTrue(
          rebuilt.exists(_.status == CheckpointStatus.Failed),
          rebuilt.exists(_.findings == findings),
          rebuilt.exists(_.evidence.contains("canvas review failed")),
        )
      },
      test("Passed directly from Pending is allowed (synchronous gate)") {
        val rebuilt = Checkpoint.fromEvents(
          List(
            created(CheckpointKind.GwtValidated),
            CheckpointEvent.Passed(cpId, evidence = Some("all GWT clauses parsed"), occurredAt = now.plusSeconds(1)),
          )
        )
        assertTrue(rebuilt.exists(_.status == CheckpointStatus.Passed))
      },
      test("Skipped records the reason as evidence") {
        val rebuilt = Checkpoint.fromEvents(
          List(
            created(CheckpointKind.UnitTestsPassed),
            CheckpointEvent.Skipped(cpId, reason = "no behaviour change in this PR", occurredAt = now.plusSeconds(2)),
          )
        )
        assertTrue(
          rebuilt.exists(_.status == CheckpointStatus.Skipped),
          rebuilt.exists(_.evidence.exists(_.contains("no behaviour change"))),
          rebuilt.exists(_.completedAt.isDefined),
        )
      },
      test("Started before Created fails with descriptive error") {
        val rebuilt = Checkpoint.fromEvents(List(CheckpointEvent.Started(cpId, now)))
        assertTrue(rebuilt.left.exists(_.contains("not initialized")))
      },
      test("Passed after already Passed is rejected (idempotency boundary)") {
        val rebuilt = Checkpoint.fromEvents(
          List(
            created(),
            CheckpointEvent.Passed(cpId, None, now.plusSeconds(1)),
            CheckpointEvent.Passed(cpId, None, now.plusSeconds(2)),
          )
        )
        assertTrue(rebuilt.left.exists(_.contains("Passed -> Passed")))
      },
      test("Failed after Passed is rejected (no zombie failure)") {
        val rebuilt = Checkpoint.fromEvents(
          List(
            created(),
            CheckpointEvent.Passed(cpId, None, now.plusSeconds(1)),
            CheckpointEvent.Failed(cpId, None, Nil, now.plusSeconds(2)),
          )
        )
        assertTrue(rebuilt.left.exists(_.contains("Passed -> Failed")))
      },
    )
