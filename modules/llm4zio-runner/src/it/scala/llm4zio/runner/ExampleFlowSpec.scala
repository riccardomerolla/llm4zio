package llm4zio.runner

import zio.{ IO, Scope, ZIO }
import zio.test.*

import java.nio.file.{ Files, Path }

import llm4zio.flow.*
import llm4zio.core.{ LlmConfig, LlmProvider }
import llm4zio.providers.MockProvider

object ExampleFlowSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-runner-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private def write(dir: Path, name: String, content: String): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking(Files.write(dir.resolve(name), content.getBytes))
      .mapError(e => FlowError.Persistence(s"write $name", Some(e)))
      .unit

  private def newRepo(dir: Path): IO[FlowError, GitTool] =
    val git = GitTool(dir)
    for
      _ <- git.init
      _ <- git.config("user.email", "test@example.com")
      _ <- git.config("user.name", "Test")
      _ <- git.config("commit.gpgsign", "false")
      _ <- write(dir, "README.md", "seed")
      _ <- git.commitAll("init")
    yield git

  def spec = suite("ExampleFlow")(
    test("runs a plan end-to-end: branch, per-task edit+commit, push to a bare remote") {
      ZIO.scoped {
        for
          ev     <- FlowEvents.collecting
          dir    <- tempDir
          git    <- newRepo(dir)
          remote <- tempDir
          _      <- GitTool(remote).initBare
          _      <- git.addRemote("origin", remote.toString)
          mock    = MockProvider.make(LlmConfig(LlmProvider.Mock, "mock"))
          ctx     = FlowContext(reasoning = mock, coder = mock, git = git, gh = GhTool(dir), events = ev)
          plan    = Plan("feature-x", List(Task("First step", "do A"), Task("Second step", "do B")))
          path    = dir.resolve(".llm4zio/plan.md")
          done   <- ExampleFlow.run(ctx, dir, plan, path, remote = Some("origin"))
          branch <- git.currentBranch
          refs   <- git.lsRemote("origin")
          disk   <- PlanStore.load(path)
          events <- ev.recorded
        yield assertTrue(
          done.tasks.forall(_.completed),
          branch == "feature-x",
          refs.contains("refs/heads/feature-x"),
          disk.exists(_.tasks.forall(_.completed)),
          events.contains(FlowEvent.StageStarted("First step")),
          events.contains(FlowEvent.StageCompleted("Second step")),
          events.contains(FlowEvent.StageStarted("push")),
        )
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
