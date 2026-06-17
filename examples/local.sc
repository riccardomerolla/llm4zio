//> using dep "io.github.riccardomerolla::llm4zio-runner:3.4.1"
//> using scala "3.8.3"
//> using jvm 21

/** Fully-local agentic flow — no cloud, no API key.
  *
  * Both seats run on your own machine:
  *   - reasoning (planning + review): llm4zio's LM Studio provider, talking to
  *     LM Studio's local server (default http://localhost:1234/v1).
  *   - coding: the `pi` CLI agent, routed to a local model via the pi-lmstudio
  *     extension (pi runs YOLO by default, so it edits files unattended).
  *
  * Setup (one-time):
  *   1. Run LM Studio, load a model, start its local server (port 1234).
  *   2. Install pi + the LM Studio bridge:  npm i -g @earendil-works/pi-coding-agent
  *      then  pi install npm:pi-lmstudio   (and point it at your LM Studio model).
  *   3. Set the two MODEL ids below to match what you have loaded.
  *
  * Seed a starter:  examples/seed.sh local
  * Run:             scala-cli run local.sc -- "Add a multiply function to the calculator crate"
  *
  * Requires LM Studio running with a model loaded, and `pi` (+ pi-lmstudio) installed.
  */

import llm4zio.flow.*
import llm4zio.runner.*

// Edit these to match your local setup.
val ReasoningModel = "qwen/qwen3-coder-30b"          // the model id loaded in LM Studio
val CoderModel     = "qwen/qwen3-coder-30b" // pi's address for your LM Studio model (pi-lmstudio)

flow(
  args,
  coder = pi.withModel(CoderModel),
  // A very slow local model can exceed the 300s default request timeout on a big prompt; raise it with
  // `.copy(model = Some(ReasoningModel), timeout = 10.minutes)` if you hit a 5-minute cut-off.
  reasoning = Some(lmStudio.copy(model = Some(ReasoningModel))),
  defaultPrompt = Some("Add a multiply function to the calculator crate"),
):
  val planPath = Plan.defaultPath(userPrompt)
  for
    plan      <- PlanStore.recoverOrCreate(planPath)(Planner.from(reasoning, userPrompt))
    _         <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     // One local LM Studio serves one request at a time, so run the review lenses sequentially
                     // (parallelism = 1) instead of firing all three at the single model at once.
                     reviewAndFixLoop(Reviewers.minimal, reasoning, coderChat, task.title, git.diff, parallelism = 1) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
  yield ()
