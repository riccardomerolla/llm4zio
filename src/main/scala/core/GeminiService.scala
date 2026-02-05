package core

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*

import zio.*

import models.{ GeminiError, GeminiResponse, MigrationConfig }

/** GeminiService - Wrapper for Google Gemini CLI non-interactive invocation
  *
  * Features:
  *   - Non-interactive mode execution (`gemini -p "prompt"`)
  *   - Configurable model selection
  *   - Process execution with timeout handling
  *   - Retry logic with exponential backoff
  *   - Exit code and error handling
  *   - Environment variable support for API keys
  *
  * CLI Arguments:
  *   - `-p, --prompt`: Non-interactive prompt
  *   - `-m, --model`: Gemini model to use (e.g., gemini-2.0-flash)
  *   - `-y, --yolo`: YOLO mode - auto-approve all actions
  *   - `-s, --sandbox`: Run in sandbox mode for safety
  */
trait GeminiService:
  /** Execute a prompt using the Gemini CLI
    *
    * @param prompt
    *   The prompt to send to Gemini
    * @return
    *   ZIO effect containing the Gemini response or an error
    */
  def execute(prompt: String): ZIO[Any, GeminiError, GeminiResponse]

  /** Execute a prompt with additional context
    *
    * @param prompt
    *   The prompt to send to Gemini
    * @param context
    *   Additional context to include (e.g., code, documentation)
    * @return
    *   ZIO effect containing the Gemini response or an error
    */
  def executeWithContext(prompt: String, context: String): ZIO[Any, GeminiError, GeminiResponse]

  /** Check if Gemini CLI is available in the system PATH
    *
    * @return
    *   ZIO effect that returns true if Gemini is available, false otherwise
    */
  def isAvailable: ZIO[Any, Nothing, Boolean]

object GeminiService:
  /** Access the execute method through ZIO service */
  def execute(prompt: String): ZIO[GeminiService, GeminiError, GeminiResponse] =
    ZIO.serviceWithZIO[GeminiService](_.execute(prompt))

  /** Access the executeWithContext method through ZIO service */
  def executeWithContext(prompt: String, context: String): ZIO[GeminiService, GeminiError, GeminiResponse] =
    ZIO.serviceWithZIO[GeminiService](_.executeWithContext(prompt, context))

  /** Access the isAvailable method through ZIO service */
  def isAvailable: ZIO[GeminiService, Nothing, Boolean] =
    ZIO.serviceWithZIO[GeminiService](_.isAvailable)

  /** Live implementation of GeminiService using MigrationConfig */
  val live: ZLayer[MigrationConfig, Nothing, GeminiService] = ZLayer.fromFunction { (config: MigrationConfig) =>
    new GeminiService {
      override def execute(prompt: String): ZIO[Any, GeminiError, GeminiResponse] =
        executeGeminiCLI(prompt, config)

      override def executeWithContext(prompt: String, context: String): ZIO[Any, GeminiError, GeminiResponse] =
        val combinedPrompt = s"$prompt\n\nContext:\n$context"
        executeGeminiCLI(combinedPrompt, config)

      override def isAvailable: ZIO[Any, Nothing, Boolean] =
        checkGeminiInstalled.fold(_ => false, _ => true)

      /** Execute the Gemini CLI with retry logic
        *
        * @param prompt
        *   The prompt to execute
        * @param config
        *   Migration configuration containing Gemini settings
        * @return
        *   ZIO effect with GeminiResponse or GeminiError
        */
      private def executeGeminiCLI(
        prompt: String,
        config: MigrationConfig,
      ): ZIO[Any, GeminiError, GeminiResponse] =
        val operation =
          for
            _      <- ZIO.logInfo(s"Executing Gemini CLI with model: ${config.geminiModel}")
            _      <- checkGeminiInstalled
            result <- runGeminiProcess(prompt, config)
            _      <- ZIO.logDebug(s"Gemini execution completed with exit code: ${result.exitCode}")
          yield result

        // Build retry policy from config
        val policy = RetryPolicy(
          maxRetries = config.geminiMaxRetries,
          baseDelay = Duration.fromSeconds(1),
          maxDelay = Duration.fromSeconds(30),
        )

        // Apply retry with exponential backoff on retryable errors
        RetryPolicy.withRetry(operation, policy, RetryPolicy.isRetryable)

      /** Check if Gemini CLI is installed */
      private def checkGeminiInstalled: ZIO[Any, GeminiError, Unit] =
        ZIO
          .attemptBlocking {
            val process  = new ProcessBuilder("which", "gemini")
              .redirectErrorStream(true)
              .start()
            val exitCode = process.waitFor()
            exitCode == 0
          }
          .mapError(e => GeminiError.ProcessStartFailed(e.getMessage))
          .flatMap { installed =>
            if !installed then ZIO.fail(GeminiError.NotInstalled)
            else ZIO.unit
          }

      /** Run the Gemini CLI process
        *
        * @param prompt
        *   The prompt to execute
        * @param config
        *   Migration configuration
        * @return
        *   GeminiResponse with output and exit code
        */
      private def runGeminiProcess(
        prompt: String,
        config: MigrationConfig,
      ): ZIO[Any, GeminiError, GeminiResponse] =
        for
          process  <- startProcess(prompt, config)
          output   <- readOutput(process, config)
          exitCode <- waitForCompletion(process)
          _        <- validateExitCode(exitCode, output)
        yield GeminiResponse(output, exitCode)

      /** Start the Gemini CLI process */
      private def startProcess(
        prompt: String,
        config: MigrationConfig,
      ): ZIO[Any, GeminiError, Process] =
        ZIO
          .attemptBlocking {
            val commands = List(
              "gemini",
              "-p",
              prompt,
              "-m",
              config.geminiModel,
              "-y", // YOLO mode: auto-approve all actions
              "-s", // Run in sandbox mode for safety
            )

            new ProcessBuilder(commands.asJava)
              .redirectErrorStream(true) // Merge stderr into stdout
              .start()
          }
          .mapError(e => GeminiError.ProcessStartFailed(e.getMessage))

      /** Read process output with timeout */
      private def readOutput(
        process: Process,
        config: MigrationConfig,
      ): ZIO[Any, GeminiError, String] =
        ZIO
          .attemptBlocking {
            val inputStream = process.getInputStream
            val bytes       = inputStream.readAllBytes()
            new String(bytes, StandardCharsets.UTF_8)
          }
          .mapError(e => GeminiError.OutputReadFailed(e.getMessage))
          .timeoutFail(GeminiError.Timeout(config.geminiTimeout))(config.geminiTimeout)

      /** Wait for process completion */
      private def waitForCompletion(process: Process): ZIO[Any, GeminiError, Int] =
        ZIO
          .attemptBlocking(process.waitFor())
          .mapError(e => GeminiError.ProcessFailed(e.getMessage))

      /** Validate the exit code and fail if non-zero */
      private def validateExitCode(exitCode: Int, output: String): ZIO[Any, GeminiError, Unit] =
        if exitCode != 0 then ZIO.fail(GeminiError.NonZeroExit(exitCode, output))
        else ZIO.unit
    }
  }
