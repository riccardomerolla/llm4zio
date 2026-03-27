package integration

import java.nio.file.{ Files as JFiles, Paths }
import java.time.{ Duration as JavaDuration, Instant }

import zio.*
import zio.test.*

import agent.entity.{ Agent, AgentEvent, AgentRepository }
import analysis.control.{ AnalysisAgentRunner, AnalysisAgentRunnerLive }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import app.control.FileService
import db.{
  PersistenceError as DbPersistenceError,
  SettingRow,
  TaskArtifactRow,
  TaskReportRow,
  TaskRunRow,
  TaskRepository,
}
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, AnalysisDocId }
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

  final private class StubTaskRepository extends TaskRepository:
    override def createRun(run: TaskRunRow): IO[DbPersistenceError, Long]                           = ZIO.succeed(0L)
    override def updateRun(run: TaskRunRow): IO[DbPersistenceError, Unit]                           = ZIO.unit
    override def getRun(id: Long): IO[DbPersistenceError, Option[TaskRunRow]]                       = ZIO.succeed(None)
    override def listRuns(offset: Int, limit: Int): IO[DbPersistenceError, List[TaskRunRow]]        = ZIO.succeed(Nil)
    override def deleteRun(id: Long): IO[DbPersistenceError, Unit]                                  = ZIO.unit
    override def saveReport(report: TaskReportRow): IO[DbPersistenceError, Long]                    = ZIO.succeed(0L)
    override def getReport(reportId: Long): IO[DbPersistenceError, Option[TaskReportRow]]           = ZIO.succeed(None)
    override def getReportsByTask(taskRunId: Long): IO[DbPersistenceError, List[TaskReportRow]]     = ZIO.succeed(Nil)
    override def saveArtifact(artifact: TaskArtifactRow): IO[DbPersistenceError, Long]              = ZIO.succeed(0L)
    override def getArtifactsByTask(taskRunId: Long): IO[DbPersistenceError, List[TaskArtifactRow]] = ZIO.succeed(Nil)
    override def getAllSettings: IO[DbPersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
    override def getSetting(key: String): IO[DbPersistenceError, Option[SettingRow]]                = ZIO.succeed(None)
    override def upsertSetting(key: String, value: String): IO[DbPersistenceError, Unit]            = ZIO.unit

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AnalysisPipelineIntegrationSpec")(
      test("runCodeReview: file written and committed, AnalysisCreated event persisted") {
        ZIO.scoped {
          for
            repoPath     <- initGitRepo
            workspacePath = repoPath.toString

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
                       promptLoader = None,
                       llmPromptExecutor = Some { (_, _, _) =>
                         ZIO.succeed("# Code Review Analysis\n\nCode looks good.\n")
                       },
                     )

            // ── Run the analysis ───────────────────────────────────────────────
            doc <- runner.runCodeReview(workspaceId)

            // ── Collect state for assertions ───────────────────────────────────
            eventsCapture <- analysisRepo.events.get
            analysisFile   = Paths.get(workspacePath).resolve(AnalysisAgentRunner.CodeReviewRelativePath)
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
