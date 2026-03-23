package llm4zio.providers

import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
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

  /** On Windows, `cmd.exe` may interpret literal newline characters inside a quoted command-line argument as command
    * separators. When the gemini process is launched via `cmd /c gemini -p <prompt> …`, a multi-line prompt causes
    * `cmd.exe` to truncate the argument list at the first newline, dropping everything that follows — including
    * `--output-format stream-json`. The result is that gemini outputs plain text instead of JSON stream events, so
    * every output line is parsed as a `LogLine` and no content is accumulated.
    *
    * Replace every newline sequence with a single space so the full command reaches gemini unchanged. The LLM still
    * understands the compacted prompt.
    */
  private[providers] def normalizePromptForWindowsCmd(prompt: String): String =
    prompt.replaceAll("""\r\n|\n|\r""", " ")

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

  private[providers] def buildGeminiArgs(
    prompt: String,
    config: LlmConfig,
    ctx: GeminiCliExecutionContext,
    outputFormat: String,
    isWindows: Boolean,
  ): List[String] =
    val effectivePrompt = if isWindows then normalizePromptForWindowsCmd(prompt) else prompt
    val baseArgs        = List("-p", effectivePrompt, "-m", config.model, "-y", "--output_format", outputFormat)
    val includeDirArgs  = ctx.includeDirectories.distinct.flatMap(p => List("-d", p))
    // The -s flag enables sandbox mode. The backend is controlled separately via
    // GEMINI_SANDBOX env var injected in startProcess (see GeminiSandbox.envValue).
    // Default sandbox: -s is emitted but no env var is set, letting gemini pick the backend.
    val sandboxArgs     = ctx.sandbox.map(_ => "-s").toList
    val turnLimitArgs   = ctx.turnLimit.toList.flatMap(n => List("--turn-limit", n.toString))
    baseArgs ++ includeDirArgs ++ sandboxArgs ++ turnLimitArgs

  val default: GeminiCliExecutor =
    new GeminiCliExecutor {
      private def isWindows: Boolean =
        Option(java.lang.System.getProperty("os.name")).getOrElse("").toLowerCase.contains("win")

      private def findCommand: List[String] =
        if isWindows then List("where", "gemini") else List("which", "gemini")

      private def geminiCommand: List[String] =
        if isWindows then List("cmd", "/c", "gemini") else List("gemini")

      override def checkGeminiInstalled: IO[LlmError, Unit] =
        ZIO
          .attemptBlocking {
            val process  = new ProcessBuilder(findCommand.asJava)
              .redirectErrorStream(true)
              .start()
            val exitCode = process.waitFor()
            exitCode == 0
          }
          .mapError(e => LlmError.ProviderError(s"Failed to check gemini installation: ${e.getMessage}", Some(e)))
          .flatMap { installed =>
            if !installed then ZIO.fail(LlmError.ConfigError("gemini-cli not installed"))
            else ZIO.unit
          }

      override def runGeminiProcess(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): IO[LlmError, String] =
        for
          process  <- startProcess(prompt, config, executionContext, outputFormat = "json")
          output   <- readOutput(process, config)
          exitCode <- waitForCompletion(process)
          _        <- GeminiCliExecutor.validateExitCode(
                        exitCode,
                        s"Gemini process exited with code $exitCode",
                        executionContext.turnLimit,
                      )
          finalOut <- extractFinalResponse(output)
        yield finalOut

      override def runGeminiProcessStream(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): ZStream[Any, LlmError, GeminiCliStreamEvent] =
        ZStream.unwrapScoped {
          for
            process <- ZIO.acquireRelease(startProcess(
                         prompt,
                         config,
                         executionContext,
                         outputFormat = "stream-json",
                         mergeErrorStream = false,
                       ))(terminateProcess)
            _       <- streamStderrOutput(process)
                         .mapZIO(line =>
                           if line.trim.nonEmpty then
                             ZIO.logDebug(
                               s"Gemini stream stderr: ${line.take(500)}${if line.length > 500 then "..." else ""}"
                             )
                           else ZIO.unit
                         )
                         .runDrain
                         .catchAll(err => ZIO.logDebug(s"Gemini stderr stream closed: ${err.toString}"))
                         .forkScoped
          yield streamOutput(process) ++ ZStream.fromZIO(
            waitForCompletion(process).flatMap(exitCode =>
              GeminiCliExecutor.validateExitCode(
                exitCode,
                s"Gemini stream process exited with code $exitCode",
                executionContext.turnLimit,
              )
            )
          ).drain
        }

      private def startProcess(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
        outputFormat: String,
        mergeErrorStream: Boolean = true,
      ): IO[LlmError, Process] =
        val geminiArgs = GeminiCliExecutor.buildGeminiArgs(prompt, config, executionContext, outputFormat, isWindows)
        val commands   = geminiCommand ++ geminiArgs
        val promptIdx  = geminiArgs.indexOf("-p")
        val argsForLog =
          if promptIdx >= 0 && promptIdx + 1 < geminiArgs.length
          then geminiArgs.patch(promptIdx + 1, List("<prompt>"), 1)
          else geminiArgs
        ZIO.logDebug(
          s"Starting Gemini process: gemini ${argsForLog.mkString(" ")}"
        ) *>
          ZIO
            .attemptBlocking {
              val builder = new ProcessBuilder(commands.asJava)
              executionContext.cwd.foreach(path => builder.directory(java.nio.file.Paths.get(path).toFile))
              executionContext.sandbox.foreach { s =>
                GeminiSandbox.envValue(s).foreach { v =>
                  builder.environment().put("GEMINI_SANDBOX", v)
                }
              }
              builder
                .redirectErrorStream(mergeErrorStream)
                .start()
            }
            .mapError(e => LlmError.ProviderError(s"Failed to start gemini process: ${e.getMessage}", Some(e)))
            .tapError(err => ZIO.logError(s"Failed to start Gemini process: $err"))

      private def streamOutput(process: Process): ZStream[Any, LlmError, GeminiCliStreamEvent] =
        ZStream.unwrapScoped {
          ZIO.acquireRelease(
            ZIO
              .attemptBlocking(new BufferedReader(new InputStreamReader(
                process.getInputStream,
                StandardCharsets.UTF_8,
              )))
              .mapError(e => LlmError.ProviderError(s"Failed to open gemini output stream: ${e.getMessage}", Some(e)))
          )((reader: BufferedReader) => ZIO.attemptBlocking(reader.close()).ignoreLogged)
            .map { reader =>
              ZStream
                .repeatZIOOption {
                  ZIO
                    .attemptBlocking(Option(reader.readLine()))
                    .mapError(e =>
                      Some(LlmError.ProviderError(s"Failed to read gemini stream output: ${e.getMessage}", Some(e)))
                    )
                    .someOrFail(None)
                }
                .tap(line =>
                  ZIO.logDebug(s"Gemini stream output: ${line.take(500)}${if line.length > 500 then "..." else ""}")
                )
                .map(GeminiCliProvider.parseStreamEvent)
            }
        }

      private def readOutput(process: Process, config: LlmConfig): IO[LlmError, String] =
        ZIO
          .attemptBlocking {
            val inputStream = process.getInputStream
            val bytes       = inputStream.readAllBytes()
            new String(bytes, StandardCharsets.UTF_8)
          }
          .tap(output =>
            ZIO.logDebug(s"Gemini output received: ${output.take(500)}${if output.length > 500 then "..." else ""}")
          )
          .mapError(e => LlmError.ProviderError(s"Failed to read gemini output: ${e.getMessage}", Some(e)))
          .timeoutFail(LlmError.TimeoutError(config.timeout))(config.timeout)
          .tapError {
            case LlmError.TimeoutError(d) => ZIO.logError(s"Gemini process timed out after ${d.toSeconds}s")
            case other                    => ZIO.logError(s"Gemini output read error: $other")
          }

      private def streamStderrOutput(process: Process): ZStream[Any, LlmError, String] =
        ZStream.unwrapScoped {
          ZIO.acquireRelease(
            ZIO
              .attemptBlocking(new BufferedReader(new InputStreamReader(
                process.getErrorStream,
                StandardCharsets.UTF_8,
              )))
              .mapError(e =>
                LlmError.ProviderError(s"Failed to open gemini stderr stream: ${e.getMessage}", Some(e))
              )
          )((reader: BufferedReader) => ZIO.attemptBlocking(reader.close()).ignoreLogged)
            .map { reader =>
              ZStream.repeatZIOOption {
                ZIO
                  .attemptBlocking(Option(reader.readLine()))
                  .mapError(e =>
                    Some(LlmError.ProviderError(s"Failed to read gemini stderr: ${e.getMessage}", Some(e)))
                  )
                  .someOrFail(None)
              }
            }
        }

      private def waitForCompletion(process: Process): IO[LlmError, Int] =
        ZIO
          .attemptBlocking(process.waitFor())
          .mapError(e => LlmError.ProviderError(s"Process wait failed: ${e.getMessage}", Some(e)))

      private def extractFinalResponse(output: String): IO[LlmError, String] =
        ZIO
          .fromEither(GeminiCliProvider.extractResponse(output))
          .mapError(err => LlmError.ParseError(err, output))

      private def terminateProcess(process: Process): UIO[Unit] =
        ZIO
          .attemptBlocking {
            process.destroy()
            if process.isAlive then
              val _ = process.destroyForcibly()
            ()
          }
          .ignoreLogged
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
  ) derives JsonDecoder

  final private case class GeminiStreamJsonEvent(
    `type`: String,
    role: Option[String] = None,
    content: Option[String] = None,
    delta: Option[Boolean] = None,
    tool_name: Option[String] = None,
    tool_id: Option[String] = None,
    tool_input: Option[String] = None,
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
      val jsonDecoded = jsonCandidates(normalized)
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
            case "tool_use"    => GeminiCliStreamEvent.ToolUse(event.tool_name, event.tool_id, event.tool_input)
            case "tool_result" => GeminiCliStreamEvent.ToolResult(event.tool_id, event.status, event.content)
            case "error"       =>
              GeminiCliStreamEvent.Error(
                message = event.error.flatMap(_.message),
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

  private def extractAfterPreamble(text: String): Option[String] =
    val content = text.linesIterator
      .dropWhile(line => line.trim.isEmpty || isPreambleLine(line.trim))
      .mkString("\n")
      .trim
    Option(content).filter(_.nonEmpty)

  private def jsonCandidates(raw: String): List[String] =
    val fencedSlices = extractJsonFromMarkdownFences(raw)
    val balancedJson = extractBalancedJsonObjects(raw) ++ fencedSlices.flatMap(extractBalancedJsonObjects)
    (raw :: fencedSlices ::: balancedJson).map(_.trim).filter(_.nonEmpty).distinct

  private def extractJsonFromMarkdownFences(raw: String): List[String] =
    val fencePattern = "(?is)```(?:[\\w.+-]+)?\\s*(.*?)\\s*```".r
    fencePattern
      .findAllMatchIn(raw)
      .flatMap { matched =>
        val content = matched.group(1).trim
        Option.when(content.nonEmpty)(content)
      }
      .toList

  private def extractBalancedJsonObjects(raw: String): List[String] =
    final case class ScanState(
      startIdx: Option[Int] = None,
      depth: Int = 0,
      inString: Boolean = false,
      escaping: Boolean = false,
      results: List[String] = Nil,
    )

    raw.zipWithIndex.foldLeft(ScanState()) {
      case (state, (ch, idx)) =>
        if state.inString then
          if state.escaping then state.copy(escaping = false)
          else if ch == '\\' then state.copy(escaping = true)
          else if ch == '"' then state.copy(inString = false)
          else state
        else
          ch match
            case '"'                    =>
              state.copy(inString = true)
            case '{'                    =>
              state.copy(
                startIdx = state.startIdx.orElse(Some(idx)),
                depth = state.depth + 1,
              )
            case '}' if state.depth > 0 =>
              val nextDepth = state.depth - 1
              if nextDepth == 0 then
                state.startIdx match
                  case Some(start) =>
                    state.copy(
                      startIdx = None,
                      depth = 0,
                      results = raw.substring(start, idx + 1) :: state.results,
                    )
                  case None        =>
                    state.copy(depth = 0)
              else state.copy(depth = nextDepth)
            case _                      =>
              state
    }.results.reverse

  private def streamStatsToUsage(stats: Option[GeminiStreamStats]): Option[TokenUsage] =
    stats.flatMap(s =>
      for
        inputCount  <- s.input_tokens
        outputCount <- s.output_tokens
        totalCount  <- s.total_tokens
      yield TokenUsage(prompt = inputCount, completion = outputCount, total = totalCount)
    )

  def make(
    config: LlmConfig,
    executor: GeminiCliExecutor,
    executionContext: GeminiCliExecutionContext = GeminiCliExecutionContext.default,
  ): LlmService =
    new LlmService:
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

                case GeminiCliStreamEvent.Result(status, errorMessage, stats) if status.contains("error") =>
                  ZStream.fail(
                    LlmError.ProviderError(
                      errorMessage.map(msg => s"Gemini CLI returned an error: $msg").getOrElse(
                        "Gemini CLI returned an error"
                      ),
                      None,
                    )
                  )

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

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        for
          streamed  <- executeStream(prompt)
                         .runFold(new StringBuilder()) { (acc, chunk) =>
                           if chunk.delta.nonEmpty then acc.append(chunk.delta) else acc
                         }
                         .map(_.result())
          candidates = jsonCandidates(streamed)
          parsed     = candidates.iterator
                         .map(candidate => candidate -> candidate.fromJson[A])
                         .collectFirst { case (_, Right(value)) => value }
          parseError = candidates.iterator
                         .map(_.fromJson[A])
                         .collectFirst { case Left(error) => error }
                         .orElse(streamed.fromJson[A].left.toOption)
                         .getOrElse("no JSON candidate found")
          parsed    <-
            ZIO.fromOption(parsed).orElseFail(
              LlmError.ParseError(
                s"Failed to parse response as structured output: $parseError",
                streamed,
              )
            )
        yield parsed

      override def isAvailable: UIO[Boolean] =
        executor.checkGeminiInstalled.fold(_ => false, _ => true)

  val layer: ZLayer[LlmConfig & GeminiCliExecutor, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, executor: GeminiCliExecutor) =>
      make(config, executor)
    }
