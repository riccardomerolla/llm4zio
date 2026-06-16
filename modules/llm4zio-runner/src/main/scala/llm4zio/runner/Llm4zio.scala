package llm4zio.runner

import java.nio.file.Path

import zio.*
import zio.http.Client

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.*
import llm4zio.providers.HttpClient

/** Runner entry points. [[flow]] (in this package) is the script surface — top-level in a `.sc`, one `unsafeRun`
  * inside. [[Llm4zio.run]] is the embedding surface for real ZIO apps: builds a [[FlowContext]], streams progress to
  * the terminal, runs the body, and provides the zio-http client layers.
  *
  * {{{
  * object MyApp extends zio.ZIOAppDefault:
  *   def run = Llm4zio.run(workDir, reasoning, coder) { ctx =>
  *     // ... a flow over ctx ...
  *   }
  * }}}
  */
object Llm4zio:

  /** Best-effort human-readable reason from a failed flow's cause, for the final ✖ banner. */
  private[runner] def failMessage(cause: Cause[FlowError]): String =
    val base = cause.failureOption
      .map(_.message)
      .orElse(cause.dieOption.map(_.getMessage))
      .getOrElse(if cause.isInterrupted then "interrupted" else "unknown error")
    base + geminiCatchAllHint(base)

  // gemini repeatedly returns a generic catch-all ("an unknown error occurred") for what is usually
  // quota/rate-limit exhaustion. classify() leaves it unclassified (a transient ProviderError), so the
  // typed error stays unchanged — here, purely presentationally, we append an actionable hint.
  private def geminiCatchAllHint(message: String): String =
    val lower = message.toLowerCase
    if lower.contains("an unknown error occurred") then
      " — gemini repeatedly returned its catch-all error; this is often quota/rate-limit exhaustion." +
        " Check your gemini quota, or set LLM4ZIO_USAGE_WAIT to wait and resume."
    else ""

  def run(
    workDir: Path,
    reasoning: ConnectorConfig,
    coder: CliConnectorConfig,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  )(
    body: FlowContext => ZIO[Any, Any, Any]
  ): ZIO[Any, Throwable, Unit] =
    (for
      logPath <- RunnerLog.newLogFile
      palette <- Palette.auto
      _       <- Console.printLine(Banner.line(Banner.version, logPath)).orDie
      _       <- Console.printLine("").orDie
      _       <- ZIO
                   .scoped {
                     val policy =
                       if usageLimit.enabled then usageLimit else UsageWaitEnv.parse(sys.env.get("LLM4ZIO_USAGE_WAIT"))
                     for
                       base      <- if palette.enabled then TerminalSurface.live(palette) else TerminalSurface.plain
                       // Tee every rendered tree line into the log file too, so the log is a complete record.
                       surface    = TerminalSurface.teeingToLog(base)
                       retries    = RetryEnv.parse(sys.env.get("LLM4ZIO_RETRIES"))
                       bundle    <- DefaultFlowContext.build(reasoning, coder, workDir, reviewers, policy, retries)
                       (ctx, hub) = bundle
                       tracker   <- CostTracker.make
                       // Two fire-and-forget subscribers on the bounded event hub. Both drain fast
                       // (terminal write / map update); the hub back-pressures the producer if a
                       // subscriber stalls, which paces output rather than dropping events.
                       consumed  <- TerminalListener.consumeTo(hub, palette, surface)
                       _         <- tracker.consume(hub)
                       _         <- {
                         given FlowEvents = hub
                         withUsageLimitRetry(policy)(
                           body(ctx).mapError {
                             case fe: FlowError => fe
                             case other         => FlowError.Llm(other.toString)
                           }
                         ).unit
                           // On exit, first drain the hub so trailing events (notably a final StageFailed) render
                           // rather than being interrupted away; on failure, follow with an authoritative ✖ banner
                           // written straight to the surface — so a failed run can never look like a clean finish.
                           .onExit {
                             case Exit.Failure(cause) =>
                               TerminalListener.awaitDrained(hub, consumed, 3.seconds) *>
                                 surface.log("\n" + palette.fail(s"flow failed: ${failMessage(cause)}"))
                             case Exit.Success(_)     =>
                               TerminalListener.awaitDrained(hub, consumed, 3.seconds)
                           }
                           .ensuring(tracker.summary.flatMap(s => surface.log("\n" + s)))
                       }
                     yield ()
                   }
                   .provideSomeLayer[HttpClient & Client](RunnerLog.fileOnly(logPath))
    yield ())
      .provide(Client.default, HttpClient.live)
      .mapError {
        case t: Throwable => t
        case other        => new RuntimeException(other.toString)
      }

  /** A script was started without a usable prompt. Carried as a Throwable so [[script]] composes with [[run]]'s
    * `ZIO[Any, Throwable, Unit]`; [[flow]] renders `message` as the usage line and exits 2.
    */
  final case class ScriptUsage(usage: String) extends RuntimeException(usage)

  /** First non-blank CLI arg, else the script's default, else a usage error. */
  def resolvePrompt(args: List[String], defaultPrompt: Option[String] = None): Either[String, String] =
    args.headOption
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(defaultPrompt)
      .toRight("""usage: scala-cli run <script>.sc -- "<prompt>"""")

  /** The reasoning connector a script uses: the explicit one, else the coder's read-only twin. */
  private[runner] def scriptReasoning(coder: CliConnectorConfig, explicit: Option[ConnectorConfig]): ConnectorConfig =
    explicit.getOrElse(coder.copy(readOnly = true))

  /** Adapt a context-function body to [[run]]'s plain-function shape, riding `prompt` in the context. */
  private[runner] def withPrompt[A](
    prompt: String
  )(
    body: FlowContext ?=> ZIO[Any, FlowError, A]
  ): FlowContext => ZIO[Any, FlowError, A] =
    ctx => body(using ctx.copy(userPrompt = prompt))

  /** The pure-ZIO core of [[flow]]: resolve the prompt, derive the read-only reasoning twin when none is given, then
    * delegate to [[run]] with the prompt riding in the [[FlowContext]]. Kept separate from [[flow]] so everything up to
    * the single `unsafeRun` is an ordinary testable effect.
    */
  def script(
    args: List[String],
    coder: CliConnectorConfig,
    reasoning: Option[ConnectorConfig] = None,
    defaultPrompt: Option[String] = None,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
    workDir: Path = Path.of(".").toAbsolutePath.normalize,
  )(
    body: FlowContext ?=> ZIO[Any, FlowError, Any]
  ): ZIO[Any, Throwable, Unit] =
    resolvePrompt(args, defaultPrompt) match
      case Left(usage)   => ZIO.fail(ScriptUsage(usage))
      case Right(prompt) =>
        run(workDir, scriptReasoning(coder, reasoning), coder, reviewers, usageLimit)(withPrompt(prompt)(body))
