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
  ): UIO[(FlowContext, FlowEvents.Hub)] =
    FlowEvents.hub().map { hub =>
      (FlowContext(reasoning, coder, GitTool(workDir), GhTool(workDir), hub, reviewers), hub)
    }

  /** Build connectors from config: API reasoning (needs an [[HttpClient]]), a CLI coder rooted in `workDir`, and any
    * extra cross-agent reviewers.
    */
  def build(
    reasoningCfg: ApiConnectorConfig,
    coderCfg: CliConnectorConfig,
    workDir: Path,
    reviewerCfgs: List[ConnectorConfig] = Nil,
  ): ZIO[HttpClient, LlmError, (FlowContext, FlowEvents.Hub)] =
    ZIO.serviceWithZIO[HttpClient] { http =>
      val registry = ConnectorFactories.createRegistry(http, LiveCliProcessExecutor.instance)
      for
        reasoning <- registry.resolveApi(enrichApi(reasoningCfg))
        coder     <- registry.resolveCli(coderCfg.copy(workingDir = Some(workDir.toString)))
        reviewers <- ZIO.foreach(reviewerCfgs)(cfg => registry.resolve(withWorkdir(cfg, workDir)))
        bundle    <- make(reasoning, coder, workDir, reviewers)
      yield bundle
    }

  private def withWorkdir(cfg: ConnectorConfig, workDir: Path): ConnectorConfig = cfg match
    case c: CliConnectorConfig => c.copy(workingDir = Some(workDir.toString))
    case other                 => other

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
