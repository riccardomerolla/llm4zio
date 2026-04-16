package llm4zio.core

import zio.*
import zio.stream.ZStream
import zio.test.*

object CliProcessExecutorSpec extends ZIOSpecDefault:

  class MockCliProcessExecutor(
    responses: Map[List[String], ProcessResult] = Map.empty,
    streamResponses: Map[List[String], List[String]] = Map.empty,
  ) extends CliProcessExecutor:
    override def run(argv: List[String], cwd: String, envVars: Map[String, String]): IO[LlmError, ProcessResult] =
      ZIO.fromOption(responses.get(argv))
        .orElseFail(LlmError.ProviderError(s"No mock response for argv: ${argv.mkString(" ")}"))
    override def runStreaming(argv: List[String], cwd: String, envVars: Map[String, String])
      : ZStream[Any, LlmError, String] =
      ZStream.fromIterable(streamResponses.getOrElse(argv, Nil))

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CliProcessExecutor")(
    test("MockCliProcessExecutor returns canned response") {
      val mock = MockCliProcessExecutor(
        responses = Map(List("echo", "hello") -> ProcessResult(List("hello"), 0))
      )
      for result <- mock.run(List("echo", "hello"), "/tmp", Map.empty)
      yield assertTrue(
        result.stdout == List("hello"),
        result.exitCode == 0,
      )
    },
    test("MockCliProcessExecutor fails for unknown argv") {
      val mock = MockCliProcessExecutor()
      for result <- mock.run(List("unknown"), "/tmp", Map.empty).exit
      yield assertTrue(result.isFailure)
    },
    test("MockCliProcessExecutor streams lines") {
      val mock = MockCliProcessExecutor(
        streamResponses = Map(List("tail", "-f") -> List("line1", "line2", "line3"))
      )
      for lines <- mock.runStreaming(List("tail", "-f"), "/tmp", Map.empty).runCollect
      yield assertTrue(lines.toList == List("line1", "line2", "line3"))
    },
  )
