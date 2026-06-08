//> using dep "io.github.riccardomerolla::llm4zio-runner:2.7.7"
//> using scala "3.8.3"
//> using jvm 21

/** Interactive *live-coding* flow — the steerable counterpart of `implement.scala`.
  *
  * Instead of a one-shot reply per task, each task drives a held `claude` session: it streams its thinking and tool
  * calls to the terminal live, can ask you clarifying questions mid-task (`ask_user`), and routes every tool call
  * through an approval gate — all over an in-process MCP server the runtime starts for the run. Planning is interactive
  * too. Once a task finishes, the runtime commits it; progress is resumable via `.llm4zio/plan-*.md`.
  *
  * `examples/05-interactive-live/create-test-project.sh` seeds the crate and copies this script alongside it. Requires
  * `claude` logged in and `cargo` on PATH (no API key — everything runs over the claude CLI).
  */

import zio.*
import java.nio.file.Path

import llm4zio.core.{ CliConnectorConfig, ConnectorId }
import llm4zio.flow.*
import llm4zio.runner.{ InteractiveCoder, Llm4zio, TerminalInteraction }

object Main extends ZIOAppDefault:

  // Reasoning (planning) over the claude CLI. `coder` is required by Llm4zio.run to build the FlowContext, but the
  // live path doesn't use ctx.coder — InteractiveCoder opens a fresh claude AgentSession per task instead.
  private val reasoning = CliConnectorConfig(ConnectorId.ClaudeCli)
  private val coder     = CliConnectorConfig(ConnectorId.ClaudeCli)

  def run =
    getArgs.flatMap { args =>
      val prompt      = args.headOption.getOrElse("Make the calculator crate more useful")
      val workDir     = Path.of(".").toAbsolutePath.normalize
      val interaction = TerminalInteraction.live

      Llm4zio.run(workDir, reasoning, coder) { ctx =>
        given FlowEvents = ctx.events
        for
          plan     <- Planner.interactive(ctx.reasoning, prompt, interaction)
          planPath  = workDir.resolve(s".llm4zio/plan-${plan.epicId}.md")
          _        <- PlanStore.save(planPath, plan)
          _        <- stage("branch")(ctx.git.createBranch(plan.epicId).unit)
          // autoApprove keeps the demo flowing (claude runs in the runtime-owned branch sandbox); swap in
          // ApprovalPolicy.interactive(interaction) to confirm each tool call on the terminal.
          _        <- ZIO.scoped {
                        InteractiveCoder.openSessions(workDir, interaction, ApprovalPolicy.autoApprove).flatMap {
                          openSession =>
                            implementTaskLoopLive(planPath, plan, interaction, openSession) { (task, _) =>
                              ctx.git.commitAll(s"${plan.epicId}: ${task.title}").unit
                            }
                        }
                      }
        yield ()
      }
    }
