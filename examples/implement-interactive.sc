//> using dep "io.github.riccardomerolla::llm4zio-runner:3.11.1"
//> using scala "3.8.3"
//> using jvm 21

/** Interactive planning + coding — the ZIO-native counterpart of orca's
  * `implement-interactive.sc`. Same shape as `implement.sc`, but the planner may
  * ask clarifying questions on the terminal before proposing the plan. Use it
  * for open-ended prompts.
  *
  * Seed a starter:  examples/seed.sh implement-interactive
  * Run:             scala-cli run implement-interactive.sc -- "Make the calculator crate more useful"
  */

import llm4zio.flow.*
import llm4zio.runner.*

flow(args, defaultPrompt = Some("Make the calculator crate more useful")):
  val planPath = Plan.defaultPath(userPrompt)
  for
    plan      <- PlanStore.recoverOrCreate(planPath)(
                   Planner.interactive(reasoning, userPrompt, TerminalInteraction.live)
                 )
    _         <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     reviewAndFixLoop(Reviewers.minimal, reasoning, coderChat, task.title, git.diff) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
  yield ()
