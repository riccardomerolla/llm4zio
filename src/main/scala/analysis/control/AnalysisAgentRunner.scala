package analysis.control

import java.nio.file.{ Files, Path, Paths }
import java.time.{ Duration as JavaDuration, Instant }

import zio.*
import zio.json.JsonCodec
import zio.schema.{ Schema, derived }

import _root_.config.entity.{ AIProvider, AIProviderConfig }
import agent.control.BuiltInAgentSynchronizer
import agent.entity.{ Agent, AgentRepository }
import analysis.entity.{ AnalysisDoc, AnalysisEvent, AnalysisRepository, AnalysisType }
import app.control.FileService
import db.TaskRepository
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutionContext, GeminiCliExecutor, HttpClient }
import orchestration.control.AgentConfigResolver
import shared.errors.FileError
import shared.ids.Ids
import workspace.control.CliAgentRunner
import workspace.entity.{ Workspace, WorkspaceRepository }

enum AnalysisAgentRunnerError(val message: String) derives JsonCodec, Schema:
  case WorkspaceNotFound(workspaceId: String)
    extends AnalysisAgentRunnerError(s"Workspace not found: $workspaceId")
  case WorkspaceDisabled(workspaceId: String)
    extends AnalysisAgentRunnerError(s"Workspace is disabled: $workspaceId")
  case ConfiguredAgentNotFound(agentName: String)
    extends AnalysisAgentRunnerError(s"Configured analysis agent not found: $agentName")
  case ConfiguredAgentDisabled(agentName: String)
    extends AnalysisAgentRunnerError(s"Configured analysis agent is disabled: $agentName")
  case NoAnalysisAgentAvailable(profile: String, workspaceId: String)
    extends AnalysisAgentRunnerError(s"No enabled $profile agent available for workspace: $workspaceId")
  case ProcessFailed(agentName: String, cause: String)
    extends AnalysisAgentRunnerError(s"Analysis process failed for $agentName: $cause")
  case ProcessTimedOut(agentName: String, timeout: JavaDuration)
    extends AnalysisAgentRunnerError(s"Analysis process timed out for $agentName after ${timeout.toSeconds}s")
  case NonZeroExit(agentName: String, exitCode: Int, output: String)
    extends AnalysisAgentRunnerError(s"Analysis process exited with code $exitCode for $agentName: $output")
  case EmptyOutput(agentName: String)
    extends AnalysisAgentRunnerError(s"Analysis agent $agentName returned empty output")
  case FileWriteFailed(path: String, cause: String)
    extends AnalysisAgentRunnerError(s"Failed to write analysis file at $path: $cause")
  case GitFailed(command: String, details: String)
    extends AnalysisAgentRunnerError(s"Git command failed: $command. $details")
  case WorkspacePersistenceFailed(operation: String, details: String)
    extends AnalysisAgentRunnerError(s"Workspace persistence failed during $operation: $details")
  case AnalysisPersistenceFailed(operation: String, details: String)
    extends AnalysisAgentRunnerError(s"Analysis persistence failed during $operation: $details")
  case SettingsPersistenceFailed(operation: String, details: String)
    extends AnalysisAgentRunnerError(s"Settings persistence failed during $operation: $details")
  case ProviderExecutionFailed(agentName: String, details: String)
    extends AnalysisAgentRunnerError(s"Analysis provider execution failed for $agentName: $details")

trait AnalysisAgentRunner:
  def runCodeReview(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc]
  def runArchitecture(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc]
  def runSecurity(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc]

