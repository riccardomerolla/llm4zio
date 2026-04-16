package llm4zio.core

import zio.*
import zio.stream.ZStream

trait CliProcessExecutor:
  def run(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty): IO[LlmError, ProcessResult]
  def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String] = Map.empty)
    : ZStream[Any, LlmError, String]

final case class ProcessResult(stdout: List[String], exitCode: Int)
