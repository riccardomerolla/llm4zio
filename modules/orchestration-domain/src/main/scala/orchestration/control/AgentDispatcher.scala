package orchestration.control

import java.nio.file.{ Files, Paths }
import java.time.Instant

import zio.*

import _root_.config.entity.{ ConfigRepository, ProviderConfig }
import agent.entity.AgentRegistry
import llm4zio.core.*
import llm4zio.providers.{ GeminiCliExecutor, HttpClient }
import memory.entity.{ Scope, * }
import shared.errors.PersistenceError
import taskrun.entity.{TaskArtifactRow, TaskReportRow, TaskRepository}

final case class StepDispatchResult(
  agentName: String,
  content: String,
  completedAt: Instant,
)

trait AgentDispatcher:
  def dispatch(
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): IO[PersistenceError, StepDispatchResult]

object AgentDispatcher:
  def dispatch(
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): ZIO[AgentDispatcher, PersistenceError, StepDispatchResult] =
    ZIO.serviceWithZIO[AgentDispatcher](_.dispatch(stepPlan, taskRunId))

  val live
    : ZLayer[
      TaskRepository & AgentRegistry & LlmService & MemoryRepository & ConfigRepository & AgentConfigResolver &
        HttpClient & GeminiCliExecutor,
      Nothing,
      AgentDispatcher,
    ] =
    ZLayer.fromZIO {
      for
        repository     <- ZIO.service[TaskRepository]
        registry       <- ZIO.service[AgentRegistry]
        llmService     <- ZIO.service[LlmService]
        memoryRepo     <- ZIO.service[MemoryRepository]
        configRepo     <- ZIO.service[ConfigRepository]
        configResolver <- ZIO.service[AgentConfigResolver]
        httpClient     <- ZIO.service[HttpClient]
        cliExecutor    <- ZIO.service[GeminiCliExecutor]
        providerCache  <- Ref.Synchronized.make(Map.empty[LlmConfig, LlmService])
      yield AgentDispatcherLive(
        repository = repository,
        registry = registry,
        llmService = llmService,
        memoryRepository = memoryRepo,
        configRepository = configRepo,
        configResolver = configResolver,
        httpClient = httpClient,
        cliExecutor = cliExecutor,
        providerCache = providerCache,
      )
    }

