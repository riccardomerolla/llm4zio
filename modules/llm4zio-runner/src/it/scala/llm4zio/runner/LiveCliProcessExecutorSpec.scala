package llm4zio.runner

import zio.test.*

object LiveCliProcessExecutorSpec extends ZIOSpecDefault:
  def spec = suite("LiveCliProcessExecutor")(
    test("runs a command and captures stdout + exit code") {
      for result <- LiveCliProcessExecutor.instance.run(List("echo", "hello-llm4zio"), ".", Map.empty)
      yield assertTrue(result.exitCode == 0, result.stdout.exists(_.contains("hello-llm4zio")))
    },
    test("non-existent program fails the effect") {
      for res <- LiveCliProcessExecutor.instance.run(List("definitely-not-a-real-binary-xyz"), ".", Map.empty).either
      yield assertTrue(res.isLeft)
    },
  ) @@ TestAspect.sequential
