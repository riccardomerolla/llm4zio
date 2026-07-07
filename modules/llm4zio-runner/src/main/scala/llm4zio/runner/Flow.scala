package llm4zio.runner

import zio.*

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.{ FlowContext, FlowError, UsageLimitPolicy }

/** Script entry point — a thin frame over [[Llm4zio.unsafeMain]], the runner's shared process entry. A flow script is
  * two lines of frame:
  *
  * {{{
  * import llm4zio.flow.*
  * import llm4zio.runner.*
  *
  * flow(args, defaultPrompt = Some("Add a multiply function")):
  *   for
  *     plan <- Planner.from(reasoning, userPrompt)
  *     ...
  *   yield ()
  * }}}
  *
  * The body is an ordinary ZIO effect with the [[FlowContext]] in given scope (so `git`/`gh`/`coder`/`reasoning`/
  * `userPrompt` resolve bare, and `stage`/`implementTaskLoop` find their event sink). The coder defaults to the
  * `LLM4ZIO_CODER` selection (claude|codex|gemini, claude when unset); reasoning defaults to the coder's read-only
  * twin.
  *
  * Process behaviour: Ctrl-C interrupts the flow fiber (stages unwind, the ✖ banner renders, JVM exits 130); a missing
  * prompt prints usage and exits 2; a failed flow exits 1 (the runner has already rendered the failure).
  */
def flow(
  args: Array[String],
  coder: CliConnectorConfig = Connectors.coderFromEnv(),
  reasoning: Option[ConnectorConfig] = None,
  defaultPrompt: Option[String] = None,
  reviewers: List[ConnectorConfig] = Nil,
  usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  verbosity: Option[Verbosity] = None,
)(
  body: FlowContext ?=> ZIO[Any, FlowError, Any]
): Unit =
  Llm4zio.unsafeMain(
    Llm4zio.script(args.toList, coder, reasoning, defaultPrompt, reviewers, usageLimit, verbosity)(body)
  )
