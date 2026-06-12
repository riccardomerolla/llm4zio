package llm4zio.runner

import java.nio.file.{ Files, Path }

import zio.{ IO, ZIO }

import llm4zio.flow.*

/** A worked, orca-shaped flow: create a branch, then for each task drive the coder, write its output, and commit —
  * finally pushing the branch.
  *
  * It reads top-to-bottom like a script. A real flow's per-task step lets the CLI coder edit files directly; here we
  * persist the coder's reply so the example is deterministic and the spine is exercised end-to-end. Opening a PR is a
  * one-liner on top: `ctx.gh.createPr(title, body, base = Some("main"))`.
  */
object ExampleFlow:

  def run(
    ctx: FlowContext,
    repoDir: Path,
    plan: Plan,
    planPath: Path,
    remote: Option[String] = None,
  ): IO[FlowError, Plan] =
    import ctx.given // the FlowEvents sink, so stage/implementTaskLoop resolve it

    for
      _     <- stage("branch")(ctx.git.createBranch(plan.epicId).unit)
      coder <- Chat.start(ctx.coder, system = Some("You implement one task at a time."))
      done  <- implementTaskLoop(planPath, plan) { task =>
                 for
                   reply <- coder.ask(s"Implement ${task.title}: ${task.description}")
                   _     <- writeFile(repoDir, s"${slug(task.title)}.md", reply)
                   _     <- ctx.git.commitAll(s"${plan.epicId}: ${task.title}")
                 yield ()
               }
      _     <- ZIO.foreachDiscard(remote)(r => stage("push")(ctx.git.push(r, plan.epicId)))
    yield done

  private def slug(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

  private def writeFile(dir: Path, name: String, content: String): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking(Files.write(dir.resolve(name), content.getBytes))
      .mapError(e => FlowError.Persistence(s"write $name", Some(e)))
      .unit
