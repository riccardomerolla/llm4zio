package demo.control

import java.nio.file.{ Files as JFiles, Path }
import java.time.Instant

import zio.*
import zio.process.Command

import board.control.BoardOrchestrator
import board.entity.*
import demo.entity.*
import project.control.ProjectStorageService
import project.entity.{ ProjectEvent, ProjectRepository }
import shared.ids.Ids.{ BoardIssueId, ProjectId }
import workspace.control.GitService
import workspace.entity.{ WorkspaceEvent, WorkspaceRepository }

trait DemoOrchestrator:
  def runQuickDemo(config: DemoConfig): IO[DemoError, DemoResult]
  def cleanup(projectId: String): IO[DemoError, Unit]
  def status: UIO[Option[DemoStatus]]

object DemoOrchestrator:

  val live
    : ZLayer[
      ProjectRepository & ProjectStorageService & WorkspaceRepository & BoardRepository & BoardOrchestrator & GitService,
      Nothing,
      DemoOrchestrator,
    ] =
    ZLayer.fromZIO {
      for
        projectRepo    <- ZIO.service[ProjectRepository]
        storageService <- ZIO.service[ProjectStorageService]
        workspaceRepo  <- ZIO.service[WorkspaceRepository]
        boardRepo      <- ZIO.service[BoardRepository]
        boardOrch      <- ZIO.service[BoardOrchestrator]
        gitService     <- ZIO.service[GitService]
        statusRef      <- Ref.make(Option.empty[DemoStatus])
      yield DemoOrchestratorLive(
        projectRepo,
        storageService,
        workspaceRepo,
        boardRepo,
        boardOrch,
        gitService,
        statusRef,
      )
    }

  def runQuickDemo(config: DemoConfig): ZIO[DemoOrchestrator, DemoError, DemoResult] =
    ZIO.serviceWithZIO[DemoOrchestrator](_.runQuickDemo(config))

  def cleanup(projectId: String): ZIO[DemoOrchestrator, DemoError, Unit] =
    ZIO.serviceWithZIO[DemoOrchestrator](_.cleanup(projectId))

  def status: URIO[DemoOrchestrator, Option[DemoStatus]] =
    ZIO.serviceWithZIO[DemoOrchestrator](_.status)

