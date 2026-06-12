package llm4zio.runner

import llm4zio.core.{ CliConnectorConfig, ConnectorId }

/** Ready-made CLI coding-agent configs, orca-style: a flow script references `claude` / `codex` / `gemini` directly
  * (`flow(args, coder = codex)`) instead of hand-rolling a `CliConnectorConfig` match. Each is edit-capable; derive the
  * read-only reasoning twin with `.copy(readOnly = true)` (which [[flow]] does for you by default).
  */
val claude: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))

val codex: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.Codex, flags = Map("sandbox" -> "workspace-write"))

// gemini auto-approves edits via its built-in -y.
val gemini: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.GeminiCli)

extension (config: CliConnectorConfig)
  /** Pin a specific model, e.g. `claude.withModel("opus")`. */
  def withModel(name: String): CliConnectorConfig = config.copy(model = Some(name))

object Connectors:
  /** The coder selected by `LLM4ZIO_CODER` (claude|codex|gemini), defaulting to [[claude]] — the swap-backend-without-
    * editing-the-script knob every example used to hand-roll.
    */
  def coderFromEnv(env: Map[String, String] = sys.env): CliConnectorConfig =
    env.getOrElse("LLM4ZIO_CODER", "claude") match
      case "codex"  => codex
      case "gemini" => gemini
      case _        => claude
