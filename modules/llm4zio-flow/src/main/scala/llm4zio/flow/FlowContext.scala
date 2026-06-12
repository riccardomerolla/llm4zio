package llm4zio.flow

import java.nio.file.Path

import llm4zio.core.{ ConnectorCapabilities, LlmService }

/** Everything a flow needs, bundled in one place.
  *
  * The role split from the design: reasoning steps (planning, review judgements, structured output) run over
  * `reasoning` — typically an API connector — while code-editing steps run over `coder`, a CLI coding agent
  * (claude/codex/gemini) that owns the file-editing tool loop. `git`/`gh` carry out version-control side effects;
  * `events` is the progress sink.
  */
final case class FlowContext(
  reasoning: LlmService,
  coder: LlmService,
  git: GitTool,
  gh: GhTool,
  events: FlowEvents,
  // Extra review backends (cross-agent review). The reasoning connector is the
  // default reviewer; these are run alongside it.
  reviewers: List[LlmService] = Nil,
  // What the coder can do (interactive/ask-user/approval/…), so a flow can refuse an unsupported workflow up front.
  coderCapabilities: ConnectorCapabilities = ConnectorCapabilities(),
  // The free-form prompt a flow script was started with (first CLI arg, or the script's default).
  userPrompt: String = "",
  // The repository the flow operates on; tools and connectors are rooted here.
  workDir: Path = Path.of(".").toAbsolutePath.normalize,
):
  /** Expose the event sink as a given so `stage`/`fail` resolve it implicitly. */
  given FlowEvents = events