object AnalysisAgentRunner:
  val CodeReviewAgentSettingKey: String   = "analysis.code-review.agent"
  val ArchitectureAgentSettingKey: String = "analysis.architecture.agent"
  val SecurityAgentSettingKey: String     = "analysis.security.agent"
  val CodeReviewRelativePath: String      = ".llm4zio/analysis/code-review.md"
  val ArchitectureRelativePath: String    = ".llm4zio/analysis/architecture.md"
  val SecurityRelativePath: String        = ".llm4zio/analysis/security.md"

  def runCodeReview(workspaceId: String): ZIO[AnalysisAgentRunner, AnalysisAgentRunnerError, AnalysisDoc] =
    ZIO.serviceWithZIO[AnalysisAgentRunner](_.runCodeReview(workspaceId))

  def runArchitecture(workspaceId: String): ZIO[AnalysisAgentRunner, AnalysisAgentRunnerError, AnalysisDoc] =
    ZIO.serviceWithZIO[AnalysisAgentRunner](_.runArchitecture(workspaceId))

  def runSecurity(workspaceId: String): ZIO[AnalysisAgentRunner, AnalysisAgentRunnerError, AnalysisDoc] =
    ZIO.serviceWithZIO[AnalysisAgentRunner](_.runSecurity(workspaceId))

  val live
    : ZLayer[
      WorkspaceRepository & AgentRepository & AnalysisRepository & TaskRepository & FileService &
        AgentConfigResolver & LlmService & HttpClient & GeminiCliExecutor,
      Nothing,
      AnalysisAgentRunner,
    ] =
    ZLayer.fromZIO {
      for
        workspaceRepository <- ZIO.service[WorkspaceRepository]
        agentRepository     <- ZIO.service[AgentRepository]
        analysisRepository  <- ZIO.service[AnalysisRepository]
        taskRepository      <- ZIO.service[TaskRepository]
        fileService         <- ZIO.service[FileService]
        configResolver      <- ZIO.service[AgentConfigResolver]
        llmService          <- ZIO.service[LlmService]
        httpClient          <- ZIO.service[HttpClient]
        cliExecutor         <- ZIO.service[GeminiCliExecutor]
        providerCache       <- Ref.Synchronized.make(Map.empty[LlmConfig, LlmService])
      yield AnalysisAgentRunnerLive(
        workspaceRepository = workspaceRepository,
        agentRepository = agentRepository,
        analysisRepository = analysisRepository,
        taskRepository = taskRepository,
        fileService = fileService,
        llmPromptExecutor = Some(
          promptExecutor(
            configResolver = configResolver,
            llmService = llmService,
            httpClient = httpClient,
            cliExecutor = cliExecutor,
            providerCache = providerCache,
          )
        ),
      )
    }

  private def promptExecutor(
    configResolver: AgentConfigResolver,
    llmService: LlmService,
    httpClient: HttpClient,
    cliExecutor: GeminiCliExecutor,
    providerCache: Ref.Synchronized[Map[LlmConfig, LlmService]],
  ): (Workspace, Agent, String) => IO[AnalysisAgentRunnerError, String] =
    (workspace, agent, prompt) =>
      executeWithAgentConfig(
        agentName = agent.name,
        prompt = prompt,
        workspacePath = workspace.localPath,
        configResolver = configResolver,
        llmService = llmService,
        httpClient = httpClient,
        cliExecutor = cliExecutor,
        providerCache = providerCache,
      ).mapBoth(
        err => AnalysisAgentRunnerError.ProviderExecutionFailed(agent.name, renderLlmError(err)),
        _.content,
      )

  private def executeWithAgentConfig(
    agentName: String,
    prompt: String,
    workspacePath: String,
    configResolver: AgentConfigResolver,
    llmService: LlmService,
    httpClient: HttpClient,
    cliExecutor: GeminiCliExecutor,
    providerCache: Ref.Synchronized[Map[LlmConfig, LlmService]],
  ): IO[LlmError, LlmResponse] =
    configResolver
      .resolveConfig(agentName)
      .either
      .flatMap {
        case Right(config) =>
          ZIO.logDebug(
            s"Analysis resolved provider=${config.provider}, model=${config.model}${
                renderFallbackChain(config)
              } for agent '$agentName'"
          ) *>
            withFailover(config, prompt, workspacePath, httpClient, cliExecutor, providerCache)
        case Left(err)     =>
          ZIO.logWarning(
            s"Failed to resolve AI config for agent '$agentName' ($err); falling back to default LLM service"
          ) *>
            llm4zio.core.Streaming.collect(llmService.executeStream(prompt))
      }

  private def withFailover(
    config: AIProviderConfig,
    prompt: String,
    workspacePath: String,
    httpClient: HttpClient,
    cliExecutor: GeminiCliExecutor,
    providerCache: Ref.Synchronized[Map[LlmConfig, LlmService]],
  ): IO[LlmError, LlmResponse] =
    val configs = fallbackConfigs(config)
    attemptWithFallback(
      configs = configs,
      attemptIndex = 1,
      totalAttempts = configs.size,
      prompt = prompt,
      workspacePath = workspacePath,
      httpClient = httpClient,
      cliExecutor = cliExecutor,
      providerCache = providerCache,
    )

  private def attemptWithFallback(
    configs: List[LlmConfig],
    attemptIndex: Int,
    totalAttempts: Int,
    prompt: String,
    workspacePath: String,
    httpClient: HttpClient,
    cliExecutor: GeminiCliExecutor,
    providerCache: Ref.Synchronized[Map[LlmConfig, LlmService]],
  ): IO[LlmError, LlmResponse] =
    configs match
      case Nil              => ZIO.fail(LlmError.ConfigError("No LLM provider configured"))
      case cfg :: remaining =>
        ZIO.logDebug(
          s"Analysis LLM attempt $attemptIndex/$totalAttempts using provider=${cfg.provider}, model=${cfg.model}"
        ) *>
          executeWithConfig(cfg, prompt, workspacePath, httpClient, cliExecutor, providerCache)
            .catchAll { err =>
              val failureLog =
                ZIO.logWarning(
                  s"Analysis LLM attempt $attemptIndex/$totalAttempts failed for provider=${cfg.provider}, model=${
                      cfg.model
                    }: ${renderLlmError(err)}"
                )
              remaining match
                case Nil =>
                  failureLog *> ZIO.fail(err)
                case _   =>
                  failureLog *>
                    attemptWithFallback(
                      configs = remaining,
                      attemptIndex = attemptIndex + 1,
                      totalAttempts = totalAttempts,
                      prompt = prompt,
                      workspacePath = workspacePath,
                      httpClient = httpClient,
                      cliExecutor = cliExecutor,
                      providerCache = providerCache,
                    )
            }

  private def executeWithConfig(
    cfg: LlmConfig,
    prompt: String,
    workspacePath: String,
    httpClient: HttpClient,
    cliExecutor: GeminiCliExecutor,
    providerCache: Ref.Synchronized[Map[LlmConfig, LlmService]],
  ): IO[LlmError, LlmResponse] =
    cfg.provider match
      case LlmProvider.GeminiCli =>
        llm4zio.core.Streaming.collect(
          llm4zio.providers.GeminiCliProvider
            .make(
              config = cfg,
              executor = cliExecutor,
              executionContext = GeminiCliExecutionContext(
                cwd = Some(workspacePath),
                includeDirectories = List(workspacePath),
              ),
            )
            .executeStream(prompt)
        )
      case _                     =>
        providerFor(cfg, httpClient, cliExecutor, providerCache).flatMap(svc => llm4zio.core.Streaming.collect(svc.executeStream(prompt)))

  private def fallbackConfigs(primary: AIProviderConfig): List[LlmConfig] =
    val primaryLlm = aiConfigToLlmConfig(primary)
    val fallback   = primary.fallbackChain.models.map { ref =>
      aiConfigToLlmConfig(
        AIProviderConfig.withDefaults(
          primary.copy(
            provider = ref.provider.getOrElse(primary.provider),
            model = ref.modelId,
          )
        )
      )
    }
    (primaryLlm :: fallback).distinct

  private def providerFor(
    cfg: LlmConfig,
    httpClient: HttpClient,
    cliExecutor: GeminiCliExecutor,
    providerCache: Ref.Synchronized[Map[LlmConfig, LlmService]],
  ): IO[LlmError, LlmService] =
    providerCache.modifyZIO { current =>
      current.get(cfg) match
        case Some(existing) => ZIO.succeed((existing, current))
        case None           =>
          ZIO
            .attempt(buildProvider(cfg, httpClient, cliExecutor))
            .mapError(th => LlmError.ConfigError(Option(th.getMessage).getOrElse(th.toString)))
            .map(created => (created, current + (cfg -> created)))
    }

  private def buildProvider(
    cfg: LlmConfig,
    httpClient: HttpClient,
    cliExecutor: GeminiCliExecutor,
  ): LlmService =
    cfg.provider match
      case LlmProvider.GeminiCli => llm4zio.providers.GeminiCliProvider.make(cfg, cliExecutor)
      case LlmProvider.GeminiApi => llm4zio.providers.GeminiApiProvider.make(cfg, httpClient)
      case LlmProvider.OpenAI    => llm4zio.providers.OpenAIProvider.make(cfg, httpClient)
      case LlmProvider.Anthropic => llm4zio.providers.AnthropicProvider.make(cfg, httpClient)
      case LlmProvider.LmStudio  => llm4zio.providers.LmStudioProvider.make(cfg, httpClient)
      case LlmProvider.Ollama    => llm4zio.providers.OllamaProvider.make(cfg, httpClient)
      case LlmProvider.OpenCode  => llm4zio.providers.OpenCodeProvider.make(cfg, httpClient)

  private def aiConfigToLlmConfig(aiConfig: AIProviderConfig): LlmConfig =
    LlmConfig(
      provider = aiProviderToLlmProvider(aiConfig.provider),
      model = aiConfig.model,
      baseUrl = aiConfig.baseUrl,
      apiKey = aiConfig.apiKey,
      timeout = aiConfig.timeout,
      maxRetries = aiConfig.maxRetries,
      requestsPerMinute = aiConfig.requestsPerMinute,
      burstSize = aiConfig.burstSize,
      acquireTimeout = aiConfig.acquireTimeout,
      temperature = aiConfig.temperature,
      maxTokens = aiConfig.maxTokens,
    )

  private def aiProviderToLlmProvider(aiProvider: AIProvider): LlmProvider =
    aiProvider match
      case AIProvider.GeminiCli => LlmProvider.GeminiCli
      case AIProvider.GeminiApi => LlmProvider.GeminiApi
      case AIProvider.OpenAi    => LlmProvider.OpenAI
      case AIProvider.Anthropic => LlmProvider.Anthropic
      case AIProvider.LmStudio  => LlmProvider.LmStudio
      case AIProvider.Ollama    => LlmProvider.Ollama
      case AIProvider.OpenCode  => LlmProvider.OpenCode

  private def renderLlmError(err: LlmError): String =
    err match
      case LlmError.ParseError(message, raw)     =>
        val sample = raw.replace("\r\n", "\n").trim.take(400)
        s"ParseError(message=$message, raw=$sample)"
      case LlmError.ProviderError(message, _)    => s"ProviderError(message=$message)"
      case LlmError.AuthenticationError(message) => s"AuthenticationError(message=$message)"
      case LlmError.InvalidRequestError(message) => s"InvalidRequestError(message=$message)"
      case LlmError.TimeoutError(duration)       => s"TimeoutError(duration=${duration.render})"
      case LlmError.ToolError(toolName, message) => s"ToolError(tool=$toolName, message=$message)"
      case LlmError.ConfigError(message)         => s"ConfigError(message=$message)"
      case LlmError.RateLimitError(retryAfter)   =>
        retryAfter.fold("RateLimitError")(duration => s"RateLimitError(retryAfter=${duration.render})")

  private def renderFallbackChain(config: AIProviderConfig): String =
    val fallbacks = config.fallbackChain.models.map { ref =>
      val provider = ref.provider.getOrElse(config.provider)
      s"$provider:${ref.modelId}"
    }
    if fallbacks.isEmpty then ""
    else s", fallbacks=[${fallbacks.mkString(", ")}]"

