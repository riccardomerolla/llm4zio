//> using dep "io.github.riccardomerolla::llm4zio-runner:3.11.1"
//> using scala "3.8.3"
//> using jvm 21

/** Persistent planning + coding, enhanced with a plan self-review and a shared
  * codebase brief — the ZIO-native counterpart of orca's `implement-enhanced.sc`.
  *
  * Two steps chain onto planning, both on the reasoning connector:
  * `.reviewed(reasoning)` critiques the draft plan; `.briefed(reasoning, prompt)`
  * writes a one-off codebase brief that `plan.taskPrompt(task)` prepends to every
  * task. The brief rides in the single plan file (a trailing `# Brief` section),
  * so resume reuses it — no sidecar.
  *
  * Formatting runs before every review round and commit (LLM4ZIO_FORMAT, e.g.
  * "cargo fmt"); an optional lint runs each round (LLM4ZIO_LINT, e.g.
  * "cargo check --tests").
  *
  * Seed a starter:  examples/seed.sh implement-enhanced
  * Run:             scala-cli run implement-enhanced.sc -- "Add a multiply function to the calculator crate"
  */

import llm4zio.core.ConnectorId
import llm4zio.flow.*
import llm4zio.runner.*

// gemini's free tier 429s under concurrent reviewers; throttle it (0 = unbounded for the rest).
val coderCfg          = Connectors.coderFromEnv()
val reviewParallelism = if coderCfg.connectorId == ConnectorId.GeminiCli then 1 else 0

flow(args, coder = coderCfg, defaultPrompt = Some("Add a multiply function to the calculator crate")):
  val planPath = Plan.defaultPath(userPrompt)
  val format   = Formatter.step(sys.env.get("LLM4ZIO_FORMAT"), workDir)
  val lint     = sys.env.get("LLM4ZIO_LINT").map(c => Reviewers.lintCommand(List("bash", "-c", c), workDir))
  for
    plan      <- stage("Plan (review + brief)") {
                   PlanStore.recoverOrCreate(planPath) {
                     Planner.from(reasoning, userPrompt).reviewed(reasoning).briefed(reasoning, userPrompt)
                   }
                 }
    _         <- stage("Branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   for
                     _ <- coderChat.ask(plan.taskPrompt(task))
                     _ <- reviewAndFixLoop(
                            Reviewers.all,
                            reasoning,
                            coderChat,
                            task.title,
                            git.diff,
                            parallelism = reviewParallelism,
                            lint = lint,
                            format = format,
                          )
                     _ <- format // format once more before committing
                     _ <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
                   yield ()
                 }
  yield ()
