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
  def make(reasoning: LlmService, coder: LlmService, workDir: Path): UIO[(FlowContext, FlowEvents.Hub)] =
    FlowEvents.hub().map { hub =>
      (FlowContext(reasoning, coder, GitTool(workDir), GhTool(workDir), hub), hub)
    }

  /** Build connectors from config: API reasoning (needs an [[HttpClient]]) + a
    * CLI coder rooted in `workDir`.
    */
  def build(
    reasoningCfg: ApiConnectorConfig,
    coderCfg: CliConnectorConfig,
    workDir: Path,
  ): ZIO[HttpClient, LlmError, (FlowContext, FlowEvents.Hub)] =
    ZIO.serviceWithZIO[HttpClient] { http =>
      val registry = ConnectorFactories.createRegistry(http, LiveCliProcessExecutor.instance)
      for
        reasoning <- registry.resolveApi(reasoningCfg)
        coder     <- registry.resolveCli(coderCfg.copy(workingDir = Some(workDir.toString)))
        bundle    <- make(reasoning, coder, workDir)
      yield bundle
    }
