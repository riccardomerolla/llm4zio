package taskrun.entity

import java.time.Instant

import zio.test.*

import shared.ids.Ids.*

object TaskRunFoldSpec extends ZIOSpecDefault:

  private val runId      = TaskRunId("run-fold-1")
  private val workflowId = WorkflowId("wf-fold-1")
  private val now        = Instant.parse("2026-03-05T10:00:00Z")

  private def baseEvents: List[TaskRunEvent] = List(
    TaskRunEvent.Created(runId, workflowId, "coder", "test", now),
    TaskRunEvent.Started(runId, "coding", now.plusSeconds(1)),
  )

  def spec: Spec[Any, Nothing] =
    suite("TaskRun fold — proof-of-work events")(
      suite("WalkthroughGenerated")(
        test("sets walkthrough on run") {
          val events = baseEvents :+ TaskRunEvent.WalkthroughGenerated(
            runId,
            "Refactored auth middleware to validate JWT expiry.",
            now.plusSeconds(10),
          )
          val run    = TaskRun.fromEvents(events).toOption.get
          assertTrue(run.walkthrough == Some("Refactored auth middleware to validate JWT expiry."))
        },
        test("overwrites previous walkthrough") {
          val events = baseEvents ++ List(
            TaskRunEvent.WalkthroughGenerated(runId, "First summary.", now.plusSeconds(10)),
            TaskRunEvent.WalkthroughGenerated(runId, "Updated summary.", now.plusSeconds(20)),
          )
          val run    = TaskRun.fromEvents(events).toOption.get
          assertTrue(run.walkthrough == Some("Updated summary."))
        },
      ),
      suite("PrLinked")(
        test("sets PR link and status on run") {
          val events = baseEvents :+ TaskRunEvent.PrLinked(
            runId,
            "https://github.com/owner/repo/pull/42",
            PrStatus.Open,
            now.plusSeconds(10),
          )
          val run    = TaskRun.fromEvents(events).toOption.get
          assertTrue(
            run.prLink == Some("https://github.com/owner/repo/pull/42"),
            run.prStatus == Some(PrStatus.Open),
          )
        },
        test("updates PR status when re-linked") {
          val events = baseEvents ++ List(
            TaskRunEvent.PrLinked(runId, "https://github.com/owner/repo/pull/42", PrStatus.Open, now.plusSeconds(10)),
            TaskRunEvent.PrLinked(runId, "https://github.com/owner/repo/pull/42", PrStatus.Merged, now.plusSeconds(20)),
          )
          val run    = TaskRun.fromEvents(events).toOption.get
          assertTrue(run.prStatus == Some(PrStatus.Merged))
        },
      ),
      suite("CiStatusUpdated")(
        test("sets CI status on run") {
          val events = baseEvents :+ TaskRunEvent.CiStatusUpdated(runId, CiStatus.Passed, now.plusSeconds(10))
          val run    = TaskRun.fromEvents(events).toOption.get
          assertTrue(run.ciStatus == Some(CiStatus.Passed))
        },
        test("updates CI status on subsequent events") {
          val events = baseEvents ++ List(
            TaskRunEvent.CiStatusUpdated(runId, CiStatus.Pending, now.plusSeconds(5)),
            TaskRunEvent.CiStatusUpdated(runId, CiStatus.Running, now.plusSeconds(10)),
            TaskRunEvent.CiStatusUpdated(runId, CiStatus.Passed, now.plusSeconds(20)),
          )
          val run    = TaskRun.fromEvents(events).toOption.get
          assertTrue(run.ciStatus == Some(CiStatus.Passed))
        },
      ),
      suite("TokenUsageRecorded")(
        test("sets token usage on run") {
          val events = baseEvents :+ TaskRunEvent.TokenUsageRecorded(
            runId,
            inputTokens = 8000L,
            outputTokens = 4200L,
            runtimeSeconds = 45L,
            occurredAt = now.plusSeconds(10),
          )
          val run    = TaskRun.fromEvents(events).toOption.get
          assertTrue(
            run.tokenUsage == Some(TokenUsage(inputTokens = 8000L, outputTokens = 4200L, totalTokens = 12200L)),
            run.runtimeSeconds == Some(45L),
          )
        }
      ),
      suite("PrStatus ADT")(
        test("all variants are distinct") {
          assertTrue(
            PrStatus.Open != PrStatus.Merged,
            PrStatus.Merged != PrStatus.Closed,
            PrStatus.Closed != PrStatus.Draft,
          )
        }
      ),
      suite("CiStatus ADT")(
        test("all variants are distinct") {
          assertTrue(
            CiStatus.Pending != CiStatus.Running,
            CiStatus.Running != CiStatus.Passed,
            CiStatus.Passed != CiStatus.Failed,
          )
        }
      ),
      suite("new events on un-initialized run")(
        test("WalkthroughGenerated on empty stream returns error") {
          val result = TaskRun.fromEvents(List(
            TaskRunEvent.WalkthroughGenerated(runId, "summary", now)
          ))
          assertTrue(result.isLeft)
        },
        test("PrLinked on empty stream returns error") {
          val result = TaskRun.fromEvents(List(
            TaskRunEvent.PrLinked(runId, "https://example.com/pr/1", PrStatus.Open, now)
          ))
          assertTrue(result.isLeft)
        },
        test("CiStatusUpdated on empty stream returns error") {
          val result = TaskRun.fromEvents(List(
            TaskRunEvent.CiStatusUpdated(runId, CiStatus.Passed, now)
          ))
          assertTrue(result.isLeft)
        },
        test("TokenUsageRecorded on empty stream returns error") {
          val result = TaskRun.fromEvents(List(
            TaskRunEvent.TokenUsageRecorded(runId, 100L, 50L, 10L, now)
          ))
          assertTrue(result.isLeft)
        },
      ),
    )
