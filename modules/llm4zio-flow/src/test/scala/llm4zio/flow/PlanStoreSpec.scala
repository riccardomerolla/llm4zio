package llm4zio.flow

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object PlanStoreSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-flow-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("PlanStore")(
    test("save then load round-trips a plan") {
      ZIO.scoped {
        for
          dir  <- tempDir
          plan  = Plan("epic-1", List(Task("a", "do a"), Task("b", "do b", completed = true)))
          path  = dir.resolve("plan.md")
          _    <- PlanStore.save(path, plan)
          back <- PlanStore.load(path)
        yield assertTrue(back.contains(plan))
      }
    },
    test("load returns None when the file is absent") {
      ZIO.scoped {
        for
          dir  <- tempDir
          back <- PlanStore.load(dir.resolve("missing.md"))
        yield assertTrue(back.isEmpty)
      }
    },
    test("recoverOrCreate loads an existing plan without re-running create") {
      ZIO.scoped {
        for
          dir   <- tempDir
          path   = dir.resolve("plan.md")
          stored = Plan("epic", List(Task("x", "")))
          _     <- PlanStore.save(path, stored)
          ran   <- Ref.make(false)
          got   <- PlanStore.recoverOrCreate(path)(ran.set(true).as(Plan("other", Nil)))
          reran <- ran.get
        yield assertTrue(got == stored, !reran)
      }
    },
    test("recoverOrCreate runs create and persists it when no plan exists") {
      ZIO.scoped {
        for
          dir  <- tempDir
          path  = dir.resolve("nested/plan.md")
          fresh = Plan("epic", List(Task("x", "")))
          got  <- PlanStore.recoverOrCreate(path)(ZIO.succeed(fresh))
          disk <- PlanStore.load(path)
        yield assertTrue(got == fresh, disk.contains(fresh))
      }
    },
    test("delete removes the plan file and is a no-op when already absent") {
      ZIO.scoped {
        for
          dir  <- tempDir
          path  = dir.resolve("plan.md")
          _    <- PlanStore.save(path, Plan("e", List(Task("x", ""))))
          _    <- PlanStore.delete(path)
          gone <- PlanStore.load(path)
          _    <- PlanStore.delete(path) // no-op, must not fail
        yield assertTrue(gone.isEmpty)
      }
    },
  )
