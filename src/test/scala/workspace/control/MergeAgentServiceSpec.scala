package workspace.control

import java.time.Instant

import zio.*
import zio.test.*

import activity.control.ActivityHub
import activity.entity.{ ActivityEvent, ActivityEventType }
import issues.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.{ AnalysisDocId, EventId, IssueId, TaskRunId }
import workspace.entity.*

object MergeAgentServiceSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T16:00:00Z")

  final private class StubIssueRepository(state: Ref[Map[IssueId, AgentIssue]]) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit] =
      state.modify { current =>
        val updated = event match
          case done: IssueEvent.MarkedDone =>
            current.updatedWith(done.issueId)(_.map(_.copy(state = IssueState.Done(done.doneAt, done.result))))
          case _                           => current
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
    override def get(id: String): IO[PersistenceError, Option[Workspace]]  = ZIO.succeed(workspaces.get(id))
    override def delete(id: String): IO[PersistenceError, Unit]            = ZIO.unit

    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                = ZIO.unit
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        =
      ZIO.succeed(runs.values.filter(_.workspaceId == workspaceId).toList)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(runs.values.filter(_.issueRef == issueRef).toList)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 =
      ZIO.succeed(runs.get(id))

  final private class StubGitService(calls: Ref[List[String]]) extends GitService:
    override def status(repoPath: String): IO[GitError, GitStatus]                                         = ZIO.succeed(
      GitStatus(branch = "main", staged = Nil, unstaged = Nil, untracked = Nil)
    )
    override def diff(repoPath: String, staged: Boolean): IO[GitError, GitDiff]                            = ZIO.succeed(GitDiff(Nil))
    override def diffStat(repoPath: String, staged: Boolean): IO[GitError, GitDiffStat]                    =
      ZIO.succeed(GitDiffStat(Nil))
    override def diffFile(repoPath: String, filePath: String, staged: Boolean): IO[GitError, String]       =
      ZIO.succeed("")
    override def log(repoPath: String, limit: Int): IO[GitError, List[GitLogEntry]]                        = ZIO.succeed(Nil)
    override def branchInfo(repoPath: String): IO[GitError, GitBranchInfo]                                 =
      ZIO.succeed(GitBranchInfo(current = "main", all = List("main", "agent/merge-1"), isDetached = false))
    override def showFile(repoPath: String, filePath: String, ref: String): IO[GitError, String]           = ZIO.succeed("")
    override def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind]              =
      ZIO.succeed(AheadBehind(0, 0))
    override def checkout(repoPath: String, branch: String): IO[GitError, Unit]                            =
      calls.update(_ :+ s"checkout:$repoPath:$branch")
    override def mergeNoFastForward(repoPath: String, branch: String, message: String): IO[GitError, Unit] =
      calls.update(_ :+ s"merge:$repoPath:$branch:$message")

  final private class StubActivityHub(events: Ref[List[ActivityEvent]], subscribers: Ref[Set[Queue[ActivityEvent]]])
    extends ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] =
      events.update(_ :+ event) *> subscribers.get.flatMap(queues => ZIO.foreachDiscard(queues)(_.offer(event).unit))

    override def subscribe: UIO[Dequeue[ActivityEvent]] =
      for
        queue <- Queue.unbounded[ActivityEvent]
        _     <- subscribers.update(_ + queue)
      yield queue

  final private case class Harness(
    issueId: IssueId,
    issueState: Ref[Map[IssueId, AgentIssue]],
    gitCalls: Ref[List[String]],
    activityEvents: Ref[List[ActivityEvent]],
    activityHub: ActivityHub,
    layer: ZLayer[Any, Nothing, MergeAgentService],
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
      activityEvents <- Ref.make(List.empty[ActivityEvent])
      subscribers    <- Ref.make(Set.empty[Queue[ActivityEvent]])
      activityHub     = StubActivityHub(activityEvents, subscribers)
      workspace       = Workspace(
                          id = "ws-1",
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
      issueRepo       = StubIssueRepository(issueState)
      workspaceRepo   = StubWorkspaceRepository(Map(workspace.id -> workspace), Map(run.id -> run))
      gitService      = StubGitService(gitCalls)
      layer           =
        ZLayer.succeed(issueRepo) ++
          ZLayer.succeed(workspaceRepo) ++
          ZLayer.succeed(gitService) ++
          ZLayer.succeed(activityHub) >>> MergeAgentService.live
    yield Harness(IssueId("merge-1"), issueState, gitCalls, activityEvents, activityHub, layer)

  private def waitUntilDone(issueState: Ref[Map[IssueId, AgentIssue]], issueId: IssueId): UIO[Unit] =
    issueState.get.flatMap { state =>
      if state.get(issueId).exists(_.state.isInstanceOf[IssueState.Done]) then ZIO.unit
      else ZIO.sleep(25.millis) *> waitUntilDone(issueState, issueId)
    }.timeout(2.seconds).flatMap {
      case Some(_) => ZIO.unit
      case None    => ZIO.dieMessage("merge did not complete")
    }

  def spec: Spec[TestEnvironment, Any] = suite("MergeAgentServiceSpec")(
    test("mergeOnce checks out base branch, merges agent branch, and marks issue done") {
      for
        harness <- makeHarness
        result  <- MergeAgentService
                     .mergeOnce(harness.issueId)
                     .provideLayer(harness.layer)
        issue   <- harness.issueState.get.map(_(harness.issueId))
        calls   <- harness.gitCalls.get
      yield assertTrue(
        result == (),
        calls.headOption.contains("checkout:/tmp/repo:main"),
        calls.exists(_.startsWith("merge:/tmp/repo:agent/merge-1:Merge issue #merge-1")),
        issue.state.isInstanceOf[IssueState.Done],
      )
    },
    test("activity event for Merging queues automatic merge processing") {
      for
        harness <- makeHarness
        _       <- ZIO.scoped {
                     for
                       env <- harness.layer.build
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
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
