package llm4zio.providers

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import zio.*
import zio.json.*
import zio.stream.ZStream
import llm4zio.core.*
import llm4zio.tools.{AnyTool, JsonSchema}

trait GeminiCliExecutor:
  def checkGeminiInstalled: IO[LlmError, Unit]
  def runGeminiProcess(prompt: String, config: LlmConfig): IO[LlmError, String]

object GeminiCliExecutor:
  val default: GeminiCliExecutor =
    new GeminiCliExecutor {
      override def checkGeminiInstalled: IO[LlmError, Unit] =
        ZIO
          .attemptBlocking {
            val process  = new ProcessBuilder("which", "gemini")
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

      override def runGeminiProcess(prompt: String, config: LlmConfig): IO[LlmError, String] =
        for
          process  <- startProcess(prompt, config)
          output   <- readOutput(process, config)
          exitCode <- waitForCompletion(process)
          _        <- validateExitCode(exitCode, output)
        yield output

      private def startProcess(prompt: String, config: LlmConfig): IO[LlmError, Process] =
        val commands = List(
          "gemini",
          "-p",
          prompt,
          "-m",
          config.model,
          "-y",
        )

        ZIO.logDebug(s"Starting Gemini process: gemini -p <prompt> -m ${config.model} -y") *>
          ZIO
            .attemptBlocking {
              new ProcessBuilder(commands.asJava)
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
    }

  val live: ULayer[GeminiCliExecutor] =
    ZLayer.succeed(default)

object GeminiCliProvider:
  def make(config: LlmConfig, executor: GeminiCliExecutor): LlmService =
    new LlmService:
      override def execute(prompt: String): IO[LlmError, LlmResponse] =
        for
          _      <- ZIO.logInfo(s"Executing Gemini CLI with model: ${config.model}")
          _      <- executor.checkGeminiInstalled
          output <- executor.runGeminiProcess(prompt, config)
          _      <- ZIO.logDebug(s"Gemini execution completed")
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
            metadata = response.metadata
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
            metadata = response.metadata
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
                        .mapError(err => LlmError.ParseError(s"Failed to parse response as structured output: $err", response.content))
        yield parsed

      override def isAvailable: UIO[Boolean] =
        executor.checkGeminiInstalled.fold(_ => false, _ => true)

  val layer: ZLayer[LlmConfig & GeminiCliExecutor, Nothing, LlmService] =
    ZLayer.fromFunction { (config: LlmConfig, executor: GeminiCliExecutor) =>
      make(config, executor)
    }
