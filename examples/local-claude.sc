//> using dep "io.github.riccardomerolla::llm4zio-runner:3.4.1"
//> using scala "3.8.3"
//> using jvm 21

/** Fully-local agentic flow with **Claude Code** as the coder — no cloud, no API key.
  *
  * Both seats run on your own machine against LM Studio:
  *   - reasoning (planning + review): llm4zio's LM Studio provider (native API on :1234).
  *   - coding: the `claude` CLI (Claude Code) pointed at LM Studio's Anthropic-compatible
  *     `/v1/messages` endpoint via ANTHROPIC_BASE_URL + ANTHROPIC_AUTH_TOKEN. Those are
  *     injected into the coder connector below (llm4zio passes them through to `claude`),
  *     so you don't need to export anything. See https://lmstudio.ai/blog/claudecode.
  *
  * Setup (one-time):
  *   1. Start LM Studio's server:  `lms server start --port 1234`  and load a model.
  *      Claude Code is context-hungry — load the model with **at least 25K context**.
  *   2. Install Claude Code (`claude` on PATH). No Anthropic API key needed: it talks to
  *      LM Studio, not Anthropic.
  *   3. Set the two model ids below to the model loaded in LM Studio.
  *
  * Seed a starter:  examples/seed.sh local-claude
  * Run:             scala-cli run local-claude.sc -- "Add a multiply function to the calculator crate"
  *
  * Requires LM Studio running with a model loaded, and `claude` (Claude Code) installed.
  */

import llm4zio.flow.*
import llm4zio.runner.*

// Edit these to match the model loaded in LM Studio.
val ReasoningModel = "qwen/qwen3.6-35b-a3b"
val CoderModel     = "qwen/qwen3.6-35b-a3b"

// Claude Code as the coder, routed to LM Studio's Anthropic-compatible endpoint (no Anthropic key).
// llm4zio passes these env vars through to the spawned `claude` process (merged onto the environment).
val claudeLocal = claude.withModel(CoderModel).copy(
  envVars = Map(
    "ANTHROPIC_BASE_URL"   -> "http://localhost:1234",
    "ANTHROPIC_AUTH_TOKEN" -> "lmstudio",
  )
)

flow(
  args,
  coder = claudeLocal,
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
                     // One local LM Studio serves one request at a time, so run the review lenses sequentially.
                     reviewAndFixLoop(Reviewers.minimal, reasoning, coderChat, task.title, git.diff, parallelism = 1) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
  yield ()
