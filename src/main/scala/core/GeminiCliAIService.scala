package core

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*

import zio.*

import models.{ AIError, AIProviderConfig, AIResponse, RateLimitError }

trait GeminiCliExecutor:
  def checkGeminiInstalled: ZIO[Any, AIError, Unit]
  def runGeminiProcess(prompt: String, providerConfig: AIProviderConfig): ZIO[Any, AIError, (String, Int)]

object GeminiCliExecutor:
  val live: ULayer[GeminiCliExecutor] =
    ZLayer.succeed(new GeminiCliExecutor {
      override def checkGeminiInstalled: ZIO[Any, AIError, Unit] =
        ZIO
          .attemptBlocking {
            val process  = new ProcessBuilder("which", "gemini")
              .redirectErrorStream(true)
              .start()
            val exitCode = process.waitFor()
            exitCode == 0
          }
          .mapError(e => AIError.ProcessStartFailed(e.getMessage))
          .flatMap { installed =>
            if !installed then ZIO.fail(AIError.NotAvailable("gemini-cli"))
            else ZIO.unit
          }

      override def runGeminiProcess(
        prompt: String,
        providerConfig: AIProviderConfig,
      ): ZIO[Any, AIError, (String, Int)] =
        for
          process  <- startProcess(prompt, providerConfig)
          output   <- readOutput(process, providerConfig)
          exitCode <- waitForCompletion(process)
          _        <- validateExitCode(exitCode, output)
        yield (output, exitCode)

      private def startProcess(
        prompt: String,
        providerConfig: AIProviderConfig,
      ): ZIO[Any, AIError, Process] =
        val commands = List(
          "gemini",
          "-p",
          prompt,
          "-m",
          providerConfig.model,
          "-y",
          "-s",
        )

        ZIO.logDebug(s"Starting Gemini process: gemini -p <prompt> -m ${providerConfig.model} -y -s") *>
          ZIO
            .attemptBlocking {
              new ProcessBuilder(commands.asJava)
                .redirectErrorStream(true)
                .start()
            }
            .mapError(e => AIError.ProcessStartFailed(e.getMessage))
            .tapError(err => ZIO.logError(s"Failed to start Gemini process: ${err.message}"))

      private def readOutput(process: Process, providerConfig: AIProviderConfig): ZIO[Any, AIError, String] =
        ZIO
          .attemptBlocking {
            val inputStream = process.getInputStream
            val bytes       = inputStream.readAllBytes()
            new String(bytes, StandardCharsets.UTF_8)
          }
          .tap(output =>
            ZIO.logDebug(s"Gemini output received: ${output.take(500)}${if output.length > 500 then "..." else ""}")
          )
          .mapError(e => AIError.OutputReadFailed(e.getMessage))
          .tapError(err => ZIO.logError(s"Failed to read Gemini output: ${err.message}"))
          .timeoutFail(AIError.Timeout(providerConfig.timeout))(providerConfig.timeout)
          .tapError {
            case AIError.Timeout(d) => ZIO.logError(s"Gemini process timed out after ${d.toSeconds}s")
            case other              => ZIO.logError(s"Gemini output read error: ${other.message}")
          }

      private def waitForCompletion(process: Process): ZIO[Any, AIError, Int] =
        ZIO
          .attemptBlocking(process.waitFor())
          .mapError(e => AIError.ProcessFailed(e.getMessage))

      private def validateExitCode(exitCode: Int, output: String): ZIO[Any, AIError, Unit] =
        if exitCode != 0 then
          ZIO.logError(s"Gemini process exited with code $exitCode. Output: ${output.take(500)}${
              if output.length > 500 then "..." else ""
            }") *>
            ZIO.fail(AIError.NonZeroExit(exitCode, output))
        else ZIO.unit
    })

object GeminiCliAIService:
  val layer: ZLayer[models.AIProviderConfig & RateLimiter, Nothing, AIService] =
    layerWithExecutor(GeminiCliExecutor.live)

  private[core] def layerWithExecutor(
    executorLayer: ULayer[GeminiCliExecutor]
  ): ZLayer[AIProviderConfig & RateLimiter, Nothing, AIService] =
    (ZLayer.service[AIProviderConfig] ++ ZLayer.service[RateLimiter] ++ executorLayer) >>> ZLayer.fromFunction {
      (providerConfig: AIProviderConfig, rateLimiter: RateLimiter, executor: GeminiCliExecutor) =>
        new AIService {
          override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
            rateLimiter.acquire.mapError(mapRateLimitError) *> executeGeminiCLI(prompt, providerConfig, executor)

          override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
            val combinedPrompt = s"$prompt\n\nContext:\n$context"
            rateLimiter.acquire.mapError(mapRateLimitError) *> executeGeminiCLI(
              combinedPrompt,
              providerConfig,
              executor,
            )

          override def isAvailable: ZIO[Any, Nothing, Boolean] =
            executor.checkGeminiInstalled.fold(_ => false, _ => true)

          private def executeGeminiCLI(
            prompt: String,
            config: AIProviderConfig,
            cliExecutor: GeminiCliExecutor,
          ): ZIO[Any, AIError, AIResponse] =
            val operation =
              for
                _                  <- ZIO.logInfo(s"Executing Gemini CLI with model: ${config.model}")
                _                  <- cliExecutor.checkGeminiInstalled
                (output, exitCode) <- cliExecutor.runGeminiProcess(prompt, config)
                _                  <- ZIO.logDebug(s"Gemini execution completed with exit code: $exitCode")
              yield AIResponse(
                output = output,
                metadata = Map(
                  "exitCode" -> exitCode.toString,
                  "provider" -> "gemini-cli",
                  "model"    -> config.model,
                ),
              )

            val policy = RetryPolicy(
              maxRetries = config.maxRetries,
              baseDelay = Duration.fromSeconds(1),
              maxDelay = Duration.fromSeconds(30),
            )

            RetryPolicy.withRetry(operation, policy, RetryPolicy.isRetryableAI)

          private def mapRateLimitError(error: RateLimitError): AIError = error match
            case RateLimitError.AcquireTimeout(timeout) =>
              AIError.RateLimitExceeded(timeout)
            case RateLimitError.InvalidConfig(details)  =>
              AIError.RateLimitMisconfigured(details)
        }
    }
