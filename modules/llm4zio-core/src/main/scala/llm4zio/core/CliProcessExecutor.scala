package llm4zio.core

import zio.*
import zio.stream.ZStream

trait CliProcessExecutor:
  def run(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty): IO[LlmError, ProcessResult]
  def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty)
    : ZStream[Any, LlmError, String]

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
