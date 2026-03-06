package issues.entity

import java.time.Instant

import zio.*
import zio.test.*

import shared.ids.Ids.*
import taskrun.entity.*

object IssueWorkReportHydratorSpec extends ZIOSpecDefault:

  private val issueId    = IssueId("issue-hyd-1")
  private val issueId2   = IssueId("issue-hyd-2")
  private val runId      = TaskRunId("run-hyd-1")
  private val runId2     = TaskRunId("run-hyd-2")
  private val workflowId = WorkflowId("wf-hyd-1")
  private val agentId    = AgentId("agent-hyd-1")
  private val now        = Instant.parse("2026-03-05T10:00:00Z")

  private def makeIssue(id: IssueId, rId: Option[TaskRunId]): AgentIssue =
    AgentIssue(
      id = id,
      runId = rId,
      conversationId = None,
      title = "Test issue",
      description = "",
      issueType = "task",
      priority = "medium",
      requiredCapabilities = Nil,
      state = IssueState.InProgress(agentId, now),
      tags = Nil,
      contextPath = "",
      sourceFolder = "",
    )

  private def makeRun(
    id: TaskRunId,
    walkthrough: Option[String] = None,
    prLink: Option[String] = None,
    prStatus: Option[PrStatus] = None,
    ciStatus: Option[CiStatus] = None,
    tokenUsage: Option[TokenUsage] = None,
    runtimeSeconds: Option[Long] = None,
    reports: List[TaskReport] = Nil,
    artifacts: List[TaskArtifact] = Nil,
  ): TaskRun =
    TaskRun(
      id = id,
      workflowId = workflowId,
      state = TaskRunState.Completed(now, now.plusSeconds(10), "done"),
      agentName = agentId.value,
      source = "test",
      reports = reports,
      artifacts = artifacts,
      walkthrough = walkthrough,
      prLink = prLink,
      prStatus = prStatus,
      ciStatus = ciStatus,
      tokenUsage = tokenUsage,
      runtimeSeconds = runtimeSeconds,
    )

  def spec: Spec[Any, Nothing] =
    suite("IssueWorkReportHydrator")(
      test("hydrating empty state leaves projection empty") {
        for
          proj    <- IssueWorkReportProjection.make
          hydrator = IssueWorkReportHydrator(proj)
          _       <- hydrator.hydrate(issues = Nil, runs = Nil)
          all     <- proj.getAll
        yield assertTrue(all.isEmpty)
      },
      test("hydrating a run with walkthrough populates the projection") {
        val issue = makeIssue(issueId, Some(runId))
        val run   = makeRun(runId, walkthrough = Some("Auth refactored."))
        for
          proj    <- IssueWorkReportProjection.make
          hydrator = IssueWorkReportHydrator(proj)
          _       <- hydrator.hydrate(issues = List(issue), runs = List(run))
          result  <- proj.get(issueId)
        yield assertTrue(result.get.walkthrough == Some("Auth refactored."))
      },
      test("hydrating a run with all proof-of-work fields populates all signals") {
        val usage  = TokenUsage(8000L, 4200L, 12200L)
        val report = TaskReport(ReportId("r1"), "analysis", "summary", "ok", now)
        val issue  = makeIssue(issueId, Some(runId))
        val run    = makeRun(
          runId,
          walkthrough = Some("Summary."),
          prLink = Some("https://github.com/pr/42"),
          prStatus = Some(PrStatus.Open),
          ciStatus = Some(CiStatus.Passed),
          tokenUsage = Some(usage),
          runtimeSeconds = Some(45L),
          reports = List(report),
        )
        for
          proj    <- IssueWorkReportProjection.make
          hydrator = IssueWorkReportHydrator(proj)
          _       <- hydrator.hydrate(issues = List(issue), runs = List(run))
          result  <- proj.get(issueId)
        yield assertTrue(
          result.get.walkthrough == Some("Summary."),
          result.get.prLink == Some("https://github.com/pr/42"),
          result.get.prStatus == Some(PrStatus.Open),
          result.get.ciStatus == Some(CiStatus.Passed),
          result.get.tokenUsage == Some(usage),
          result.get.runtimeSeconds == Some(45L),
          result.get.reports == List(report),
        )
      },
      test("hydrating skips issues with no linked run") {
        val issue = makeIssue(issueId, None)
        for
          proj    <- IssueWorkReportProjection.make
          hydrator = IssueWorkReportHydrator(proj)
          _       <- hydrator.hydrate(issues = List(issue), runs = Nil)
          all     <- proj.getAll
        yield assertTrue(all.isEmpty)
      },
      test("hydrating skips issues whose runId has no matching run") {
        val issue = makeIssue(issueId, Some(runId))
        for
          proj    <- IssueWorkReportProjection.make
          hydrator = IssueWorkReportHydrator(proj)
          _       <- hydrator.hydrate(issues = List(issue), runs = Nil)
          all     <- proj.getAll
        yield assertTrue(all.isEmpty)
      },
      test("hydrating run with no proof-of-work fields creates no entry") {
        val issue = makeIssue(issueId, Some(runId))
        val run   = makeRun(runId) // all None
        for
          proj    <- IssueWorkReportProjection.make
          hydrator = IssueWorkReportHydrator(proj)
          _       <- hydrator.hydrate(issues = List(issue), runs = List(run))
          all     <- proj.getAll
        yield assertTrue(all.isEmpty)
      },
      test("hydrating multiple issues with different runs works correctly") {
        val issue1 = makeIssue(issueId, Some(runId))
        val issue2 = makeIssue(issueId2, Some(runId2))
        val run1   = makeRun(runId, walkthrough = Some("Summary 1."))
        val run2   = makeRun(runId2, prLink = Some("https://github.com/pr/43"), prStatus = Some(PrStatus.Merged))
        for
          proj    <- IssueWorkReportProjection.make
          hydrator = IssueWorkReportHydrator(proj)
          _       <- hydrator.hydrate(issues = List(issue1, issue2), runs = List(run1, run2))
          result1 <- proj.get(issueId)
          result2 <- proj.get(issueId2)
        yield assertTrue(
          result1.get.walkthrough == Some("Summary 1."),
          result2.get.prLink == Some("https://github.com/pr/43"),
          result2.get.prStatus == Some(PrStatus.Merged),
        )
      },
      test("hydrating sets agentSummary from issue state for InProgress issues") {
        val issue = makeIssue(issueId, Some(runId))
        val run   = makeRun(runId, walkthrough = Some("Summary."))
        for
          proj    <- IssueWorkReportProjection.make
          hydrator = IssueWorkReportHydrator(proj)
          _       <- hydrator.hydrate(issues = List(issue), runs = List(run))
          result  <- proj.get(issueId)
        yield assertTrue(result.get.agentSummary.isDefined)
      },
    )
