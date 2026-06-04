package llm4zio.runner

import java.nio.file.Path

import zio.*

import llm4zio.core.*
import llm4zio.flow.*
import llm4zio.providers.{ ConnectorFactories, HttpClient }

/** Wiring for a real [[FlowContext]]: reasoning over an API connector, coding over a CLI agent rooted in the target
  * repo, git/gh tools on that repo, and a broadcast event hub.
  */
object DefaultFlowContext:

  /** Bundle already-built connectors with git/gh on `workDir` and a fresh hub. */
  def make(
    reasoning: LlmService,
    coder: LlmService,
    workDir: Path,
    reviewers: List[LlmService] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  ): UIO[(FlowContext, FlowEvents.Hub)] =
    FlowEvents.hub().map { hub =>
      given FlowEvents = hub
      def tap(svc: LlmService, agent: String): LlmService =
        val tapped = EventTappingService(svc, agent, hub, workDir)
        if usageLimit.enabled then UsageLimitAware(tapped, usageLimit) else tapped
      val reasoningT                                      = tap(reasoning, "reasoning")
      val coderT                                          = tap(coder, "coder")
      val reviewersT                                      = reviewers.zipWithIndex.map { case (r, i) => tap(r, s"reviewer:${i + 1}") }
      (FlowContext(reasoningT, coderT, GitTool(workDir), GhTool(workDir), hub, reviewersT), hub)
    }

  /** Build connectors from config: a reasoning connector — **API or CLI** (a CLI reasoner needs no API key, e.g. an
    * all-gemini run) — a CLI coder rooted in `workDir`, and any extra cross-agent reviewers.
    */
  def build(
    reasoning: ConnectorConfig,
    coder: CliConnectorConfig,
    workDir: Path,
    reviewerCfgs: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  ): ZIO[HttpClient, LlmError, (FlowContext, FlowEvents.Hub)] =
    ZIO.serviceWithZIO[HttpClient] { http =>
      val registry = ConnectorFactories.createRegistry(http, LiveCliProcessExecutor.instance)
      for
        reasoningC <- registry.resolve(prepare(reasoning, workDir))
        coderC     <- registry.resolveCli(coder.copy(workingDir = Some(workDir.toString)))
        reviewers  <- ZIO.foreach(reviewerCfgs)(cfg => registry.resolve(prepare(cfg, workDir)))
        bundle     <- make(reasoningC, coderC, workDir, reviewers, usageLimit)
      yield bundle
    }

  /** Ready a config for resolution: fill an API config's baseUrl/key (see [[enrichApi]]); root a CLI config in
    * `workDir`.
    */
  def prepare(cfg: ConnectorConfig, workDir: Path): ConnectorConfig = cfg match
    case api: ApiConnectorConfig => enrichApi(api)
    case cli: CliConnectorConfig => cli.copy(workingDir = Some(workDir.toString))

  /** Fill an API config's base URL (from the provider default) and API key (from the environment) when the caller left
    * them unset — so examples can name just the provider + model.
    */
  def enrichApi(cfg: ApiConnectorConfig): ApiConnectorConfig =
    val envKey = cfg.connectorId match
      case ConnectorId.Anthropic => sys.env.get("ANTHROPIC_API_KEY")
      case ConnectorId.OpenAI    => sys.env.get("OPENAI_API_KEY")
      case ConnectorId.GeminiApi => sys.env.get("GEMINI_API_KEY").orElse(sys.env.get("GOOGLE_API_KEY"))
      case _                     => None
    cfg.copy(
      baseUrl = cfg.baseUrl.orElse(LlmProvider.defaultBaseUrl(cfg.toLlmConfig.provider)),
      apiKey = cfg.apiKey.orElse(envKey),
    )
