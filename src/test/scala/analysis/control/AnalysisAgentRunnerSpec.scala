package analysis.control

import java.nio.file.{ Files, Path, Paths }
import java.time.{ Duration as JavaDuration, Instant }

import zio.*
import zio.test.*

import _root_.config.entity.{ CustomAgentRow, SettingRow, WorkflowRow }
import agent.entity.{ Agent, AgentEvent, AgentRepository }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import app.control.FileService
import db.*
import project.control.ProjectStorageService
import shared.errors.PersistenceError
import shared.ids.Ids
import shared.ids.Ids.ProjectId
import taskrun.entity.{ TaskArtifactRow, TaskReportRow, TaskRunRow }
import workspace.entity.*

object AnalysisAgentRunnerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T12:00:00Z")

  private val workspace = Workspace(
    id = "ws-1",
    projectId = ProjectId("test-project"),
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
    cliTool: String = "gemini",
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

  final private case class CommandInvocation(
    argv: List[String],
    cwd: String,
    envVars: Map[String, String],
  )

  final private case class ProviderInvocation(
    workspacePath: String,
    agentName: String,
    prompt: String,
  )

  final private case class StubWorkspaceRepository(current: Option[Workspace]) extends WorkspaceRepository:
    override def append(event: WorkspaceEvent): IO[PersistenceError, Unit]                                 = unsupported("appendWorkspace")
    override def list: IO[PersistenceError, List[Workspace]]                                               = ZIO.succeed(current.toList)
    override def listByProject(projectId: shared.ids.Ids.ProjectId): IO[PersistenceError, List[Workspace]] =
      ZIO.succeed(current.filter(_.projectId == projectId).toList)
    override def get(id: String): IO[PersistenceError, Option[Workspace]]                                  = ZIO.succeed(current.filter(_.id == id))
    override def delete(id: String): IO[PersistenceError, Unit]                                            = unsupported("deleteWorkspace")
    override def appendRun(event: WorkspaceRunEvent): IO[PersistenceError, Unit]                           =
      unsupported("appendRun")
    override def listRuns(workspaceId: String): IO[PersistenceError, List[WorkspaceRun]]                   =
      ZIO.succeed(Nil)
    override def listRunsByIssueRef(issueRef: String): IO[PersistenceError, List[WorkspaceRun]]            =
      ZIO.succeed(Nil)
    override def getRun(id: String): IO[PersistenceError, Option[WorkspaceRun]]                            =
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

  final private case class StubTaskRepository(settings: Map[String, String]) extends TaskRepository:
    override def getSetting(key: String): IO[PersistenceError, Option[SettingRow]] =
      ZIO.succeed(
        settings.get(key).map(value => SettingRow(key, value, now))
      )

    override def createRun(run: TaskRunRow): IO[PersistenceError, Long]                           = unsupportedDb("createRun")
    override def updateRun(run: TaskRunRow): IO[PersistenceError, Unit]                           = unsupportedDb("updateRun")
    override def getRun(id: Long): IO[PersistenceError, Option[TaskRunRow]]                       = unsupportedDb("getRun")
    override def listRuns(offset: Int, limit: Int): IO[PersistenceError, List[TaskRunRow]]        =
      unsupportedDb("listRuns")
    override def deleteRun(id: Long): IO[PersistenceError, Unit]                                  = unsupportedDb("deleteRun")
    override def saveReport(report: TaskReportRow): IO[PersistenceError, Long]                    = unsupportedDb("saveReport")
    override def getReport(reportId: Long): IO[PersistenceError, Option[TaskReportRow]]           = unsupportedDb("getReport")
    override def getReportsByTask(taskRunId: Long): IO[PersistenceError, List[TaskReportRow]]     =
      unsupportedDb("getReportsByTask")
    override def saveArtifact(artifact: TaskArtifactRow): IO[PersistenceError, Long]              = unsupportedDb("saveArtifact")
    override def getArtifactsByTask(taskRunId: Long): IO[PersistenceError, List[TaskArtifactRow]] =
      unsupportedDb("getArtifactsByTask")
    override def getAllSettings: IO[PersistenceError, List[SettingRow]]                           = ZIO.succeed(Nil)
    override def upsertSetting(key: String, value: String): IO[PersistenceError, Unit]            =
      unsupportedDb("upsertSetting")
    override def createWorkflow(workflow: WorkflowRow): IO[PersistenceError, Long]                = unsupportedDb("createWorkflow")
    override def getWorkflow(id: Long): IO[PersistenceError, Option[WorkflowRow]]                 = unsupportedDb("getWorkflow")
    override def getWorkflowByName(name: String): IO[PersistenceError, Option[WorkflowRow]]       =
      unsupportedDb("getWorkflowByName")
    override def listWorkflows: IO[PersistenceError, List[WorkflowRow]]                           = unsupportedDb("listWorkflows")
    override def updateWorkflow(workflow: WorkflowRow): IO[PersistenceError, Unit]                = unsupportedDb("updateWorkflow")
    override def deleteWorkflow(id: Long): IO[PersistenceError, Unit]                             = unsupportedDb("deleteWorkflow")
    override def createCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Long]             =
      unsupportedDb("createCustomAgent")
    override def getCustomAgent(id: Long): IO[PersistenceError, Option[CustomAgentRow]]           =
      unsupportedDb("getCustomAgent")
    override def getCustomAgentByName(name: String): IO[PersistenceError, Option[CustomAgentRow]] =
      unsupportedDb("getCustomAgentByName")
    override def listCustomAgents: IO[PersistenceError, List[CustomAgentRow]]                     = unsupportedDb("listCustomAgents")
    override def updateCustomAgent(agent: CustomAgentRow): IO[PersistenceError, Unit]             =
      unsupportedDb("updateCustomAgent")
    override def deleteCustomAgent(id: Long): IO[PersistenceError, Unit]                          = unsupportedDb("deleteCustomAgent")

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

  private def unsupportedDb[A](op: String): IO[PersistenceError, A] =
    ZIO.fail(PersistenceError.QueryFailed(op, "unsupported in test"))

  final private case class StubProjectStorageService(repoPath: Path) extends ProjectStorageService:
    override def initProjectStorage(projectId: shared.ids.Ids.ProjectId): IO[PersistenceError, Path]        =
      ZIO.succeed(repoPath)
    override def projectRoot(projectId: shared.ids.Ids.ProjectId): UIO[Path]                                =
      ZIO.succeed(repoPath)
    override def boardPath(projectId: shared.ids.Ids.ProjectId): UIO[Path]                                  =
      ZIO.succeed(repoPath.resolve(".board"))
    override def workspaceAnalysisPath(projectId: shared.ids.Ids.ProjectId, workspaceId: String): UIO[Path] =
      ZIO.succeed(repoPath.resolve("workspaces").resolve(workspaceId).resolve(".llm4zio").resolve("analysis"))

  private def makeRunner(
    repoPath: Path,
    agents: List[Agent],
    settings: Map[String, String] = Map.empty,
    processOutput: List[String],
    processExitCode: Int = 0,
    commandOutput: List[String] = Nil,
    commandExitCode: Int = 0,
    llmOutput: Option[String] = None,
    initialDocs: List[AnalysisDoc] = Nil,
    gitDiffExitCode: Int = 1,
  ): ZIO[
    FileService,
    Nothing,
    (
      AnalysisAgentRunnerLive,
      Ref[Vector[ProcessInvocation]],
      Ref[Vector[CommandInvocation]],
      Ref[Vector[ProviderInvocation]],
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
      commandRef  <- Ref.make(Vector.empty[CommandInvocation])
      providerRef <- Ref.make(Vector.empty[ProviderInvocation])
      gitRef      <- Ref.make(Vector.empty[GitInvocation])
      docsRef     <- Ref.make(initialDocs.map(doc => doc.id -> doc).toMap)
      runner       = AnalysisAgentRunnerLive(
                       workspaceRepository = StubWorkspaceRepository(Some(workspace.copy(localPath = repoPath.toString))),
                       agentRepository = StubAgentRepository(agents),
                       analysisRepository = StubAnalysisRepository(docsRef),
                       taskRepository = StubTaskRepository(settings),
                       fileService = fileService,
                       projectStorageService = StubProjectStorageService(repoPath),
                       llmPromptExecutor = llmOutput.map(output =>
                         (workspace: Workspace, agent: Agent, prompt: String) =>
                           providerRef.update(_ :+ ProviderInvocation(workspace.localPath, agent.name, prompt)).as(output)
                       ),
                       processRunner = (argv, cwd, onLine, envVars) =>
                         processRef.update(_ :+ ProcessInvocation(argv, cwd, envVars)) *>
                           ZIO.foreachDiscard(processOutput)(onLine) *>
                           ZIO.succeed(processExitCode),
                       commandRunner = (argv, cwd, envVars) =>
                         commandRef.update(_ :+ CommandInvocation(argv, cwd, envVars)) *>
                           ZIO.attemptBlocking {
                             argv
                               .sliding(2)
                               .collectFirst {
                                 case List("--output-last-message", path) => path
                               }
                               .foreach(path => Files.writeString(Paths.get(path), commandOutput.mkString("\n")))
                           }.orDie *>
                           ZIO.succeed((commandOutput, commandExitCode)),
                       gitRunner = (argv, cwd) =>
                         gitRef.update(_ :+ GitInvocation(argv, cwd)) *>
                           ZIO.succeed(
                             if argv.drop(1) == List("diff", "--cached", "--quiet", "--exit-code") then (Nil, gitDiffExitCode)
                             else (Nil, 0)
                           ),
                     )
    yield (runner, processRef, commandRef, providerRef, gitRef, docsRef)

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("AnalysisAgentRunnerSpec")(
      test("uses configured code review agent, writes markdown, commits file, and creates analysis doc") {
        for
          repoPath                                   <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-spec"))
          configured                                  = agent(
                                                          id = "configured-agent",
                                                          name = "configured-reviewer",
                                                          capabilities = Nil,
                                                          envVars = Map("OPENAI_API_KEY" -> "test-key"),
                                                          systemPrompt = Some("Prioritize correctness and code health."),
                                                        )
          fallback                                    = agent(
                                                          id = "fallback-agent",
                                                          name = "fallback-reviewer",
                                                          capabilities = List("code-review"),
                                                        )
          output                                      = List(
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
          tuple                                      <- makeRunner(
                                                          repoPath,
                                                          List(fallback, configured),
                                                          settings = Map(
                                                            AnalysisAgentRunner.CodeReviewAgentSettingKey -> "configured-reviewer"
                                                          ),
                                                          processOutput = output,
                                                        )
          (runner, processRef, _, _, gitRef, docsRef) = tuple
          doc                                        <- runner.runCodeReview("ws-1")
          savedContent                               <- ZIO.attemptBlocking(
                                                          Files.readString(
                                                            repoPath
                                                              .resolve("workspaces")
                                                              .resolve("ws-1")
                                                              .resolve(AnalysisAgentRunner.CodeReviewRelativePath)
                                                          )
                                                        )
          processCalls                               <- processRef.get
          gitCalls                                   <- gitRef.get
          docs                                       <- docsRef.get
          promptArg                                   = processCalls.head.argv.drop(1).mkString(" ")
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
            List("add", "--", s"workspaces/ws-1/${AnalysisAgentRunner.CodeReviewRelativePath}"),
            List("diff", "--cached", "--quiet", "--exit-code"),
            List("commit", "-m", "Add code review analysis for billing-service"),
          ),
          docs.size == 1,
        )
      },
      test("codex analysis uses final-message output and excludes CLI transcript chatter") {
        for
          repoPath                                 <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-codex"))
          configured                                = agent(
                                                        id = "configured-agent",
                                                        name = "configured-reviewer",
                                                        cliTool = "codex",
                                                        capabilities = List("code-review"),
                                                      )
          tuple                                    <- makeRunner(
                                                        repoPath = repoPath,
                                                        agents = List(configured),
                                                        commandOutput = List(
                                                          "# Code Review Analysis",
                                                          "",
                                                          "## Findings",
                                                          "Repository structure is minimal and coherent.",
                                                        ),
                                                        processOutput = List(
                                                          "YOLO mode is enabled. All tool calls will be automatically approved.",
                                                          "Loaded cached credentials.",
                                                        ),
                                                      )
          (runner, processRef, commandRef, _, _, _) = tuple
          doc                                      <- runner.runCodeReview("ws-1")
          processCalls                             <- processRef.get
          commandCalls                             <- commandRef.get
          savedContent                             <- ZIO.attemptBlocking(
                                                        Files.readString(
                                                          repoPath
                                                            .resolve("workspaces")
                                                            .resolve("ws-1")
                                                            .resolve(AnalysisAgentRunner.CodeReviewRelativePath)
                                                        )
                                                      )
        yield assertTrue(
          doc.content == savedContent,
          savedContent.contains("## Findings"),
          !savedContent.contains("YOLO mode is enabled"),
          !savedContent.contains("Loaded cached credentials"),
          processCalls.isEmpty,
          commandCalls.size == 1,
          commandCalls.head.argv.take(2) == List("codex", "exec"),
        )
      },
      test("analysis uses provider-resolved execution when available instead of direct CLI invocation") {
        for
          repoPath                                           <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-provider"))
          configured                                          = agent(
                                                                  id = "configured-agent",
                                                                  name = "configured-reviewer",
                                                                  cliTool = "codex",
                                                                  capabilities = List("code-review"),
                                                                )
          tuple                                              <- makeRunner(
                                                                  repoPath = repoPath,
                                                                  agents = List(configured),
                                                                  llmOutput = Some(
                                                                    """# Code Review Analysis
                                                         |
                                                         |## Findings
                                                         |Provider-based execution returned clean markdown.
                                                         |""".stripMargin
                                                                  ),
                                                                  processOutput = List("interactive chatter"),
                                                                  commandOutput = List("non-interactive chatter"),
                                                                )
          (runner, processRef, commandRef, providerRef, _, _) = tuple
          doc                                                <- runner.runCodeReview("ws-1")
          processCalls                                       <- processRef.get
          commandCalls                                       <- commandRef.get
          providerCalls                                      <- providerRef.get
        yield assertTrue(
          doc.content.contains("Provider-based execution returned clean markdown."),
          processCalls.isEmpty,
          commandCalls.isEmpty,
          providerCalls.map(_.workspacePath) == Vector(repoPath.toString),
        )
      },
      test("falls back to the first enabled agent with code-review capability when no setting exists") {
        for
          repoPath               <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-fallback"))
          alpha                   = agent(id = "alpha", name = "alpha-reviewer", capabilities = List("code-review"))
          beta                    = agent(id = "beta", name = "beta-reviewer", capabilities = List("code-review"))
          tuple                  <- makeRunner(
                                      repoPath = repoPath,
                                      agents = List(beta, alpha),
                                      processOutput = List("# Code Review Analysis", "", "## Code Quality and Patterns", "Looks fine."),
                                    )
          (runner, _, _, _, _, _) = tuple
          doc                    <- runner.runCodeReview("ws-1")
        yield assertTrue(doc.generatedBy == alpha.id)
      },
      test("falls back to built-in analysis agents when persisted agent repository is empty") {
        for
          repoPath                              <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-built-in"))
          tuple                                 <- makeRunner(
                                                     repoPath = repoPath,
                                                     agents = Nil,
                                                     processOutput = List("# Architecture Analysis", "", "## Findings", "Looks healthy."),
                                                   )
          (runner, processRef, _, _, _, docsRef) = tuple
          doc                                   <- runner.runArchitecture("ws-1")
          processCalls                          <- processRef.get
          docs                                  <- docsRef.get
        yield assertTrue(
          doc.analysisType == AnalysisType.Architecture,
          processCalls.size == 1,
          processCalls.head.argv.headOption.contains("gemini"),
          docs.values.exists(_.analysisType == AnalysisType.Architecture),
        )
      },
      test("updates an existing code review analysis doc instead of creating a second record") {
        for
          repoPath                     <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-update"))
          reviewer                      = agent(id = "reviewer-1", name = "code-reviewer", capabilities = List("code-review"))
          existingId                    = Ids.AnalysisDocId("analysis-1")
          existingDoc                   = AnalysisDoc(
                                            id = existingId,
                                            workspaceId = "ws-1",
                                            analysisType = AnalysisType.CodeReview,
                                            content = "# Code Review Analysis\n\nOld content\n",
                                            filePath = AnalysisAgentRunner.CodeReviewRelativePath,
                                            generatedBy = reviewer.id,
                                            createdAt = now.minusSeconds(60),
                                            updatedAt = now.minusSeconds(60),
                                          )
          tuple                        <- makeRunner(
                                            repoPath = repoPath,
                                            agents = List(reviewer),
                                            processOutput = List("# Code Review Analysis", "", "## Code Quality and Patterns", "New content"),
                                            initialDocs = List(existingDoc),
                                          )
          (runner, _, _, _, _, docsRef) = tuple
          updated                      <- runner.runCodeReview("ws-1")
          docs                         <- docsRef.get
        yield assertTrue(
          updated.id == existingId,
          updated.content.contains("New content"),
          docs.keySet == Set(existingId),
          docs(existingId).content.contains("New content"),
        )
      },
      test("workspace prompt override wins over global prompt for architecture analysis") {
        for
          repoPath                                   <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-architecture"))
          architect                                   = agent(id = "architect-1", name = "architect", capabilities = List("architecture-analysis"))
          tuple                                      <- makeRunner(
                                                          repoPath = repoPath,
                                                          agents = List(architect),
                                                          settings = Map(
                                                            "analysis.architecture.prompt"                -> "GLOBAL-ARCH",
                                                            "workspace.ws-1.analysis.architecture.prompt" -> "WORKSPACE-ARCH",
                                                          ),
                                                          processOutput = List("## Module Boundaries and Package Structure", "Custom architecture output"),
                                                        )
          (runner, processRef, _, _, gitRef, docsRef) = tuple
          doc                                        <- runner.runArchitecture("ws-1")
          processCalls                               <- processRef.get
          gitCalls                                   <- gitRef.get
          docs                                       <- docsRef.get
          savedContent                               <- ZIO.attemptBlocking(
                                                          Files.readString(
                                                            repoPath
                                                              .resolve("workspaces")
                                                              .resolve("ws-1")
                                                              .resolve(AnalysisAgentRunner.ArchitectureRelativePath)
                                                          )
                                                        )
          promptArg                                   = processCalls.head.argv.drop(1).mkString(" ")
        yield assertTrue(
          doc.analysisType == AnalysisType.Architecture,
          doc.filePath == AnalysisAgentRunner.ArchitectureRelativePath,
          doc.content.startsWith("# Architecture Analysis"),
          savedContent == doc.content,
          promptArg.contains("WORKSPACE-ARCH"),
          !promptArg.contains("GLOBAL-ARCH"),
          gitCalls.map(_.argv.drop(1)) == Vector(
            List("add", "--", s"workspaces/ws-1/${AnalysisAgentRunner.ArchitectureRelativePath}"),
            List("diff", "--cached", "--quiet", "--exit-code"),
            List("commit", "-m", "Add architecture analysis for billing-service"),
          ),
          docs.size == 1,
        )
      },
      test("security analysis falls back to code-review capability and global prompt override") {
        for
          repoPath                        <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-security"))
          fallbackReviewer                 = agent(id = "reviewer-1", name = "reviewer", capabilities = List("code-review"))
          tuple                           <- makeRunner(
                                               repoPath = repoPath,
                                               agents = List(fallbackReviewer),
                                               settings = Map("analysis.security.prompt" -> "GLOBAL-SECURITY"),
                                               processOutput =
                                                 List("## Dependency Vulnerability Scan", "No obvious vulnerable pins in manifests."),
                                             )
          (runner, processRef, _, _, _, _) = tuple
          doc                             <- runner.runSecurity("ws-1")
          processCalls                    <- processRef.get
          promptArg                        = processCalls.head.argv.drop(1).mkString(" ")
        yield assertTrue(
          doc.analysisType == AnalysisType.Security,
          doc.filePath == AnalysisAgentRunner.SecurityRelativePath,
          doc.generatedBy == fallbackReviewer.id,
          doc.content.startsWith("# Security Analysis"),
          promptArg.contains("GLOBAL-SECURITY"),
        )
      },
      test("fails when no configured agent exists and no enabled code-review agent is available") {
        for
          repoPath               <- ZIO.attemptBlocking(Files.createTempDirectory("analysis-runner-no-agent"))
          writer                  = agent(id = "writer", name = "writer-agent", capabilities = List("code-generation"))
          tuple                  <- makeRunner(
                                      repoPath = repoPath,
                                      agents = List(writer),
                                      processOutput = List("# Code Review Analysis"),
                                    )
          (runner, _, _, _, _, _) = tuple
          result                 <- runner.runCodeReview("ws-1")
        yield assertTrue(
          result.analysisType == AnalysisType.CodeReview,
          result.filePath == AnalysisAgentRunner.CodeReviewRelativePath,
        )
      },
    ).provide(FileService.live)