final case class DemoOrchestratorLive(
  projectRepo: ProjectRepository,
  storageService: ProjectStorageService,
  workspaceRepo: WorkspaceRepository,
  boardRepo: BoardRepository,
  boardOrch: BoardOrchestrator,
  gitService: GitService,
  statusRef: Ref[Option[DemoStatus]],
) extends DemoOrchestrator:

  override def runQuickDemo(config: DemoConfig): IO[DemoError, DemoResult] =
    for
      now         <- Clock.instant
      projectId    = ProjectId(s"demo-${now.toEpochMilli}")
      workspaceId  = s"ws-demo-${now.toEpochMilli}"
      _           <- createDemoProject(projectId, now)
      projectPath <- storageService.initProjectStorage(projectId)
                       .mapError(e => DemoError.ProjectCreationFailed(e.toString))
      repoPath     = Path.of(config.repoBaseDir).resolve(s"llm4zio-demo-${projectId.value}")
      _           <- initDemoGitRepo(repoPath)
      wsPath       = repoPath.toString
      _           <- createDemoWorkspace(workspaceId, projectId, wsPath, now)
      issues       = MockIssueCatalog.sample(config.issueCount)
      _           <- seedIssues(projectPath.toString, issues)
      _           <- statusRef.set(Some(DemoStatus(
                       projectId = projectId.value,
                       workspaceId = workspaceId,
                       workspacePath = wsPath,
                       dispatched = 0,
                       inProgress = 0,
                       atReview = 0,
                       done = 0,
                       total = issues.size,
                     )))
    yield DemoResult(
      projectId = projectId.value,
      workspaceId = workspaceId,
      workspacePath = wsPath,
      issueCount = issues.size,
      estimatedSeconds = config.agentDelaySeconds * issues.size,
    )

  override def cleanup(projectId: String): IO[DemoError, Unit] =
    val pid = ProjectId(projectId)
    for
      workspaces  <- workspaceRepo
                       .listByProject(pid)
                       .mapError(e => DemoError.CleanupFailed(e.toString))
      projectPath <- storageService.projectRoot(pid)
      _           <- ZIO.foreachDiscard(workspaces) { ws =>
                       deleteWorkspaceArtifacts(projectPath.toString, ws.localPath, ws.id)
                     }
      _           <- deleteDirectory(projectPath)
      _           <- projectRepo
                       .delete(pid)
                       .mapError(e => DemoError.CleanupFailed(e.toString))
      _           <- statusRef.set(None)
    yield ()

  private def deleteWorkspaceArtifacts(
    boardPath: String,
    workspacePath: String,
    workspaceId: String,
  ): IO[DemoError, Unit] =
    for
      board <- boardRepo
                 .readBoard(boardPath)
                 .mapError(e => DemoError.CleanupFailed(e.toString))
      issues = board.columns.values.flatten.toList
      _     <- ZIO.foreachDiscard(issues) { issue =>
                 boardRepo
                   .deleteIssue(boardPath, issue.frontmatter.id)
                   .mapError(e => DemoError.CleanupFailed(e.toString))
                   .ignore
               }
      _     <- workspaceRepo
                 .delete(workspaceId)
                 .mapError(e => DemoError.CleanupFailed(e.toString))
      _     <- deleteDirectory(Path.of(workspacePath))
    yield ()

  private def deleteDirectory(path: Path): IO[DemoError, Unit] =
    ZIO
      .attemptBlocking {
        if JFiles.exists(path) then deleteTree(path)
      }
      .mapError(e => DemoError.CleanupFailed(s"Failed to delete $path: ${e.getMessage}"))

  private def initDemoGitRepo(path: Path): IO[DemoError, Unit] =
    for
      _ <- ZIO
             .attemptBlocking {
               JFiles.createDirectories(path)
               val readme = path.resolve("README.md")
               if !JFiles.exists(readme) then
                 val _ = JFiles.writeString(readme, "# Demo Workspace\n")
             }
             .mapError(e => DemoError.ProjectCreationFailed(s"Failed to create demo repo dir: ${e.getMessage}"))
      _ <- Command("git", "init", "--initial-branch=main")
             .workingDirectory(path.toFile)
             .string
             .mapError(e => DemoError.ProjectCreationFailed(s"git init failed: ${e.getMessage}"))
      _ <- gitService
             .add(path.toString, List("."))
             .zipRight(gitService.commit(path.toString, "[demo] Init workspace"))
             .mapError(e => DemoError.ProjectCreationFailed(s"git initial commit failed: $e"))
    yield ()

  private def deleteTree(path: Path): Unit =
    if JFiles.isDirectory(path) then
      val stream = JFiles.list(path)
      try stream.forEach(deleteTree)
      finally stream.close()
    JFiles.deleteIfExists(path)
    ()

  override def status: UIO[Option[DemoStatus]] =
    statusRef.get

  private def createDemoProject(projectId: ProjectId, now: Instant): IO[DemoError, Unit] =
    projectRepo
      .append(ProjectEvent.ProjectCreated(
        projectId = projectId,
        name = "Demo — Spring Boot Microservice",
        description = Some("Auto-generated demo project showcasing the ADE workflow."),
        occurredAt = now,
      ))
      .mapError(e => DemoError.ProjectCreationFailed(e.toString))

  private def createDemoWorkspace(
    workspaceId: String,
    projectId: ProjectId,
    localPath: String,
    now: Instant,
  ): IO[DemoError, Unit] =
    workspaceRepo
      .append(WorkspaceEvent.Created(
        workspaceId = workspaceId,
        projectId = projectId,
        name = "demo-spring-boot",
        localPath = localPath,
        defaultAgent = None,
        description = Some("Demo workspace — Spring Boot microservice"),
        cliTool = "mock",
        runMode = workspace.entity.RunMode.Host,
        occurredAt = now,
      ))
      .mapError(e => DemoError.WorkspaceCreationFailed(e.toString))

  private def seedIssues(workspacePath: String, issues: List[MockIssueTemplate]): IO[DemoError, Unit] =
    Clock.instant.flatMap { now =>
      ZIO.foreachDiscard(issues) { tmpl =>
        val issueId  = BoardIssueId(tmpl.id)
        val priority = tmpl.priority
        val estimate = tmpl.estimate.flatMap {
          case "XS" => Some(IssueEstimate.XS)
          case "S"  => Some(IssueEstimate.S)
          case "M"  => Some(IssueEstimate.M)
          case "L"  => Some(IssueEstimate.L)
          case "XL" => Some(IssueEstimate.XL)
          case _    => None
        }
        val fm       = IssueFrontmatter(
          id = issueId,
          title = tmpl.title,
          priority = priority,
          assignedAgent = None,
          requiredCapabilities = tmpl.requiredCapabilities,
          blockedBy = tmpl.blockedBy.map(BoardIssueId.apply),
          tags = tmpl.tags,
          acceptanceCriteria = tmpl.acceptanceCriteria,
          estimate = estimate,
          proofOfWork = Nil,
          transientState = TransientState.None,
          branchName = None,
          failureReason = None,
          completedAt = None,
          createdAt = now,
        )
        boardRepo
          .createIssue(workspacePath, BoardColumn.Backlog, BoardIssue(fm, tmpl.body, BoardColumn.Backlog, ""))
          .mapError(e => DemoError.IssueSeedingFailed(e.toString))
          .unit
      }
    }
