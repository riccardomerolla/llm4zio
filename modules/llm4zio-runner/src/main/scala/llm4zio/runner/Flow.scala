package llm4zio.runner

import zio.*

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.{ FlowContext, FlowError, UsageLimitPolicy }

/** Script entry point — the library's only `unsafeRun`. A flow script is two lines of frame:
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
  args: Seq[String],
  coder: CliConnectorConfig = Connectors.coderFromEnv(),
  reasoning: Option[ConnectorConfig] = None,
  defaultPrompt: Option[String] = None,
  reviewers: List[ConnectorConfig] = Nil,
  usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
)(
  body: FlowContext ?=> ZIO[Any, FlowError, Any]
): Unit =
  val effect =
    Llm4zio.script(args.toList, coder, reasoning, defaultPrompt, reviewers, usageLimit)(body)
  Unsafe.unsafe { implicit unsafe =>
    val runtime = Runtime.default
    val fiber   = runtime.unsafe.fork(effect)
    // Ctrl-C → interrupt the flow fiber and wait for it to unwind (stages close, banner renders).
    val hook    = new Thread(() => { val _ = Unsafe.unsafe(implicit u => runtime.unsafe.run(fiber.interrupt)) })
    java.lang.Runtime.getRuntime.addShutdownHook(hook)
    val exit    = runtime.unsafe.run(fiber.join)
    try java.lang.Runtime.getRuntime.removeShutdownHook(hook)
    catch case _: IllegalStateException => () // shutdown already in progress (Ctrl-C path)
    exit match
      case Exit.Success(_)                            => ()
      case Exit.Failure(cause) if cause.isInterrupted => () // SIGINT path: the JVM itself exits 130
      case Exit.Failure(cause)                        =>
        cause.failureOption match
          case Some(usage: Llm4zio.ScriptUsage) =>
            java.lang.System.err.print(usage.getMessage + "\n")
            sys.exit(2)
          case _                                =>
            sys.exit(1) // run() already rendered the ✖ banner + reason
  }
