package llm4zio.runner

import zio.*

import java.nio.file.Path

import llm4zio.core.*
import llm4zio.providers.{ConnectorFactories, HttpClient}
import llm4zio.flow.*

/** Wiring for a real [[FlowContext]]: reasoning over an API connector, coding
  * over a CLI agent rooted in the target repo, git/gh tools on that repo, and a
  * broadcast event hub.
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

  /** Build connectors from config: API reasoning (needs an [[HttpClient]]), a
    * CLI coder rooted in `workDir`, and any extra cross-agent reviewers.
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
        reasoning <- registry.resolveApi(reasoningCfg)
        coder     <- registry.resolveCli(coderCfg.copy(workingDir = Some(workDir.toString)))
        reviewers <- ZIO.foreach(reviewerCfgs)(cfg => registry.resolve(withWorkdir(cfg, workDir)))
        bundle    <- make(reasoning, coder, workDir, reviewers)
      yield bundle
    }

  private def withWorkdir(cfg: ConnectorConfig, workDir: Path): ConnectorConfig = cfg match
    case c: CliConnectorConfig => c.copy(workingDir = Some(workDir.toString))
    case other                 => other
