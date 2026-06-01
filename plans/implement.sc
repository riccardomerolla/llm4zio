//> using dep "io.github.riccardomerolla::llm4zio-runner:0.1.0"
//> using scala "3.8.3"
//> using jvm 21

/** Persistent planning + coding flow (autonomous planning) — the ZIO-native
  * counterpart of orca's `implement.sc`.
  *
  * The reasoning connector (an API model) breaks the prompt into a `Plan`; the
  * plan is persisted to `.llm4zio/plan-<epicId>.md` so a re-run resumes from the
  * first incomplete task. Each task is implemented on one epic branch by the
  * coder (the `claude` CLI, which edits files in the repo), reviewed by the
  * reasoning model via `reviewAndFixLoop`, and committed.
  *
  * `examples/01-simple/create-test-project.sh` seeds a calculator crate and
  * copies this script alongside it, then prints:
  *
  *   scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  *
  * Requires `claude` logged in, `cargo` on PATH, and a reasoning API key in the
  * environment (e.g. ANTHROPIC_API_KEY).
  */

import zio.*
import java.nio.file.Path

import llm4zio.core.{ApiConnectorConfig, CliConnectorConfig, ConnectorId}
import llm4zio.flow.*
import llm4zio.runner.Llm4zio

object Main extends ZIOAppDefault:

  // Reasoning over an API connector; code-editing over the claude CLI rooted in
  // the repo, allowed to apply edits headlessly.
  private val reasoning = ApiConnectorConfig(ConnectorId.Anthropic, model = Some("claude-sonnet-4-5"))
  private val coder     = CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))

  def run =
    getArgs.flatMap { args =>
      val prompt  = args.headOption.getOrElse("Add a multiply function to the calculator crate")
      val workDir = Path.of(".").toAbsolutePath.normalize

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
                         _ <- reviewAndFixLoop(ctx.reasoning, coderChat, task.title, ctx.git.diff)
                         _ <- ctx.git.commitAll(s"${plan.epicId}: ${task.title}").unit
                       yield ()
                     }
        yield ()
      }
    }
