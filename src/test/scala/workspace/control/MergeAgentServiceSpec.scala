package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import _root_.config.entity.ConfigRepository
import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import db.{ CustomAgentRow, SettingRow, WorkflowRow }
import issues.entity.*
import orchestration.control.WorkReportEventBus
import shared.errors.PersistenceError
import shared.ids.Ids.{ AnalysisDocId, EventId, IssueId, TaskRunId }
import taskrun.entity.{ CiStatus, TaskRunEvent }
import workspace.entity.*

object MergeAgentServiceSpec extends ZIOSpecDefault:

  private val now           = Instant.parse("2026-03-13T16:00:00Z")
  private val mergeConflict = GitError.CommandFailed("git merge --no-ff", "CONFLICT (content)")

  final private class StubIssueRepository(
    state: Ref[Map[IssueId, AgentIssue]],
    events: Ref[List[IssueEvent]],
  ) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit] =
      events.update(_ :+ event) *> state.modify { current =>
        val updated = event match
          case done: IssueEvent.MarkedDone                =>
            current.updatedWith(done.issueId)(_.map(_.copy(
              state = IssueState.Done(done.doneAt, done.result),
              mergeConflictFiles = Nil,
            )))
          case moved: IssueEvent.MovedToRework            =>
            current.updatedWith(moved.issueId)(_.map(_.copy(state = IssueState.Rework(moved.movedAt, moved.reason))))
          case moved: IssueEvent.MovedToMerging           =>
            current.updatedWith(moved.issueId)(_.map(_.copy(
              state = IssueState.Merging(moved.movedAt),
              mergeConflictFiles = Nil,
            )))
          case conflict: IssueEvent.MergeConflictRecorded =>
            current.updatedWith(conflict.issueId)(_.map(_.copy(mergeConflictFiles = conflict.conflictingFiles)))
          case failed: IssueEvent.MergeFailed             =>
            current.updatedWith(failed.issueId)(_.map(_.copy(mergeConflictFiles = failed.conflictFiles)))
          case _                                          => current
        ((), updated)
      }

    override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
      state.get.flatMap(map => ZIO.fromOption(map.get(id)).orElseFail(PersistenceError.NotFound("issue", id.value)))

    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] = ZIO.succeed(Nil)

    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      state.get.map(_.values.toList.filter(issue =>
        filter.states.isEmpty || filter.states.contains(IssueStateTag.fromState(issue.state))
      ))

    override def delete(id: IssueId): IO[PersistenceError, Unit] =
      state.update(_ - id).unit

  final private class StubWorkspaceRepository(
    workspaces: Map[String, Workspace],
    runs: Map[String, WorkspaceRun],
  ) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]               = ZIO.succeed(workspaces.values.toList)
    override def listByProject(projectId: shared.ids.Ids.ProjectId): IO[PersistenceError, List[Workspace]] =
      ZIO.succeed(workspaces.values.filter(_.projectId == projectId).toList)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]  = ZIO.succeed(workspaces.get(id))
    override def delete(id: String): IO[PersistenceError, Unit]            = ZIO.unit

    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        =
      ZIO.succeed(runs.values.filter(_.workspaceId == workspaceId).toList)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(runs.values.filter(_.issueRef == issueRef).toList)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 =
      ZIO.succeed(runs.get(id))

  final private class StubGitService(calls: Ref[List[String]], mergeFailure: Ref[Option[GitError]]) extends GitService:
    override def status(repoPath: String): IO[GitError, GitStatus]                                            = ZIO.succeed(
      GitStatus(branch = "main", staged = Nil, unstaged = Nil, untracked = Nil)
    )
    override def diff(repoPath: String, staged: Boolean): IO[GitError, GitDiff]                               = ZIO.succeed(GitDiff(Nil))
    override def diffStat(repoPath: String, staged: Boolean): IO[GitError, GitDiffStat]                       =
      ZIO.succeed(GitDiffStat(Nil))
    override def diffFile(repoPath: String, filePath: String, staged: Boolean): IO[GitError, String]          =
      ZIO.succeed("")
    override def log(repoPath: String, limit: Int): IO[GitError, List[GitLogEntry]]                           = ZIO.succeed(Nil)
    override def branchInfo(repoPath: String): IO[GitError, GitBranchInfo]                                    =
      ZIO.succeed(GitBranchInfo(current = "main", all = List("main", "agent/merge-1"), isDetached = false))
    override def showFile(repoPath: String, filePath: String, ref: String): IO[GitError, String]              = ZIO.succeed("")
    override def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind]                 =
      ZIO.succeed(AheadBehind(0, 0))
    override def checkout(repoPath: String, branch: String): IO[GitError, Unit]                               =
      calls.update(_ :+ s"checkout:$repoPath:$branch")
    override def add(repoPath: String, paths: List[String]): IO[GitError, Unit]                               =
      calls.update(_ :+ s"add:$repoPath:${paths.mkString(",")}")
    override def mv(repoPath: String, from: String, to: String): IO[GitError, Unit]                           =
      calls.update(_ :+ s"mv:$repoPath:$from:$to")
    override def commit(repoPath: String, message: String): IO[GitError, String]                              =
      calls.update(_ :+ s"commit:$repoPath:$message").as("1234567890abcdef1234567890abcdef12345678")
    override def rm(repoPath: String, path: String, recursive: Boolean): IO[GitError, Unit]                   =
      calls.update(_ :+ s"rm:$repoPath:$path:$recursive")
    override def mergeNoFastForward(repoPath: String, branch: String, message: String): IO[GitError, Unit]    =
      calls.update(_ :+ s"merge:$repoPath:$branch:$message") *>
        mergeFailure.get.flatMap {
          case Some(error) => ZIO.fail(error)
          case None        => ZIO.unit
        }
    override def mergeAbort(repoPath: String): IO[GitError, Unit]                                             =
      calls.update(_ :+ s"merge-abort:$repoPath")
    override def conflictedFiles(repoPath: String): IO[GitError, List[String]]                                =
      calls.update(_ :+ s"conflicted-files:$repoPath").as(List("src/Main.scala", "README.md"))
    override def headSha(repoPath: String): IO[GitError, String]                                              =
      calls.update(_ :+ s"head-sha:$repoPath").as("1234567890abcdef1234567890abcdef12345678")
    override def showDiffStat(repoPath: String, ref: String): IO[GitError, GitDiffStat]                       =
      calls.update(_ :+ s"show-diff-stat:$repoPath:$ref").as(
        GitDiffStat(
          List(
            DiffFileStat("src/Main.scala", 12, 3),
            DiffFileStat("README.md", 4, 1),
          )
        )
      )
    override def diffStatVsBase(repoPath: String, baseBranch: String): IO[GitError, GitDiffStat]              =
      calls.update(_ :+ s"diff-stat-vs-base:$repoPath:$baseBranch").as(GitDiffStat(Nil))
    override def diffFileVsBase(repoPath: String, filePath: String, baseBranch: String): IO[GitError, String] =
      calls.update(_ :+ s"diff-file-vs-base:$repoPath:$filePath:$baseBranch").as("")

  final private class StubActivityHub(events: Ref[List[ActivityEvent]], subscribers: Ref[Set[Queue[ActivityEvent]]])
    extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] =
      events.update(_ :+ event) *> subscribers.get.flatMap(queues => ZIO.foreachDiscard(queues)(_.offer(event).unit))

    override def subscribe: UIO[Dequeue[ActivityEvent]] =
      for
        queue <- Queue.unbounded[ActivityEvent]
        _     <- subscribers.update(_ + queue)
      yield queue

  final private class StubConfigRepository(settings: Ref[Map[String, String]]) extends ConfigRepository:
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           =
      settings.get.map(_.toList.map { case (key, value) => SettingRow(key, value, now) })
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                =
      settings.get.map(_.get(key).map(value => SettingRow(key, value, now)))
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            =
      settings.update(_.updated(key, value)).unit
    override def deleteSetting(key: String): IO[PersistenceError, Unit]                           = settings.update(_ - key).unit
    override def deleteSettingsByPrefix(prefix: String): IO[PersistenceError, Unit]               =
      settings.update(_.filterNot(_._1.startsWith(prefix))).unit
    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = ZIO.succeed(1L)
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = ZIO.succeed(None)
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       = ZIO.succeed(None)
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           = ZIO.succeed(Nil)
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                = ZIO.unit
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                             = ZIO.unit
    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             = ZIO.succeed(1L)
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           = ZIO.succeed(None)
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      ZIO.succeed(None)
    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     = ZIO.succeed(Nil)
    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             = ZIO.unit
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          = ZIO.unit

  final private case class Harness(
    issueId: IssueId,
    issueState: Ref[Map[IssueId, AgentIssue]],
    settings: Ref[Map[String, String]],
    gitCalls: Ref[List[String]],
    issueEvents: Ref[List[IssueEvent]],
    cleanedRuns: Ref[List[String]],
    mergeFailure: Ref[Option[GitError]],
    ciResult: Ref[(List[String], Int)],
    activityEvents: Ref[List[ActivityEvent]],
    activityHub: ActivityHub,
    workReportEventBus: WorkReportEventBus,
    serviceLayer: ZLayer[Any, Nothing, MergeAgentService],
    runtimeLayer: ZLayer[Any, Nothing, MergeAgentService],
  )

  private def makeHarness: UIO[Harness] =
    for
      issueState     <- Ref.make(
                          Map(
                            IssueId("merge-1") -> AgentIssue(
                              id = IssueId("merge-1"),
                              runId = Some(TaskRunId("run-1")),
                              conversationId = None,
                              title = "Merge me",
                              description = "Ready for merge",
                              issueType = "task",
                              priority = "medium",
                              requiredCapabilities = Nil,
                              state = IssueState.Merging(now),
                              tags = List("analysis-review"),
                              blockedBy = Nil,
                              blocking = Nil,
                              contextPath = "",
                              sourceFolder = "",
                              analysisDocIds = List(AnalysisDocId("doc-1")),
                              workspaceId = Some("ws-1"),
                            )
                          )
                        )
      gitCalls       <- Ref.make(List.empty[String])
      issueEvents    <- Ref.make(List.empty[IssueEvent])
      cleanedRuns    <- Ref.make(List.empty[String])
      settings       <- Ref.make(Map.empty[String, String])
      mergeFailure   <- Ref.make(Option.empty[GitError])
      ciResult       <- Ref.make((List("All checks passed"), 0))
      activityEvents <- Ref.make(List.empty[ActivityEvent])
      subscribers    <- Ref.make(Set.empty[Queue[ActivityEvent]])
      activityHub     = StubActivityHub(activityEvents, subscribers)
      configRepo      = StubConfigRepository(settings)
      taskRunHub     <- Hub.unbounded[TaskRunEvent]
      issueHub       <- Hub.unbounded[IssueEvent]
      sessionHub     <- Hub.unbounded[orchestration.control.ParallelSessionEvent]
      workQueue      <- Queue.unbounded[IssueId]
      pendingRef     <- Ref.Synchronized.make(Set.empty[IssueId])
      workReportBus   = WorkReportEventBus(
                          taskRunHub = taskRunHub,
                          issueHub = issueHub,
                          parallelSessionHub = sessionHub,
                        )
      workspace       = Workspace(
                          id = "ws-1",
                          projectId = shared.ids.Ids.ProjectId("test-project"),
                          name = "repo",
                          localPath = "/tmp/repo",
                          defaultAgent = None,
                          description = None,
                          enabled = true,
                          runMode = RunMode.Host,
                          cliTool = "gemini",
                          createdAt = now,
                          updatedAt = now,
                        )
      run             = WorkspaceRun(
                          id = "run-1",
                          workspaceId = "ws-1",
                          parentRunId = None,
                          issueRef = "#merge-1",
                          agentName = "code-agent",
                          prompt = "implement",
                          conversationId = "conv-1",
                          worktreePath = "/tmp/repo/.worktree/run-1",
                          branchName = "agent/merge-1",
                          status = RunStatus.Completed,
                          attachedUsers = Set.empty,
                          controllerUserId = None,
                          createdAt = now,
                          updatedAt = now,
                        )
      issueRepo       = StubIssueRepository(issueState, issueEvents)
      workspaceRepo   = StubWorkspaceRepository(Map(workspace.id -> workspace), Map(run.id -> run))
      gitService      = StubGitService(gitCalls, mergeFailure)
      service         = MergeAgentServiceLive(
                          issueRepository = issueRepo,
                          workspaceRepository = workspaceRepo,
                          gitService = gitService,
                          activityHub = activityHub,
                          configRepository = configRepo,
                          workReportEventBus = workReportBus,
                          cleanupMergedRun = runId => cleanedRuns.update(_ :+ runId),
                          queue = workQueue,
                          pending = pendingRef,
                          commandRunner = (_, _) => ciResult.get,
                        )
      serviceLayer    = ZLayer.succeed(service)
      runtimeLayer    = ZLayer.scoped {
                          for
                            _ <- service.bootstrap.catchAll(_ => ZIO.unit).forkScoped
                            _ <- service.listen.forkScoped
                            _ <- service.worker.forever.forkScoped
                          yield service
                        }
    yield Harness(
      IssueId("merge-1"),
      issueState,
      settings,
      gitCalls,
      issueEvents,
      cleanedRuns,
      mergeFailure,
      ciResult,
      activityEvents,
      activityHub,
      workReportBus,
      serviceLayer,
      runtimeLayer,
    )

  private def waitUntilDone(issueState: Ref[Map[IssueId, AgentIssue]], issueId: IssueId): UIO[Unit] =
    issueState.get.flatMap { state =>
      if state.get(issueId).exists(_.state.isInstanceOf[IssueState.Done]) then ZIO.unit
      else ZIO.sleep(25.millis) *> waitUntilDone(issueState, issueId)
    }.timeout(2.seconds).flatMap {
      case Some(_) => ZIO.unit
      case None    => ZIO.dieMessage("merge did not complete")
    }

  private def waitForCiEvents(
    taskRunQueue: Dequeue[TaskRunEvent],
    expectedCount: Int,
  ): IO[Throwable, List[TaskRunEvent.CiStatusUpdated]] =
    ZIO
      .collectAll(
        List.fill(expectedCount) {
          taskRunQueue.take.flatMap {
            case event: TaskRunEvent.CiStatusUpdated => ZIO.succeed(event)
            case other                               => ZIO.dieMessage(s"unexpected task run event: $other")
          }
        }
      )
      .timeoutFail(new RuntimeException(s"expected $expectedCount CI status events"))(2.seconds)

  def spec: Spec[TestEnvironment, Any] = suite("MergeAgentServiceSpec")(
    test("mergeOnce checks out base branch, merges agent branch, and marks issue done") {
      for
        harness <- makeHarness
        result  <- MergeAgentService.mergeOnce(harness.issueId).provideLayer(harness.serviceLayer)
        issue   <- harness.issueState.get.map(_(harness.issueId))
        calls   <- harness.gitCalls.get
        events  <- harness.issueEvents.get
        cleaned <- harness.cleanedRuns.get
      yield assertTrue(
        result == (),
        calls.headOption.contains("checkout:/tmp/repo:main"),
        calls.exists(_.startsWith("merge:/tmp/repo:agent/merge-1:Merge issue #merge-1")),
        calls.contains("head-sha:/tmp/repo"),
        calls.contains("show-diff-stat:/tmp/repo:1234567890abcdef1234567890abcdef12345678"),
        issue.state.isInstanceOf[IssueState.Done],
        events.exists(_.isInstanceOf[IssueEvent.MergeAttempted]),
        events.exists {
          case success: IssueEvent.MergeSucceeded =>
            success.commitSha == "1234567890abcdef1234567890abcdef12345678" &&
            success.filesChanged == 2 &&
            success.insertions == 16 &&
            success.deletions == 4
          case _                                  => false
        },
        cleaned == List("run-1"),
      )
    },
    test("activity event for Merging queues automatic merge processing") {
      for
        harness <- makeHarness
        _       <- ZIO.scoped {
                     for
                       env <- harness.runtimeLayer.build
                       _   <- (
                                for
                                  _ <- harness.activityHub.publish(
                                         ActivityEvent(
                                           id = EventId.generate,
                                           eventType = ActivityEventType.RunStateChanged,
                                           source = "issues-board",
                                           summary = "Issue #merge-1 moved to Merging",
                                           payload = Some("""{"issueId":"merge-1","status":"Merging"}"""),
                                           createdAt = now,
                                         )
                                       )
                                  _ <- waitUntilDone(harness.issueState, harness.issueId)
                                yield ()
                              ).provideEnvironment(env)
                     yield ()
                   }
        issue   <- harness.issueState.get.map(_(harness.issueId))
        events  <- harness.activityEvents.get
      yield assertTrue(
        issue.state.isInstanceOf[IssueState.Done],
        events.exists(_.source == "merge-agent"),
      )
    },
    test("merge conflict aborts merge, records files, moves issue to Rework, and emits conflict activity") {
      for
        harness     <- makeHarness
        _           <- harness.mergeFailure.set(Some(mergeConflict))
        result      <- MergeAgentService.mergeOnce(harness.issueId).provideLayer(harness.serviceLayer).either
        issue       <- harness.issueState.get.map(_(harness.issueId))
        calls       <- harness.gitCalls.get
        events      <- harness.activityEvents.get
        issueEvents <- harness.issueEvents.get
      yield assertTrue(
        result == Left(MergeAgentError.GitFailure(mergeConflict)),
        calls.contains("conflicted-files:/tmp/repo"),
        calls.contains("merge-abort:/tmp/repo"),
        issue.state match
          case IssueState.Rework(_, reason) => reason == "merge conflict: src/Main.scala, README.md"
          case _                            => false,
        issue.mergeConflictFiles == List("src/Main.scala", "README.md"),
        events.exists(_.eventType == ActivityEventType.MergeConflict),
        issueEvents.exists {
          case failed: IssueEvent.MergeFailed => failed.conflictFiles == List("src/Main.scala", "README.md")
          case _                              => false
        },
      )
    },
    test("required CI verification publishes running/passed status and only then marks issue done") {
      for
        harness           <- makeHarness
        _                 <- harness.settings.set(
                               Map(
                                 "workspace.ws-1.mergePolicy.requireCi" -> "true",
                                 "workspace.ws-1.mergePolicy.ciCommand" -> "sbt test",
                               )
                             )
        _                 <- harness.ciResult.set((List("All checks passed"), 0))
        tuple             <- ZIO.scoped {
                               for
                                 taskRunQueue <- harness.workReportEventBus.subscribeTaskRun
                                 result       <- MergeAgentService.mergeOnce(harness.issueId).provideLayer(harness.serviceLayer)
                                 ciEvents     <- waitForCiEvents(taskRunQueue, 2)
                               yield (result, ciEvents)
                             }
        (result, ciEvents) = tuple
        issue             <- harness.issueState.get.map(_(harness.issueId))
        events            <- harness.issueEvents.get
      yield assertTrue(
        result == (),
        issue.state.isInstanceOf[IssueState.Done],
        ciEvents.map(_.runId) == List(TaskRunId("run-1"), TaskRunId("run-1")),
        ciEvents.map(_.ciStatus) == List(CiStatus.Running, CiStatus.Passed),
        events.exists {
          case ci: IssueEvent.CiVerificationResult =>
            ci.passed && ci.details == "All checks passed"
          case _                                   => false
        },
      )
    },
    test("required CI verification moves issue to Rework when command fails") {
      for
        harness           <- makeHarness
        _                 <- harness.settings.set(
                               Map(
                                 "workspace.ws-1.mergePolicy.requireCi" -> "true",
                                 "workspace.ws-1.mergePolicy.ciCommand" -> "sbt test",
                               )
                             )
        _                 <- harness.ciResult.set((List("[error] tests failed", "1 failing"), 1))
        tuple             <- ZIO.scoped {
                               for
                                 taskRunQueue <- harness.workReportEventBus.subscribeTaskRun
                                 result       <- MergeAgentService.mergeOnce(harness.issueId).provideLayer(harness.serviceLayer).either
                                 ciEvents     <- waitForCiEvents(taskRunQueue, 2)
                               yield (result, ciEvents)
                             }
        (result, ciEvents) = tuple
        issue             <- harness.issueState.get.map(_(harness.issueId))
        events            <- harness.issueEvents.get
      yield assertTrue(
        result == Left(MergeAgentError.CiVerificationFailed("[error] tests failed | 1 failing")),
        issue.state match
          case IssueState.Rework(_, reason) => reason == "CI verification failed: [error] tests failed | 1 failing"
          case _                            => false,
        ciEvents.map(_.ciStatus) == List(CiStatus.Running, CiStatus.Failed),
        events.exists {
          case ci: IssueEvent.CiVerificationResult =>
            !ci.passed && ci.details == "[error] tests failed | 1 failing"
          case _                                   => false
        },
      )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
