package llm4zio.javaapi

import java.util.function.Consumer

import scala.jdk.CollectionConverters.ListHasAsScala

import zio.{ Exit, Runtime, Unsafe, ZIO }

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.{ FlowContext, FlowError }
import llm4zio.runner.{ Connectors, Llm4zio }

/** The Java entry point — the analog of the `.sc` surface's `flow(args) { body }`, holding the library's single
  * `unsafeRun`. A Java flow is a `public static void main` that calls [[flow]]:
  *
  * {{{
  * import llm4zio.javaapi.*;
  * public class Implement {
  *   public static void main(String[] args) {
  *     Llm4zioJava.flow(args, "Add a multiply function", flow -> {
  *       var plan = flow.recoverOrCreatePlan(flow.defaultPlanPath());
  *       flow.stage("branch", () -> flow.git().checkoutOrCreate(plan.epicId()));
  *       // ...
  *     });
  *   }
  * }
  * }}}
  */
object Llm4zioJava:

  /** Run a Java-authored flow. The body is handed a [[JavaFlow]] inside the runner's scoped session (live progress
    * tree, cost summary, trace recorder, resume). Coder backend follows `LLM4ZIO_CODER` (claude|codex|gemini);
    * reasoning defaults to the coder's read-only twin. On failure the runner renders the ✖ banner; a missing prompt
    * exits 2, a failed flow exits 1, Ctrl-C exits 130.
    */
  def flow(args: Array[String], defaultPrompt: String, body: Consumer[JavaFlow]): Unit =
    runEffect(script(args.toList, Connectors.coderFromEnv(), defaultPrompt, None, Nil, body))

  /** As [[flow]], with an explicit coder backend. */
  def flow(args: Array[String], defaultPrompt: String, coder: CliConnectorConfig, body: Consumer[JavaFlow]): Unit =
    runEffect(script(args.toList, coder, defaultPrompt, None, Nil, body))

  /** As [[flow]], with explicit coder + reasoning seats (e.g. a local LM Studio reasoner). */
  def flow(
    args: Array[String],
    defaultPrompt: String,
    coder: CliConnectorConfig,
    reasoning: ConnectorConfig,
    body: Consumer[JavaFlow],
  ): Unit =
    runEffect(script(args.toList, coder, defaultPrompt, Some(reasoning), Nil, body))

  /** As [[flow]], with explicit coder + reasoning + extra cross-agent reviewers. */
  def flow(
    args: Array[String],
    defaultPrompt: String,
    coder: CliConnectorConfig,
    reasoning: ConnectorConfig,
    reviewers: java.util.List[ConnectorConfig],
    body: Consumer[JavaFlow],
  ): Unit =
    runEffect(script(args.toList, coder, defaultPrompt, Some(reasoning), reviewers.asScala.toList, body))

  /** The library's single `unsafeRun`: fork the flow effect, wire Ctrl-C to interrupt it, and map the exit to process
    * behaviour (missing prompt → exit 2, failed flow → exit 1, SIGINT → 130).
    */
  private def runEffect(effect: ZIO[Any, Throwable, Unit]): Unit =
    Unsafe.unsafe { implicit unsafe =>
      val runtime = Runtime.default
      val fiber   = runtime.unsafe.fork(effect)
      val hook    = new Thread(() => { val _ = Unsafe.unsafe(implicit u => runtime.unsafe.run(fiber.interrupt)) })
      java.lang.Runtime.getRuntime.addShutdownHook(hook)
      val exit    = runtime.unsafe.run(fiber.join)
      try java.lang.Runtime.getRuntime.removeShutdownHook(hook)
      catch case _: IllegalStateException => () // shutdown already in progress (Ctrl-C path)
      exit match
        case Exit.Success(_)                                => ()
        case Exit.Failure(cause) if cause.isInterruptedOnly => () // SIGINT path: the JVM itself exits 130
        case Exit.Failure(cause)                            =>
          cause.failureOption match
            case Some(usage: Llm4zio.ScriptUsage) =>
              java.lang.System.err.print(usage.getMessage + "\n")
              sys.exit(2)
            case _                                =>
              sys.exit(1) // run() already rendered the ✖ banner + reason
    }

  /** The pure-effect core of [[flow]] (everything up to the single `unsafeRun`), kept separate so it is testable:
    * resolve the prompt + repo, then run the Java `body` inside a [[FlowContext]]-scoped session on a blocking thread,
    * mapping any exception thrown out of the Java body back to a typed [[FlowError]].
    */
  def script(
    args: List[String],
    coder: CliConnectorConfig,
    defaultPrompt: String,
    reasoning: Option[ConnectorConfig],
    reviewers: List[ConnectorConfig],
    body: Consumer[JavaFlow],
  ): ZIO[Any, Throwable, Unit] =
    Llm4zio.script(args, coder, reasoning = reasoning, reviewers = reviewers, defaultPrompt = Option(defaultPrompt))(
      adapt(body)
    )

  /** Adapt a `Consumer[JavaFlow]` to the context-function body `Llm4zio.script` expects: capture the running runtime,
    * run the (blocking) Java body on it, and translate a thrown exception back into the typed error channel.
    */
  private def adapt(body: Consumer[JavaFlow]): FlowContext ?=> ZIO[Any, FlowError, Any] =
    for
      runtime <- ZIO.runtime[Any]
      _       <- ZIO
                   .attemptBlocking(body.accept(new JavaFlow(runtime, summon[FlowContext])))
                   .mapError(Llm4zioException.toFlowError)
    yield ()