final case class AnalysisAgentRunnerLive(
  workspaceRepository: WorkspaceRepository,
  agentRepository: AgentRepository,
  analysisRepository: AnalysisRepository,
  taskRepository: TaskRepository,
  fileService: FileService,
  llmPromptExecutor: Option[(Workspace, Agent, String) => IO[AnalysisAgentRunnerError, String]] = None,
  processRunner: (List[String], String, String => Task[Unit], Map[String, String]) => Task[Int] =
    CliAgentRunner.runProcessStreaming,
  commandRunner: (List[String], String, Map[String, String]) => Task[(List[String], Int)] =
    CliAgentRunner.runProcess,
  gitRunner: (List[String], String) => Task[(List[String], Int)] =
    (argv, cwd) => CliAgentRunner.runProcess(argv, cwd),
) extends AnalysisAgentRunner:

  override def runCodeReview(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc] =
    runAnalysis(workspaceId, AnalysisProfile.codeReview)

  override def runArchitecture(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc] =
    runAnalysis(workspaceId, AnalysisProfile.architecture)

  override def runSecurity(workspaceId: String): IO[AnalysisAgentRunnerError, AnalysisDoc] =
    runAnalysis(workspaceId, AnalysisProfile.security)

  private def runAnalysis(workspaceId: String, profile: AnalysisProfile): IO[AnalysisAgentRunnerError, AnalysisDoc] =
    for
      _         <- ZIO.logDebug(s"Starting ${profile.slug} analysis for workspace $workspaceId")
      workspace <- loadWorkspace(workspaceId)
      agent     <- selectAgent(workspace, profile)
      _         <- ZIO.logDebug(
                     s"Selected agent '${agent.name}' for ${profile.slug} analysis in workspace '${workspace.name}'"
                   )
      prompt    <- resolvePrompt(workspace, agent, profile)
      _         <- ZIO.logDebug(
                     s"Executing ${profile.slug} analysis for workspace '${workspace.name}' with agent '${agent.name}'"
                   )
      markdown  <- executeReview(workspace, agent, profile, prompt)
      filePath   = Paths.get(workspace.localPath).resolve(profile.relativePath)
      _         <- writeAnalysisFile(filePath, markdown)
      _         <- commitAnalysisFile(workspace, profile)
      doc       <- persistAnalysisDoc(workspace, agent, profile, markdown, filePath)
    yield doc

  private def loadWorkspace(workspaceId: String): IO[AnalysisAgentRunnerError, Workspace] =
    workspaceRepository
      .get(workspaceId)
      .mapError(err => AnalysisAgentRunnerError.WorkspacePersistenceFailed("getWorkspace", err.toString))
      .flatMap {
        case Some(workspace) if workspace.enabled => ZIO.succeed(workspace)
        case Some(_)                              => ZIO.fail(AnalysisAgentRunnerError.WorkspaceDisabled(workspaceId))
        case None                                 => ZIO.fail(AnalysisAgentRunnerError.WorkspaceNotFound(workspaceId))
      }

  private def selectAgent(workspace: Workspace, profile: AnalysisProfile): IO[AnalysisAgentRunnerError, Agent] =
    configuredAgent(profile).flatMap {
      case Some(name) =>
        agentRepository
          .findByName(name)
          .mapError(err => AnalysisAgentRunnerError.AnalysisPersistenceFailed("findConfiguredAgent", err.toString))
          .flatMap {
            case Some(agent) if agent.enabled => ZIO.succeed(agent)
            case Some(_)                      => ZIO.fail(AnalysisAgentRunnerError.ConfiguredAgentDisabled(name))
            case None                         =>
              builtInAgentByName(name)
                .orElseFail(AnalysisAgentRunnerError.ConfiguredAgentNotFound(name))
          }
      case None       =>
        agentRepository
          .list()
          .mapError(err => AnalysisAgentRunnerError.AnalysisPersistenceFailed("listAgents", err.toString))
          .flatMap { persistedAgents =>
            val availableAgents =
              candidateAgentsForProfile(persistedAgents, profile)
            ZIO
              .fromOption(availableAgents.headOption)
              .orElseFail(AnalysisAgentRunnerError.NoAnalysisAgentAvailable(profile.slug, workspace.id))
          }
    }

  private def builtInAgentByName(name: String): IO[AnalysisAgentRunnerError, Agent] =
    Clock.instant.map(now =>
      BuiltInAgentSynchronizer
        .seedBuiltInAgents(now)
        .find(_.name.equalsIgnoreCase(name.trim))
    )
      .flatMap(ZIO.fromOption(_))
      .orElseFail(AnalysisAgentRunnerError.ConfiguredAgentNotFound(name))

  private def mergedAgentsWithBuiltIns(persistedAgents: List[Agent]): List[Agent] =
    val persistedNames = persistedAgents.map(_.name.trim.toLowerCase).toSet
    persistedAgents ++
      BuiltInAgentSynchronizer
        .seedBuiltInAgents(Instant.EPOCH)
        .filterNot(agent => persistedNames.contains(agent.name.trim.toLowerCase))

  private def candidateAgentsForProfile(persistedAgents: List[Agent], profile: AnalysisProfile): List[Agent] =
    val persistedMatches =
      persistedAgents
        .filter(agent => agent.enabled && hasProfileCapability(agent, profile))
        .sortBy(_.name.toLowerCase)
    if persistedMatches.nonEmpty then persistedMatches
    else
      mergedAgentsWithBuiltIns(persistedAgents)
        .drop(persistedAgents.size)
        .filter(agent => agent.enabled && hasProfileCapability(agent, profile))
        .sortBy(_.name.toLowerCase)

  private def configuredAgent(profile: AnalysisProfile): IO[AnalysisAgentRunnerError, Option[String]] =
    taskRepository
      .getSetting(profile.agentSettingKey)
      .mapError(err =>
        AnalysisAgentRunnerError.SettingsPersistenceFailed(s"get${profile.name}AgentSetting", err.toString)
      )
      .map(_.map(_.value.trim).filter(_.nonEmpty))

  private def resolvePrompt(
    workspace: Workspace,
    agent: Agent,
    profile: AnalysisProfile,
  ): IO[AnalysisAgentRunnerError, String] =
    for
      workspaceOverride <- setting(workspacePromptKey(workspace.id, profile))
      globalOverride    <- setting(globalPromptKey(profile))
    yield composePrompt(
      workspace = workspace,
      agent = agent,
      profile = profile,
      body = workspaceOverride.orElse(globalOverride).getOrElse(profile.defaultPromptBody),
    )

  private def setting(key: String): IO[AnalysisAgentRunnerError, Option[String]] =
    taskRepository
      .getSetting(key)
      .mapError(err => AnalysisAgentRunnerError.SettingsPersistenceFailed(s"getSetting:$key", err.toString))
      .map(_.map(_.value.trim).filter(_.nonEmpty))

  private def executeReview(
    workspace: Workspace,
    agent: Agent,
    profile: AnalysisProfile,
    prompt: String,
  ): IO[AnalysisAgentRunnerError, String] =
    llmPromptExecutor match
      case Some(executeWithProvider) =>
        ZIO.logDebug(
          s"Running ${profile.slug} analysis via AI provider for agent '${agent.name}' in workspace '${workspace.name}'"
        ) *>
          executeWithProvider(workspace, agent, prompt).flatMap { raw =>
            ZIO
              .fromEither(normalizeMarkdown(raw, profile.documentTitle))
              .mapError(_ => AnalysisAgentRunnerError.EmptyOutput(agent.name))
          }
      case None                      =>
        if agent.cliTool.trim.equalsIgnoreCase("codex") then
          executeCodexReview(workspace, agent, profile, prompt)
        else
          executeStreamingReview(workspace, agent, profile, prompt)

  private def executeStreamingReview(
    workspace: Workspace,
    agent: Agent,
    profile: AnalysisProfile,
    prompt: String,
  ): IO[AnalysisAgentRunnerError, String] =
    for
      _         <- ZIO.logDebug(
                     s"Running ${profile.slug} analysis via CLI tool '${agent.cliTool}' for workspace '${workspace.name}'"
                   )
      outputRef <- Ref.make(Vector.empty[String])
      argv       = CliAgentRunner.buildArgv(
                     cliTool = agent.cliTool,
                     prompt = prompt,
                     worktreePath = workspace.localPath,
                     runMode = workspace.runMode,
                     repoPath = workspace.localPath,
                     envVars = agent.envVars,
                     dockerMemoryLimit = agent.dockerMemoryLimit,
                     dockerCpuLimit = agent.dockerCpuLimit,
                   )
      exitCode  <- processRunner(
                     argv,
                     workspace.localPath,
                     line => outputRef.update(_ :+ line),
                     agent.envVars,
                   )
                     .mapError(err => AnalysisAgentRunnerError.ProcessFailed(agent.name, err.getMessage))
                     .timeoutFail(AnalysisAgentRunnerError.ProcessTimedOut(agent.name, agent.timeout))(
                       zio.Duration.fromJava(agent.timeout)
                     )
      output    <- outputRef.get.map(_.mkString("\n").trim)
      _         <- ZIO.fail(AnalysisAgentRunnerError.NonZeroExit(agent.name, exitCode, output)).when(exitCode != 0)
      markdown  <- ZIO
                     .fromEither(normalizeMarkdown(output, profile.documentTitle))
                     .mapError(_ => AnalysisAgentRunnerError.EmptyOutput(agent.name))
    yield markdown

  private def executeCodexReview(
    workspace: Workspace,
    agent: Agent,
    profile: AnalysisProfile,
    prompt: String,
  ): IO[AnalysisAgentRunnerError, String] =
    ZIO.acquireReleaseWith(createCodexOutputFile(workspace, profile))(deleteIfExists) { outputFile =>
      val argv = List(
        "codex",
        "exec",
        "--skip-git-repo-check",
        "--color",
        "never",
        "--output-last-message",
        outputFile.toString,
        prompt,
      )
      for
        _                <- ZIO.logDebug(
                              s"Running ${profile.slug} analysis via Codex for workspace '${workspace.name}' (agent='${agent.name}')"
                            )
        result           <- commandRunner(argv, workspace.localPath, agent.envVars)
                              .mapError(err => AnalysisAgentRunnerError.ProcessFailed(agent.name, err.getMessage))
                              .timeoutFail(AnalysisAgentRunnerError.ProcessTimedOut(agent.name, agent.timeout))(
                                zio.Duration.fromJava(agent.timeout)
                              )
        (lines, exitCode) = result
        stdout            = lines.mkString("\n").trim
        _                <- ZIO.fail(AnalysisAgentRunnerError.NonZeroExit(agent.name, exitCode, stdout)).when(exitCode != 0)
        raw              <- ZIO.attemptBlocking(Files.readString(outputFile)).mapError(err =>
                              AnalysisAgentRunnerError.FileWriteFailed(outputFile.toString, s"readCodexOutput: ${err.getMessage}")
                            )
        markdown         <- ZIO
                              .fromEither(normalizeMarkdown(raw, profile.documentTitle))
                              .mapError(_ => AnalysisAgentRunnerError.EmptyOutput(agent.name))
      yield markdown
    }

  private def createCodexOutputFile(
    workspace: Workspace,
    profile: AnalysisProfile,
  ): IO[AnalysisAgentRunnerError, Path] =
    ZIO.attemptBlocking {
      val dir = Paths.get(workspace.localPath).resolve(".llm4zio").resolve("analysis").resolve(".tmp")
      Files.createDirectories(dir)
      Files.createTempFile(dir, s"${profile.slug}-", ".md")
    }.mapError(err =>
      AnalysisAgentRunnerError.FileWriteFailed(
        workspace.localPath,
        s"createCodexOutputFile: ${err.getMessage}",
      )
    )

  private def deleteIfExists(path: Path): UIO[Unit] =
    ZIO.attemptBlocking(Files.deleteIfExists(path)).ignore

  private def writeAnalysisFile(path: Path, markdown: String): IO[AnalysisAgentRunnerError, Unit] =
    for
      _ <- fileService
             .ensureDirectory(path.getParent)
             .mapError(mapFileError("ensureAnalysisDir", path))
      _ <- fileService
             .writeFileAtomic(path, markdown)
             .mapError(mapFileError("writeAnalysisFile", path))
    yield ()

  private def commitAnalysisFile(workspace: Workspace, profile: AnalysisProfile): IO[AnalysisAgentRunnerError, Unit] =
    for
      _             <- runGitChecked(workspace.localPath, "git add", List("add", "--", profile.relativePath))
      (_, diffExit) <- runGit(workspace.localPath, List("diff", "--cached", "--quiet", "--exit-code"))
      _             <- diffExit match
                         case 0    => ZIO.unit
                         case 1    =>
                           runGitChecked(
                             workspace.localPath,
                             "git commit",
                             List("commit", "-m", s"Add ${profile.commitLabel} analysis for ${workspace.name}"),
                           )
                         case code =>
                           ZIO.fail(
                             AnalysisAgentRunnerError.GitFailed(
                               "git diff --cached --quiet --exit-code",
                               s"exit=$code",
                             )
                           )
    yield ()

  private def persistAnalysisDoc(
    workspace: Workspace,
    agent: Agent,
    profile: AnalysisProfile,
    markdown: String,
    absolutePath: Path,
  ): IO[AnalysisAgentRunnerError, AnalysisDoc] =
    for
      now      <- Clock.instant
      existing <-
        analysisRepository
          .listByWorkspace(workspace.id)
          .mapError(err => AnalysisAgentRunnerError.AnalysisPersistenceFailed("listByWorkspace", err.toString))
          .map(
            _.find(doc =>
              doc.analysisType == profile.analysisType && doc.filePath == profile.relativePath
            )
          )
      doc      <- existing match
                    case Some(current) =>
                      val updated = current.copy(content = markdown, updatedAt = now)
                      analysisRepository
                        .append(AnalysisEvent.AnalysisUpdated(current.id, markdown, now))
                        .mapError(err =>
                          AnalysisAgentRunnerError.AnalysisPersistenceFailed("appendAnalysisUpdated", err.toString)
                        )
                        .as(updated)
                    case None          =>
                      val docId   = Ids.AnalysisDocId.generate
                      val created = AnalysisDoc(
                        id = docId,
                        workspaceId = workspace.id,
                        analysisType = profile.analysisType,
                        content = markdown,
                        filePath = profile.relativePath,
                        generatedBy = agent.id,
                        createdAt = now,
                        updatedAt = now,
                      )
                      analysisRepository
                        .append(
                          AnalysisEvent.AnalysisCreated(
                            docId = docId,
                            workspaceId = workspace.id,
                            analysisType = profile.analysisType,
                            content = markdown,
                            filePath = profile.relativePath,
                            generatedBy = agent.id,
                            occurredAt = now,
                          )
                        )
                        .mapError(err =>
                          AnalysisAgentRunnerError.AnalysisPersistenceFailed("appendAnalysisCreated", err.toString)
                        )
                        .as(created)
      _        <- ZIO.logInfo(s"Saved ${profile.slug} analysis for workspace ${workspace.id} at $absolutePath")
    yield doc

  private def composePrompt(
    workspace: Workspace,
    agent: Agent,
    profile: AnalysisProfile,
    body: String,
  ): String =
    val agentInstructions = agent.systemPrompt.map(_.trim).filter(_.nonEmpty).map(_ + "\n\n").getOrElse("")
    s"""${agentInstructions}Perform a read-only ${profile.analysisLabel} for the repository at:
       |${workspace.localPath}
       |
       |Do not modify files. Inspect the repository and return markdown only.
       |
       |Return markdown only.
       |
       |$body
       |""".stripMargin

  private def hasProfileCapability(agent: Agent, profile: AnalysisProfile): Boolean =
    val capabilities = agent.capabilities.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet
    profile.capabilities.exists(capabilities.contains)

  private def runGit(repoPath: String, args: List[String]): IO[AnalysisAgentRunnerError, (List[String], Int)] =
    gitRunner("git" :: args, repoPath)
      .mapError(err =>
        AnalysisAgentRunnerError.GitFailed(
          s"git ${args.mkString(" ")}",
          err.getMessage,
        )
      )

  private def runGitChecked(repoPath: String, commandLabel: String, args: List[String])
    : IO[AnalysisAgentRunnerError, Unit] =
    runGit(repoPath, args).flatMap {
      case (lines, exitCode) =>
        if exitCode == 0 then ZIO.unit
        else ZIO.fail(AnalysisAgentRunnerError.GitFailed(commandLabel, lines.mkString("\n").trim))
    }

  private def mapFileError(operation: String, path: Path)(error: FileError): AnalysisAgentRunnerError =
    AnalysisAgentRunnerError.FileWriteFailed(path.toString, s"$operation: ${error.message}")

  private[control] def normalizeMarkdown(raw: String, documentTitle: String): Either[String, String] =
    val normalized = raw.replace("\r\n", "\n").trim
    if normalized.isEmpty then Left("empty")
    else
      val lines     = normalized.linesIterator.toList
      val unwrapped =
        if lines.headOption.exists(_.trim.startsWith("```")) && lines.lastOption.exists(_.trim == "```") then
          lines.drop(1).dropRight(1)
        else lines
      val body      = unwrapped.mkString("\n").trim
      if body.isEmpty then Left("empty")
      else if body.linesIterator.take(1).exists(_.trim.equalsIgnoreCase(s"# $documentTitle")) then
        Right(body + "\n")
      else Right(s"# $documentTitle\n\n$body\n")

  private def globalPromptKey(profile: AnalysisProfile): String =
    s"analysis.${profile.slug}.prompt"

  private def workspacePromptKey(workspaceId: String, profile: AnalysisProfile): String =
    s"workspace.$workspaceId.analysis.${profile.slug}.prompt"

