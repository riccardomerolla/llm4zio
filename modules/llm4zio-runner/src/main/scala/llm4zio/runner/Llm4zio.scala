package llm4zio.runner

import java.nio.file.{ Files, Path }

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
    verbosity: Option[Verbosity] = None,
    workspace: Path = Path.of(".").toAbsolutePath.normalize,
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
                       base        <- if palette.enabled then TerminalSurface.live(palette) else TerminalSurface.plain
                       // Tee every rendered tree line into the log file too, so the log is a complete record.
                       surface      = TerminalSurface.teeingToLog(base)
                       retries      = RetryEnv.parse(sys.env.get("LLM4ZIO_RETRIES"))
                       flakyRetries = FlakyRetryEnv.parse(sys.env.get("LLM4ZIO_FLAKY_RETRIES"))
                       traceKeep    = TraceKeepEnv.parse(sys.env.get("LLM4ZIO_TRACE_KEEP"))
                       level        = verbosity.getOrElse(VerbosityEnv.parse(sys.env.get("LLM4ZIO_VERBOSITY")))
                       autoResume   = AutoResumeEnv.parse(sys.env.get("LLM4ZIO_AUTO_RESUME"))
                       bundle      <- DefaultFlowContext.build(
                                        reasoning,
                                        coder,
                                        workDir,
                                        reviewers,
                                        policy,
                                        retries,
                                        flakyRetries,
                                        workspace,
                                      )
                       (ctx, hub)   = bundle
                       tracker     <- CostTracker.make
                       // Two fire-and-forget subscribers on the bounded event hub. Both drain fast
                       // (terminal write / map update); the hub back-pressures the producer if a
                       // subscriber stalls, which paces output rather than dropping events.
                       consumed    <- TerminalListener.consumeTo(hub, palette, surface, level)
                       _           <- tracker.consume(hub)
                       // At debug, raw provider lines are teed here through the same surface lock the animator/tree use,
                       // so a high-volume stream-json firehose can pace debug output (bounded by the hub's back-pressure).
                       rawSink      = Option.when(level == Verbosity.Debug)((l: String) => surface.log(palette.raw(l)))
                       recorder    <- FlowRecorder.install(hub, Workspace.llm4zioDir(workspace, workDir), traceKeep, rawSink)
                       _           <- ZIO.when(level == Verbosity.Debug)(
                                        surface.log(palette.info(s"trace: ${recorder.tracePath}"))
                                      )
                       _           <- {
                         given FlowEvents = hub
                         AutoResume.withAutoResume(autoResume)(
                           withUsageLimitRetry(policy)(
                             body(ctx).mapError {
                               case fe: FlowError => fe
                               case other         => FlowError.Llm(other.toString)
                             }
                           )
                         ).unit
                           // On exit, first drain the hub so trailing events (notably a final StageFailed) render
                           // rather than being interrupted away; on failure, follow with an authoritative ✖ banner
                           // written straight to the surface — so a failed run can never look like a clean finish.
                           // The full cause (stack trace, defects) goes to the file-only logger for post-mortem; the
                           // console still shows just the one-line reason.
                           .onExit {
                             case Exit.Failure(cause) =>
                               ZIO.logErrorCause("flow failed", cause) *>
                                 TerminalListener.awaitDrained(hub, consumed, 3.seconds) *>
                                 surface.log("\n" + palette.fail(s"flow failed: ${failMessage(cause)}"))
                             case Exit.Success(_)     =>
                               TerminalListener.awaitDrained(hub, consumed, 3.seconds)
                           }
                           // The footer renders and forgets; the cost ledger is the durable record: one structured
                           // JSON line per run (stage × agent × model cells), appended next to the traces and never
                           // pruned — LLM4ZIO_RUN_LABEL stamps a correlation key for cross-run aggregation.
                           .ensuring(
                             tracker.summary.flatMap(s => surface.log("\n" + s)) *>
                               CostLedger.write(
                                 Workspace.llm4zioDir(workspace, workDir),
                                 tracker,
                                 recorder.runId,
                                 Workspace.repoId(workspace, workDir).getOrElse(workDir.getFileName.toString),
                                 sys.env.get("LLM4ZIO_RUN_LABEL").map(_.trim).filter(_.nonEmpty),
                                 ctx.userPrompt,
                               )
                           )
                       }
                     yield ()
                   }
                   .provideSomeLayer[HttpClient & Client](RunnerLog.fileOnly(logPath))
    yield ())
      .provide(HttpClient.reliableClient, HttpClient.live)
      .mapError {
        case t: Throwable => t
        case other        => new RuntimeException(other.toString)
      }

  /** A script was started without a usable prompt. Carried as a Throwable so [[script]] composes with [[run]]'s
    * `ZIO[Any, Throwable, Unit]`; [[flow]] renders `message` as the usage line and exits 2.
    */
  final case class ScriptUsage(usage: String) extends RuntimeException(usage)

  private val promptUsage = """usage: scala-cli run <script>.sc -- "<prompt>""""

  /** First non-blank CLI arg, else the script's default, else a usage error. */
  def resolvePrompt(args: List[String], defaultPrompt: Option[String] = None): Either[String, String] =
    args.headOption
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(defaultPrompt)
      .toRight(promptUsage)

  /** Resolve the final prompt string from parsed args + the script default, reading the file when
    * `--prompt-file`/`@file` was given. Precedence: prompt file > positional text > script default > usage error. The
    * file's contents become the prompt **verbatim**. A missing/unreadable file is a [[ScriptUsage]] (exit 2), not a
    * flow failure.
    */
  def readPrompt(args: FlowArgs, defaultPrompt: Option[String]): IO[ScriptUsage, String] =
    args.promptFile match
      case Some(path) =>
        ZIO
          .attemptBlocking(Files.readString(path))
          .mapError(e => ScriptUsage(s"could not read prompt file $path: ${e.getMessage}"))
      case None       =>
        args.promptText.orElse(defaultPrompt) match
          case Some(prompt) => ZIO.succeed(prompt)
          case None         => ZIO.fail(ScriptUsage(promptUsage))

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
    verbosity: Option[Verbosity] = None,
    workspace: Path = Path.of(".").toAbsolutePath.normalize,
  )(
    body: FlowContext ?=> ZIO[Any, FlowError, Any]
  ): ZIO[Any, Throwable, Unit] =
    FlowArgs.parse(args) match
      case Left(usage)   => ZIO.fail(ScriptUsage(usage))
      case Right(parsed) =>
        for
          prompt <- readPrompt(parsed, defaultPrompt)
          repo   <- resolveRepo(parsed, workspace)
          _      <- run(repo, scriptReasoning(coder, reasoning), coder, reviewers, usageLimit, verbosity, workspace)(
                      withPrompt(prompt)(body)
                    )
        yield ()

  /** The repository a script operates on: the `--repo` directory (resolved against the workspace) when given, else the
    * workspace itself. A `--repo` that is not an existing directory is a [[ScriptUsage]] (exit 2) — fail fast before
    * any connector or git work.
    */
  private[runner] def resolveRepo(parsed: FlowArgs, workspace: Path): IO[ScriptUsage, Path] =
    parsed.repo match
      case None       => ZIO.succeed(workspace)
      case Some(repo) =>
        val resolved = workspace.resolve(repo).normalize
        ZIO.attemptBlocking(Files.isDirectory(resolved)).orElseSucceed(false).flatMap { isDir =>
          if isDir then ZIO.succeed(resolved)
          else ZIO.fail(ScriptUsage(s"--repo is not a directory: $resolved"))
        }

  /** Run a flow effect as a process main — the runner's single entry-point `unsafeRun`, shared by the `.sc` surface
    * ([[flow]]) and the Java surface (`Llm4zioJava.flow`): fork the effect, wire Ctrl-C to interrupt the flow fiber
    * (stages unwind, the ✖ banner renders), and map the exit to process behaviour — a missing prompt ([[ScriptUsage]])
    * exits 2, a failed flow exits 1, SIGINT lets the JVM itself exit 130.
    */
  def unsafeMain(effect: ZIO[Any, Throwable, Unit]): Unit =
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
        case Exit.Success(_)                                => ()
        case Exit.Failure(cause) if cause.isInterruptedOnly => () // SIGINT path: the JVM itself exits 130
        case Exit.Failure(cause)                            =>
          cause.failureOption match
            case Some(usage: ScriptUsage) =>
              java.lang.System.err.print(usage.getMessage + "\n")
              sys.exit(2)
            case _                        =>
              sys.exit(1) // run() already rendered the ✖ banner + reason
    }
