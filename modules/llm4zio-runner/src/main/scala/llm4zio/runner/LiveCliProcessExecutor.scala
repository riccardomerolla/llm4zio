package llm4zio.runner

import java.io.File

import zio.*
import zio.process.Command
import zio.stream.ZStream

import llm4zio.core.{ CliProcessExecutor, LlmError, ProcessResult }

/** A real [[CliProcessExecutor]] over zio-process, for driving CLI coding agents (claude/codex/gemini) from a flow.
  */
object LiveCliProcessExecutor:

  val instance: CliProcessExecutor = new CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      argv match
        case Nil         => ZIO.fail(LlmError.InvalidRequestError("empty argv"))
        case cmd :: args =>
          (for
            process <- command(cmd, args, cwd, envVars).run
            linesF  <- process.stdout.lines.fork
            exit    <- process.exitCode
            lines   <- linesF.join
          yield ProcessResult(lines.toList, exit.code))
            .mapError(t => LlmError.ProviderError(s"$cmd failed: ${t.getMessage}", None))

    override def runStreaming(
      argv: List[String],
      cwd: String,
      envVars: Map[String, String],
    ): ZStream[Any, LlmError, String] =
      argv match
        case Nil         => ZStream.fail(LlmError.InvalidRequestError("empty argv"))
        case cmd :: args =>
          ZStream
            .unwrap(command(cmd, args, cwd, envVars).run.map(_.stdout.linesStream))
            .mapError(t => LlmError.ProviderError(s"$cmd failed: ${t.getMessage}", None))

  private def command(cmd: String, args: List[String], cwd: String, envVars: Map[String, String]): Command =
    val base = Command(cmd, args*).workingDirectory(new File(cwd))
    if envVars.isEmpty then base else base.env(envVars)