final case class AgentDispatcherLive(
  repository: TaskRepository,
  registry: AgentRegistry,
  llmService: LlmService,
  memoryRepository: MemoryRepository,
  configRepository: ConfigRepository,
  configResolver: AgentConfigResolver,
  httpClient: HttpClient,
  cliExecutor: GeminiCliExecutor,
  providerCache: Ref.Synchronized[Map[LlmConfig, LlmService]],
) extends AgentDispatcher:

  override def dispatch(
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): IO[PersistenceError, StepDispatchResult] =
    val candidates = (stepPlan.assignedAgent.toList ++ stepPlan.fallbackAgents).map(_.trim).filter(_.nonEmpty).distinct
    candidates match
      case Nil => ZIO.fail(PersistenceError.QueryFailed("dispatch", s"No agent assigned for step '${stepPlan.step}'"))
      case _   => dispatchWithFallback(candidates, stepPlan, taskRunId)

  private def dispatchWithFallback(
    candidates: List[String],
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): IO[PersistenceError, StepDispatchResult] =
    candidates match
      case Nil             =>
        ZIO.fail(
          PersistenceError.QueryFailed(
            "dispatch",
            s"All assigned/fallback agents failed for step '${stepPlan.step}'",
          )
        )
      case agentName :: xs =>
        dispatchWithRetries(agentName, stepPlan, taskRunId, stepPlan.retryLimit)
          .orElse(dispatchWithFallback(xs, stepPlan, taskRunId))

  private def dispatchWithRetries(
    agentName: String,
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
    retryLimit: Int,
  ): IO[PersistenceError, StepDispatchResult] =
    dispatchOnce(agentName, stepPlan, taskRunId).retry(Schedule.recurs(Math.max(0, retryLimit)))

  private def dispatchOnce(
    agentName: String,
    stepPlan: WorkflowStepPlan,
    taskRunId: Long,
  ): IO[PersistenceError, StepDispatchResult] =
    for
      startedAtNanos  <- Clock.nanoTime
      attemptedName   <- ZIO.succeed(agentName)
      result          <- (for
                           now            <- Clock.instant
                           agentInfo      <- registry.findByName(attemptedName).flatMap {
                                               case Some(value) => ZIO.succeed(value)
                                               case None        =>
                                                 ZIO.fail(
                                                   PersistenceError.QueryFailed(
                                                     "dispatch",
                                                     s"Agent '$attemptedName' not found in registry",
                                                   )
                                                 )
                                             }
                           customPrompt   <- repository
                                               .getCustomAgentByName(agentInfo.name)
                                               .map(_.map(_.systemPrompt).filter(_.trim.nonEmpty))
                           run            <-
                             repository.getRun(taskRunId).someOrFail(PersistenceError.NotFound("task_runs", taskRunId.toString))
                           agentWorkspace <- ensureAgentWorkspace(taskRunId, agentInfo.name)
                           prompt          = buildPrompt(
                                               systemPrompt = customPrompt,
                                               step = stepPlan.step,
                                               taskRunId = taskRunId,
                                               workflowId = run.workflowId,
                                               currentPhase = run.currentPhase,
                                             )
                           response       <- executeWithAgentConfig(agentInfo.name, prompt)
                                               .mapError(err => PersistenceError.QueryFailed("llm.execute", err.toString))
                           completedAt    <- Clock.instant
                           _              <- repository.saveReport(
                                               TaskReportRow(
                                                 id = 0L,
                                                 taskRunId = taskRunId,
                                                 stepName = stepPlan.step,
                                                 reportType = "markdown",
                                                 content = response.content,
                                                 createdAt = completedAt,
                                               )
                                             )
                           _              <- repository.saveArtifact(
                                               TaskArtifactRow(
                                                 id = 0L,
                                                 taskRunId = taskRunId,
                                                 stepName = stepPlan.step,
                                                 key = "step.agent",
                                                 value = agentInfo.name,
                                                 createdAt = completedAt,
                                               )
                                             )
                           _              <- repository.saveArtifact(
                                               TaskArtifactRow(
                                                 id = 0L,
                                                 taskRunId = taskRunId,
                                                 stepName = stepPlan.step,
                                                 key = "step.nodeId",
                                                 value = stepPlan.nodeId,
                                                 createdAt = completedAt,
                                               )
                                             )
                           _              <- repository.saveArtifact(
                                               TaskArtifactRow(
                                                 id = 0L,
                                                 taskRunId = taskRunId,
                                                 stepName = stepPlan.step,
                                                 key = "step.agentWorkspace",
                                                 value = agentWorkspace.path.toString,
                                                 createdAt = completedAt,
                                               )
                                             )
                           _              <- persistMemoryArtifacts(taskRunId, stepPlan.step, stepPlan.nodeId, response.content).forkDaemon
                         yield StepDispatchResult(
                           agentName = agentInfo.name,
                           content = response.content,
                           completedAt = completedAt,
                         )).exit
      finishedAtNanos <- Clock.nanoTime
      latencyMs        = Math.max(0L, (finishedAtNanos - startedAtNanos) / 1000000L)
      _               <- result match
                           case Exit.Success(value) =>
                             registry.recordInvocation(value.agentName, success = true, latencyMs = latencyMs) *>
                               registry.updateHealth(value.agentName, success = true, message = Some("Execution completed"))
                           case Exit.Failure(_)     =>
                             registry.recordInvocation(attemptedName, success = false, latencyMs = latencyMs) *>
                               registry.updateHealth(attemptedName, success = false, message = Some("Execution failed"))
      value           <- result match
                           case Exit.Success(v) => ZIO.succeed(v)
                           case Exit.Failure(c) => ZIO.failCause(c)
    yield value

  private def buildPrompt(
    systemPrompt: Option[String],
    step: String,
    taskRunId: Long,
    workflowId: Option[Long],
    currentPhase: Option[String],
  ): String =
    val custom = systemPrompt.map(_.trim).filter(_.nonEmpty).map(v => s"$v\n\n").getOrElse("")
    s"""${custom}Execute workflow step.
       |
       |- taskRunId: $taskRunId
       |- workflowId: ${workflowId.map(_.toString).getOrElse("n/a")}
       |- step: $step
       |- currentPhase: ${currentPhase.getOrElse("n/a")}
       |
       |Return a concise markdown result for this step execution.
       |""".stripMargin

  private def executeWithAgentConfig(agentName: String, prompt: String): IO[LlmError, LlmResponse] =
    configResolver
      .resolveConfig(agentName)
      .either
      .flatMap {
        case Right(config) => withFailover(config, prompt)
        case Left(_)       => llm4zio.core.Streaming.collect(llmService.executeStream(prompt))
      }

  private def withFailover(config: ProviderConfig, prompt: String): IO[LlmError, LlmResponse] =
    fallbackConfigs(config)
      .foldLeft[IO[LlmError, LlmResponse]](ZIO.fail(LlmError.ConfigError("No LLM provider configured"))) {
        (acc, cfg) =>
          acc.orElse(
            providerFor(cfg).flatMap(svc => llm4zio.core.Streaming.collect(svc.executeStream(prompt)))
          )
      }

  private def fallbackConfigs(primary: ProviderConfig): List[LlmConfig] =
    val primaryLlm = primary.toLlmConfig
    val fallback   = primary.fallbackChain.models.map { ref =>
      ProviderConfig.withDefaults(
        primary.copy(
          provider = ref.provider.getOrElse(primary.provider),
          model = ref.modelId,
        )
      ).toLlmConfig
    }
    (primaryLlm :: fallback).distinct

  private def providerFor(cfg: LlmConfig): IO[LlmError, LlmService] =
    providerCache.modifyZIO { current =>
      current.get(cfg) match
        case Some(existing) => ZIO.succeed((existing, current))
        case None           =>
          ZIO
            .attempt(buildProvider(cfg))
            .mapError(th => LlmError.ConfigError(Option(th.getMessage).getOrElse(th.toString)))
            .map(created => (created, current + (cfg -> created)))
    }

  private def buildProvider(cfg: LlmConfig): LlmService =
    cfg.provider match
      case LlmProvider.GeminiCli => llm4zio.providers.GeminiCliProvider.make(cfg, cliExecutor)
      case LlmProvider.GeminiApi => llm4zio.providers.GeminiApiProvider.make(cfg, httpClient)
      case LlmProvider.OpenAI    => llm4zio.providers.OpenAIProvider.make(cfg, httpClient)
      case LlmProvider.Anthropic => llm4zio.providers.AnthropicProvider.make(cfg, httpClient)
      case LlmProvider.LmStudio  => llm4zio.providers.LmStudioProvider.make(cfg, httpClient)
      case LlmProvider.Ollama    => llm4zio.providers.OllamaProvider.make(cfg, httpClient)
      case LlmProvider.OpenCode  => llm4zio.providers.OpenCodeProvider.make(cfg, httpClient)
      case LlmProvider.Mock      => llm4zio.providers.MockProvider.make(cfg)

  private def ensureAgentWorkspace(
    taskRunId: Long,
    agentName: String,
  ): IO[PersistenceError, AgentWorkspace] =
    for
      now  <- Clock.instant
      path <- ZIO
                .attemptBlocking {
                  val p =
                    Paths.get(".migration-state", "agent-workspaces", sanitizeForPath(agentName), taskRunId.toString)
                  Files.createDirectories(p)
                  p
                }
                .mapError(err =>
                  PersistenceError.QueryFailed("agentWorkspace", Option(err.getMessage).getOrElse(err.toString))
                )
    yield AgentWorkspace(
      agentId = shared.ids.Ids.AgentId(agentName),
      path = path.toString,
      createdAt = now,
      sizeBytes = 0L,
    )

  private def sanitizeForPath(value: String): String =
    value.trim.toLowerCase.replaceAll("[^a-z0-9._-]+", "-")

  private def persistMemoryArtifacts(
    taskRunId: Long,
    stepName: String,
    nodeId: String,
    responseContent: String,
  ): UIO[Unit] =
    (for
      settings <- configRepository.getSettingsByPrefix("memory.")
      cfg       = ConversationMemory.fromSettingsMap(settings.map(v => v.key -> v.value).toMap)
      _        <- ZIO.when(cfg.enabled) {
                    for
                      fromArtifacts <- repository
                                         .getArtifactsByTask(taskRunId)
                                         .map(_.filter(a => a.stepName == stepName && a.key.startsWith("memory.")))
                      fromContent    = parseMemoryLines(responseContent)
                      allEntries     = (
                                         fromArtifacts.map(a => MemoryArtifact(a.key, a.value)) ++
                                           fromContent
                                       ).filter(_.value.trim.nonEmpty).distinct
                      _             <- ZIO.foreachDiscard(allEntries) { item =>
                                         saveMemoryEntry(taskRunId, nodeId, item)
                                       }
                    yield ()
                  }
    yield ()).ignore

  private def saveMemoryEntry(
    taskRunId: Long,
    nodeId: String,
    artifact: MemoryArtifact,
  ): IO[Throwable, Unit] =
    for
      now <- Clock.instant
      _   <- memoryRepository.save(
               MemoryEntry(
                 id = MemoryId.make,
                 scope = Scope(s"task-run:$taskRunId"),
                 sessionId = SessionId(nodeId),
                 text = artifact.value.trim,
                 embedding = Vector.empty,
                 tags = List("workflow", s"task:$taskRunId"),
                 kind = toMemoryKind(artifact.key),
                 createdAt = now,
                 lastAccessedAt = now,
               )
             )
    yield ()

  private def parseMemoryLines(content: String): List[MemoryArtifact] =
    val Pattern = """(?i)^\s*(memory\.[a-z0-9_-]+)\s*[:=]\s*(.+?)\s*$""".r
    content.linesIterator.toList.flatMap {
      case Pattern(key, value) => Some(MemoryArtifact(key, value))
      case _                   => None
    }

  private def toMemoryKind(key: String): MemoryKind =
    key.trim.toLowerCase match
      case k if k.startsWith("memory.preference") => MemoryKind.Preference
      case k if k.startsWith("memory.context")    => MemoryKind.Context
      case k if k.startsWith("memory.summary")    => MemoryKind.Summary
      case _                                      => MemoryKind.Fact

  final private case class MemoryArtifact(key: String, value: String)
