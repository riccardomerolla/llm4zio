package llm4zio.javaapi

import java.nio.file.{ Files, Path }
import java.util.function.Consumer

import zio.test.*
import zio.{ IO, Scope, ZIO }

import llm4zio.core.{ LlmConfig, LlmProvider }
import llm4zio.flow.{ FlowContext, FlowError, FlowEvent, FlowEvents, GhTool, GitTool, Plan, PlanStore, Task }
import llm4zio.providers.MockProvider

/** End-to-end through the real [[JavaFlow]] handle, the way a `.java` flow drives it: a Scala-authored
  * `Consumer[JavaFlow]` mirroring `examples/java/Implement.java`, run on a live runtime against a temp git repo with the
  * Mock provider. Proves the facade wires stage / recoverOrCreatePlan / startChat / implementTaskLoop / git together and
  * produces the same progress-event protocol as the `.sc` surface.
  */
object JavaFlowItSpec extends ZIOSpecDefault:

  // Privileged mint: the Java facade is exercised full-grant, like the Bridge grants it in production.
  private given llm4zio.flow.Caps.All = llm4zio.flow.Caps.grantAll

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-java-")).orDie)(d =>
      ZIO.attempt(Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))).orDie
    )

  private def newRepo(dir: Path): IO[FlowError, GitTool] =
    val git = GitTool(dir)
    for
      _ <- git.init
      _ <- git.config("user.email", "test@example.com")
      _ <- git.config("user.name", "Test")
      _ <- git.config("commit.gpgsign", "false")
      _ <- ZIO.attemptBlocking(Files.write(dir.resolve("README.md"), "seed".getBytes))
             .mapError(e => FlowError.Persistence("seed", Some(e)))
      _ <- git.commitAll("init")
    yield git

  def spec = suite("JavaFlow end-to-end")(
    test("a Java-authored flow branches, recovers the plan, edits + commits per task, persists progress") {
      ZIO.scoped {
        for
          ev       <- FlowEvents.collecting
          dir      <- tempDir
          git      <- newRepo(dir)
          mock      = MockProvider.make(LlmConfig(LlmProvider.Mock, "mock"))
          ctx       = FlowContext(reasoning = mock, coder = mock, git = git, gh = GhTool(dir), events = ev, workDir = dir)
          planPath  = dir.resolve(".llm4zio/plan.md")
          plan      = Plan("feature-x", List(Task("First step", "do A"), Task("Second step", "do B")))
          _        <- PlanStore.save(planPath, plan)
          rt       <- ZIO.runtime[Any]
          commits   = scala.collection.mutable.ListBuffer.empty[CommitResult]
          // Authored in Scala but shaped like Implement.java. (In Java the case-class accessors read `task.title()`;
          // here, in Scala, they're `task.title`. The void stage lambda is annotated `Runnable` to pick that overload.)
          body      = ((jf: JavaFlow) => {
                        val recovered = jf.recoverOrCreatePlan(planPath)
                        jf.stage("branch", (() => jf.git().checkoutOrCreate(recovered.epicId)): Runnable)
                        val chat      = jf.startChat("implement one task at a time")
                        val _         = jf.implementTaskLoop(
                          planPath,
                          recovered,
                          task => {
                            val _ = chat.ask(task.description)
                            Files.write(dir.resolve(task.title.replace(' ', '-') + ".txt"), task.description.getBytes)
                            commits += jf.git().commitAll(recovered.epicId + ": " + task.title)
                          },
                        )
                      }): Consumer[JavaFlow]
          _        <- ZIO.attemptBlocking(body.accept(new JavaFlow(rt, ctx)))
          branch   <- git.currentBranch
          disk     <- PlanStore.load(planPath)
          events   <- ev.recorded
          // Evaluate the Java static `Files.exists` outside `assertTrue` — calling a Java static inside the macro
          // mis-expands to a module reference (`Files$`).
          edits    <- ZIO.attempt(Files.exists(dir.resolve("First-step.txt")) && Files.exists(dir.resolve("Second-step.txt")))
        yield assertTrue(
          branch == "feature-x",
          disk.exists(_.tasks.forall(_.completed)),
          edits,
          commits.toList.forall(_.isCommitted), // each task's edit was committed
          commits.size == 2,
          events.contains(FlowEvent.StageStarted("branch")),
          events.contains(FlowEvent.StageStarted("First step")),
          events.contains(FlowEvent.StageCompleted("Second step")),
        )
      }
    }
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
