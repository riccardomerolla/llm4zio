package llm4zio.providers

import java.time.ZoneId

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.process.{ Command, ProcessInput }
import zio.stream.ZStream

import llm4zio.core.*
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
)

object GeminiCliExecutionContext:
  val default: GeminiCliExecutionContext = GeminiCliExecutionContext()

object GeminiCliExecutor:

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
        ZIO.logError(s"Gemini CLI exited with code $exitCode: $stderr") *>
          ZIO.fail(LlmError.ProviderError(s"Gemini CLI exited with code $exitCode: $stderr", None))

  /** Environment variables injected into every spawned gemini process.
    *
    * `GEMINI_CLI_TRUST_WORKSPACE=true` is always set: llm4zio runs gemini non-interactively with `-y` (auto-approve),
    * and gemini's folder-trust gate otherwise aborts with exit 55 (`FatalUntrustedWorkspaceError`) in any untrusted
    * directory — including the fresh temp working dirs the example flows run in. Trusting the workspace is gemini's
    * documented bypass for headless/automated environments and is consistent with the `-y` autonomy already chosen.
    *
    * `GEMINI_SANDBOX` selects the sandbox backend when a concrete one is configured (see [[GeminiSandbox.envValue]]);
    * the `Default` case emits `-s` only and lets gemini pick, so no env var is set.
    */
  private[providers] def geminiProcessEnv(ctx: GeminiCliExecutionContext): Map[String, String] =
    val trust   = Map("GEMINI_CLI_TRUST_WORKSPACE" -> "true")
    val sandbox = ctx.sandbox.flatMap(GeminiSandbox.envValue).map("GEMINI_SANDBOX" -> _).toMap
    trust ++ sandbox

  private[providers] def buildGeminiArgs(
    config: LlmConfig,
    ctx: GeminiCliExecutionContext,
    outputFormat: String,
  ): List[String] =
    val baseArgs       = List("-m", config.model, "-y", "--output-format", outputFormat)
    val includeDirArgs = ctx.includeDirectories.distinct.flatMap(p => List("--include-directories", p))
    // The -s flag enables sandbox mode. The backend is controlled separately via
    // GEMINI_SANDBOX env var injected in startProcess (see GeminiSandbox.envValue).
    // Default sandbox: -s is emitted but no env var is set, letting gemini pick the backend.
    val sandboxArgs    = ctx.sandbox.map(_ => "-s").toList
    val turnLimitArgs  = ctx.turnLimit.toList.flatMap(n => List("--turn-limit", n.toString))
    baseArgs ++ includeDirArgs ++ sandboxArgs ++ turnLimitArgs

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
      ): Command =
        val argv    = geminiArgv(config, ctx, outputFormat)
        val base    = Command(argv.head, argv.tail*)
        val withCwd = ctx.cwd.fold(base)(p => base.workingDirectory(java.nio.file.Paths.get(p).toFile))
        val env     = GeminiCliExecutor.geminiProcessEnv(ctx)
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
            process <- ZIO.acquireRelease(
                         geminiCmd(prompt, config, executionContext, "json").run.mapError(startError)
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
            _       <- ZIO.logDebug(
                         s"Starting Gemini stream: ${geminiArgv(config, executionContext, "stream-json").mkString(" ")}"
                       )
            process <- geminiCmd(prompt, config, executionContext, "stream-json").run.mapError(startError)
            // Drain stderr: benign chatter at debug, anything else at WARN (so a stderr-only failure isn't lost).
            // Killing the child first on teardown (finalizer below, registered last ⇒ runs first) closes this stream
            // so the drain fiber can never wedge scope shutdown.
            _       <- process.stderr.linesStream
                         .foreach(line =>
                           val shown = line.take(500) + (if line.length > 500 then "..." else "")
                           if GeminiCliProvider.isKnownStderrNoise(line) then ZIO.logDebug(s"Gemini stderr: $shown")
                           else ZIO.logWarning(s"Gemini stderr: $shown")
                         )
                         .ignore
                         .forkScoped
            _       <- ZIO.addFinalizer(process.killForcibly.ignore)
          yield process.stdout.linesStream
            .mapError(e => LlmError.ProviderError(s"Failed to read gemini stream output: ${e.getMessage}", Some(e)))
            .tap(line =>
              ZIO.logDebug(s"Gemini stream output: ${line.take(500)}${if line.length > 500 then "..." else ""}")
            )
            .map(GeminiCliProvider.parseStreamEvent) ++
            ZStream.fromZIO(
              process.exitCode
                .mapError(e => LlmError.ProviderError(s"Process wait failed: ${e.getMessage}", Some(e)))
                .flatMap(exit =>
                  GeminiCliExecutor.validateExitCode(
                    exit.code,
                    s"Gemini stream process exited with code ${exit.code}",
                    executionContext.turnLimit,
                  )
                )
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
        val turn = ctx.turnLimit.map(l => List("--turn-limit", l.toString)).getOrElse(Nil)
        base ++ dirs ++ turn ++ List("-p", prompt)

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
                  ZIO.logDebug(
                    s"Gemini stream initialized${model.fold("")(m =>
                        s" with model=$m"
                      )}${sessionId.fold("")(id => s", session=$id")}"
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

                case GeminiCliStreamEvent.Error(message, _, _) =>
                  ZStream.fail(
                    LlmError.ProviderError(
                      message.map(m => s"Gemini CLI stream error: $m").getOrElse("Gemini CLI stream error"),
                      None,
                    )
                  )

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
        for
          resp   <- Streaming.collect(executeStream(StructuredOutputs.withSchemaHint(prompt, schema)))
          parsed <- StructuredOutputs.parseFromText[A](resp.content, schema)
        yield (parsed, resp.usage, resp.metadata.get("model"))

      override def isAvailable: UIO[Boolean] =
        executor.checkGeminiInstalled.fold(_ => false, _ => true)

  val layer: ZLayer[LlmConfig & GeminiCliExecutor, Nothing, CliConnector] =
    ZLayer.fromFunction { (config: LlmConfig, executor: GeminiCliExecutor) =>
      make(config, executor): CliConnector
    }
