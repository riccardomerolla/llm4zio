package workspace.control

import zio.*
import zio.test.*

import workspace.entity.AgentProcessRef

object InteractiveAgentRunnerSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("InteractiveAgentRunnerSpec")(
    test("start + sendInput streams stdout and stderr independently") {
      val argv = List(
        "sh",
        "-c",
        """read line; echo "out:$line"; echo "err:$line" 1>&2""",
      )
      for
        runner <- ZIO.service[InteractiveAgentRunner]
        proc   <- runner.start(argv, "/tmp")
        _      <- runner.sendInput(proc, "ping")
        out    <- proc.stdout.runHead.timeoutFail(new RuntimeException("stdout timeout"))(2.seconds)
        err    <- proc.stderr.runHead.timeoutFail(new RuntimeException("stderr timeout"))(2.seconds)
        _      <- runner.terminate(proc).ignore
      yield assertTrue(out.contains("out:ping"), err.contains("err:ping"))
    },
    test("terminate stops a running process") {
      for
        runner <- ZIO.service[InteractiveAgentRunner]
        proc   <- runner.start(List("sh", "-c", "sleep 30"), "/tmp")
        alive1 <- runner.isAlive(proc)
        _      <- runner.terminate(proc)
        alive2 <- runner.isAlive(proc)
      yield assertTrue(alive1, !alive2)
    },
    test("pause and resume keep the process alive") {
      for
        runner <- ZIO.service[InteractiveAgentRunner]
        proc   <- runner.start(List("sh", "-c", "sleep 30"), "/tmp")
        _      <- runner.pause(proc)
        alive1 <- runner.isAlive(proc)
        _      <- runner.resume(proc)
        alive2 <- runner.isAlive(proc)
        _      <- runner.terminate(proc).ignore
      yield assertTrue(alive1, alive2)
    },
    test("register/resolve/unregister tracks process by runId") {
      for
        runner <- ZIO.service[InteractiveAgentRunner]
        proc   <- runner.start(List("sh", "-c", "sleep 30"), "/tmp")
        ref    <- runner.register("run-123", proc)
        found1 <- runner.resolve(ref)
        _      <- runner.unregister(ref)
        found2 <- runner.resolve(AgentProcessRef("run-123"))
        _      <- runner.terminate(proc).ignore
      yield assertTrue(found1.isDefined, found2.isEmpty)
    },
  ).provideLayerShared(InteractiveAgentRunner.live)
