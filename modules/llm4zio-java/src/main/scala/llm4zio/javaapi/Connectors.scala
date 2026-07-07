package llm4zio.javaapi

import scala.jdk.CollectionConverters.MapHasAsScala

import llm4zio.core.{ ApiConnectorConfig, CliConnectorConfig }
import llm4zio.runner as runner

/** Java-facing connector presets + transforms, mirroring `llm4zio.runner`'s `claude`/`codex`/`gemini`/`pi`/`lmStudio`.
  * Java can't call a Scala case class's named-arg `copy`, so the `with*` helpers stand in for `.withModel(…)` /
  * `.copy(model = …, envVars = …, timeout = …)` and return the config to hand to [[Llm4zioJava.flow]].
  */
object Connectors:
  /** Claude Code CLI coder (edit-capable). */
  def claude(): CliConnectorConfig = runner.claude

  /** Codex CLI coder. */
  def codex(): CliConnectorConfig = runner.codex

  /** Gemini CLI coder. */
  def gemini(): CliConnectorConfig = runner.gemini

  /** pi CLI coder (local). */
  def pi(): CliConnectorConfig = runner.pi

  /** The coder selected by `LLM4ZIO_CODER` (claude|codex|gemini|pi), default claude. */
  def coderFromEnv(): CliConnectorConfig = runner.Connectors.coderFromEnv()

  /** LM Studio's local OpenAI-compatible reasoning seat (no cloud/API key). */
  def lmStudio(): ApiConnectorConfig = runner.lmStudio

  /** Pin a model on a CLI coder. */
  def withModel(config: CliConnectorConfig, model: String): CliConnectorConfig = config.copy(model = Some(model))

  /** Pin a model on an API reasoning seat. */
  def withModel(config: ApiConnectorConfig, model: String): ApiConnectorConfig = config.copy(model = Some(model))

  /** Add environment variables to a CLI coder (e.g. routing Claude Code at a local server). */
  def withEnv(config: CliConnectorConfig, env: java.util.Map[String, String]): CliConnectorConfig =
    config.copy(envVars = env.asScala.toMap)

  /** Make a CLI config read-only (the planning/review seat). */
  def readOnly(config: CliConnectorConfig): CliConnectorConfig = config.copy(readOnly = true)

  /** Set the request timeout on an API reasoning seat. */
  def withTimeoutSeconds(config: ApiConnectorConfig, seconds: Long): ApiConnectorConfig =
    config.copy(timeout = java.time.Duration.ofSeconds(seconds))
