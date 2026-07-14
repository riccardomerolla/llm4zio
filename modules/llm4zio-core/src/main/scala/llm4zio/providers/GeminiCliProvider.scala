package llm4zio.providers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.time.ZoneId

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.process.{ Command, ProcessInput }
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.observability.StreamRecorder
import llm4zio.tools.{ AnyTool, JsonSchema }

enum GeminiCliStreamEvent:
  case LogLine(line: String)
  case Init(model: Option[String], sessionId: Option[String])
  case Message(role: Option[String], content: Option[String], delta: Boolean)
  case ToolUse(toolName: Option[String], toolId: Option[String], input: Option[String] = None)
  case ToolResult(toolId: Option[String], status: Option[String], content: Option[String] = None)
  case Error(message: Option[String], code: Option[Int], errorType: Option[String])
  case Result(status: Option[String], errorMessage: Option[String], stats: Option[GeminiCliProvider.GeminiStreamStats])

enum GeminiSandbox:
  case Docker
  case Podman
  case SeatbeltMacOS // sandbox-exec (macOS only)
  case Runsc         // gVisor (Linux)
  case Lxc           // LXC/LXD (Linux, experimental)
  case Default       // -s only, no backend preference

object GeminiSandbox:
  /** Value to set in GEMINI_SANDBOX env var. None = let gemini choose (Default case). */
  def envValue(s: GeminiSandbox): Option[String] = s match
    case Docker        => Some("docker")
    case Podman        => Some("podman")
    case SeatbeltMacOS => Some("sandbox-exec")
    case Runsc         => Some("runsc")
    case Lxc           => Some("lxc")
    case Default       => None

trait GeminiCliExecutor:
  def checkGeminiInstalled: IO[LlmError, Unit]
  def runGeminiProcess(
    prompt: String,
    config: LlmConfig,
    executionContext: GeminiCliExecutionContext = GeminiCliExecutionContext.default,
  ): IO[LlmError, String]
  def runGeminiProcessStream(
    prompt: String,
    config: LlmConfig,
    executionContext: GeminiCliExecutionContext = GeminiCliExecutionContext.default,
  ): ZStream[Any, LlmError, GeminiCliStreamEvent]

final case class GeminiCliExecutionContext(
  cwd: Option[String] = None,
  includeDirectories: List[String] = Nil,
  sandbox: Option[GeminiSandbox] = None,
  turnLimit: Option[Int] = None,
  // Read-only: drive gemini with `--approval-mode plan` (no edits) instead of `-y` (auto-approve all).
  readOnly: Boolean = false,
)

object GeminiCliExecutionContext:
  val default: GeminiCliExecutionContext = GeminiCliExecutionContext()

  /** Project the generic CLI config onto gemini's execution context. Every gemini-relevant field a flow can set on
    * [[llm4zio.core.CliConnectorConfig]] must be mapped here — `turnLimit` was once dropped in the factory, so a
    * configured turn cap never reached the process and a confused headless gemini could burn turns unbounded.
    */
  def from(cfg: CliConnectorConfig): GeminiCliExecutionContext =
    GeminiCliExecutionContext(
      cwd = cfg.workingDir,
      turnLimit = cfg.turnLimit,
      readOnly = cfg.readOnly,
    )

