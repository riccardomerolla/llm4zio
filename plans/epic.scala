//> using dep "io.github.riccardomerolla::llm4zio-runner:2.8.0"
//> using scala "3.8.3"
//> using jvm 21

/** Run an epic: a resumable multi-task workstream with a full review pass — the
  * ZIO-native counterpart of orca's `epic.sc`.
  *
  * Two layers stack here:
  *   - **On-disk epic.** `.llm4zio/epic.md` holds the task list. A fresh run
  *     generates it; a re-run recovers it and restarts from the first incomplete
  *     task (each task's checkbox is committed as it lands, so a crash loses no
  *     progress). The file is removed when the epic completes.
  *   - **Full reviewer roster.** After each task the seven review lenses
  *     (`Reviewers.all`) run and the coder fixes their findings. One CLI agent
  *     does everything — planning, coding, review — selectable via
  *     `LLM4ZIO_CODER=claude|codex|gemini` (default `claude`).
  *
  * At the end the docs are updated and the epic file is cleaned up.
  *
  * `examples/04-epic/create-test-project.sh` seeds a Java todo-cli and copies
  * this script alongside it. Needs the chosen agent CLI logged in (no API key).
  */

import zio.*
import java.nio.file.Path

import llm4zio.core.{CliConnectorConfig, ConnectorId}
import llm4zio.flow.*
import llm4zio.runner.Llm4zio

object Main extends ZIOAppDefault:

  /** Reasoning (planning), coding, and the full reviewer roster all run over one
    * CLI agent — selectable via LLM4ZIO_CODER=claude|codex|gemini (default claude).
    * No API key required; a single CLI login is enough.
    */
  private def cliFor(name: String): CliConnectorConfig = name match
    case "codex"  => CliConnectorConfig(ConnectorId.Codex, flags = Map("sandbox" -> "workspace-write"))
    case "gemini" => CliConnectorConfig(ConnectorId.GeminiCli) // gemini auto-approves edits via built-in -y
    case _        => CliConnectorConfig(ConnectorId.ClaudeCli, flags = Map("permission-mode" -> "acceptEdits"))

  def run =
    getArgs.flatMap { args =>
      val prompt  = args.headOption.getOrElse(
        "Persist tasks to a JSON file, add 'done <id>' and 'delete <id>' commands, and priority levels"
      )
      val workDir   = Path.of(".").toAbsolutePath.normalize
      val backend   = sys.env.getOrElse("LLM4ZIO_CODER", "claude")
      val coder     = cliFor(backend)
      val reasoning = cliFor(backend)
      // The seven review lenses fan out concurrently by default. gemini's free tier 429s under that burst, so run its
      // reviews one at a time (gemini's own retry/backoff then absorbs transient limits); 0 = unbounded for the rest.
      val reviewParallelism = if backend == "gemini" then 1 else 0

      Llm4zio.run(workDir, reasoning, coder) { ctx =>
        given FlowEvents = ctx.events
        val planPath = workDir.resolve(".llm4zio/epic.md")
        // Optional project formatter (e.g. LLM4ZIO_FORMAT="sbt fmt"): run before each review round and before commit.
        val format   = Formatter.step(sys.env.get("LLM4ZIO_FORMAT"), workDir)

        for
          plan      <- stage("Acquire epic")(PlanStore.recoverOrCreate(planPath)(Planner.from(ctx.reasoning, prompt)))
          _         <- stage("Branch")(ctx.git.checkoutOrCreate(plan.epicId))
          coderChat <- Chat.start(ctx.coder, system = Some("You implement one task at a time in the current repo."))
          _         <- implementTaskLoop(planPath, plan) { task =>
                         for
                           _ <- coderChat.ask(task.description).mapError(e => FlowError.Llm(e.message, Some(e)))
                           _ <- reviewAndFixLoop(
                                  Reviewers.all,
                                  ctx.reasoning,
                                  coderChat,
                                  task.title,
                                  ctx.git.diff,
                                  parallelism = reviewParallelism,
                                  format = format,
                                )
                           _ <- format // format once more before committing the task
                           _ <- ctx.git.commitAll(s"${plan.epicId}: ${task.title}").unit
                         yield ()
                       }
          _         <- stage("Update documentation") {
                         coderChat
                           .ask("All tasks are done. Update the project docs (README, doc-comments) for the changes made — only what's affected, no new sections.")
                           .mapError(e => FlowError.Llm(e.message, Some(e))) *>
                           ctx.git.commitAll("docs: update for completed epic").unit
                       }
          _         <- stage("Clean up epic file")(PlanStore.delete(planPath))
        yield ()
      }
    }
