package issues.entity

import java.time.Instant

import zio.*
import zio.test.*

import orchestration.control.{ ParallelSessionEvent, WorkReportEventBus }
import orchestration.entity.DiffStats
import shared.ids.Ids.{ AgentId, ArtifactId, IssueId, ReportId, TaskRunId }
import taskrun.entity.*

object IssueWorkReportSubscriberSpec extends ZIOSpecDefault:

  private val issueId = IssueId("issue-sub-1")
  private val runId   = TaskRunId("run-sub-1")
  private val agentId = AgentId("agent-sub-1")
  private val now     = Instant.parse("2026-03-05T10:00:00Z")

  /** A stub IssueRepository that maps a single runId -> issueId for testing. */
  private def stubIssueRepo(runIdToIssue: Map[TaskRunId, IssueId]): IssueRepository =
    new IssueRepository:
      def append(event: IssueEvent): IO[shared.errors.PersistenceError, Unit] = ZIO.unit

      def get(id: IssueId): IO[shared.errors.PersistenceError, AgentIssue] =
        ZIO.fail(shared.errors.PersistenceError.NotFound("issue", id.value))

      def list(filter: IssueFilter): IO[shared.errors.PersistenceError, List[AgentIssue]] =
        // Return stubs for any issue whose runId is in our map
        val matching = runIdToIssue.collect {
          case (rId, iId) if filter.runId.contains(rId) =>
            AgentIssue(
              id = iId,
              runId = Some(rId),
              conversationId = None,
              title = "stub",
              description = "",
              issueType = "task",
              priority = "medium",
              requiredCapabilities = Nil,
              state = IssueState.Open(now),
              tags = Nil,
              contextPath = "",
              sourceFolder = "",
            )
        }.toList
        ZIO.succeed(matching)

      def delete(id: IssueId): IO[shared.errors.PersistenceError, Unit] = ZIO.unit

  private def setup(
    issueRepo: IssueRepository = stubIssueRepo(Map(runId -> issueId))
  ): UIO[(WorkReportEventBus, IssueWorkReportProjection, IssueWorkReportSubscriber)] =
    for
      bus        <- WorkReportEventBus.make
      projection <- IssueWorkReportProjection.make
      subscriber  = IssueWorkReportSubscriber(bus, projection, issueRepo)
    yield (bus, projection, subscriber)

  def spec: Spec[Any, Nothing] =
    suite("IssueWorkReportSubscriber")(
      test("TaskRunEvent.WalkthroughGenerated updates projection walkthrough") {
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup()
            _                       <- subscriber.start
            _                       <- bus.publishTaskRun(
                                         TaskRunEvent.WalkthroughGenerated(runId, "Auth refactored.", now)
                                       )
            _                       <- ZIO.sleep(50.millis)
            result                  <- proj.get(issueId)
          yield assertTrue(result.get.walkthrough == Some("Auth refactored."))
        }
      },
      test("TaskRunEvent.PrLinked updates projection PR link and status") {
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup()
            _                       <- subscriber.start
            _                       <- bus.publishTaskRun(
                                         TaskRunEvent.PrLinked(runId, "https://github.com/pr/42", PrStatus.Open, now)
                                       )
            _                       <- ZIO.sleep(50.millis)
            result                  <- proj.get(issueId)
          yield assertTrue(
            result.get.prLink == Some("https://github.com/pr/42"),
            result.get.prStatus == Some(PrStatus.Open),
          )
        }
      },
      test("TaskRunEvent.CiStatusUpdated updates projection CI status") {
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup()
            _                       <- subscriber.start
            _                       <- bus.publishTaskRun(TaskRunEvent.CiStatusUpdated(runId, CiStatus.Passed, now))
            _                       <- ZIO.sleep(50.millis)
            result                  <- proj.get(issueId)
          yield assertTrue(result.get.ciStatus == Some(CiStatus.Passed))
        }
      },
      test("TaskRunEvent.TokenUsageRecorded updates projection token usage") {
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup()
            _                       <- subscriber.start
            _                       <- bus.publishTaskRun(
                                         TaskRunEvent.TokenUsageRecorded(runId, 8000L, 4200L, 45L, now)
                                       )
            _                       <- ZIO.sleep(50.millis)
            result                  <- proj.get(issueId)
          yield assertTrue(
            result.get.tokenUsage == Some(TokenUsage(8000L, 4200L, 12200L)),
            result.get.runtimeSeconds == Some(45L),
          )
        }
      },
      test("TaskRunEvent.ReportAdded appends to projection reports") {
        val report = TaskReport(ReportId("r1"), "analysis", "summary", "ok", now)
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup()
            _                       <- subscriber.start
            _                       <- bus.publishTaskRun(TaskRunEvent.ReportAdded(runId, report, now))
            _                       <- ZIO.sleep(50.millis)
            result                  <- proj.get(issueId)
          yield assertTrue(result.get.reports == List(report))
        }
      },
      test("TaskRunEvent.ArtifactAdded appends to projection artifacts") {
        val artifact = TaskArtifact(ArtifactId("a1"), "build", "binary", "app.jar", now)
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup()
            _                       <- subscriber.start
            _                       <- bus.publishTaskRun(TaskRunEvent.ArtifactAdded(runId, artifact, now))
            _                       <- ZIO.sleep(50.millis)
            result                  <- proj.get(issueId)
          yield assertTrue(result.get.artifacts == List(artifact))
        }
      },
      test("IssueEvent.Assigned updates projection agent summary") {
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup()
            _                       <- subscriber.start
            _                       <- bus.publishIssue(IssueEvent.Assigned(issueId, agentId, now, now))
            _                       <- ZIO.sleep(50.millis)
            result                  <- proj.get(issueId)
          yield assertTrue(result.get.agentSummary.isDefined)
        }
      },
      test("IssueEvent.Completed updates projection agent summary") {
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup()
            _                       <- subscriber.start
            _                       <- bus.publishIssue(IssueEvent.Completed(issueId, agentId, now, "Done.", now))
            _                       <- ZIO.sleep(50.millis)
            result                  <- proj.get(issueId)
          yield assertTrue(result.get.agentSummary.isDefined)
        }
      },
      test("ParallelSessionEvent.WorktreeAgentCompleted updates projection diff stats and agent summary") {
        val sessionId         = "session-sub-1"
        val stats             = DiffStats(4, 87, 23)
        // For ParallelSessionEvent, the subscriber uses the correlationId as a lookup hint.
        // In this test we use a repo stub that matches on a special filter.
        val parallelIssueId   = IssueId("issue-parallel-1")
        val parallelIssueRepo = new IssueRepository:
          def append(event: IssueEvent): IO[shared.errors.PersistenceError, Unit] = ZIO.unit
          def get(id: IssueId): IO[shared.errors.PersistenceError, AgentIssue]    =
            ZIO.fail(shared.errors.PersistenceError.NotFound("issue", id.value))
          def list(filter: IssueFilter): IO[shared.errors.PersistenceError, List[AgentIssue]] =
            // Return the issue when listing by workspaceRunId hint (no filter match — return all for test)
            ZIO.succeed(List(
              AgentIssue(
                id = parallelIssueId,
                runId = None,
                conversationId = None,
                title = "stub",
                description = "",
                issueType = "task",
                priority = "medium",
                requiredCapabilities = Nil,
                state = IssueState.Open(now),
                tags = Nil,
                contextPath = "",
                sourceFolder = "",
              )
            ))
          def delete(id: IssueId): IO[shared.errors.PersistenceError, Unit]       = ZIO.unit

        ZIO.scoped {
          for
            bus        <- WorkReportEventBus.make
            projection <- IssueWorkReportProjection.make
            subscriber  = IssueWorkReportSubscriber(bus, projection, parallelIssueRepo)
            _          <- subscriber.start
            _          <- bus.publishParallelSession(
                            ParallelSessionEvent.WorktreeAgentCompleted(
                              sessionId = sessionId,
                              stepId = "step-1",
                              agentName = "coder",
                              branch = "feat/issue-parallel-1",
                              diffStats = stats,
                              summary = "Implemented the feature.",
                              occurredAt = now,
                            )
                          )
            _          <- ZIO.sleep(50.millis)
            result     <- projection.get(parallelIssueId)
          yield assertTrue(
            result.get.diffStats == Some(stats),
            result.get.agentSummary == Some("Implemented the feature."),
          )
        }
      },
      test("events for unknown runId are silently ignored") {
        val unknownRunId = TaskRunId("run-unknown")
        val emptyRepo    = stubIssueRepo(Map.empty)
        ZIO.scoped {
          for
            (bus, proj, subscriber) <- setup(emptyRepo)
            _                       <- subscriber.start
            _                       <- bus.publishTaskRun(
                                         TaskRunEvent.WalkthroughGenerated(unknownRunId, "ignored", now)
                                       )
            _                       <- ZIO.sleep(50.millis)
            all                     <- proj.getAll
          yield assertTrue(all.isEmpty)
        }
      },
    ) @@ TestAspect.withLiveClock
