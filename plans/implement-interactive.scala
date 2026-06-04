//> using dep "io.github.riccardomerolla::llm4zio-runner:2.5.0"
//> using scala "3.8.3"
//> using jvm 21

/** Interactive planning + coding flow — the ZIO-native counterpart of orca's
  * `implement-interactive.sc`.
  *
  * Same shape as `implement.scala`, but planning is interactive: the reasoner
  * (the `claude` CLI) may ask clarifying questions on the terminal (via
  * `TerminalInteraction`) before proposing the plan. Use it for open-ended
  * prompts. Once a plan is proposed, implementation proceeds exactly as in 01-simple.
  *
  * `examples/02-interactive/create-test-project.sh` seeds the crate and copies
  * this script alongside it. Requires `claude` logged in and `cargo` on PATH
  * (no API key — reasoning runs over the claude CLI).
  */

import zio.*
import java.nio.file.Path

import llm4zio.core.{CliConnectorConfig, ConnectorId}
import llm4zio.flow.*
import llm4zio.runner.{Llm4zio, TerminalInteraction}

object Main extends ZIOAppDefault:

  // CLI for both reasoning and coding — no API key needed.
  private val coder     = CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))
  private val reasoning = CliConnectorConfig(ConnectorId.ClaudeCli)

  def run =
    getArgs.flatMap { args =>
      val prompt  = args.headOption.getOrElse("Make the calculator crate more useful")
      val workDir = Path.of(".").toAbsolutePath.normalize

      Llm4zio.run(workDir, reasoning, coder) { ctx =>
        given FlowEvents = ctx.events
        for
          plan      <- Planner.interactive(ctx.reasoning, prompt, TerminalInteraction.live)
          planPath   = workDir.resolve(s".llm4zio/plan-${plan.epicId}.md")
          _         <- PlanStore.save(planPath, plan)
          _         <- stage("branch")(ctx.git.createBranch(plan.epicId).unit)
          coderChat <- Chat.start(ctx.coder, system = Some("You implement one task at a time in the current repo."))
          _         <- implementTaskLoop(planPath, plan) { task =>
                         for
                           _ <- coderChat.ask(task.description).mapError(e => FlowError.Llm(e.toString))
                           _ <- reviewAndFixLoop(Reviewers.minimal, ctx.reasoning, coderChat, task.title, ctx.git.diff)
                           _ <- ctx.git.commitAll(s"${plan.epicId}: ${task.title}").unit
                         yield ()
                       }
        yield ()
      }
    }
