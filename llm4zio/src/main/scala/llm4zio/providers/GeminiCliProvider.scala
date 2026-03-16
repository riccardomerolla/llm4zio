package llm4zio.providers

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.stream.ZStream

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

trait GeminiCliExecutor:
  def checkGeminiInstalled: IO[LlmError, Unit]
  def runGeminiProcess(
    prompt: String,
    config: LlmConfig,
    executionContext: GeminiCliExecutionContext = GeminiCliExecutionContext.default,
  ): IO[LlmError, String]

final case class GeminiCliExecutionContext(
  cwd: Option[String] = None,
  includeDirectories: List[String] = Nil,
)

object GeminiCliExecutionContext:
  val default: GeminiCliExecutionContext = GeminiCliExecutionContext()

object GeminiCliExecutor:
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
          process  <- startProcess(prompt, config, executionContext)
          output   <- readOutput(process, config)
          exitCode <- waitForCompletion(process)
          _        <- validateExitCode(exitCode, output)
          finalOut <- extractFinalResponse(output)
        yield finalOut

      private def startProcess(
        prompt: String,
        config: LlmConfig,
        executionContext: GeminiCliExecutionContext,
      ): IO[LlmError, Process] =
        val commands = geminiCommand ++ List(
          "-p",
          prompt,
          "-m",
          config.model,
          "-y",
          "--output-format",
          "json",
        ) ++ executionContext.includeDirectories.distinct.flatMap(path => List("--include-directories", path))

        ZIO.logDebug(
          s"Starting Gemini process: gemini -p <prompt> -m ${config.model} -y --output-format json"
        ) *>
          ZIO
            .attemptBlocking {
              val builder = new ProcessBuilder(commands.asJava)
              executionContext.cwd.foreach(path => builder.directory(java.nio.file.Paths.get(path).toFile))
              builder
                .redirectErrorStream(true)
                .start()
            }
            .mapError(e => LlmError.ProviderError(s"Failed to start gemini process: ${e.getMessage}", Some(e)))
            .tapError(err => ZIO.logError(s"Failed to start Gemini process: $err"))

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

      private def waitForCompletion(process: Process): IO[LlmError, Int] =
        ZIO
          .attemptBlocking(process.waitFor())
          .mapError(e => LlmError.ProviderError(s"Process wait failed: ${e.getMessage}", Some(e)))

      private def validateExitCode(exitCode: Int, output: String): IO[LlmError, Unit] =
        if exitCode != 0 then
          ZIO.logError(s"Gemini process exited with code $exitCode. Output: ${output.take(500)}${
              if output.length > 500 then "..." else ""
            }") *>
            ZIO.fail(LlmError.ProviderError(s"Gemini process exited with code $exitCode", None))
        else ZIO.unit

      private def extractFinalResponse(output: String): IO[LlmError, String] =
        ZIO
          .fromEither(GeminiCliProvider.extractResponse(output))
          .mapError(err => LlmError.ParseError(err, output))
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

  private[providers] def extractResponse(output: String): Either[String, String] =
    val normalized = output.replace("\r\n", "\n").trim
    if normalized.isEmpty then Left("Gemini CLI returned empty output")
    else
      // Try to decode any candidate as a GeminiHeadlessResponse.  A successful decode means we
      // found a proper JSON envelope (even if it contains a Gemini error payload) — use that
      // result directly.  Only fall back to plain-text extraction when NO candidate decoded as
      // JSON at all (e.g. the CLI emitted startup messages followed by plain prose or markdown).
      val jsonDecoded = jsonCandidates(normalized)
        .iterator
        .flatMap(tryDecodeHeadless)
        .nextOption()
        .orElse(tryDecodeHeadless(normalized))

      jsonDecoded match
        case Some(result) => result
        case None         =>
          // No JSON envelope found — some Gemini CLI versions (especially when Chrome DevTools
          // extensions are loaded) emit preamble lines followed by the response as plain text.
          // Strip all known preamble lines from the top and return whatever content remains.
          extractAfterPreamble(normalized)
            .toRight("Failed to decode Gemini CLI JSON output: output did not contain a JSON envelope")

  // Returns None if `raw` cannot be decoded as GeminiHeadlessResponse, otherwise returns the
  // interpreted result (Right = content, Left = Gemini API error message).
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

  // Patterns that identify known Gemini CLI startup/preamble lines.
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
    // Drop leading preamble lines and blank separator lines, then return the rest.
    val content = text.linesIterator
      .dropWhile(line => line.trim.isEmpty || isPreambleLine(line.trim))
      .mkString("\n")
      .trim
    Option(content).filter(_.nonEmpty)

  private def jsonCandidates(raw: String): List[String] =
    val firstBrace = raw.indexOf('{')
    val lastBrace  = raw.lastIndexOf('}')
    val braceSlice =
      if firstBrace >= 0 && lastBrace >= firstBrace then
        val suffix = raw.substring(0, lastBrace + 1)
        suffix.indices
          .filter(idx => suffix.charAt(idx) == '{')
          .map(idx => suffix.substring(idx))
          .toList
      else Nil
    (raw :: braceSlice).distinct

  def make(config: LlmConfig, executor: GeminiCliExecutor): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        for
          _      <- ZIO.logInfo(s"Executing Gemini CLI with model: ${config.model}")
          _      <- executor.checkGeminiInstalled
          output <- executor.runGeminiProcess(prompt, config)
          _      <- ZIO.logDebug("Gemini execution completed")
        yield LlmResponse(
          content = output,
          usage = None,
          metadata = Map(
            "provider" -> "gemini-cli",
            "model"    -> config.model,
          ),
        )

      override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
        // Gemini CLI doesn't support streaming, so we convert the full response to a single chunk
        ZStream.fromZIO(execute(prompt)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage,
            metadata = response.metadata,
          )
        }

      override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
        // Gemini CLI doesn't support history, so we concatenate messages into a single prompt
        val combinedPrompt = messages.map { msg =>
          s"${msg.role}: ${msg.content}"
        }.mkString("\n\n")
        execute(combinedPrompt)

      override def executeStreamWithHistory(messages: List[Message]): ZStream[Any, LlmError, LlmChunk] =
        ZStream.fromZIO(executeWithHistory(messages)).map { response =>
          LlmChunk(
            delta = response.content,
            finishReason = Some("stop"),
            usage = response.usage,
            metadata = response.metadata,
          )
        }

      override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
        // Gemini CLI doesn't support tool calling natively
        ZIO.fail(LlmError.InvalidRequestError("Gemini CLI does not support tool calling"))

      override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
        // Gemini CLI doesn't support structured output natively
        for
          response <- execute(prompt)
          parsed   <- ZIO.fromEither(response.content.fromJson[A])
                        .mapError(err =>
                          LlmError.ParseError(s"Failed to parse response as structured output: $err", response.content)
                        )
        yield parsed

      override def isAvailable: UIO[Boolean] =
        executor.checkGeminiInstalled.fold(_ => false, _ => true)

  val layer: ZLayer[LlmConfig & GeminiCliExecutor, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, executor: GeminiCliExecutor) =>
      make(config, executor)
    }
