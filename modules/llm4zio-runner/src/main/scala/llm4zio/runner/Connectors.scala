package llm4zio.runner

import llm4zio.core.{ ApiConnectorConfig, CliConnectorConfig, ConnectorId }

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

// pi runs YOLO by default (edits without prompting), so no edit-enabling flag is needed.
val pi: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.Pi)

/** LM Studio's local OpenAI-compatible server (default port 1234) — a reasoning/structured-output seat that needs no
  * cloud or API key. Set the model with `.copy(model = Some("..."))`. Pair with a local coder (e.g. `pi`) for a
  * fully-local flow.
  */
val lmStudio: ApiConnectorConfig =
  ApiConnectorConfig(ConnectorId.LmStudio, baseUrl = Some("http://localhost:1234/v1"))

extension (config: CliConnectorConfig)
  /** Pin a specific model, e.g. `claude.withModel("opus")`. */
  def withModel(name: String): CliConnectorConfig = config.copy(model = Some(name))

  /** Bound the agent's tool-call turns per ask (gemini `--turn-limit`). A headless agent that finishes its work and
    * then keeps emitting no-op commands burns quota unbounded without this; past the limit the turn fails with the
    * typed `LlmError.TurnLimitError`, so the flow decides (files already written usually mean the work survived).
    */
  def withTurnLimit(turns: Int): CliConnectorConfig = config.copy(turnLimit = Some(turns))

object Connectors:
  /** The coder selected by `LLM4ZIO_CODER` (claude|codex|gemini|pi), defaulting to [[claude]] — the
    * swap-backend-without-editing-the-script knob every example used to hand-roll.
    */
  def coderFromEnv(env: Map[String, String] = sys.env): CliConnectorConfig =
    env.getOrElse("LLM4ZIO_CODER", "claude") match
      case "codex"  => codex
      case "gemini" => gemini
      case "pi"     => pi
      case _        => claude
