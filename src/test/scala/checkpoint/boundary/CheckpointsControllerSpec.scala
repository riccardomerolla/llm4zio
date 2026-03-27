package checkpoint.boundary

import java.nio.file.Paths
import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import checkpoint.control.*
import issues.entity.IssueWorkReport
import shared.ids.Ids.IssueId
import taskrun.entity.*

object CheckpointsControllerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-26T10:00:00Z")

  private val checkpoint = CheckpointSnapshot(
    checkpoint = Checkpoint("run-1", "analysis", now, Map.empty, "sum"),
    state = TaskState(
      runId = "run-1",
      startedAt = now.minusSeconds(60),
      currentStep = "Analysis",
      completedSteps = Set.empty,
      artifacts = Map("tests.output" -> "2 passed"),
      errors = Nil,
      config = _root_.config.entity.MigrationConfig(Paths.get("src"), Paths.get("out")),
      workspace = None,
      status = TaskStatus.Running,
      lastCheckpoint = now,
      taskRunId = Some("run-1"),
      currentStepName = Some("Analysis"),
    ),
  )

  private val summary = CheckpointRunSummary(
    runId = "run-1",
    agentName = "codex",
    stage = "EXEC",
    currentStepLabel = "Analysis",
    conversationId = Some("101"),
    workspaceId = Some("ws-1"),
    issueId = Some("issue-1"),
    checkpointCount = 1,
    lastCheckpointAt = Some(now),
    statusMessage = Some("Running"),
  )

  private val review = CheckpointRunReview(
    summary = summary,
    workspaceRun = None,
    issue = None,
    checkpoints = List(checkpoint),
    selected = Some(
      CheckpointSnapshotReview(
        snapshot = checkpoint,
        gitDiff = Some("diff --git"),
        testSignals = List(CheckpointTextEvidence("tests", "2 passed")),
        conversationExcerpt = Nil,
        proofOfWork = Some(IssueWorkReport.empty(IssueId("issue-1"), now)),
      )
    ),
    comparison = Some(CheckpointComparison("discovery", "analysis", true, Nil, Nil, Nil, Nil, Nil)),
  )

  final private class StubService(ref: Ref[List[String]]) extends CheckpointReviewService:
    override def listActiveRuns: IO[CheckpointReviewError, List[CheckpointRunSummary]] =
      ZIO.succeed(List(summary))

    override def getRunReview(
      runId: String,
      selectedStep: Option[String],
      compareBase: Option[String],
      compareTarget: Option[String],
    ): IO[CheckpointReviewError, CheckpointRunReview] =
      ZIO.succeed(review)

    override def getSnapshotReview(
      runId: String,
      stepName: String,
    ): IO[CheckpointReviewError, Option[CheckpointSnapshotReview]] =
      ZIO.succeed(review.selected)

    override def compare(
      runId: String,
      leftStep: String,
      rightStep: String,
    ): IO[CheckpointReviewError, Option[CheckpointComparison]] =
      ZIO.succeed(review.comparison)

    override def act(
      runId: String,
      action: CheckpointOperatorAction,
      note: Option[String],
    ): IO[CheckpointReviewError, CheckpointActionResult] =
      ref.update(_ :+ s"${action.toString}:${note.getOrElse("")}").as(CheckpointActionResult(action, runId, "saved"))

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CheckpointsControllerSpec")(
      test("GET /checkpoints renders the checkpoint review page") {
        for
          ref      <- Ref.make(List.empty[String])
          response <-
            CheckpointsController.make(
              new StubService(ref)
            ).routes.runZIO(Request.get(URL(Path.decode("/checkpoints"))))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("Checkpoint Review"),
          body.contains("run-1"),
        )
      },
      test("GET /checkpoints/:runId renders the detail page") {
        for
          ref      <- Ref.make(List.empty[String])
          response <- CheckpointsController.make(
                        new StubService(ref)
                      ).routes.runZIO(Request.get(URL(Path.decode("/checkpoints/run-1"))))
          body     <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("Intervene"),
          body.contains("Compare Checkpoints"),
        )
      },
      test("POST /checkpoints/:runId/actions/:action redirects back to detail page") {
        for
          ref      <- Ref.make(List.empty[String])
          request   = Request(
                        method = Method.POST,
                        url = URL(Path.decode("/checkpoints/run-1/actions/redirect")),
                        body = Body.fromString("note=please+adjust"),
                      )
          response <- CheckpointsController.make(new StubService(ref)).routes.runZIO(request)
          calls    <- ref.get
        yield assertTrue(
          response.status == Status.SeeOther,
          response.headers.header(Header.Location).exists(_.renderedValue.contains("/checkpoints/run-1?flash=")),
          calls.exists(_.contains("please adjust")),
        )
      },
    )
