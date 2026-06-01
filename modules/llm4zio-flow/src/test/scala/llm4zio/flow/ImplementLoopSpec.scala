package llm4zio.flow

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

object ImplementLoopSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-loop-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  def spec = suite("implementTaskLoop")(
    test("runs each incomplete task in order, skips done ones, persists progress") {
      ZIO.scoped {
        for
          ev   <- FlowEvents.collecting
          dir  <- tempDir
          path  = dir.resolve("plan.md")
          plan  = Plan("epic", List(Task("a", "", completed = true), Task("b", "x"), Task("c", "y")))
          ran  <- Ref.make(List.empty[String])
          out  <- implementTaskLoop(path, plan)(t => ran.update(_ :+ t.title))(using ev)
          seen <- ran.get
          disk <- PlanStore.load(path)
        yield assertTrue(
          seen == List("b", "c"),
          out.tasks.forall(_.completed),
          disk.contains(out),
        )
      }
    },
    test("on a failing task it stops and persists completed-so-far (resumable)") {
      ZIO.scoped {
        for
          ev   <- FlowEvents.collecting
          dir  <- tempDir
          path  = dir.resolve("plan.md")
          plan  = Plan("epic", List(Task("a", ""), Task("b", ""), Task("c", "")))
          res  <- implementTaskLoop(path, plan) { t =>
                    if t.title == "b" then ZIO.fail(FlowError.Aborted("boom")) else ZIO.unit
                  }(using ev).either
          disk <- PlanStore.load(path)
        yield assertTrue(
          res == Left(FlowError.Aborted("boom")),
          disk.exists(p => p.tasks.find(_.title == "a").exists(_.completed)),
          disk.exists(p => p.tasks.find(_.title == "b").exists(!_.completed)),
        )
      }
    },
  ) @@ TestAspect.sequential
