package llm4zio.core

import zio.*
import zio.stream.ZStream

trait CliProcessExecutor:
  def run(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty): IO[LlmError, ProcessResult]
  def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty)
    : ZStream[Any, LlmError, String]

  /** Like [[run]], but feeds `stdin` to the process — used to pass a large prompt without hitting argv length
    * limits (`ARG_MAX`). Default: ignore `stdin` and delegate to [[run]]; executors that support stdin override. */
  def runWithStdin(
    argv: List[String],
    cwd: String,
    envVars: Map[String, String],
    @scala.annotation.unused stdin: String,
  ): IO[LlmError, ProcessResult] =
    run(argv, cwd, envVars)

  /** Like [[runStreaming]], but feeds `stdin` to the process (large prompt, avoids `ARG_MAX`). Default: ignore
    * `stdin` and delegate to [[runStreaming]]; executors that support stdin override. */
  def runStreamingWithStdin(
    argv: List[String],
    cwd: String,
    envVars: Map[String, String],
    @scala.annotation.unused stdin: String,
  ): ZStream[Any, LlmError, String] =
    runStreaming(argv, cwd, envVars)

  /** A held bidirectional process: a stdin sink (offer one line per user turn; shut the queue down to send EOF) and a
    * stdout line stream. Scoped — the process is killed when the scope closes. Used by interactive agent sessions.
    * Default: unsupported (most executors don't need it).
    */
  def runBidirectional(
    argv: List[String],
    @scala.annotation.unused cwd: String,
    @scala.annotation.unused envVars: Map[String, String] = Map.empty,
  ): ZIO[Scope, LlmError, (Queue[String], ZStream[Any, LlmError, String])] =
    ZIO.fail(
      LlmError.InvalidRequestError(s"${argv.headOption.getOrElse("process")} does not support bidirectional sessions")
    )

final case class ProcessResult(stdout: List[String], exitCode: Int)
