package issues.entity

import java.time.Instant

import zio.*
import zio.test.*

import orchestration.entity.DiffStats
import shared.ids.Ids.{ ArtifactId, IssueId, ReportId }
import taskrun.entity.{ CiStatus, PrStatus, TaskArtifact, TaskReport, TokenUsage }

object IssueWorkReportProjectionSpec extends ZIOSpecDefault:

  private val issueId  = IssueId("issue-proj-1")
  private val issueId2 = IssueId("issue-proj-2")
  private val now      = Instant.parse("2026-03-05T10:00:00Z")

  private def freshProjection: UIO[IssueWorkReportProjection] = IssueWorkReportProjection.make

  def spec: Spec[Any, Nothing] =
    suite("IssueWorkReportProjection")(
      test("get returns None for unknown issue") {
        for
          proj   <- freshProjection
          result <- proj.get(issueId)
        yield assertTrue(result.isEmpty)
      },
      test("getAll returns empty map initially") {
        for
          proj   <- freshProjection
          result <- proj.getAll
        yield assertTrue(result.isEmpty)
      },
      test("updateWalkthrough creates entry and sets walkthrough") {
        for
          proj   <- freshProjection
          _      <- proj.updateWalkthrough(issueId, "Auth middleware refactored.", now)
          result <- proj.get(issueId)
        yield assertTrue(
          result.isDefined,
          result.get.walkthrough == Some("Auth middleware refactored."),
          result.get.issueId == issueId,
        )
      },
      test("updateWalkthrough overwrites previous value") {
        for
          proj   <- freshProjection
          _      <- proj.updateWalkthrough(issueId, "First.", now)
          _      <- proj.updateWalkthrough(issueId, "Second.", now.plusSeconds(5))
          result <- proj.get(issueId)
        yield assertTrue(result.get.walkthrough == Some("Second."))
      },
      test("updateAgentSummary creates entry and sets agent summary") {
        for
          proj   <- freshProjection
          _      <- proj.updateAgentSummary(issueId, "3 turns, 2 file edits.", now)
          result <- proj.get(issueId)
        yield assertTrue(result.get.agentSummary == Some("3 turns, 2 file edits."))
      },
      test("updateDiffStats creates entry and sets diff stats") {
        val stats = DiffStats(filesChanged = 4, linesAdded = 87, linesRemoved = 23)
        for
          proj   <- freshProjection
          _      <- proj.updateDiffStats(issueId, stats, now)
          result <- proj.get(issueId)
        yield assertTrue(result.get.diffStats == Some(stats))
      },
      test("updatePrLink creates entry and sets PR link and status") {
        for
          proj   <- freshProjection
          _      <- proj.updatePrLink(issueId, "https://github.com/owner/repo/pull/42", PrStatus.Open, now)
          result <- proj.get(issueId)
        yield assertTrue(
          result.get.prLink == Some("https://github.com/owner/repo/pull/42"),
          result.get.prStatus == Some(PrStatus.Open),
        )
      },
      test("updatePrLink updates status on second call") {
        for
          proj   <- freshProjection
          _      <- proj.updatePrLink(issueId, "https://github.com/owner/repo/pull/42", PrStatus.Open, now)
          _      <- proj.updatePrLink(issueId, "https://github.com/owner/repo/pull/42", PrStatus.Merged, now.plusSeconds(60))
          result <- proj.get(issueId)
        yield assertTrue(result.get.prStatus == Some(PrStatus.Merged))
      },
      test("updateCiStatus creates entry and sets CI status") {
        for
          proj   <- freshProjection
          _      <- proj.updateCiStatus(issueId, CiStatus.Passed, now)
          result <- proj.get(issueId)
        yield assertTrue(result.get.ciStatus == Some(CiStatus.Passed))
      },
      test("updateCiStatus updates on subsequent calls") {
        for
          proj   <- freshProjection
          _      <- proj.updateCiStatus(issueId, CiStatus.Pending, now)
          _      <- proj.updateCiStatus(issueId, CiStatus.Running, now.plusSeconds(10))
          _      <- proj.updateCiStatus(issueId, CiStatus.Failed, now.plusSeconds(60))
          result <- proj.get(issueId)
        yield assertTrue(result.get.ciStatus == Some(CiStatus.Failed))
      },
      test("updateTokenUsage creates entry and sets token usage and runtime") {
        val usage = TokenUsage(inputTokens = 8000L, outputTokens = 4200L, totalTokens = 12200L)
        for
          proj   <- freshProjection
          _      <- proj.updateTokenUsage(issueId, usage, runtimeSeconds = 45L, now)
          result <- proj.get(issueId)
        yield assertTrue(
          result.get.tokenUsage == Some(usage),
          result.get.runtimeSeconds == Some(45L),
        )
      },
      test("addReport appends to reports list") {
        val report = TaskReport(ReportId("r1"), "analysis", "summary", "ok", now)
        for
          proj   <- freshProjection
          _      <- proj.addReport(issueId, report, now)
          result <- proj.get(issueId)
        yield assertTrue(result.get.reports == List(report))
      },
      test("addReport accumulates multiple reports") {
        val r1 = TaskReport(ReportId("r1"), "analysis", "summary", "ok", now)
        val r2 = TaskReport(ReportId("r2"), "transform", "detail", "done", now.plusSeconds(5))
        for
          proj   <- freshProjection
          _      <- proj.addReport(issueId, r1, now)
          _      <- proj.addReport(issueId, r2, now.plusSeconds(5))
          result <- proj.get(issueId)
        yield assertTrue(result.get.reports == List(r1, r2))
      },
      test("addArtifact appends to artifacts list") {
        val artifact = TaskArtifact(ArtifactId("a1"), "build", "binary", "app.jar", now)
        for
          proj   <- freshProjection
          _      <- proj.addArtifact(issueId, artifact, now)
          result <- proj.get(issueId)
        yield assertTrue(result.get.artifacts == List(artifact))
      },
      test("multiple signals on same issue accumulate correctly") {
        val stats = DiffStats(2, 10, 5)
        for
          proj   <- freshProjection
          _      <- proj.updateWalkthrough(issueId, "Summary.", now)
          _      <- proj.updateDiffStats(issueId, stats, now.plusSeconds(1))
          _      <- proj.updatePrLink(issueId, "https://github.com/pr/1", PrStatus.Open, now.plusSeconds(2))
          _      <- proj.updateCiStatus(issueId, CiStatus.Passed, now.plusSeconds(3))
          result <- proj.get(issueId)
        yield assertTrue(
          result.get.walkthrough == Some("Summary."),
          result.get.diffStats == Some(stats),
          result.get.prLink == Some("https://github.com/pr/1"),
          result.get.ciStatus == Some(CiStatus.Passed),
        )
      },
      test("signals on different issues are independent") {
        for
          proj    <- freshProjection
          _       <- proj.updateWalkthrough(issueId, "Summary 1.", now)
          _       <- proj.updateWalkthrough(issueId2, "Summary 2.", now)
          result1 <- proj.get(issueId)
          result2 <- proj.get(issueId2)
        yield assertTrue(
          result1.get.walkthrough == Some("Summary 1."),
          result2.get.walkthrough == Some("Summary 2."),
        )
      },
      test("getAll returns all issues with their reports") {
        for
          proj   <- freshProjection
          _      <- proj.updateWalkthrough(issueId, "A", now)
          _      <- proj.updateWalkthrough(issueId2, "B", now)
          result <- proj.getAll
        yield assertTrue(result.size == 2)
      },
      test("lastUpdated is set on update") {
        for
          proj   <- freshProjection
          _      <- proj.updateWalkthrough(issueId, "Summary.", now)
          result <- proj.get(issueId)
        yield assertTrue(result.get.lastUpdated == now)
      },
      test("lastUpdated advances with each update") {
        val later = now.plusSeconds(30)
        for
          proj   <- freshProjection
          _      <- proj.updateWalkthrough(issueId, "Summary.", now)
          _      <- proj.updateCiStatus(issueId, CiStatus.Passed, later)
          result <- proj.get(issueId)
        yield assertTrue(result.get.lastUpdated == later)
      },
    )
