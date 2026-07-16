//> using dep "io.github.riccardomerolla::llm4zio-runner:3.18.2"
//> using scala "3.8.3"
//> using jvm 21

/** Run an epic: a resumable multi-task workstream with the full review roster —
  * the ZIO-native counterpart of orca's `epic.sc`.
  *
  * `.llm4zio/plan-<hash>.md` holds the task list; a re-run resumes from the first
  * incomplete task (each checkbox is committed as it lands). After each task the
  * seven review lenses (`Reviewers.all`) run and the coder fixes their findings.
  * At the end the docs are updated and the plan file is cleaned up.
  *
  * Seed a starter:  examples/seed.sh epic
  * Run:             scala-cli run epic.sc -- "<a multi-task change request>"
  */

import llm4zio.core.ConnectorId
import llm4zio.flow.*
import llm4zio.runner.*

val coderCfg          = Connectors.coderFromEnv()
// gemini's free tier 429s when the seven lenses fan out concurrently; serialize its reviews.
val reviewParallelism = if coderCfg.connectorId == ConnectorId.GeminiCli then 1 else 0

flow(
  args,
  coder = coderCfg,
  defaultPrompt = Some(
    "Persist tasks to a JSON file (load on startup, save on every change), add 'done <id>' and " +
      "'delete <id>' commands, and support priority levels (low/medium/high) with a 'list --priority' filter"
  ),
):
  val planPath = Plan.defaultPath(userPrompt)
  val format   = Formatter.step(sys.env.get("LLM4ZIO_FORMAT"), workDir)
  for
    plan      <- stage("Acquire epic")(PlanStore.recoverOrCreate(planPath)(Planner.from(reasoning, userPrompt)))
    _         <- stage("Branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   for
                     _ <- coderChat.ask(task.description)
                     _ <- reviewAndFixLoop(
                            Reviewers.all,
                            reasoning,
                            coderChat,
                            task.title,
                            git.diff,
                            parallelism = reviewParallelism,
                            format = format,
                          )
                     _ <- format
                     _ <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
                   yield ()
                 }
    _         <- stage("Update documentation") {
                   coderChat.ask(
                     "All tasks are done. Update the project docs (README, doc-comments) for the changes made — " +
                       "only what's affected, no new sections."
                   ) *> git.commitAll("docs: update for completed epic").unit
                 }
    _         <- stage("Clean up epic file")(PlanStore.delete(planPath))
  yield ()
