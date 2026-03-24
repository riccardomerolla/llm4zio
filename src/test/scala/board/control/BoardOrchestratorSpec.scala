package board.control

import java.time.Instant

import zio.*
import zio.test.*

import activity.control.ActivityHub
import activity.entity.ActivityEvent
import board.entity.*
import shared.errors.PersistenceError
import shared.ids.Ids.BoardIssueId
import workspace.control.{ AssignRunRequest, GitService, WorkspaceRunService }
import workspace.entity.*

object BoardOrchestratorSpec extends ZIOSpecDefault:
  private val workspacePath = "/tmp/workspace"

  private def issue(
    id: String,
    column: BoardColumn,
    blockedBy: List[String] = Nil,
    branchName: Option[String] = None,
  ): BoardIssue =
    BoardIssue(
      frontmatter = IssueFrontmatter(
        id = BoardIssueId(id),
        title = s"Issue $id",
        priority = IssuePriority.Medium,
        assignedAgent = None,
        requiredCapabilities = Nil,
        blockedBy = blockedBy.map(BoardIssueId.apply),
        tags = Nil,
        acceptanceCriteria = Nil,
        estimate = None,
        proofOfWork = Nil,
        transientState = TransientState.None,
        branchName = branchName,
        failureReason = None,
        completedAt = None,
        createdAt = Instant.parse("2026-03-20T10:00:00Z"),
      ),
      body = s"Body for $id",
      column = column,
      directoryPath = s"$workspacePath/.board/${column.folderName}/$id",
    )

  final private case class InMemoryBoardRepo(ref: Ref[Map[BoardIssueId, BoardIssue]]) extends BoardRepository:
    override def initBoard(workspacePath: String): IO[BoardError, Unit] = ZIO.unit

    override def readBoard(workspacePath: String): IO[BoardError, Board] =
      ref.get.map { issuesById =>
        val issues = issuesById.values.toList
        Board(
          workspacePath = workspacePath,
          columns = BoardColumn.values.map(column => column -> issues.filter(_.column == column)).toMap,
        )
      }

    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue] =
      ref.get.flatMap(map => ZIO.fromOption(map.get(issueId)).orElseFail(BoardError.IssueNotFound(issueId.value)))

    override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue)
      : IO[BoardError, BoardIssue] =
      ref.update(_ + (issue.frontmatter.id -> issue.copy(column = column))).as(issue.copy(column = column))

    override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
      : IO[BoardError, BoardIssue] =
      ref.modify { map =>
        map.get(issueId) match
          case Some(current) =>
            val moved = current.copy(
              column = toColumn,
              directoryPath = s"$workspacePath/.board/${toColumn.folderName}/${issueId.value}",
            )
            (Right(moved), map.updated(issueId, moved))
          case None          =>
            (Left(BoardError.IssueNotFound(issueId.value)), map)
      }.flatMap(ZIO.fromEither(_))

    override def updateIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      update: IssueFrontmatter => IssueFrontmatter,
    ): IO[BoardError, BoardIssue] =
      ref.modify { map =>
        map.get(issueId) match
          case Some(current) =>
            val updated = current.copy(frontmatter = update(current.frontmatter))
            (Right(updated), map.updated(issueId, updated))
          case None          =>
            (Left(BoardError.IssueNotFound(issueId.value)), map)
      }.flatMap(ZIO.fromEither(_))

    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
      ref.update(_ - issueId)

    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      ref.get.map(_.values.filter(_.column == column).toList)

    override def invalidateWorkspace(workspacePath: String): UIO[Unit] =
      ZIO.unit

  final private case class StubWorkspaceRunService(
    assignedRef: Ref[List[(String, AssignRunRequest)]],
    cleanupRef: Ref[List[String]],
  ) extends WorkspaceRunService:
    override def assign(workspaceId: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      assignedRef.update(_ :+ (workspaceId -> req)) *>
        Clock.instant.map(now =>
          WorkspaceRun(
            id = "run-1",
            workspaceId = workspaceId,
            parentRunId = None,
            issueRef = req.issueRef,
            agentName = req.agentName,
            prompt = req.prompt,
            conversationId = "1",
            worktreePath = s"$workspacePath/.worktree/run-1",
            branchName = s"agent/${req.agentName}-${req.issueRef}",
            status = RunStatus.Pending,
            attachedUsers = Set.empty,
            controllerUserId = None,
            createdAt = now,
            updatedAt = now,
          )
        )

    override def continueRun(
      runId: String,
      followUpPrompt: String,
      agentNameOverride: Option[String],
    ): IO[WorkspaceError, WorkspaceRun] =
      ZIO.dieMessage("continueRun unused in BoardOrchestratorSpec")

    override def cancelRun(runId: String): IO[WorkspaceError, Unit] =
      ZIO.dieMessage("cancelRun unused in BoardOrchestratorSpec")

    override def cleanupAfterSuccessfulMerge(runId: String): UIO[Unit] =
      cleanupRef.update(_ :+ runId)

  final private case class StubWorkspaceRepo(
    workspace: Workspace,
    runsByIssueRef: Ref[Map[String, List[WorkspaceRun]]],
  ) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit] = ZIO.unit
    override def list: IO[PersistenceError, List[Workspace]]               = ZIO.succeed(List(workspace))
    override def get(id: String): IO[PersistenceError, Option[Workspace]]  =
      ZIO.succeed(Option.when(id == workspace.id)(workspace))
    override def delete(id: String): IO[PersistenceError, Unit]            = ZIO.unit

    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit] = ZIO.unit

    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]] =
      runsByIssueRef.get.map(_.values.flatten.toList)

    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      runsByIssueRef.get.map(_.getOrElse(issueRef, Nil))

    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]] =
      runsByIssueRef.get.map(_.values.flatten.find(_.id == id))

  final private case class StubGitService(
    mergesRef: Ref[List[(String, String, String)]],
    currentBranch: String = "main",
    detached: Boolean = false,
  ) extends GitService:
    override def status(repoPath: String): IO[GitError, GitStatus]                                            =
      ZIO.succeed(GitStatus(currentBranch, Nil, Nil, Nil))
    override def diff(repoPath: String, staged: Boolean): IO[GitError, GitDiff]                               = ZIO.succeed(GitDiff(Nil))
    override def diffStat(repoPath: String, staged: Boolean): IO[GitError, GitDiffStat]                       = ZIO.succeed(GitDiffStat(Nil))
    override def diffFile(repoPath: String, filePath: String, staged: Boolean): IO[GitError, String]          = ZIO.succeed("")
    override def log(repoPath: String, limit: Int): IO[GitError, List[GitLogEntry]]                           = ZIO.succeed(Nil)
    override def branchInfo(repoPath: String): IO[GitError, GitBranchInfo]                                    =
      ZIO.succeed(GitBranchInfo(current = currentBranch, all = List("main"), isDetached = detached))
    override def showFile(repoPath: String, filePath: String, ref: String): IO[GitError, String]              = ZIO.succeed("")
    override def aheadBehind(repoPath: String, baseBranch: String): IO[GitError, AheadBehind]                 =
      ZIO.succeed(AheadBehind(0, 0))
    override def checkout(repoPath: String, branch: String): IO[GitError, Unit]                               = ZIO.unit
    override def add(repoPath: String, paths: List[String]): IO[GitError, Unit]                               = ZIO.unit
    override def mv(repoPath: String, from: String, to: String): IO[GitError, Unit]                           = ZIO.unit
    override def commit(repoPath: String, message: String): IO[GitError, String]                              = ZIO.succeed("sha")
    override def rm(repoPath: String, path: String, recursive: Boolean): IO[GitError, Unit]                   = ZIO.unit
    override def mergeNoFastForward(repoPath: String, branch: String, message: String): IO[GitError, Unit]    =
      mergesRef.update(_ :+ (repoPath, branch, message))
    override def mergeAbort(repoPath: String): IO[GitError, Unit]                                             = ZIO.unit
    override def conflictedFiles(repoPath: String): IO[GitError, List[String]]                                = ZIO.succeed(Nil)
    override def headSha(repoPath: String): IO[GitError, String]                                              = ZIO.succeed("sha")
    override def showDiffStat(repoPath: String, ref: String): IO[GitError, GitDiffStat]                       = ZIO.succeed(GitDiffStat(Nil))
    override def diffStatVsBase(repoPath: String, baseBranch: String): IO[GitError, GitDiffStat]              =
      ZIO.succeed(GitDiffStat(Nil))
    override def diffFileVsBase(repoPath: String, filePath: String, baseBranch: String): IO[GitError, String] =
      ZIO.succeed("")

  private val noopActivityHub = new ActivityHub:
    override def publish(event: ActivityEvent): UIO[Unit] = ZIO.unit
    override def subscribe: UIO[Dequeue[ActivityEvent]]   = Queue.unbounded[ActivityEvent]

  private def makeOrchestrator(
    issues: List[BoardIssue],
    runsByIssueRefSeed: Map[String, List[WorkspaceRun]] = Map.empty,
  ): UIO[(
    BoardOrchestratorLive,
    Ref[Map[BoardIssueId, BoardIssue]],
    Ref[List[(String, AssignRunRequest)]],
    Ref[List[String]],
    Ref[List[(String, String, String)]],
  )] =
    for
      boardRef       <- Ref.make(issues.map(issue => issue.frontmatter.id -> issue).toMap)
      assignedRef    <- Ref.make(List.empty[(String, AssignRunRequest)])
      cleanupRef     <- Ref.make(List.empty[String])
      mergesRef      <- Ref.make(List.empty[(String, String, String)])
      runsByIssueRef <- Ref.make(runsByIssueRefSeed)
      repo            = InMemoryBoardRepo(boardRef)
      runService      = StubWorkspaceRunService(assignedRef, cleanupRef)
      workspace       = Workspace(
                          id = "ws-1",
                          name = "workspace",
                          localPath = workspacePath,
                          defaultAgent = Some("agent-default"),
                          description = None,
                          enabled = true,
                          runMode = RunMode.Host,
                          cliTool = "codex",
                          createdAt = Instant.parse("2026-03-20T09:00:00Z"),
                          updatedAt = Instant.parse("2026-03-20T09:00:00Z"),
                        )
      workspaceRepo   = StubWorkspaceRepo(workspace, runsByIssueRef)
      gitService      = StubGitService(mergesRef)
      orchestrator    = BoardOrchestratorLive(
                          boardRepository = repo,
                          dependencyResolver = BoardDependencyResolverLive(),
                          workspaceRunService = runService,
                          workspaceRepository = workspaceRepo,
                          gitService = gitService,
                          activityHub = noopActivityHub,
                        )
    yield (orchestrator, boardRef, assignedRef, cleanupRef, mergesRef)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("BoardOrchestratorSpec")(
      test("dispatchCycle assigns ready todo issue and moves it to in-progress") {
        val readyTask = issue("task-1", BoardColumn.Todo, blockedBy = List("dep-1"))
        val depDone   = issue("dep-1", BoardColumn.Done)

        for
          (orchestrator, boardRef, assignedRef, _, _) <- makeOrchestrator(List(readyTask, depDone))
          result                                      <- orchestrator.dispatchCycle(workspacePath)
          state                                       <- boardRef.get
          assigned                                    <- assignedRef.get
          task                                         = state(BoardIssueId("task-1"))
        yield assertTrue(
          result.dispatchedIssueIds == List(BoardIssueId("task-1")),
          result.skippedIssueIds.isEmpty,
          task.column == BoardColumn.InProgress,
          task.frontmatter.branchName.contains("agent/agent-default-task-1"),
          task.frontmatter.assignedAgent.contains("agent-default"),
          assigned == List(
            "ws-1" -> AssignRunRequest(
              issueRef = "task-1",
              prompt = "Body for task-1",
              agentName = "agent-default",
            )
          ),
        )
      },
      test("completeIssue success moves issue to review, preserving branch") {
        val inProgress = issue("task-2", BoardColumn.InProgress, branchName = Some("agent/agent-default-task-2"))

        val run = WorkspaceRun(
          id = "run-2",
          workspaceId = "ws-1",
          parentRunId = None,
          issueRef = "task-2",
          agentName = "agent-default",
          prompt = "Body",
          conversationId = "1",
          worktreePath = s"$workspacePath/.worktree/run-2",
          branchName = "agent/agent-default-task-2",
          status = RunStatus.Completed,
          attachedUsers = Set.empty,
          controllerUserId = None,
          createdAt = Instant.parse("2026-03-20T10:00:00Z"),
          updatedAt = Instant.parse("2026-03-20T10:10:00Z"),
        )

        for
          (orchestrator, boardRef, _, cleanupRef, mergesRef) <-
            makeOrchestrator(List(inProgress), runsByIssueRefSeed = Map("task-2" -> List(run)))
          _                                                  <- orchestrator.completeIssue(
                                                                  workspacePath,
                                                                  BoardIssueId("task-2"),
                                                                  success = true,
                                                                  details = "linked PR #22",
                                                                )
          state                                              <- boardRef.get
          reviewIssue                                         = state(BoardIssueId("task-2"))
          cleanup                                            <- cleanupRef.get
          merges                                             <- mergesRef.get
        yield assertTrue(
          reviewIssue.column == BoardColumn.Review,
          reviewIssue.frontmatter.completedAt.isEmpty,
          reviewIssue.frontmatter.branchName.contains("agent/agent-default-task-2"),
          reviewIssue.frontmatter.proofOfWork.contains("linked PR #22"),
          cleanup.isEmpty,
          merges.isEmpty,
        )
      },
      test("completeIssue failure moves issue to backlog with rework reason") {
        val inProgress = issue("task-3", BoardColumn.InProgress, branchName = Some("agent/agent-default-task-3"))

        for
          (orchestrator, boardRef, _, _, _) <- makeOrchestrator(List(inProgress))
          _                                 <- orchestrator.completeIssue(
                                                 workspacePath,
                                                 BoardIssueId("task-3"),
                                                 success = false,
                                                 details = "tests are failing",
                                               )
          state                             <- boardRef.get
          failed                             = state(BoardIssueId("task-3"))
        yield assertTrue(
          failed.column == BoardColumn.Backlog,
          failed.frontmatter.failureReason.contains("tests are failing"),
          failed.frontmatter.transientState.isInstanceOf[TransientState.Rework],
          failed.frontmatter.completedAt.isEmpty,
        )
      },
    )
