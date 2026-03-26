package shared.web

import java.time.Instant

import zio.test.*

import checkpoint.control.*
import issues.entity.*
import shared.ids.Ids.*
import taskrun.entity.*

object CheckpointsViewSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T10:00:00Z")

  private val summary = CheckpointRunSummary(
    runId = "run-1",
    agentName = "codex",
    stage = "EXEC",
    currentStepLabel = "Analysis",
    conversationId = Some("101"),
    workspaceId = Some("ws-1"),
    issueId = Some("issue-1"),
    checkpointCount = 2,
    lastCheckpointAt = Some(now),
    statusMessage = Some("Running analysis"),
  )

  private val snapshot = CheckpointSnapshot(
    checkpoint = Checkpoint("run-1", "analysis", now, Map.empty, "sum"),
    state = TaskState(
      runId = "run-1",
      startedAt = now.minusSeconds(60),
      currentStep = "Analysis",
      completedSteps = Set("Discovery"),
      artifacts = Map("tests.output" -> "2 passed"),
      errors = Nil,
      config = _root_.config.entity.MigrationConfig(java.nio.file.Paths.get("src"), java.nio.file.Paths.get("out")),
      workspace = None,
      status = TaskStatus.Running,
      lastCheckpoint = now,
      taskRunId = Some("run-1"),
      currentStepName = Some("Analysis"),
    ),
  )

  private val review = CheckpointRunReview(
    summary = summary,
    workspaceRun = None,
    issue = None,
    checkpoints = List(snapshot),
    selected = Some(
      CheckpointSnapshotReview(
        snapshot = snapshot,
        gitDiff = Some("diff --git a/a.scala b/a.scala"),
        testSignals = List(CheckpointTextEvidence("tests", "2 passed")),
        conversationExcerpt = List(CheckpointConversationExcerpt("user", "user", "Please continue", now)),
        proofOfWork = Some(IssueWorkReport.empty(IssueId("issue-1"), now).copy(agentSummary = Some("Still running"))),
      )
    ),
    comparison = Some(
      CheckpointComparison("discovery", "analysis", true, List("Analysis"), Nil, Nil, List("warning"), Nil)
    ),
  )

  def spec: Spec[Any, Nothing] =
    suite("CheckpointsViewSpec")(
      test("page renders active checkpoint cards") {
        val html = CheckpointsView.page(List(summary))
        assertTrue(
          html.contains("Checkpoint Review"),
          html.contains("run-1"),
          html.contains("/checkpoints/run-1"),
        )
      },
      test("detail page renders intervention and comparison sections") {
        val html =
          CheckpointsView.detailPage(review, Some("analysis"), Some("discovery"), Some("analysis"), Some("saved"))
        assertTrue(
          html.contains("Intervene"),
          html.contains("Approve / Continue"),
          html.contains("Compare Checkpoints"),
          html.contains("saved"),
        )
      },
      test("snapshot fragment renders diff, tests, and conversation excerpt") {
        val html = CheckpointsView.snapshotFragment("run-1", review.selected.get)
        assertTrue(
          html.contains("Git Diff"),
          html.contains("diff --git"),
          html.contains("2 passed"),
          html.contains("Conversation Excerpt"),
          html.contains("Please continue"),
        )
      },
    )
