//> using dep "io.github.riccardomerolla::llm4zio-runner:3.15.0"
//> using scala "3.8.3"
//> using jvm 21

/** Prompt → enhanced plan → PR, fully autonomous — the ZIO-native counterpart of
  * orca's `implement-enhanced` branch-PR flow.
  *
  * The same enhanced backbone as `implement-enhanced.sc` (plan self-review +
  * shared codebase brief, persisted to `.llm4zio/plan-<epicId>.md`), but instead
  * of stopping at commits it pushes the epic branch and opens a pull request:
  * branch → implement each task with the review loop → push → summarise the
  * branch-vs-base diff → open the PR → return to the starting branch.
  *
  * Unlike `implement-enhanced.sc` (which runs offline against a seeded crate),
  * this opens a real PR, so it needs a repo with a remote and `gh` authenticated.
  *
  * Run: scala-cli run implement-enhanced-pr.sc -- "Add a multiply function to the calculator crate"
  * Requires the chosen agent CLI logged in (`claude` by default) and `gh` authenticated, in a repo with a remote.
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
    start     <- git.currentBranch // return here at the end
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
    _         <- stage("Push branch")(git.push("origin", plan.epicId))
    base      <- git.defaultBase
    diff      <- git.diffVsBase(base)
    summary   <- stage("Summarise PR")(summarisePr(reasoning, diff, context = Some(s"Implements: $userPrompt")))
    _         <- stage("Open PR")(gh.createPr(summary.title, summary.body, base = Some(base)))
    _         <- PlanStore.delete(planPath)
    _         <- stage(s"Return to $start")(git.checkout(start))
  yield ()