final private case class AnalysisProfile(
  name: String,
  slug: String,
  analysisType: AnalysisType,
  relativePath: String,
  agentSettingKey: String,
  analysisLabel: String,
  documentTitle: String,
  commitLabel: String,
  capabilities: List[String],
  defaultPromptBody: String,
)

object AnalysisProfile:
  val codeReview: AnalysisProfile =
    AnalysisProfile(
      name = "CodeReview",
      slug = "code-review",
      analysisType = AnalysisType.CodeReview,
      relativePath = AnalysisAgentRunner.CodeReviewRelativePath,
      agentSettingKey = AnalysisAgentRunner.CodeReviewAgentSettingKey,
      analysisLabel = "code review analysis",
      documentTitle = "Code Review Analysis",
      commitLabel = "code review",
      capabilities = List("code-review"),
      defaultPromptBody =
        """Focus on:
          |- Code quality and patterns
          |- Technical debt areas
          |- Test coverage assessment
          |- Naming conventions and consistency
          |- Potential bug patterns
          |
          |Use exactly this structure:
          |# Code Review Analysis
          |
          |## Code Quality and Patterns
          |Provide concrete findings with relevant files or modules when known.
          |
          |## Technical Debt Areas
          |Describe notable maintenance risks or cleanup opportunities.
          |
          |## Test Coverage Assessment
          |Call out missing coverage, weak assertions, or risky untested paths.
          |
          |## Naming Conventions and Consistency
          |Highlight inconsistent names, APIs, or structural conventions.
          |
          |## Potential Bug Patterns
          |List likely correctness or regression risks with concise rationale.
          |
          |If a section has no material issues, say so explicitly.""".stripMargin,
    )

  val architecture: AnalysisProfile =
    AnalysisProfile(
      name = "Architecture",
      slug = "architecture",
      analysisType = AnalysisType.Architecture,
      relativePath = AnalysisAgentRunner.ArchitectureRelativePath,
      agentSettingKey = AnalysisAgentRunner.ArchitectureAgentSettingKey,
      analysisLabel = "architecture analysis",
      documentTitle = "Architecture Analysis",
      commitLabel = "architecture",
      capabilities = List("architecture-analysis", "architecture", "code-review"),
      defaultPromptBody =
        """Focus on:
          |- Module boundaries and package structure
          |- Dependency graph with a mermaid diagram
          |- API surface inventory
          |- Design pattern identification
          |- Coupling and cohesion assessment
          |- Recommended improvements
          |
          |Use exactly this structure:
          |# Architecture Analysis
          |
          |## Module Boundaries and Package Structure
          |Describe the main architectural slices and responsibilities.
          |
          |## Dependency Graph
          |Include a mermaid diagram showing major modules and dependencies.
          |
          |## API Surface Inventory
          |Summarize the primary external and internal interfaces.
          |
          |## Design Pattern Identification
          |Identify recurring design patterns and noteworthy architectural conventions.
          |
          |## Coupling and Cohesion Assessment
          |Call out tight coupling, weak cohesion, or layering problems.
          |
          |## Recommended Improvements
          |List pragmatic architectural improvements in priority order.
          |
          |If a section has no material issues, say so explicitly.""".stripMargin,
    )

  val security: AnalysisProfile =
    AnalysisProfile(
      name = "Security",
      slug = "security",
      analysisType = AnalysisType.Security,
      relativePath = AnalysisAgentRunner.SecurityRelativePath,
      agentSettingKey = AnalysisAgentRunner.SecurityAgentSettingKey,
      analysisLabel = "security analysis",
      documentTitle = "Security Analysis",
      commitLabel = "security",
      capabilities = List("security-analysis", "security-review", "security", "code-review"),
      defaultPromptBody =
        """Focus on:
          |- Dependency vulnerability scan signals visible in the repository
          |- Secret and credential exposure risks
          |- Input validation gaps
          |- Authentication and authorization patterns
          |- OWASP top-10 assessment
          |- Security recommendations
          |
          |Use exactly this structure:
          |# Security Analysis
          |
          |## Dependency Vulnerability Scan
          |Review dependency manifests and call out notable version or supply-chain risks.
          |
          |## Secret and Credential Exposure Risks
          |Highlight embedded secrets, risky config defaults, or unsafe handling patterns.
          |
          |## Input Validation Gaps
          |Assess validation, encoding, and sanitization boundaries.
          |
          |## Authentication and Authorization Patterns
          |Summarize authn/authz design and any obvious weaknesses.
          |
          |## OWASP Top 10 Assessment
          |Map likely risks to OWASP categories where relevant.
          |
          |## Security Recommendations
          |List the highest-leverage security improvements.
          |
          |If a section has no material issues, say so explicitly.""".stripMargin,
    )
