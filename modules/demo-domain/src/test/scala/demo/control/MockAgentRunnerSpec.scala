package demo.control

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

import demo.entity.DemoConfig

object MockAgentRunnerSpec extends ZIOSpecDefault:

  private def withTempDir[A](f: Path => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking {
        val p                       = Files.createTempDirectory("mock-agent-runner-test")
        def run(cmd: String*): Unit =
          val pb   = new ProcessBuilder(cmd*)
          pb.directory(p.toFile)
          pb.redirectErrorStream(true)
          val proc = pb.start()
          proc.getInputStream.transferTo(java.io.OutputStream.nullOutputStream())
          proc.waitFor()
          ()
        run("git", "init")
        run("git", "config", "user.email", "test@example.com")
        run("git", "config", "user.name", "Test")
        p
      }
    )(dir => ZIO.attemptBlocking(deleteRecursively(dir)).orDie)(f)

  private def deleteRecursively(path: Path): Unit =
    if Files.isDirectory(path) then
      val children = Files.list(path).toArray(n => new Array[Path](n))
      children.foreach(deleteRecursively)
    Files.deleteIfExists(path)
    ()

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("MockAgentRunner")(
    test("runner returns exit code 0 after the configured delay") {
      withTempDir { dir =>
        for
          config <- ZIO.succeed(DemoConfig(enabled = true, issueCount = 5, agentDelaySeconds = 1))
          runFn   = MockAgentRunner.runner(config)
          fiber  <- runFn(Nil, dir.toString, _ => ZIO.unit, Map.empty).fork
          _      <- TestClock.adjust(2.seconds)
          result <- fiber.join
        yield assertTrue(result == 0)
      }
    },
    test("runner emits start and completion log lines") {
      withTempDir { dir =>
        for
          lines  <- Ref.make(List.empty[String])
          config  = DemoConfig(enabled = true, issueCount = 5, agentDelaySeconds = 1)
          runFn   = MockAgentRunner.runner(config)
          fiber  <- runFn(Nil, dir.toString, line => lines.update(line :: _), Map.empty).fork
          _      <- TestClock.adjust(2.seconds)
          _      <- fiber.join
          logged <- lines.get
        yield assertTrue(logged.exists(_.contains("[mock]")))
      }
    },
    test("runner writes a markdown file into docs/mock-implementation/") {
      withTempDir { dir =>
        for
          config  <- ZIO.succeed(DemoConfig(enabled = true, issueCount = 5, agentDelaySeconds = 1))
          runFn    = MockAgentRunner.runner(config)
          fiber   <- runFn(Nil, dir.toString, _ => ZIO.unit, Map.empty).fork
          _       <- TestClock.adjust(2.seconds)
          _       <- fiber.join
          hasFile <- ZIO.attemptBlocking {
                       val docsDir = dir.resolve("docs").resolve("mock-implementation")
                       Files.isDirectory(docsDir) && Files.list(docsDir).count() > 0
                     }
        yield assertTrue(hasFile)
      }
    },
  )
