//> using dep "io.github.riccardomerolla::llm4zio-runner:3.12.0"
//> using scala "3.8.3"
//> using jvm 21

/** LLM-as-a-Judge as an in-flow quality GATE — Layer 2 of the three-layer eval model.
  *
  * Same shape as `implement.sc` (plan → branch → implement each task → commit), but the
  * per-task quality mechanism is a *score-driven fix loop* instead of `reviewAndFixLoop`:
  * after the coder implements a task, a `Judge` scores the task's diff on three 0–2
  * dimensions — correctness / scope / safety. While any dimension is below the bar
  * (`Bar = 2`, i.e. full marks), the sub-bar dimensions' reasoning is fed back to the
  * coder to revise; it re-judges, bounded by `maxRounds = 3`, and FAILS the flow if the
  * bar is never cleared (the diff stays uncommitted, for inspection).
  *
  * `reviewAndFixLoop` *finds and fixes issues*; this `Judge` loop *scores the result
  * against a numeric bar and gates the commit*. The Judge runs on `reasoning` (the
  * coder's read-only twin) — pick the backend with LLM4ZIO_CODER=claude|codex|gemini|pi
  * (default claude). No API key — one CLI login is enough.
  *
  * One-line swaps:
  *   - This REPLACES `reviewAndFixLoop`. To AUGMENT instead, run `reviewAndFixLoop` first
  *     (issue-fixing) and keep this loop as an extra numeric bar.
  *   - Layer 1 deterministic `Checks` (e.g. `noPii`) are intentionally omitted here — their
  *     best-effort regexes false-positive on code diffs. See `judge-suite.sc` for Layer 1.
  *   - On exhaustion this calls `fail(...)`; swap it for an advisory `FlowEvent.Info`, or a
  *     human-in-the-loop approval, if you'd rather not hard-stop.
  *   - The inline `judgeAndFixLoop` is a script-local pattern; a reusable
  *     `flow.judgeAndFixLoop` is planned with the eval layer's behavioural (Layer 3) follow-on.
  *   - Stays local (no PR). See `implement-enhanced-pr.sc` for the push/PR step.
  *
  * Seed a starter:  examples/seed.sh judge-gate
  * Run:             scala-cli run judge-gate.sc -- "Add multiply and divide; divide errors on divide-by-zero"
  */

import zio.IO

import llm4zio.eval.*
import llm4zio.flow.*
import llm4zio.runner.*

val Bar = 2

val dimensions = List(
  Dimension("correctness", "Does the change correctly implement what the request asked for?"),
  Dimension("scope", "Does the change stay within the request — no unrelated or unrequested edits?"),
  Dimension("safety", "Is the change free of secrets, PII, and destructive or unsafe operations?"),
)

/** Revision prompt for the dimensions that scored below the bar. */
def fixPrompt(below: List[DimensionScore]): String =
  val rubric = dimensions.map(d => d.name -> d.rubric).toMap
  val lines  = below
    .map(d => s"- ${d.name} (scored ${d.score}/$Bar — ${rubric.getOrElse(d.name, "")}): ${d.reasoning}")
    .mkString("\n")
  s"""A reviewer scored your change below the quality bar on these dimensions.
     |Raise each to $Bar/$Bar, then stop:
     |$lines""".stripMargin

/** Judge the current diff; while any dimension is below the bar, feed the reasoning back to
  * the coder and re-judge, up to `maxRounds`. Fails the flow if the bar is never cleared.
  */
def judgeAndFixLoop(
  coder: Chat,
  judge: Evaluator[Sample],
  taskTitle: String,
  request: String,
  maxRounds: Int = 3,
)(using ctx: FlowContext
): IO[FlowError, Unit] =
  def round(n: Int): IO[FlowError, Unit] =
    for
      diff   <- git.diffAll
      result <- judge
                  .evaluate(Sample(response = diff, query = Some(request), expected = Some(taskTitle)))
                  .mapError(e => FlowError.Llm(e.message, Some(e)))
      below   = result.scores.filter(_.score < Bar)
      _      <- if below.isEmpty then ctx.events.publish(FlowEvent.Info(s"judge: '$taskTitle' cleared the bar"))
                else if n >= maxRounds then
                  fail(
                    s"judge gate not cleared after $maxRounds round(s):\n" +
                      below.map(d => s"- ${d.name} ${d.score}/$Bar: ${d.reasoning}").mkString("\n")
                  )
                else
                  ctx.events.publish(
                    FlowEvent.Info(s"judge round $n: ${below.map(_.name).mkString(", ")} below bar — revising")
                  ) *> coder.ask(fixPrompt(below)) *> round(n + 1)
    yield ()
  round(1)

flow(
  args,
  defaultPrompt = Some(
    "Add `multiply` and `divide` functions to the calculator crate; `divide` must return an error on divide-by-zero"
  ),
):
  val judge    = Judge.of(reasoning, dimensions)
  val planPath = Plan.defaultPath(userPrompt)
  for
    plan      <- PlanStore.recoverOrCreate(planPath)(Planner.from(reasoning, userPrompt))
    _         <- stage("branch")(git.checkoutOrCreate(plan.epicId))
    coderChat <- Chat.start(coder, system = Some("You implement one task at a time in the current repo."))
    _         <- implementTaskLoop(planPath, plan) { task =>
                   coderChat.ask(task.description) *>
                     judgeAndFixLoop(coderChat, judge, task.title, userPrompt) *>
                     git.commitAll(s"${plan.epicId}: ${task.title}").unit
                 }
  yield ()
