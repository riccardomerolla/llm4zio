//> using dep "io.github.riccardomerolla::llm4zio-runner:3.14.0"
//> using scala "3.8.3"
//> using jvm 21

/** Interactive *live-coding* flow — the steerable counterpart of `implement.sc`.
  *
  * Each task drives a held `claude` session: it streams thinking and tool calls
  * live, can ask clarifying questions mid-task (`ask_user`), and routes tool
  * calls through an approval gate — over an in-process MCP server the runtime
  * starts for the run. Planning is interactive too.
  *
  * Seed a starter:  examples/seed.sh implement-live
  * Run:             scala-cli run implement-live.sc -- "Make the calculator crate more useful"
  */

import zio.ZIO

import llm4zio.flow.*
import llm4zio.runner.*

// The live path doesn't use ctx.coder — InteractiveCoder opens a fresh claude AgentSession per task.
flow(args, defaultPrompt = Some("Make the calculator crate more useful")):
  val interaction = TerminalInteraction.live
  val planPath    = Plan.defaultPath(userPrompt)
  for
    plan <- PlanStore.recoverOrCreate(planPath)(Planner.interactive(reasoning, userPrompt, interaction))
    _    <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    // autoApprove keeps the demo flowing (claude runs in the runtime-owned branch sandbox); swap in
    // ApprovalPolicy.interactive(interaction) to confirm each tool call on the terminal.
    _    <- ZIO.scoped {
              InteractiveCoder.openSessions(workDir, interaction, ApprovalPolicy.autoApprove).flatMap { openSession =>
                implementTaskLoopLive(planPath, plan, interaction, openSession) { (task, _) =>
                  git.commitAll(s"${plan.epicId}: ${task.title}").unit
                }
              }
            }
  yield ()