object GeminiCliExecutor:

  /** Cap on captured (non-noise) stderr lines per process — enough for gemini's error report + stack trace. */
  private val stderrCaptureLimit = 200

  private[providers] def validateExitCode(
    exitCode: Int,
    stderr: String,
    turnLimit: Option[Int],
  ): IO[LlmError, Unit] =
    exitCode match
      case 0  => ZIO.unit
      case 42 =>
        ZIO.logWarning(s"Gemini CLI rejected the input (exit 42): $stderr") *>
          ZIO.fail(LlmError.InvalidRequestError(stderr))
      case 53 =>
        ZIO.logWarning(s"Gemini CLI turn limit exceeded (exit 53): $stderr") *>
          ZIO.fail(LlmError.TurnLimitError(turnLimit))
      case _  =>
        // Classify before falling back to a generic ProviderError: a quota-driven death (gemini prints the reason —
        // with its reset time — to stderr) must surface as the typed usage-limit error, not a retriable-looking blob.
        ZIO.logError(s"Gemini CLI exited with code $exitCode: $stderr") *>
          Clock.instant.flatMap { now =>
            ZIO.fail(
              UsageLimits
                .classify("gemini", stderr, now, ZoneId.systemDefault)
                .getOrElse(LlmError.ProviderError(s"Gemini CLI exited with code $exitCode: $stderr", None))
            )
          }

  /** The first captured stderr line carrying a quota/usage-limit signal. Gemini prints the real reason for a dead turn
    * — e.g. `TerminalQuotaError: You have exhausted your capacity on this model. Your quota will reset after 21h1m53s.`
    * — to stderr only, while stdout gets a catch-all error event or nothing at all; this line is what lets
    * [[UsageLimits.classify]] type the failure (with a concrete reset time) instead of burning retries.
    */
  private[providers] def quotaDiagnostic(stderr: Chunk[String]): Option[String] =
    stderr.find(UsageLimits.isGeminiQuota).map(_.trim)

  /** Fold the stderr quota diagnostic (when one exists) into a fatal stream event's message, so downstream
    * classification sees gemini's real reason instead of the stdout catch-all. Events whose message already carries a
    * quota signal pass through unchanged; non-fatal events pass through unchanged.
    */
  private[providers] def withStderrDiagnostic(
    event: GeminiCliStreamEvent,
    stderr: Chunk[String],
  ): GeminiCliStreamEvent =
    quotaDiagnostic(stderr) match
      case None       => event
      case Some(diag) =>
        def merged(base: Option[String]): Option[String] =
          base.map(_.trim).filter(_.nonEmpty) match
            case Some(m) if UsageLimits.isGeminiQuota(m) => Some(m)
            case Some(m)                                 => Some(s"$m — $diag")
            case None                                    => Some(diag)
        event match
          case GeminiCliStreamEvent.Error(message, code, errorType)                                 =>
            GeminiCliStreamEvent.Error(merged(message), code, errorType)
          case GeminiCliStreamEvent.Result(status, errorMessage, stats) if status.contains("error") =>
            GeminiCliStreamEvent.Result(status, merged(errorMessage), stats)
          case other                                                                                => other

  /** Exit-time validation for the stream path, over the captured stderr. Two shapes beyond [[validateExitCode]]:
    *   - quota exhaustion with a nonzero exit → the typed usage-limit error, not a generic ProviderError;
    *   - quota exhaustion with exit 0 and NO assistant text → gemini died of quota but "succeeded": without this the
    *     empty stream surfaces downstream as a retriable "empty response" and burns the whole flaky-retry + auto-resume
    *     budget against a wall that will not move for hours.
    * Exits 42/53 keep their specific meanings (rejected input / turn limit) regardless of stderr.
    */
  private[providers] def validateStreamExit(
    exitCode: Int,
    stderr: Chunk[String],
    sawAssistantText: Boolean,
    turnLimit: Option[Int],
  ): IO[LlmError, Unit] =
    def classifiedQuota(diag: String): IO[LlmError, Nothing] =
      Clock.instant.flatMap { now =>
        val err = UsageLimits
          .classify("gemini", diag, now, ZoneId.systemDefault)
          .getOrElse(LlmError.UsageLimitError(resetAt = None, provider = "gemini", message = diag))
        ZIO.logWarning(s"Gemini CLI quota exhausted (from stderr): $diag") *> ZIO.fail(err)
      }
    val stderrText                                           = stderr.mkString("\n").trim
    exitCode match
      case 0       =>
        quotaDiagnostic(stderr) match
          case Some(diag) if !sawAssistantText => classifiedQuota(diag)
          case _                               => ZIO.unit
      case 42 | 53 =>
        validateExitCode(
          exitCode,
          if stderrText.nonEmpty then stderrText else s"Gemini stream process exited with code $exitCode",
          turnLimit,
        )
      case _       =>
        quotaDiagnostic(stderr) match
          case Some(diag) => classifiedQuota(diag)
          case None       =>
            validateExitCode(
              exitCode,
              if stderrText.nonEmpty then stderrText else s"Gemini stream process exited with code $exitCode",
              turnLimit,
            )

  /** Environment variables injected into every spawned gemini process.
    *
    * `GEMINI_CLI_TRUST_WORKSPACE=true` is always set: llm4zio runs gemini non-interactively with `-y` (auto-approve),
    * and gemini's folder-trust gate otherwise aborts with exit 55 (`FatalUntrustedWorkspaceError`) in any untrusted
    * directory — including the fresh temp working dirs the example flows run in. Trusting the workspace is gemini's
    * documented bypass for headless/automated environments and is consistent with the `-y` autonomy already chosen.
    *
    * `GEMINI_SANDBOX` selects the sandbox backend when a concrete one is configured (see [[GeminiSandbox.envValue]]);
    * the `Default` case emits `-s` only and lets gemini pick, so no env var is set.
    *
    * `GEMINI_CLI_SYSTEM_DEFAULTS_PATH` points at the turn-limit settings file when a cap is configured: gemini has NO
    * `--turn-limit` flag (yargs strict mode exits 1 with "Unknown arguments"), the cap is the `model.maxSessionTurns`
    * SETTING. The system-DEFAULTS layer has the lowest settings precedence, so a user's or an org's own settings —
    * including their own `maxSessionTurns` — still win, and nothing in the target repo is touched.
    */
  private[providers] def geminiProcessEnv(
    ctx: GeminiCliExecutionContext,
    turnLimitSettings: Option[Path] = None,
  ): Map[String, String] =
    val trust   = Map("GEMINI_CLI_TRUST_WORKSPACE" -> "true")
    val sandbox = ctx.sandbox.flatMap(GeminiSandbox.envValue).map("GEMINI_SANDBOX" -> _).toMap
    val turnCap = turnLimitSettings.map(p => "GEMINI_CLI_SYSTEM_DEFAULTS_PATH" -> p.toString).toMap
    trust ++ sandbox ++ turnCap

  /** The system-defaults settings payload that caps a session's turns. Tripping the cap raises gemini's
    * `FatalTurnLimitedError` (exit 53), which [[validateExitCode]] types as `LlmError.TurnLimitError`.
    */
  private[providers] def turnLimitSettingsJson(turns: Int): String =
    s"""{"model":{"maxSessionTurns":$turns}}"""

  /** Materialize the settings file behind `GEMINI_CLI_SYSTEM_DEFAULTS_PATH` for `ctx`, if a cap is configured: a tiny
    * JSON in the system temp dir, one per limit value, rewritten on every spawn (idempotent, nothing to clean up).
    */
  private[providers] def turnLimitSettingsFile(ctx: GeminiCliExecutionContext): IO[LlmError, Option[Path]] =
    ZIO.foreach(ctx.turnLimit) { turns =>
      ZIO
        .attemptBlocking {
          val path = Path.of(java.lang.System.getProperty("java.io.tmpdir"), s"llm4zio-gemini-max-turns-$turns.json")
          Files.write(path, turnLimitSettingsJson(turns).getBytes(StandardCharsets.UTF_8))
          path
        }
        .mapError(e => LlmError.ProviderError(s"Failed to write gemini turn-limit settings: ${e.getMessage}", Some(e)))
    }

  private[providers] def buildGeminiArgs(
    config: LlmConfig,
    ctx: GeminiCliExecutionContext,
    outputFormat: String,
  ): List[String] =
    // Read-only ⇒ `--approval-mode plan` (no edits); otherwise `-y` auto-approves all tools.
    val approvalArgs   = if ctx.readOnly then List("--approval-mode", "plan") else List("-y")
    val baseArgs       = List("-m", config.model) ++ approvalArgs ++ List("--output-format", outputFormat)
    val includeDirArgs = ctx.includeDirectories.distinct.flatMap(p => List("--include-directories", p))
    // The -s flag enables sandbox mode. The backend is controlled separately via
    // GEMINI_SANDBOX env var injected in startProcess (see GeminiSandbox.envValue).
    // Default sandbox: -s is emitted but no env var is set, letting gemini pick the backend.
    // NB: ctx.turnLimit deliberately emits NO argv — gemini has no --turn-limit flag; the cap goes
    // via the settings file behind GEMINI_CLI_SYSTEM_DEFAULTS_PATH (see geminiProcessEnv).
    val sandboxArgs    = ctx.sandbox.map(_ => "-s").toList
    baseArgs ++ includeDirArgs ++ sandboxArgs

  val default: GeminiCliExecutor =
    new GeminiCliExecutor {
      private def isWindows: Boolean =
        Option(java.lang.System.getProperty("os.name")).getOrElse("").toLowerCase.contains("win")

      private def geminiArgv(config: LlmConfig, ctx: GeminiCliExecutionContext, outputFormat: String): List[String] =
        val gemini = if isWindows then List("cmd", "/c", "gemini") else List("gemini")
        gemini ++ GeminiCliExecutor.buildGeminiArgs(config, ctx, outputFormat)

      /** A zio-process [[Command]] for gemini: argv + working dir + additive env + the prompt on stdin (bypasses
        * ARG_MAX). zio-process owns the lifecycle — the child is killed when the using scope closes and its streams are
        * interruptible — so a failed turn or a Ctrl+C tears gemini down instead of deadlocking on an uninterruptible
        * blocking read (the cause of the earlier hang).
        */
      private def geminiCmd(
        prompt: String,
        config: LlmConfig,
        ctx: GeminiCliExecutionContext,
        outputFormat: String,
        turnLimitSettings: Option[Path],
      ): Command =
        val argv    = geminiArgv(config, ctx, outputFormat)
        val base    = Command(argv.head, argv.tail*)
        val withCwd = ctx.cwd.fold(base)(p => base.workingDirectory(java.nio.file.Paths.get(p).toFile))
        val env     = GeminiCliExecutor.geminiProcessEnv(ctx, turnLimitSettings)
        val withEnv = if env.isEmpty then withCwd else withCwd.env(env)
        withEnv.stdin(ProcessInput.fromUTF8String(prompt))

      private def startError(e: Throwable): LlmError =
        LlmError.ProviderError(s"Failed to start gemini process: ${e.getMessage}", Some(e))

      override def checkGeminiInstalled: IO[LlmError, Unit] =
        val probe = if isWindows then Command("where", "gemini") else Command("which", "gemini")
        probe.exitCode
          .mapError(e => LlmError.ProviderError(s"Failed to check gemini installation: ${e.getMessage}", Some(e)))
          .flatMap(code =>
            if code.code == 0 then ZIO.unit else ZIO.fail(LlmError.ConfigError("gemini-cli not installed"))
          )

      override def runGeminiProcess(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): IO[LlmError, String] =
        ZIO.scoped {
          for
            _       <- ZIO.logDebug(s"Starting Gemini: ${geminiArgv(config, executionContext, "json").mkString(" ")}")
            turnCap <- GeminiCliExecutor.turnLimitSettingsFile(executionContext)
            process <- ZIO.acquireRelease(
                         geminiCmd(prompt, config, executionContext, "json", turnCap).run.mapError(startError)
                       )(p => p.killForcibly.ignore)
            errF    <- process.stderr.string.fork
            output  <-
              process.stdout.string
                .mapError(e => LlmError.ProviderError(s"Failed to read gemini output: ${e.getMessage}", Some(e)))
            exit    <- process.exitCode
                         .mapError(e => LlmError.ProviderError(s"Process wait failed: ${e.getMessage}", Some(e)))
            stderr  <- errF.join.orElseSucceed("")
            _       <- GeminiCliExecutor.validateExitCode(
                         exit.code,
                         if stderr.trim.nonEmpty then stderr.trim else s"Gemini process exited with code ${exit.code}",
                         executionContext.turnLimit,
                       )
            out     <- ZIO
                         .fromEither(GeminiCliProvider.extractResponse(output))
                         .mapError(err => LlmError.ParseError(err, output))
          yield out
        }.timeoutFail(LlmError.TimeoutError(config.timeout))(config.timeout)

      override def runGeminiProcessStream(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
        ZStream.unwrapScoped {
          for
            _          <- ZIO.logDebug(
                            s"Starting Gemini stream: ${geminiArgv(config, executionContext, "stream-json").mkString(" ")}"
                          )
            turnCap    <- GeminiCliExecutor.turnLimitSettingsFile(executionContext)
            process    <- geminiCmd(prompt, config, executionContext, "stream-json", turnCap).run.mapError(startError)
            stderrRef  <- Ref.make(Chunk.empty[String])
            sawText    <- Ref.make(false)
            // Drain stderr: benign chatter at debug, anything else at WARN — and captured (bounded), because gemini
            // prints the real reason for a dead turn (e.g. TerminalQuotaError with the quota reset time) to stderr
            // only, while stdout carries a catch-all error or nothing. Killing the child first on teardown
            // (finalizer below, registered last ⇒ runs first) closes this stream so the drain fiber can never wedge
            // scope shutdown.
            errDrain   <- process.stderr.linesStream
                            .foreach { line =>
                              val shown = line.take(500) + (if line.length > 500 then "..." else "")
                              if GeminiCliProvider.isKnownStderrNoise(line) then ZIO.logDebug(s"Gemini stderr: $shown")
                              else
                                ZIO.logWarning(s"Gemini stderr: $shown") *>
                                  stderrRef.update(c =>
                                    if c.size < GeminiCliExecutor.stderrCaptureLimit then c :+ line else c
                                  )
                            }
                            .ignore
                            .forkScoped
            _          <- ZIO.addFinalizer(process.killForcibly.ignore)
            // Fatal events and the exit check consult stderr for the quota diagnostic; the write races the stdout
            // pipe, so give the drain a moment to reach EOF (gemini exits right after a fatal error) before reading.
            awaitStderr = errDrain.join.timeout(2.seconds).ignore
          yield process.stdout.linesStream
            .mapError(e => LlmError.ProviderError(s"Failed to read gemini stream output: ${e.getMessage}", Some(e)))
            .tap(line =>
              ZIO.logDebug(s"Gemini stream output: ${line.take(500)}${if line.length > 500 then "..." else ""}")
            )
            .map(GeminiCliProvider.parseStreamEvent)
            .mapZIO {
              case msg @ GeminiCliStreamEvent.Message(role, content, _)
                   if role.exists(_.equalsIgnoreCase("assistant")) && content.exists(_.trim.nonEmpty) =>
                sawText.set(true).as(msg)
              case err: GeminiCliStreamEvent.Error                                             =>
                awaitStderr *> stderrRef.get.map(GeminiCliExecutor.withStderrDiagnostic(err, _))
              case res @ GeminiCliStreamEvent.Result(status, _, _) if status.contains("error") =>
                awaitStderr *> stderrRef.get.map(GeminiCliExecutor.withStderrDiagnostic(res, _))
              case other                                                                       => ZIO.succeed(other)
            } ++
            ZStream.fromZIO(
              for
                exit    <- process.exitCode
                             .mapError(e => LlmError.ProviderError(s"Process wait failed: ${e.getMessage}", Some(e)))
                _       <- awaitStderr // process is gone; let the capture reach EOF before reading
                stderr  <- stderrRef.get
                hadText <- sawText.get
                _       <- GeminiCliExecutor.validateStreamExit(exit.code, stderr, hadText, executionContext.turnLimit)
              yield ()
            ).drain
        }
    }

  val live: ULayer[GeminiCliExecutor] =
    ZLayer.succeed(default)

object GeminiCliProvider:
  final private case class GeminiHeadlessError(
    `type`: Option[String] = None,
    message: Option[String] = None,
    code: Option[Int] = None,
  ) derives JsonDecoder

  final private case class GeminiHeadlessResponse(
    response: Option[String] = None,
    error: Option[GeminiHeadlessError] = None,
  ) derives JsonDecoder

  final case class GeminiStreamError(
    `type`: Option[String] = None,
    message: Option[String] = None,
    code: Option[Int] = None,
  ) derives JsonDecoder

  final case class GeminiStreamStats(
    total_tokens: Option[Int] = None,
    input_tokens: Option[Int] = None,
    output_tokens: Option[Int] = None,
    cached: Option[Int] = None,
  ) derives JsonDecoder

  final private case class GeminiStreamJsonEvent(
    `type`: String,
    role: Option[String] = None,
    content: Option[String] = None,
    delta: Option[Boolean] = None,
    tool_name: Option[String] = None,
    tool_id: Option[String] = None,
    // Real gemini stream-json: tool args arrive as `parameters` (a JSON object), results as `output`,
    // and errors as a flat `text` field (not a nested error object).
    parameters: Option[Json] = None,
    output: Option[String] = None,
    text: Option[String] = None,
    message: Option[String] = None, // error events also carry a flat top-level `message`
    status: Option[String] = None,
    model: Option[String] = None,
    session_id: Option[String] = None,
    error: Option[GeminiStreamError] = None,
    stats: Option[GeminiStreamStats] = None,
  ) derives JsonDecoder

  private[providers] def extractResponse(output: String): Either[String, String] =
    val normalized = output.replace("\r\n", "\n").trim
    if normalized.isEmpty then Left("Gemini CLI returned empty output")
    else
      val jsonDecoded = StructuredOutputs.jsonCandidates(normalized)
        .iterator
        .flatMap(tryDecodeHeadless)
        .nextOption()
        .orElse(tryDecodeHeadless(normalized))

      jsonDecoded match
        case Some(result) => result
        case None         =>
          extractAfterPreamble(normalized)
            .toRight("Failed to decode Gemini CLI JSON output: output did not contain a JSON envelope")

  def parseStreamEvent(line: String): GeminiCliStreamEvent =
    val trimmed = line.trim
    if trimmed.isEmpty then GeminiCliStreamEvent.LogLine(line)
    else
      trimmed.fromJson[GeminiStreamJsonEvent] match
        case Right(event) =>
          event.`type` match
            case "init"        => GeminiCliStreamEvent.Init(event.model, event.session_id)
            case "message"     => GeminiCliStreamEvent.Message(event.role, event.content, event.delta.getOrElse(false))
            case "tool_use"    =>
              GeminiCliStreamEvent.ToolUse(event.tool_name, event.tool_id, event.parameters.map(_.toString))
            case "tool_result" => GeminiCliStreamEvent.ToolResult(event.tool_id, event.status, event.output)
            case "error"       =>
              // Prefer the real flat `text`; fall back to a nested error object, then to the raw event line — so an
              // error we don't fully recognise still surfaces *something* instead of a useless empty message.
              GeminiCliStreamEvent.Error(
                message = event.text
                  .map(_.trim)
                  .filter(_.nonEmpty)
                  .orElse(event.message.map(_.trim).filter(_.nonEmpty))
                  .orElse(event.error.flatMap(_.message))
                  .orElse(Some(trimmed)),
                code = event.error.flatMap(_.code),
                errorType = event.error.flatMap(_.`type`),
              )
            case "result"      =>
              GeminiCliStreamEvent.Result(event.status, event.error.flatMap(_.message), event.stats)
            case _             => GeminiCliStreamEvent.LogLine(line)
        case Left(_)      => GeminiCliStreamEvent.LogLine(line)

  private def tryDecodeHeadless(raw: String): Option[Either[String, String]] =
    raw.fromJson[GeminiHeadlessResponse].toOption.map { response =>
      response.error.flatMap(_.message.map(_.trim).filter(_.nonEmpty)) match
        case Some(message) =>
          val details = List(
            response.error.flatMap(_.`type`).map(t => s"type=$t"),
            response.error.flatMap(_.code).map(code => s"code=$code"),
          ).flatten.mkString(", ")
          Left(
            if details.nonEmpty then s"Gemini CLI returned an error ($details): $message"
            else s"Gemini CLI returned an error: $message"
          )
        case None          =>
          val content = response.response.map(_.trim).filter(_.nonEmpty)
          content.toRight("Gemini CLI JSON output did not contain a response")
    }

  private val preamblePatterns: List[scala.util.matching.Regex] = List(
    "^Loaded cached .*".r,
    "^Loading extension: .*".r,
    "^Server '.+' supports tool updates.*".r,
    "^Attempt \\d+ failed: .*".r,
    "^YOLO mode is enabled.*".r,
  )

  private def isPreambleLine(line: String): Boolean =
    preamblePatterns.exists(_.matches(line))

  /** Benign stderr chatter the gemini CLI prints to stderr regardless of success — not worth surfacing. Anything else
    * on stderr is treated as a real diagnostic and logged at WARN.
    */
  private[providers] def isKnownStderrNoise(line: String): Boolean =
    val l = line.trim
    l.isEmpty ||
    l.contains("256-color support not detected") ||
    l.contains("color support detected") || // "Warning: Limited color support detected (TERM=...)"
    l.contains("support not detected") ||   // "True color (24-bit) support not detected..."
    l.contains("tmux") ||                   // the ~/.tmux.conf suggestion lines that follow
    l.startsWith("set -g ") ||              // tmux.conf snippet continuation
    l.startsWith("set -ga ") ||
    l.startsWith("Ripgrep is not available") ||
    l.startsWith("YOLO mode is enabled") ||
    l.startsWith("Shell cwd was reset to") ||
    l.startsWith("Loaded cached") ||
    l.startsWith("Loading extension") ||
    l.contains("[IDEClient]")

  private def extractAfterPreamble(text: String): Option[String] =
    val content = text.linesIterator
      .dropWhile(line => line.trim.isEmpty || isPreambleLine(line.trim))
      .mkString("\n")
      .trim
    Option(content).filter(_.nonEmpty)

  private def streamStatsToUsage(stats: Option[GeminiStreamStats]): Option[TokenUsage] =
    stats.flatMap(s =>
      for
        inputCount  <- s.input_tokens
        outputCount <- s.output_tokens
        totalCount  <- s.total_tokens
      yield TokenUsage(prompt = inputCount, completion = outputCount, total = totalCount, cached = s.cached)
    )

  def make(
    config: LlmConfig,
    executor: GeminiCliExecutor,
    executionContext: GeminiCliExecutionContext = GeminiCliExecutionContext.default,
  ): CliConnector =
    new CliConnector:

      // CliConnector methods
      override def id: ConnectorId = ConnectorId.GeminiCli

      override def interactionSupport: InteractionSupport = InteractionSupport.InteractiveStdin

      override def healthCheck: IO[LlmError, HealthStatus] =
        executor.checkGeminiInstalled.as(
          HealthStatus(Availability.Healthy, AuthStatus.Valid, None)
        ).catchAll(_ =>
          ZIO.succeed(HealthStatus(Availability.Unhealthy, AuthStatus.Unknown, None))
        )

      override def buildArgv(prompt: String, ctx: CliContext): List[String] =
        val base = List("gemini", "--yolo")
        val dirs = if ctx.repoPath.nonEmpty then List("--include-directories", ctx.repoPath) else Nil
        // ctx.turnLimit emits NO argv: gemini has no --turn-limit flag (unknown args exit 1). This
        // argv-only path cannot inject the maxSessionTurns settings env, so the cap doesn't apply here.
        base ++ dirs ++ List("-p", prompt)

      override def buildInteractiveArgv(ctx: CliContext): List[String] =
        val base = List("gemini", "--yolo")
        val dirs = if ctx.repoPath.nonEmpty then List("--include-directories", ctx.repoPath) else Nil
        base ++ dirs

      override def complete(prompt: String): IO[LlmError, String] =
        executor.runGeminiProcess(prompt, config, executionContext)
          .flatMap(output =>
            ZIO.fromEither(extractResponse(output))
              .mapError(msg => LlmError.ParseError(msg, output))
          )

      override def completeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        executeStream(prompt)

      // LlmService methods
      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        val baseMetadata = Map("provider" -> "gemini-cli", "model" -> config.model)
        ZStream.fromZIO(ZIO.logInfo(s"Executing Gemini CLI stream with model: ${config.model}")).drain ++
          ZStream.fromZIO(executor.checkGeminiInstalled).drain ++
          ZStream.fromZIO(Ref.make(baseMetadata)).flatMap { metaRef =>
            executor
              .runGeminiProcessStream(prompt, config, executionContext)
              .tap {
                case GeminiCliStreamEvent.LogLine(line) if line.trim.isEmpty || isPreambleLine(line.trim) =>
                  ZIO.logDebug(s"Gemini stream preamble: ${line.trim}")
                case GeminiCliStreamEvent.LogLine(line) if line.trim.startsWith("{")                      =>
                  // A line that looks like a stream-json event but failed to decode — surface it (WARN reaches the log;
                  // trace/debug do not) so an unrecognised/half-formed protocol line isn't lost silently.
                  ZIO.logWarning(s"Gemini stream: unparseable JSON line: ${line.trim.take(300)}")
                case GeminiCliStreamEvent.LogLine(line)                                                   =>
                  ZIO.logTrace(s"Gemini stream non-JSON output: ${line.trim}")
                case GeminiCliStreamEvent.Init(model, sessionId)                                          =>
                  // A session serving a different model than the one we passed with -m (CLI routing / fallback /
                  // user settings) is exactly the "I asked for flash, why is it burning pro quota?" surprise —
                  // surface it at WARN instead of burying it at debug.
                  ZIO.logDebug(
                    s"Gemini stream initialized${model.fold("")(m =>
                        s" with model=$m"
                      )}${sessionId.fold("")(id => s", session=$id")}"
                  ) *>
                    ZIO.foreachDiscard(model.filter(m => !m.equalsIgnoreCase(config.model)))(m =>
                      ZIO.logWarning(
                        s"Gemini CLI is serving model '$m' but '${config.model}' was requested — " +
                          "the CLI routed or fell back (check `gemini` settings and model quota)"
                      )
                    )
                case GeminiCliStreamEvent.Message(role, _, delta)                                         =>
                  ZIO.logDebug(s"Gemini stream message event role=${role.getOrElse("unknown")}, delta=$delta")
                case GeminiCliStreamEvent.ToolUse(toolName, toolId, _)                                    =>
                  ZIO.logDebug(
                    s"Gemini stream tool use${toolName.fold("")(n => s" tool=$n")}${toolId.fold("")(id => s", id=$id")}"
                  )
                case GeminiCliStreamEvent.ToolResult(toolId, status, _)                                   =>
                  ZIO.logDebug(
                    s"Gemini stream tool result${toolId.fold("")(id => s" id=$id")}${status.fold("")(v => s", status=$v")}"
                  )
                case GeminiCliStreamEvent.Error(message, code, errorType)                                 =>
                  ZIO.logWarning(
                    s"Gemini stream error event: ${message.getOrElse("unknown")} code=${code.getOrElse(-1)} type=${errorType.getOrElse("unknown")}"
                  )
                case GeminiCliStreamEvent.Result(status, errorMessage, _)                                 =>
                  ZIO.logDebug(
                    s"Gemini stream result status=${status.getOrElse("unknown")}${errorMessage.fold("")(msg =>
                        s", error=$msg"
                      )}"
                  )
              }
              .tap {
                case GeminiCliStreamEvent.LogLine(line)                                               =>
                  StreamRecorder.current.get.flatMap(_.rawLine("gemini-cli", Some(config.model), line))
                case GeminiCliStreamEvent.Error(message, code, errorType)                             =>
                  val details = List(errorType.map(t => s"type=$t"), code.map(c => s"code=$c")).flatten.mkString(", ")
                  val msg     = message.getOrElse("unknown error")
                  val text    = if details.nonEmpty then s"$msg ($details)" else msg
                  StreamRecorder.current.get.flatMap(_.streamError("gemini-cli", Some(config.model), text))
                case GeminiCliStreamEvent.Result(status, errorMessage, _) if status.contains("error") =>
                  StreamRecorder.current.get.flatMap(
                    _.streamError(
                      "gemini-cli",
                      Some(config.model),
                      errorMessage.getOrElse("Gemini CLI returned an error"),
                    )
                  )
                case _                                                                                => ZIO.unit
              }
              .flatMap {
                case GeminiCliStreamEvent.Init(model, sessionId) =>
                  val updates = model.map("model" -> _).toMap ++
                    sessionId.map("session_id" -> _).toMap ++
                    sessionId.map("sessionId" -> _).toMap
                  ZStream.fromZIO(metaRef.update(_ ++ updates)).drain

                case GeminiCliStreamEvent.Message(role, content, _)
                     if role.exists(_.equalsIgnoreCase("assistant")) =>
                  ZStream.fromZIO(metaRef.get).flatMap { meta =>
                    content.filter(_.nonEmpty) match
                      case Some(text) => ZStream.succeed(LlmChunk(delta = text, metadata = meta))
                      case None       => ZStream.empty
                  }

                case GeminiCliStreamEvent.ToolUse(toolName, toolId, input) =>
                  ZStream.fromZIO(metaRef.get).map { meta =>
                    LlmChunk(
                      delta = "",
                      metadata = meta ++ Map(
                        "event"      -> "tool_use",
                        "tool_name"  -> toolName.getOrElse(""),
                        "tool_id"    -> toolId.getOrElse(""),
                        "tool_input" -> input.getOrElse(""),
                        "toolName"   -> toolName.getOrElse(""),
                        "toolId"     -> toolId.getOrElse(""),
                        "toolInput"  -> input.getOrElse(""),
                      ),
                    )
                  }

                case GeminiCliStreamEvent.ToolResult(toolId, status, content) =>
                  ZStream.fromZIO(metaRef.get).map { meta =>
                    LlmChunk(
                      delta = "",
                      metadata = meta ++ Map(
                        "event"        -> "tool_result",
                        "tool_id"      -> toolId.getOrElse(""),
                        "tool_status"  -> status.getOrElse(""),
                        "tool_content" -> content.getOrElse(""),
                        "toolId"       -> toolId.getOrElse(""),
                        "toolStatus"   -> status.getOrElse(""),
                        "toolResult"   -> content.getOrElse(""),
                      ),
                    )
                  }

                case GeminiCliStreamEvent.Error(message, code, errorType) =>
                  ZStream.unwrap(Clock.instant.map { now =>
                    // Fold type/code into the classified text so a 429/rate_limit on an error event is recognized,
                    // mirroring the tryDecodeHeadless formatting convention.
                    val details = List(
                      errorType.map(t => s"type=$t"),
                      code.map(c => s"code=$c"),
                    ).flatten.mkString(", ")
                    val msg     = message.getOrElse("unknown error")
                    val raw     =
                      if details.nonEmpty then s"Gemini CLI stream error ($details): $msg"
                      else s"Gemini CLI stream error: $msg"
                    val err     = UsageLimits.classify("gemini", raw, now, ZoneId.systemDefault)
                      .getOrElse(LlmError.ProviderError(raw, None))
                    ZStream.fail(err)
                  })

                case GeminiCliStreamEvent.Result(status, errorMessage, _) if status.contains("error") =>
                  ZStream.unwrap(Clock.instant.map { now =>
                    val raw = errorMessage.getOrElse("Gemini CLI returned an error")
                    val err = UsageLimits.classify("gemini", raw, now, ZoneId.systemDefault)
                      .getOrElse(LlmError.ProviderError(s"Gemini CLI returned an error: $raw", None))
                    ZStream.fail(err)
                  })

                case GeminiCliStreamEvent.Result(_, _, stats) =>
                  ZStream.fromZIO(metaRef.get).map { meta =>
                    LlmChunk(
                      delta = "",
                      finishReason = Some("stop"),
                      usage = streamStatsToUsage(stats),
                      metadata = meta,
                    )
                  }

                case _ => ZStream.empty
              }
          }

      private def formatHistory(messages: List[Message]): Either[LlmError, String] =
        val systemMsgs    = messages.filter(_.role == MessageRole.System)
        val nonSystemMsgs = messages.filter(_.role != MessageRole.System)
        if nonSystemMsgs.isEmpty then
          Left(LlmError.InvalidRequestError("History must contain at least one user or assistant message"))
        else
          val systemBlock  =
            if systemMsgs.isEmpty then ""
            else s"[SYSTEM CONTEXT]\n${systemMsgs.map(_.content).mkString("\n")}\n---\n\n"
          val historyLines = nonSystemMsgs.map { msg =>
            val roleLabel = msg.role match
              case MessageRole.User      => "**User:**"
              case MessageRole.Assistant => "**Assistant:**"
              case _                     => msg.role.toString
            s"$roleLabel ${msg.content}"
          }
          Right(systemBlock + historyLines.mkString("\n\n"))

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(ZIO.fromEither(formatHistory(messages))).flatMap(executeStream)

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        ZIO.fail(LlmError.InvalidRequestError("Gemini CLI does not support tool calling"))

      // Override the inherited default to collect text from the rich event stream rather than the non-streaming
      // `complete` path — Gemini's stream carries usage + model metadata that cost tracking expects to see.
      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        executeStructuredWithUsage[A](prompt, schema).map(_._1)

      override def executeStructuredWithUsage[A: JsonCodec](
        prompt: String,
        schema: JsonSchema,
      ): IO[LlmError, (A, Option[TokenUsage], Option[String])] =
        val hinted = StructuredOutputs.withSchemaHint(prompt, schema)
        for
          resp   <- Streaming.collect(executeStream(hinted))
          parsed <- StructuredOutputs.parseFromText[A](resp.content, schema)
                      .mapError(enrichEmptyResponse(_, hinted, resp))
        yield (parsed, resp.usage, resp.metadata.get("model"))

      /** An empty structured response is retried on the flaky budget, but when it is *deterministic* (context overflow,
        * silent quota) those retries all fail identically and the bare message hides the cause. Fold the turn's
        * observable facts — served model, prompt size, token usage — into the error so the failure is diagnosable from
        * the flow log. The "empty response" substring must survive: retry classification keys on it.
        */
      private def enrichEmptyResponse(e: LlmError, prompt: String, resp: LlmResponse): LlmError = e match
        case LlmError.ProviderError(msg, cause) if msg.contains("empty response") =>
          val model = resp.metadata.getOrElse("model", config.model)
          val usage = resp.usage.fold("no token usage reported")(u =>
            s"input_tokens=${u.prompt}, output_tokens=${u.completion}"
          )
          LlmError.ProviderError(s"$msg (model=$model, prompt=${prompt.length} chars, $usage)", cause)
        case other                                                                => other

      override def isAvailable: UIO[Boolean] =
        executor.checkGeminiInstalled.fold(_ => false, _ => true)

  val layer: ZLayer[LlmConfig & GeminiCliExecutor, Nothing, CliConnector] =
    ZLayer.fromFunction { (config: LlmConfig, executor: GeminiCliExecutor) =>
      make(config, executor): CliConnector
    }
