package llm4zio.javaapi

import java.util.function.Consumer

import scala.jdk.CollectionConverters.ListHasAsScala

import zio.ZIO

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.{ FlowContext, FlowError }
import llm4zio.runner.{ Connectors, Llm4zio }

/** The Java entry point — the analog of the `.sc` surface's `flow(args) { body }`, delegating to the runner's shared
  * process entry (`Llm4zio.unsafeMain`). A Java flow is a `public static void main` that calls [[flow]]:
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
    Llm4zio.unsafeMain(script(args.toList, Connectors.coderFromEnv(), defaultPrompt, None, Nil, body))

  /** As [[flow]], with an explicit coder backend. */
  def flow(args: Array[String], defaultPrompt: String, coder: CliConnectorConfig, body: Consumer[JavaFlow]): Unit =
    Llm4zio.unsafeMain(script(args.toList, coder, defaultPrompt, None, Nil, body))

  /** As [[flow]], with explicit coder + reasoning seats (e.g. a local LM Studio reasoner). */
  def flow(
    args: Array[String],
    defaultPrompt: String,
    coder: CliConnectorConfig,
    reasoning: ConnectorConfig,
    body: Consumer[JavaFlow],
  ): Unit =
    Llm4zio.unsafeMain(script(args.toList, coder, defaultPrompt, Some(reasoning), Nil, body))

  /** As [[flow]], with explicit coder + reasoning + extra cross-agent reviewers. */
  def flow(
    args: Array[String],
    defaultPrompt: String,
    coder: CliConnectorConfig,
    reasoning: ConnectorConfig,
    reviewers: java.util.List[ConnectorConfig],
    body: Consumer[JavaFlow],
  ): Unit =
    Llm4zio.unsafeMain(script(args.toList, coder, defaultPrompt, Some(reasoning), reviewers.asScala.toList, body))

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
    * `attemptBlockingInterrupt` so cancelling the flow fiber (Ctrl-C) `Thread.interrupt`s the Java body's thread —
    * [[Bridge.runSync]] then cancels whatever inner effect that thread is waiting on, unwinding the whole flow promptly
    * instead of orphaning it.
    */
  private def adapt(body: Consumer[JavaFlow]): FlowContext ?=> ZIO[Any, FlowError, Any] =
    for
      runtime <- ZIO.runtime[Any]
      _       <- ZIO
                   .attemptBlockingInterrupt(body.accept(new JavaFlow(runtime, summon[FlowContext])))
                   .mapError(Llm4zioException.toFlowError)
    yield ()
