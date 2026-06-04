//> using dep "io.github.riccardomerolla::llm4zio-runner:2.5.0"
//> using scala "3.8.3"
//> using jvm 21

/** Persistent planning + coding flow (autonomous planning) — the ZIO-native
  * counterpart of orca's `implement.sc`.
  *
  * A CLI agent breaks the prompt into a `Plan`; the plan is persisted to
  * `.llm4zio/plan-<epicId>.md` so a re-run resumes from the first incomplete
  * task. Each task is implemented on one epic branch by the coder (a CLI agent
  * that edits files in the repo), reviewed via `reviewAndFixLoop`, and committed.
  * Reasoning and coding share the chosen backend, so no API key is needed.
  *
  * `examples/01-simple/create-test-project.sh` seeds a calculator crate and
  * copies this script alongside it, then prints:
  *
  *   scala-cli run implement.scala -- "Add a multiply function to the calculator crate"
  *
  * Requires the chosen agent CLI logged in (`claude` by default) and `cargo` on PATH.
  */

import zio.*
import java.nio.file.Path

import llm4zio.core.{CliConnectorConfig, ConnectorId}
import llm4zio.flow.*
import llm4zio.runner.Llm4zio

object Main extends ZIOAppDefault:

  /** Both reasoning (planning + review) and coding run over a CLI agent —
    * selectable via LLM4ZIO_CODER=claude|codex|gemini (default claude). No API
    * key required; a single CLI login is enough.
    */
  private def cliFor(name: String): CliConnectorConfig = name match
    case "codex"  => CliConnectorConfig(ConnectorId.Codex, flags = Map("full-auto" -> ""))
    case "gemini" => CliConnectorConfig(ConnectorId.GeminiCli) // gemini auto-approves edits via built-in -y
    case _        => CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))

  def run =
    getArgs.flatMap { args =>
      val prompt    = args.headOption.getOrElse("Add a multiply function to the calculator crate")
      val workDir   = Path.of(".").toAbsolutePath.normalize
      val backend   = sys.env.getOrElse("LLM4ZIO_CODER", "claude")
      val coder     = cliFor(backend)
      val reasoning = cliFor(backend)

      Llm4zio.run(workDir, reasoning, coder) { ctx =>
        given FlowEvents = ctx.events
        for
          plan    <- Planner.from(ctx.reasoning, prompt)
          planPath = workDir.resolve(s".llm4zio/plan-${plan.epicId}.md")
          _       <- PlanStore.save(planPath, plan)
          _       <- stage("branch")(ctx.git.createBranch(plan.epicId).unit)
          coderChat <- Chat.start(ctx.coder, system = Some("You implement one task at a time in the current repo."))
          _       <- implementTaskLoop(planPath, plan) { task =>
                       for
                         _ <- coderChat.ask(task.description).mapError(e => FlowError.Llm(e.toString))
                         _ <- reviewAndFixLoop(Reviewers.minimal, ctx.reasoning, coderChat, task.title, ctx.git.diff)
                         _ <- ctx.git.commitAll(s"${plan.epicId}: ${task.title}").unit
                       yield ()
                     }
        yield ()
      }
    }
