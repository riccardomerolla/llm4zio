package llm4zio.runner

import java.nio.file.Files

import zio.*
import zio.stream.ZStream
import zio.test.*

import llm4zio.core.LlmError

object LiveCliProcessExecutorSpec extends ZIOSpecDefault:

  /** Retry `cond` every 100ms until true, up to `tries` times. */
  private def eventually(cond: UIO[Boolean], tries: Int = 50): UIO[Boolean] =
    cond.flatMap {
      case true  => ZIO.succeed(true)
      case false => if tries <= 1 then ZIO.succeed(false) else ZIO.sleep(100.millis) *> eventually(cond, tries - 1)
    }

  private def alive(pid: String): UIO[Boolean] =
    LiveCliProcessExecutor.instance
      .run(List("kill", "-0", pid), ".", Map.empty)
      .map(_.exitCode == 0)
      .orElseSucceed(false)

  /** Start a child that records its PID and then sleeps, interrupt the consuming fiber mid-stream, and assert the
    * child is killed. `exec` makes the sleep replace the shell, so the recorded PID is the process to watch.
    */
  private def interruptKillsChild(stream: List[String] => ZStream[Any, LlmError, String]) =
    for
      dir     <- ZIO.attemptBlocking(Files.createTempDirectory("llm4zio-it")).orDie
      pidFile  = dir.resolve("pid")
      argv     = List("sh", "-c", s"echo $$$$ > $pidFile; echo started; exec sleep 30")
      fiber   <- stream(argv).runDrain.fork
      started <- eventually(ZIO.attemptBlocking(Files.exists(pidFile)).orElseSucceed(false))
      pid     <- ZIO.attemptBlocking(new String(Files.readAllBytes(pidFile)).trim).orDie
      _       <- fiber.interrupt
      dead    <- eventually(alive(pid).map(!_))
      // Safety net: never leave a 30s sleeper behind when the assertion below is about to fail.
      _       <- LiveCliProcessExecutor.instance.run(List("kill", "-9", pid), ".", Map.empty).ignore.unless(dead)
    yield assertTrue(started, dead)

  def spec = suite("LiveCliProcessExecutor")(
    test("runs a command and captures stdout + exit code") {
      for result <- LiveCliProcessExecutor.instance.run(List("echo", "hello-llm4zio"), ".", Map.empty)
      yield assertTrue(result.exitCode == 0, result.stdout.exists(_.contains("hello-llm4zio")))
    },
    test("non-existent program fails the effect") {
      for res <- LiveCliProcessExecutor.instance.run(List("definitely-not-a-real-binary-xyz"), ".", Map.empty).either
      yield assertTrue(res.isLeft)
    },
    test("runBidirectional feeds stdin lines and reads them back") {
      // A line-buffered echo loop: read a line on stdin, echo it immediately. Avoids cat's pipe block-buffering.
      val echoLoop = List("sh", "-c", "while IFS= read -r line; do printf '%s\\n' \"$line\"; done")
      ZIO.scoped {
        for
          bundle      <- LiveCliProcessExecutor.instance.runBidirectional(echoLoop, ".", Map.empty)
          (queue, out) = bundle
          fiber       <- out.take(2).runCollect.fork
          _           <- queue.offer("alpha")
          _           <- queue.offer("beta")
          lines       <- fiber.join
        yield assertTrue(lines.toList == List("alpha", "beta"))
      }
    },
    test("runStreamingWithStdin feeds the prompt on stdin (cat echoes it back)") {
      for lines <- LiveCliProcessExecutor.instance
                     .runStreamingWithStdin(List("cat"), ".", Map.empty, "alpha\nbeta")
                     .runCollect
      yield assertTrue(lines.toList == List("alpha", "beta"))
    },
    test("runWithStdin feeds the prompt on stdin and captures stdout") {
      for result <- LiveCliProcessExecutor.instance.runWithStdin(List("cat"), ".", Map.empty, "one\ntwo")
      yield assertTrue(result.exitCode == 0, result.stdout == List("one", "two"))
    },
    test("run captures stderr alongside stdout") {
      val argv = List("sh", "-c", "echo out-line; echo err-line 1>&2")
      for result <- LiveCliProcessExecutor.instance.run(argv, ".", Map.empty)
      yield assertTrue(result.stdout == List("out-line"), result.stderr == List("err-line"))
    },
    test("runStreaming fails with the captured stderr when the process exits non-zero") {
      val argv = List("sh", "-c", "echo out-line; echo boom 1>&2; exit 7")
      for res <- LiveCliProcessExecutor.instance.runStreaming(argv, ".", Map.empty).runCollect.either
      yield assertTrue(
        res.isLeft,
        res.left.toOption.exists(_.message.contains("boom")),
        res.left.toOption.exists(_.message.contains("7")),
      )
    },
    test("runStreaming succeeds and emits all lines when the process exits zero") {
      val argv = List("sh", "-c", "echo one; echo two")
      for lines <- LiveCliProcessExecutor.instance.runStreaming(argv, ".", Map.empty).runCollect
      yield assertTrue(lines.toList == List("one", "two"))
    },
    test("runStreamingWithStdin fails with the captured stderr when the process exits non-zero") {
      val argv = List("sh", "-c", "cat > /dev/null; echo out-line; echo boom 1>&2; exit 7")
      for res <- LiveCliProcessExecutor.instance
                   .runStreamingWithStdin(argv, ".", Map.empty, "ignored")
                   .runCollect
                   .either
      yield assertTrue(
        res.isLeft,
        res.left.toOption.exists(_.message.contains("boom")),
        res.left.toOption.exists(_.message.contains("7")),
      )
    },
    test("runStreaming kills the child process when the consuming fiber is interrupted") {
      interruptKillsChild(argv => LiveCliProcessExecutor.instance.runStreaming(argv, ".", Map.empty))
    },
    test("runStreamingWithStdin kills the child process when the consuming fiber is interrupted") {
      interruptKillsChild(argv => LiveCliProcessExecutor.instance.runStreamingWithStdin(argv, ".", Map.empty, "ignored"))
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
