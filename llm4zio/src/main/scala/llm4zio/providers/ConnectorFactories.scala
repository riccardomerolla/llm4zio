package llm4zio.providers

import zio.*
import llm4zio.core.*

object ConnectorFactories:

  def createRegistry(http: HttpClient, cli: CliProcessExecutor): ConnectorRegistry =
    ConnectorRegistryLive(Map(
      ConnectorId.OpenAI    -> apiFactory(ConnectorId.OpenAI, cfg => OpenAIProvider.make(cfg, http)),
      ConnectorId.Anthropic -> apiFactory(ConnectorId.Anthropic, cfg => AnthropicProvider.make(cfg, http)),
      ConnectorId.GeminiApi -> apiFactory(ConnectorId.GeminiApi, cfg => GeminiApiProvider.make(cfg, http)),
      ConnectorId.LmStudio  -> apiFactory(ConnectorId.LmStudio, cfg => LmStudioProvider.make(cfg, http)),
      ConnectorId.Ollama    -> apiFactory(ConnectorId.Ollama, cfg => OllamaProvider.make(cfg, http)),
      ConnectorId.ClaudeCli -> cliFactory(ConnectorId.ClaudeCli, cfg => ClaudeCliConnector.make(cfg, cli)),
      ConnectorId.OpenCode  -> cliFactory(ConnectorId.OpenCode, cfg => OpenCodeCliConnector.make(cfg, cli)),
      ConnectorId.GeminiCli -> geminiCliFactory(),
      ConnectorId.Codex     -> cliFactory(ConnectorId.Codex, cfg => CodexConnector.make(cfg, cli)),
      ConnectorId.Copilot   -> cliFactory(ConnectorId.Copilot, cfg => CopilotConnector.make(cfg, cli)),
      ConnectorId.Mock      -> apiFactory(ConnectorId.Mock, cfg => MockProvider.make(cfg)),
    ))

  val live: ZLayer[HttpClient & CliProcessExecutor, Nothing, ConnectorRegistry] =
    ZLayer.fromFunction(createRegistry)

  private def apiFactory(id: ConnectorId, build: LlmConfig => ApiConnector): ConnectorFactory =
    new ConnectorFactory:
      def connectorId: ConnectorId = id
      def kind: ConnectorKind = ConnectorKind.Api
      def create(config: ConnectorConfig): IO[LlmError, Connector] = config match
        case api: ApiConnectorConfig => ZIO.succeed(build(api.toLlmConfig))
        case _                       => ZIO.fail(LlmError.ConfigError(s"Expected ApiConnectorConfig for $id"))

  private def cliFactory(id: ConnectorId, build: CliConnectorConfig => CliConnector): ConnectorFactory =
    new ConnectorFactory:
      def connectorId: ConnectorId = id
      def kind: ConnectorKind = ConnectorKind.Cli
      def create(config: ConnectorConfig): IO[LlmError, Connector] = config match
        case cli: CliConnectorConfig => ZIO.succeed(build(cli))
        case _                       => ZIO.fail(LlmError.ConfigError(s"Expected CliConnectorConfig for $id"))

  private def geminiCliFactory(): ConnectorFactory =
    new ConnectorFactory:
      def connectorId: ConnectorId = ConnectorId.GeminiCli
      def kind: ConnectorKind = ConnectorKind.Cli
      def create(config: ConnectorConfig): IO[LlmError, Connector] = config match
        case cfg: CliConnectorConfig =>
          val llmConfig = LlmConfig(LlmProvider.GeminiCli, cfg.model.getOrElse("gemini-2.5-flash"))
          ZIO.succeed(GeminiCliProvider.make(llmConfig, GeminiCliExecutor.default))
        case _ => ZIO.fail(LlmError.ConfigError("Expected CliConnectorConfig for gemini-cli"))
