//> using dep "io.github.riccardomerolla::llm4zio-runner:2.9.0"
//> using scala "3.8.3"
//> using jvm 21

/** Persistent planning + coding, enhanced with a plan self-review and a shared
  * codebase brief — the ZIO-native counterpart of orca's `implement-enhanced.sc`.
  *
  * Same backbone as `implement.scala` (autonomous plan → persistent
  * `.llm4zio/plan-enhanced.md` → per-task implement + review-and-fix loop), with
  * two extra steps chained onto planning, both on the reasoning connector:
  *
  *   1. `Planner.reviewed` — the planner critiques its own draft along four
  *      dimensions (correctness, completeness, simplicity, conciseness) and
  *      returns an improved plan.
  *   2. `Planner.briefed` — the planner writes a one-off codebase brief (modules,
  *      file paths, key APIs, conventions) and attaches it. `plan.taskPrompt(task)`
  *      prepends the brief to every task, so the cold coding agent doesn't
  *      re-discover what the planner already learned.
  *
  * The brief rides in the single plan file (a trailing `# Brief` section), so
  * `recoverOrCreate` persists it on a fresh run, reuses it on resume, and removes
  * it with the file when the plan completes — no sidecar.
  *
  * Formatting runs before every review round and before each commit
  * (`LLM4ZIO_FORMAT`, e.g. "cargo fmt"); an optional lint runs each round
  * (`LLM4ZIO_LINT`, e.g. "cargo check --tests").
  *
  * `examples/07-enhanced/create-test-project.sh` seeds a calculator crate and
  * copies this script. Run:
  *   scala-cli run implement-enhanced.scala -- "Add a multiply function to the calculator crate"
  *
  * Requires the chosen agent CLI logged in (`claude` by default) and `cargo` on PATH.
  */

import zio.*
import java.nio.file.Path

import llm4zio.core.{CliConnectorConfig, ConnectorId}
import llm4zio.flow.*
import llm4zio.runner.Llm4zio

object Main extends ZIOAppDefault:

  private def cliFor(name: String): CliConnectorConfig = name match
    case "codex"  => CliConnectorConfig(ConnectorId.Codex, flags = Map("sandbox" -> "workspace-write"))
    case "gemini" => CliConnectorConfig(ConnectorId.GeminiCli)
    case _        => CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))

  def run =
    getArgs.flatMap { args =>
      val prompt    = args.headOption.getOrElse("Add a multiply function to the calculator crate")
      val workDir   = Path.of(".").toAbsolutePath.normalize
      val backend   = sys.env.getOrElse("LLM4ZIO_CODER", "claude")
      val coder     = cliFor(backend)
      val reasoning = cliFor(backend)
      // gemini's free tier 429s under concurrent reviewers; throttle it (0 = unbounded for the rest).
      val reviewParallelism = if backend == "gemini" then 1 else 0

      Llm4zio.run(workDir, reasoning, coder) { ctx =>
        given FlowEvents = ctx.events
        val planPath = workDir.resolve(".llm4zio/plan-enhanced.md")
        // Format before each review round + before commit; optional lint folded into the review (LLM4ZIO_FORMAT / _LINT).
        val format   = Formatter.step(sys.env.get("LLM4ZIO_FORMAT"), workDir)
        val lint     = sys.env.get("LLM4ZIO_LINT").map(c => Reviewers.lintCommand(List("bash", "-c", c), workDir))

        for
          // Plan → self-review → codebase brief, persisted (with the brief) so a re-run resumes without re-planning.
          plan      <- stage("Plan (review + brief)") {
                         PlanStore.recoverOrCreate(planPath) {
                           for
                             draft    <- Planner.from(ctx.reasoning, prompt)
                             reviewed <- Planner.reviewed(ctx.reasoning, draft)
                             briefed  <- Planner.briefed(ctx.reasoning, reviewed, prompt)
                           yield briefed
                         }
                       }
          _         <- stage("Branch")(ctx.git.checkoutOrCreate(plan.epicId))
          coderChat <- Chat.start(ctx.coder, system = Some("You implement one task at a time in the current repo."))
          _         <- implementTaskLoop(planPath, plan) { task =>
                         for
                           // taskPrompt prepends the shared codebase brief to the task description.
                           _ <- coderChat.ask(plan.taskPrompt(task)).mapError(e => FlowError.Llm(e.message, Some(e)))
                           _ <- reviewAndFixLoop(
                                  Reviewers.all,
                                  ctx.reasoning,
                                  coderChat,
                                  task.title,
                                  ctx.git.diff,
                                  parallelism = reviewParallelism,
                                  lint = lint,
                                  format = format,
                                )
                           _ <- format // format once more before committing
                           _ <- ctx.git.commitAll(s"${plan.epicId}: ${task.title}").unit
                         yield ()
                       }
        yield ()
      }
    }
