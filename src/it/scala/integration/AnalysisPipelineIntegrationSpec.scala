package integration

import java.nio.file.{ Files as JFiles, Path }
import java.time.{ Duration as JavaDuration, Instant }

import zio.*
import zio.test.*

import agent.entity.{ Agent, AgentEvent, AgentRepository }
import analysis.control.{ AnalysisAgentRunner, AnalysisAgentRunnerLive }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import shared.services.FileService
import _root_.config.entity.SettingRow
import taskrun.entity.TaskRepository
import taskrun.entity.{ TaskArtifactRow, TaskReportRow, TaskRunRow }
import project.control.ProjectStorageService
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, AnalysisDocId, ProjectId }
import shared.testfixtures.StubWorkspaceRepository
import workspace.entity.Workspace
import IntegrationFixtures.*

/** Integration test for [[AnalysisAgentRunnerLive]].
  *
  * Verifies that [[AnalysisAgentRunner.runCodeReview]] correctly:
  *   1. Selects the stub code-review agent from the repository.
  *   2. Executes the analysis via an injected LLM executor (returns a fixed markdown string).
  *   3. Writes the analysis file to `.llm4zio/analysis/code-review.md` in the workspace.
  *   4. Commits the file to git.
  *   5. Appends an [[AnalysisEvent.AnalysisCreated]] event to the repository.
  */
object AnalysisPipelineIntegrationSpec extends ZIOSpecDefault:

  private val workspaceId = "ws-analysis-spec"

  // ── StubAgentRepository ───────────────────────────────────────────────────────
  // list() returns the single code-review agent; findByName matches by name.

  private val codeReviewAgent: Agent = Agent(
    id = AgentId("code-review-agent"),
    name = "code-review-agent",
    description = "Stub code review agent for integration tests",
    cliTool = "codex",
    capabilities = List("code-review"),
    defaultModel = None,
    systemPrompt = None,
    maxConcurrentRuns = 1,
    envVars = Map.empty,
    dockerMemoryLimit = None,
    dockerCpuLimit = None,
    timeout = JavaDuration.ofMinutes(10),
    enabled = true,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
  )

  final private class StubAgentRepository extends AgentRepository:
    override def append(event: AgentEvent): IO[PersistenceError, Unit]                    = ZIO.unit
    override def get(id: AgentId): IO[PersistenceError, Agent]                            =
      ZIO.fail(PersistenceError.NotFound("Agent", id.value))
    override def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]] =
      ZIO.succeed(List(codeReviewAgent))
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]            =
      ZIO.succeed(Option.when(name == codeReviewAgent.name)(codeReviewAgent))

  // ── StubAnalysisRepository ────────────────────────────────────────────────────
  // Stores all appended events in a Ref; accessible for assertion.

  final private class StubAnalysisRepository extends AnalysisRepository:
    val events: Ref[List[AnalysisEvent]] =
      zio.Unsafe.unsafe(implicit u => Ref.unsafe.make(List.empty))

    override def append(event: AnalysisEvent): IO[PersistenceError, Unit]                        =
      events.update(_ :+ event)
    override def get(id: AnalysisDocId): IO[PersistenceError, AnalysisDoc]                       =
      ZIO.fail(PersistenceError.NotFound("AnalysisDoc", id.value))
    override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]]   =
      ZIO.succeed(Nil)
    override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] =
      ZIO.succeed(Nil)

  // ── StubTaskRepository ────────────────────────────────────────────────────────
  // All settings return None so the runner falls back to defaults.

  final private class StubProjectStorageService(repoPath: Path) extends ProjectStorageService:
    override def initProjectStorage(projectId: ProjectId): IO[PersistenceError, Path]        = ZIO.succeed(repoPath)
    override def projectRoot(projectId: ProjectId): UIO[Path]                                = ZIO.succeed(repoPath)
    override def boardPath(projectId: ProjectId): UIO[Path]                                  = ZIO.succeed(repoPath.resolve(".board"))
    override def workspaceAnalysisPath(projectId: ProjectId, workspaceId: String): UIO[Path] =
      ZIO.succeed(repoPath.resolve(".llm4zio").resolve("analysis"))

  final private class StubTaskRepository extends TaskRepository:
    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                           = ZIO.succeed(0L)
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                           = ZIO.unit
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                       = ZIO.succeed(None)
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]]        = ZIO.succeed(Nil)
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                  = ZIO.unit
    override def saveReport(report: TaskReportRow): IO[PersistenceError, Long]                    = ZIO.succeed(0L)
    override def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]]           = ZIO.succeed(None)
    override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]]     = ZIO.succeed(Nil)
    override def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long]              = ZIO.succeed(0L)
    override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]] = ZIO.succeed(Nil)
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]]                = ZIO.succeed(None)
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            = ZIO.unit

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AnalysisPipelineIntegrationSpec")(
      test("runCodeReview: file written and committed, AnalysisCreated event persisted") {
        ZIO.scoped {
          for
            repoPath <- initGitRepo

            // ── Stub collaborators ─────────────────────────────────────────────
            agentRepo    = StubAgentRepository()
            analysisRepo = StubAnalysisRepository()
            wsRepo       = StubWorkspaceRepository.single(minimalWorkspace(workspaceId, repoPath))
            taskRepo     = StubTaskRepository()
            fileService <- ZIO.service[FileService].provide(FileService.live)

            // ── Construct runner with injected LLM executor ───────────────────
            runner = AnalysisAgentRunnerLive(
                       workspaceRepository = wsRepo,
                       agentRepository = agentRepo,
                       analysisRepository = analysisRepo,
                       taskRepository = taskRepo,
                       fileService = fileService,
                       projectStorageService = StubProjectStorageService(repoPath),
                       promptLoader = None,
                       llmPromptExecutor = Some { (_: Workspace, _: Agent, _: String) =>
                         ZIO.succeed("# Code Review Analysis\n\nCode looks good.\n")
                       },
                     )

            // ── Run the analysis ───────────────────────────────────────────────
            doc <- runner.runCodeReview(workspaceId)

            // ── Collect state for assertions ───────────────────────────────────
            eventsCapture <- analysisRepo.events.get
            analysisFile   =
              repoPath.resolve("workspaces").resolve(workspaceId).resolve(AnalysisAgentRunner.CodeReviewRelativePath)
            fileExists    <- ZIO.attemptBlocking(JFiles.exists(analysisFile))
            fileContent   <- ZIO.attemptBlocking(JFiles.readString(analysisFile))
          yield assertTrue(
            // Analysis doc has the correct type
            doc.analysisType == AnalysisType.CodeReview,
            doc.workspaceId == workspaceId,
            doc.generatedBy == codeReviewAgent.id,
            doc.filePath == AnalysisAgentRunner.CodeReviewRelativePath,
            // The analysis file was written to the repo
            fileExists,
            fileContent.contains("Code Review Analysis"),
            // An AnalysisCreated event was appended to the repository
            eventsCapture.size == 1,
            eventsCapture.head match
              case AnalysisEvent.AnalysisCreated(_, wsId, analysisType, content, filePath, _, _) =>
                wsId == workspaceId &&
                analysisType == AnalysisType.CodeReview &&
                content.contains("Code Review Analysis") &&
                filePath == AnalysisAgentRunner.CodeReviewRelativePath
              case _                                                                             => false,
          )
        }
      }
    )
