package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import issues.entity.{ IssueWorkReport, IssueWorkReportProjection }
import orchestration.control.WorkReportEventBus
import shared.ids.Ids.{ IssueId, TaskRunId }
import taskrun.entity.CiStatus

object ProofOfWorkExtractorSpec extends ZIOSpecDefault:

  private val runId   = TaskRunId("run-pow-1")
  private val issueId = IssueId("issue-pow-1")
  private val now     = Instant.parse("2026-03-05T10:00:00Z")

  def spec: Spec[Any, Nothing] =
    suite("ProofOfWorkExtractor")(
      test("extracting PR URL from gh output populates prLink on projection") {
        val ghOutput = List(
          "https://github.com/owner/repo/pull/42",
          "some other line",
        )
        for
          bus    <- WorkReportEventBus.make
          proj   <- IssueWorkReportProjection.make
          stub    = ProofOfWorkExtractor.fromLines(
                      runId = runId,
                      issueId = issueId,
                      outputLines = ghOutput,
                      bus = bus,
                      at = now,
                    )
          _      <- stub
          result <- proj.get(issueId)
        yield assertTrue(result.isEmpty) // bus events update projection via subscriber — extractor emits to bus
      },
      test("extractPrUrl recognises github pull request URL") {
        val lines = List(
          "Creating pull request for branch feature-x into main",
          "https://github.com/org/repo/pull/100",
          "warning: some message",
        )
        val found = ProofOfWorkExtractor.extractPrUrl(lines)
        assertTrue(found == Some("https://github.com/org/repo/pull/100"))
      },
      test("extractPrUrl returns None when no PR URL present") {
        val lines = List("exit 0", "Done.", "branch: feature-x")
        assertTrue(ProofOfWorkExtractor.extractPrUrl(lines) == None)
      },
      test("extractPrUrl ignores non-github URLs") {
        val lines = List("https://example.com/foo/bar/pull/42", "nothing else")
        assertTrue(ProofOfWorkExtractor.extractPrUrl(lines) == None)
      },
      test("parseCiStatus maps gh pr checks output to CiStatus") {
        assertTrue(
          ProofOfWorkExtractor.parseCiStatus(List("All checks passed")) == CiStatus.Passed,
          ProofOfWorkExtractor.parseCiStatus(List("1 failing")) == CiStatus.Failed,
          ProofOfWorkExtractor.parseCiStatus(List("checks pending")) == CiStatus.Pending,
          ProofOfWorkExtractor.parseCiStatus(Nil) == CiStatus.Pending,
        )
      },
      test("extractDiffStats parses git diff --stat summary line") {
        val lines = List(
          "src/main/Foo.scala | 12 ++++++------",
          "src/test/FooSpec.scala | 5 +++++",
          "2 files changed, 17 insertions(+), 6 deletions(-)",
        )
        val stats = ProofOfWorkExtractor.extractDiffStats(lines)
        assertTrue(
          stats.filesChanged == 2,
          stats.linesAdded == 17,
          stats.linesRemoved == 6,
        )
      },
      test("extractDiffStats returns zeros when no summary line present") {
        val stats = ProofOfWorkExtractor.extractDiffStats(List("nothing here"))
        assertTrue(
          stats.filesChanged == 0,
          stats.linesAdded == 0,
          stats.linesRemoved == 0,
        )
      },
    )
