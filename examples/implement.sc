//> using dep "io.github.riccardomerolla::llm4zio-runner:4.3.0"
//> using scala "3.8.3"
//> using jvm 21

/** Persistent planning + coding flow (autonomous planning) — the ZIO-native
  * counterpart of orca's `implement.sc`.
  *
  * The reasoner breaks the prompt into a `Plan`, persisted at
  * `.llm4zio/plan-<hash>.md` so a re-run resumes from the first incomplete task.
  * Each task is implemented on one epic branch, reviewed via `reviewAndFixLoop`,
  * and committed. Backend selectable via LLM4ZIO_CODER=claude|codex|gemini
  * (default claude); no API key — one CLI login is enough.
  *
  * Seed a starter:  examples/seed.sh implement
  * Run:             scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
  */

import llm4zio.flow.*
import llm4zio.runner.*

// The runtime capability mint for a script: `flow(...)`'s own `Caps.All` given is scoped to the lambda passed to it,
// so top-level `def`s in this file (which call `git.*`) need their own. Static witness only — the ambient `Grants`
// FiberRef still gates every call at runtime, so this widens nothing. `Caps.grantAll` is package-private, so a
// script uses the documented public hatch `Caps.unsafe.all` — deliberately loud and greppable.
given llm4zio.flow.Caps.All = zio.Unsafe.unsafe(implicit u => llm4zio.flow.Caps.unsafe.all)


flow(args, defaultPrompt = Some("Add a multiply function to the calculator crate")):
  val planPath = Plan.defaultPath(userPrompt)
  for
    plan      <- PlanStore.recoverOrCreate(planPath)(Planner.from(reasoning, userPrompt))
    _         <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     reviewAndFixLoop(Reviewers.minimal, reasoning, coderChat, task.title, git.diff) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
  yield ()
