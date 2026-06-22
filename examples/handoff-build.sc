//> using dep "io.github.riccardomerolla::llm4zio-runner:3.9.0"
//> using scala "3.8.3"
//> using jvm 21

/** Phase 2 of the human-gated hand-off (see handoff-plan.sc): refuse to build until the spec is
  * approved, then implement the plan one task at a time.
  *
  *   handoff-plan.sc  →  (human flips "- [ ] Approved" to "- [x] Approved")  →  handoff-build.sc
  *
  * Run with the SAME --repo and --prompt-file as phase 1, from OUTSIDE the target repo:
  *
  *   scala-cli run handoff-build.sc -- --repo ~/projects/todo --prompt-file requirement.md
  *
  * The gate is non-interactive by design: in CI (or any non-TTY run) it halts with
  * `awaiting approval: set '- [x] Approved' in <path>` and a non-zero exit until the box is checked
  * — so an unapproved spec can never be implemented. The plan persisted by phase 1 is loaded and
  * resumed; a crashed run picks up where it left off. PR-based approval composes the same way: gate
  * on `gh` PR state instead of the marker.
  */

import llm4zio.flow.*
import llm4zio.runner.*

flow(
  args,
  defaultPrompt = Some(
    "Add due dates: 'add <text> --due YYYY-MM-DD', mark overdue items in 'list', and a 'due' command showing items due today"
  ),
):
  for
    plan      <- PlanStore.recoverOrCreate(planPath(userPrompt)) {
                   fail("no plan found — run handoff-plan.sc first (same --repo and --prompt-file)")
                 }
    specFile   = workspace.resolve(s"specs/${plan.epicId}.md")
    _         <- stage("Approval")(ApprovalGate.gate(specFile, Interaction.noninteractive))
    _         <- stage("Branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(
                   coder,
                   system = Some("You implement one task at a time in the current repo; the committed spec is the contract."),
                 )
    _         <- implementTaskLoop(planPath(userPrompt), plan) { task =>
                   coderChat.ask(plan.taskPrompt(task)) *> git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
  yield plan.epicId
