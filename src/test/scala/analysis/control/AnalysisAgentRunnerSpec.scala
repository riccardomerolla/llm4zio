package analysis.control

import java.nio.file.{ Files, Path }
import java.time.{ Duration as JavaDuration, Instant }

import zio.*
import zio.test.*

import agent.entity.{ Agent, AgentEvent, AgentRepository }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import app.control.FileService
import db.*
import shared.errors.PersistenceError
import shared.ids.Ids
import workspace.entity.*

object AnalysisAgentRunnerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T12:00:00Z")

  private val workspace = Workspace(
    id = "ws-1",
    name = "billing-service",
    localPath = "",
    defaultAgent = None,
    description = Some("Workspace for analysis"),
    enabled = true,
    runMode = RunMode.Host,
    cliTool = "codex",
    createdAt = now,
    updatedAt = now,
  )

  private def agent(
    id: String,
    name: String,
    cliTool: String = "codex",
    capabilities: List[String] = Nil,
    enabled: Boolean = true,
    envVars: Map[String, String] = Map.empty,
    systemPrompt: Option[String] = None,
  ) =
    Agent(
      id = Ids.AgentId(id),
      name = name,
      description = s"$name description",
      cliTool = cliTool,
      capabilities = capabilities,
      defaultModel = None,
      systemPrompt = systemPrompt,
      maxConcurrentRuns = 1,
      envVars = envVars,
      timeout = JavaDuration.ofSeconds(30),
      enabled = enabled,
      createdAt = now,
      updatedAt = now,
    )

  final private case class ProcessInvocation(
    argv: List[String],
    cwd: String,
    envVars: Map[String, String],
  )

  final private case class GitInvocation(
    argv: List[String],
    cwd: String,
  )

  final private case class StubWorkspaceRepository(current: Option[Workspace]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                      = unsupported("appendWorkspace")
    override def list: IO[PersistenceError, List[Workspace]]                                    = ZIO.succeed(current.toList)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                       = ZIO.succeed(current.filter(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                                 = unsupported("deleteWorkspace")
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                =
      unsupported("appendRun")
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]        =
      ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]] =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                 =
      ZIO.succeed(None)

  final private case class StubAgentRepository(agents: List[Agent]) extends AgentRepository:
    override def append(event: AgentEvent): IO[PersistenceError, Unit]                    =
      unsupported("appendAgent")
    override def get(id: Ids.AgentId): IO[PersistenceError, Agent]                        =
      ZIO
        .fromOption(agents.find(_.id == id))
        .orElseFail(PersistenceError.NotFound("agent", id.value))
    override def list(includeDeleted: Boolean = false): IO[PersistenceError, List[Agent]] =
      ZIO.succeed(agents)
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]            =
      ZIO.succeed(agents.find(_.name.equalsIgnoreCase(name.trim)))

  final private case class StubTaskRepository(settingValue: Option[String]) extends TaskRepository:
    override def getSetting(key: String): IO[db.PersistenceError, Option[SettingRow]] =
      ZIO.succeed(
        if key == AnalysisAgentRunner.CodeReviewAgentSettingKey then
          settingValue.map(value => SettingRow(key, value, now))
        else None
      )

    override def createRun(run: TaskRunRow): IO[db.PersistenceError, Long]                           = unsupportedDb("createRun")
    override def updateRun(run: TaskRunRow): IO[db.PersistenceError, Unit]                           = unsupportedDb("updateRun")
    override def getRun(id: Long): IO[db.PersistenceError, Option[TaskRunRow]]                       = unsupportedDb("getRun")
    override def listRuns(offset: Int, limit: Int): IO[db.PersistenceError, List[TaskRunRow]]        =
      unsupportedDb("listRuns")
    override def deleteRun(id: Long): IO[db.PersistenceError, Unit]                                  = unsupportedDb("deleteRun")
    override def saveReport(report: TaskReportRow): IO[db.PersistenceError, Long]                    = unsupportedDb("saveReport")
    override def getReport(reportId: Long): IO[db.PersistenceError, Option[TaskReportRow]]           = unsupportedDb("getReport")
    override def getReportsByTask(taskRunId: Long): IO[db.PersistenceError, List[TaskReportRow]]     =
      unsupportedDb("getReportsByTask")
    override def saveArtifact(artifact: TaskArtifactRow): IO[db.PersistenceError, Long]              = unsupportedDb("saveArtifact")
    override def getArtifactsByTask(taskRunId: Long): IO[db.PersistenceError, List[TaskArtifactRow]] =
      unsupportedDb("getArtifactsByTask")
    override def getAllSettings: IO[db.PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
    override def upsertSetting(key: String, value: String): IO[db.PersistenceError, Unit]            =
      unsupportedDb("upsertSetting")
    override def createWorkflow(workflow: WorkflowRow): IO[db.PersistenceError, Long]                = unsupportedDb("createWorkflow")
    override def getWorkflow(id: Long): IO[db.PersistenceError, Option[WorkflowRow]]                 = unsupportedDb("getWorkflow")
    override def getWorkflowByName(name: String): IO[db.PersistenceError, Option[WorkflowRow]]       =
      unsupportedDb("getWorkflowByName")
    override def listWorkflows: IO[db.PersistenceError, List[WorkflowRow]]                           = unsupportedDb("listWorkflows")
    override def updateWorkflow(workflow: WorkflowRow): IO[db.PersistenceError, Unit]                = unsupportedDb("updateWorkflow")
    override def deleteWorkflow(id: Long): IO[db.PersistenceError, Unit]                             = unsupportedDb("deleteWorkflow")
    override def createCustomAgent(agent: CustomAgentRow): IO[db.PersistenceError, Long]             =
      unsupportedDb("createCustomAgent")
    override def getCustomAgent(id: Long): IO[db.PersistenceError, Option[CustomAgentRow]]           =
      unsupportedDb("getCustomAgent")
    override def getCustomAgentByName(name: String): IO[db.PersistenceError, Option[CustomAgentRow]] =
      unsupportedDb("getCustomAgentByName")
    override def listCustomAgents: IO[db.PersistenceError, List[CustomAgentRow]]                     = unsupportedDb("listCustomAgents")
    override def updateCustomAgent(agent: CustomAgentRow): IO[db.PersistenceError, Unit]             =
      unsupportedDb("updateCustomAgent")
    override def deleteCustomAgent(id: Long): IO[db.PersistenceError, Unit]                          = unsupportedDb("deleteCustomAgent")

  final private case class StubAnalysisRepository(state: Ref[Map[Ids.AnalysisDocId, AnalysisDoc]])
    extends AnalysisRepository:
    override def append(event: AnalysisEvent): IO[PersistenceError, Unit] =
      state.update { current =>
        event match
          case created: AnalysisEvent.AnalysisCreated =>
            current + (
              created.docId -> AnalysisDoc(
                id = created.docId,
                workspaceId = created.workspaceId,
                analysisType = created.analysisType,
                content = created.content,
                filePath = created.filePath,
                generatedBy = created.generatedBy,
                createdAt = created.occurredAt,
                updatedAt = created.occurredAt,
              )
            )
          case updated: AnalysisEvent.AnalysisUpdated =>
            current.updatedWith(updated.docId)(_.map(_.copy(content = updated.content, updatedAt = updated.updatedAt)))
          case deleted: AnalysisEvent.AnalysisDeleted =>
            current - deleted.docId
      }.unit

    override def get(id: Ids.AnalysisDocId): IO[PersistenceError, AnalysisDoc] =
      state.get.flatMap(map =>
        ZIO.fromOption(map.get(id)).orElseFail(PersistenceError.NotFound("analysis_doc", id.value))
      )

    override def listByWorkspace(workspaceId: String): IO[PersistenceError, List[AnalysisDoc]] =
      state.get.map(_.values.filter(_.workspaceId == workspaceId).toList.sortBy(_.createdAt))

    override def listByType(analysisType: AnalysisType): IO[PersistenceError, List[AnalysisDoc]] =
      state.get.map(_.values.filter(_.analysisType == analysisType).toList.sortBy(_.createdAt))

  private def unsupported[A](op: String): IO[PersistenceError, A] =
    ZIO.fail(PersistenceError.QueryFailed(op, "unsupported in test"))

  private def unsupportedDb[A](op: String): IO[db.PersistenceError, A] =
    ZIO.fail(db.PersistenceError.QueryFailed(op, "unsupported in test"))

  private def makeRunner(
    repoPath: Path,
    agents: List[Agent],
    configuredAgent: Option[String],
    processOutput: List[String],
    processExitCode: Int = 0,
    initialDocs: List[AnalysisDoc] = Nil,
    gitDiffExitCode: Int = 1,
  ): ZIO[
    FileService,
    Nothing,
    (
      AnalysisAgentRunnerLive,
      Ref[Vector[ProcessInvocation]],
      Ref[Vector[GitInvocation]],
      Ref[Map[
        Ids.AnalysisDocId,
        AnalysisDoc,
      ]],
    ),
  ] =
    for
      fileService <- ZIO.service[FileService]
      processRef  <- Ref.make(Vector.empty[ProcessInvocation])
      gitRef      <- Ref.make(Vector.empty[GitInvocation])
      docsRef     <- Ref.make(initialDocs.map(doc => doc.id -> doc).toMap)
      runner       = AnalysisAgentRunnerLive(
                       workspaceRepository = StubWorkspaceRepository(Some(workspace.copy(localPath = repoPath.toString))),
                       agentRepository = StubAgentRepository(agents),
                       analysisRepository = StubAnalysisRepository(docsRef),
                       taskRepository = StubTaskRepository(configuredAgent),
                       fileService = fileService,
                       processRunner = (argv, cwd, onLine, envVars) =>
                         processRef.update(_ :+ ProcessInvocation(argv, cwd, envVars)) *>
                           ZIO.foreachDiscard(processOutput)(onLine) *>
                           ZIO.succeed(processExitCode),
                       gitRunner = (argv, cwd) =>
                         gitRef.update(_ :+ GitInvocation(argv, cwd)) *>
                           ZIO.succeed(
                             if argv.drop(1) == List("diff", "--cached", "--quiet", "--exit-code") then (Nil, gitDiffExitCode)
                             else (Nil, 0)
                           ),
                     )
    yield (runner, processRef, gitRef, docsRef)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AnalysisAgentRunnerSpec")(
      test("uses configured code review agent, writes markdown, commits file, and creates analysis doc") {
        for
          repoPath                             <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-spec"))
          configured                            = agent(
                                                    id = "configured-agent",
                                                    name = "configured-reviewer",
                                                    capabilities = Nil,
                                                    envVars = Map("OPENAI_API_KEY" -> "test-key"),
                                                    systemPrompt = Some("Prioritize correctness and code health."),
                                                  )
          fallback                              = agent(
                                                    id = "fallback-agent",
                                                    name = "fallback-reviewer",
                                                    capabilities = List("code-review"),
                                                  )
          output                                = List(
                                                    "```markdown",
                                                    "## Code Quality and Patterns",
                                                    "Solid module boundaries with a few long methods in billing reconciliation.",
                                                    "",
                                                    "## Technical Debt Areas",
                                                    "Legacy transaction helpers still mix parsing and persistence.",
                                                    "",
                                                    "## Test Coverage Assessment",
                                                    "Retry paths are weakly covered.",
                                                    "",
                                                    "## Naming Conventions and Consistency",
                                                    "Repository naming is mostly consistent.",
                                                    "",
                                                    "## Potential Bug Patterns",
                                                    "Null-like Option fallback around invoice settlement deserves review.",
                                                    "```",
                                                  )
          tuple                                <- makeRunner(repoPath, List(fallback, configured), Some("configured-reviewer"), output)
          (runner, processRef, gitRef, docsRef) = tuple
          doc                                  <- runner.runCodeReview("ws-1")
          savedContent                         <- ZIO.attemptBlocking(
                                                    Files.readString(repoPath.resolve(AnalysisAgentRunner.CodeReviewRelativePath))
                                                  )
          processCalls                         <- processRef.get
          gitCalls                             <- gitRef.get
          docs                                 <- docsRef.get
          promptArg                             = processCalls.head.argv.drop(1).mkString(" ")
        yield assertTrue(
          doc.generatedBy == configured.id,
          doc.analysisType == AnalysisType.CodeReview,
          doc.workspaceId == "ws-1",
          doc.filePath == AnalysisAgentRunner.CodeReviewRelativePath,
          savedContent == doc.content,
          doc.content.startsWith("# Code Review Analysis"),
          processCalls.head.cwd == repoPath.toString,
          processCalls.head.envVars == configured.envVars,
          promptArg.contains("## Technical Debt Areas"),
          promptArg.contains(repoPath.toString),
          gitCalls.map(_.argv.drop(1)) == Vector(
            List("add", "--", AnalysisAgentRunner.CodeReviewRelativePath),
            List("diff", "--cached", "--quiet", "--exit-code"),
            List("commit", "-m", "Add code review analysis for billing-service"),
          ),
          docs.size == 1,
        )
      },
      test("falls back to the first enabled agent with code-review capability when no setting exists") {
        for
          repoPath         <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-fallback"))
          alpha             = agent(id = "alpha", name = "alpha-reviewer", capabilities = List("code-review"))
          beta              = agent(id = "beta", name = "beta-reviewer", capabilities = List("code-review"))
          tuple            <- makeRunner(
                                repoPath = repoPath,
                                agents = List(beta, alpha),
                                configuredAgent = None,
                                processOutput = List("# Code Review Analysis", "", "## Code Quality and Patterns", "Looks fine."),
                              )
          (runner, _, _, _) = tuple
          doc              <- runner.runCodeReview("ws-1")
        yield assertTrue(doc.generatedBy == alpha.id)
      },
      test("updates an existing code review analysis doc instead of creating a second record") {
        for
          repoPath               <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-update"))
          reviewer                = agent(id = "reviewer-1", name = "code-reviewer", capabilities = List("code-review"))
          existingId              = Ids.AnalysisDocId("analysis-1")
          existingDoc             = AnalysisDoc(
                                      id = existingId,
                                      workspaceId = "ws-1",
                                      analysisType = AnalysisType.CodeReview,
                                      content = "# Code Review Analysis\n\nOld content\n",
                                      filePath = AnalysisAgentRunner.CodeReviewRelativePath,
                                      generatedBy = reviewer.id,
                                      createdAt = now.minusSeconds(60),
                                      updatedAt = now.minusSeconds(60),
                                    )
          tuple                  <- makeRunner(
                                      repoPath = repoPath,
                                      agents = List(reviewer),
                                      configuredAgent = None,
                                      processOutput = List("# Code Review Analysis", "", "## Code Quality and Patterns", "New content"),
                                      initialDocs = List(existingDoc),
                                    )
          (runner, _, _, docsRef) = tuple
          updated                <- runner.runCodeReview("ws-1")
          docs                   <- docsRef.get
        yield assertTrue(
          updated.id == existingId,
          updated.content.contains("New content"),
          docs.keySet == Set(existingId),
          docs(existingId).content.contains("New content"),
        )
      },
      test("fails when no configured agent exists and no enabled code-review agent is available") {
        for
          repoPath         <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-no-agent"))
          writer            = agent(id = "writer", name = "writer-agent", capabilities = List("code-generation"))
          tuple            <- makeRunner(
                                repoPath = repoPath,
                                agents = List(writer),
                                configuredAgent = None,
                                processOutput = List("# Code Review Analysis"),
                              )
          (runner, _, _, _) = tuple
          result           <- runner.runCodeReview("ws-1").either
        yield assertTrue(result == Left(AnalysisAgentRunnerError.NoCodeReviewAgentAvailable("ws-1")))
      },
    ).provide(FileService.live)
