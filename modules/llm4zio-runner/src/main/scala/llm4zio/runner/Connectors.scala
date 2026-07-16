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

/** Google's Antigravity CLI (argv binary `agy`) — the retail successor to [[gemini]]; `gemini` itself stays available
  * and maintained for the enterprise customer still depending on it. `--mode accept-edits` is agy's headless
  * auto-approve equivalent to gemini's built-in `-y`.
  */
val antigravity: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.AntigravityCli, flags = Map("mode" -> "accept-edits"))

// xAI's grok CLI. The connector itself adds --always-approve (or --permission-mode plan when readOnly),
// so no edit-enabling flag is needed here. Auth: `grok login` or XAI_API_KEY.
val grok: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.Grok)

// Cursor's CLI (binary `cursor-agent`). The connector adds --force (edits apply) unless readOnly,
// where print mode only proposes changes. Auth: `cursor-agent login` or CURSOR_API_KEY.
val cursor: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.Cursor)

// opencode. The connector adds --auto (auto-approve permissions) or the built-in read-only `plan` agent
// when readOnly. Models are `provider/model`, e.g. .withModel("anthropic/claude-sonnet-5").
val opencode: CliConnectorConfig =
  CliConnectorConfig(ConnectorId.OpenCode)

/** LM Studio's local OpenAI-compatible server (default port 1234) — a reasoning/structured-output seat that needs no
  * cloud or API key. Set the model with `.copy(model = Some("..."))`. Pair with a local coder (e.g. `pi`) for a
  * fully-local flow.
  */
val lmStudio: ApiConnectorConfig =
  ApiConnectorConfig(ConnectorId.LmStudio, baseUrl = Some("http://localhost:1234/v1"))

extension (config: CliConnectorConfig)
  /** Pin a specific model, e.g. `claude.withModel("opus")`. */
  def withModel(name: String): CliConnectorConfig = config.copy(model = Some(name))

  /** Bound the agent's tool-call turns per ask (gemini: the `model.maxSessionTurns` setting, injected per process —
    * gemini has no CLI flag for this). A headless agent that finishes its work and then keeps emitting no-op commands
    * burns quota unbounded without this; past the limit the turn fails with the typed `LlmError.TurnLimitError`, so the
    * flow decides (files already written usually mean the work survived).
    */
  def withTurnLimit(turns: Int): CliConnectorConfig = config.copy(turnLimit = Some(turns))

object Connectors:
  /** The coder selected by `LLM4ZIO_CODER` (claude|codex|gemini|pi|agy|grok|cursor|opencode), defaulting to [[claude]]
    * — the swap-backend-without-editing-the-script knob every example used to hand-roll.
    */
  def coderFromEnv(env: Map[String, String] = sys.env): CliConnectorConfig =
    env.getOrElse("LLM4ZIO_CODER", "claude") match
      case "codex"    => codex
      case "gemini"   => gemini
      case "pi"       => pi
      case "agy"      => antigravity
      case "grok"     => grok
      case "cursor"   => cursor
      case "opencode" => opencode
      case _          => claude
