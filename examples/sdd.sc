//> using dep "io.github.riccardomerolla::llm4zio-runner:4.3.0"
//> using scala "3.8.3"
//> using jvm 21

/** Spec-Driven Development with an engineered harness — the all-gemini, per-role-model example.
  *
  * The cycle is Spec → Tests → Implement → Verify, with the roles split across models
  * (the ids are the vals below — point them at whatever your `gemini` CLI offers):
  *
  *   - Specifier + planner (ProModel, read-only): write a spec with numbered, testable
  *     acceptance criteria, then derive the plan FROM the spec. The runtime — not an agent —
  *     writes and commits `specs/<epicId>.md`; the spec also rides as the plan brief, so every
  *     task prompt carries it and a crashed run resumes with it.
  *   - Coder (FlashModel): task 1 encodes the acceptance criteria as JUnit tests; later tasks
  *     implement production code until they pass.
  *   - Review lenses (FlashModel, read-only): per-task review-and-fix rounds.
  *
  * Harness engineering: the gates are code, not prompts. The tests task gates on
  * `mvn -q test-compile` and must come out RED (green tests encode nothing — the flow fails);
  * every later task gates on `mvn -q test` each review round; a final verify stage reruns the
  * suite and fails the flow unless the spec's criteria are green.
  *
  * Seed a starter:  examples/seed.sh sdd
  * Run:             scala-cli run sdd.sc -- "Add due dates: 'add <text> --due YYYY-MM-DD', mark overdue items in 'list', and a 'due' command showing items due today"
  *
  * Requires `gemini` logged in and `mvn` on PATH.
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.{ IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

// The runtime capability mint for a script: `flow(...)`'s own `Caps.All` given is scoped to the lambda passed to it,
// so top-level `def`s in this file (which call `git.*`) need their own. Static witness only — the ambient `Grants`
// FiberRef still gates every call at runtime, so this widens nothing. `Caps.grantAll` is package-private, so a
// script uses the documented public hatch `Caps.unsafe.all` — deliberately loud and greppable.
given llm4zio.flow.Caps.All = zio.Unsafe.unsafe(implicit u => llm4zio.flow.Caps.unsafe.all)


// The two models behind the three seats. Edit freely.
val ProModel   = "gemini-3-pro-preview"
val FlashModel = "gemini-2.5-flash"

val specInstructions =
  """You are a specification writer. Turn the change request into a precise spec for this repo:
    |context, goals, non-goals, and a numbered list of testable acceptance criteria
    |(Given/When/Then). Explore the repo as needed. Plain Markdown, no task list — the plan
    |comes later, from this spec.""".stripMargin

val planInstructions =
  Planner.defaultInstructions +
    "\n\nThe request below is a SPEC with numbered acceptance criteria. The FIRST task must be" +
    "\nexactly: encode the acceptance criteria as JUnit 5 tests (no production code). Every" +
    "\nlater task implements production code towards making those tests pass."

def writeIfAbsent(path: Path, content: String): IO[FlowError, Boolean] =
  ZIO
    .attemptBlocking {
      if Files.exists(path) then false
      else
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.write(path, content.getBytes(StandardCharsets.UTF_8))
        true
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

// Seats default to all-gemini, per-role (Pro reasoning, Flash coder + reviewers). Set
// LLM4ZIO_CODER=claude|codex|gemini|pi to run the WHOLE flow — reasoning, coder, AND reviewers —
// on that one provider (its own default model); reasoning + reviewers stay read-only.
val (coderCfg, reasoningCfg, reviewerCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") =>
      (
        gemini.withModel(FlashModel),
        gemini.withModel(ProModel).copy(readOnly = true),
        gemini.withModel(FlashModel).copy(readOnly = true),
      )
    case Some(_) =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true), agent.copy(readOnly = true))

flow(
  args,
  coder = coderCfg,
  reasoning = Some(reasoningCfg),
  reviewers = List(reviewerCfg),
  defaultPrompt = Some(
    "Add due dates: 'add <text> --due YYYY-MM-DD', mark overdue items in 'list', and a 'due' command showing items due today"
  ),
):
  val planPath  = Plan.defaultPath(userPrompt)
  val testGate  = Reviewers.lintCommand(List("mvn", "-q", "test"), workDir)
  val buildGate = Reviewers.lintCommand(List("mvn", "-q", "test-compile"), workDir)
  val reviewSvc = reviewers.headOption.getOrElse(reasoning)

  for
    plan      <- stage("Spec + plan") {
                   PlanStore.recoverOrCreate(planPath) {
                     for
                       spec <- Planner.brief(reasoning, userPrompt, specInstructions)
                       p    <- Planner.from(reasoning, spec, planInstructions)
                     yield p.copy(brief = Some(spec))
                   }
                 }
    _         <- stage("Branch")(git.checkoutOrCreate(plan.epicId))
    _         <- stage("Commit spec") {
                   writeIfAbsent(workDir.resolve(s"specs/${plan.epicId}.md"), plan.brief.getOrElse("")).flatMap {
                     wrote => ZIO.when(wrote)(git.commitAll(s"${plan.epicId}: spec")).unit
                   }
                 }
    coderChat <- Chat.start(
                   coder,
                   system = Some(
                     "You implement one task at a time in the current repo. The committed spec is the contract;" +
                       " the tests encode it — make them pass without weakening them."
                   ),
                 )
    _         <- implementTaskLoop(planPath, plan) { task =>
                   val testsTask = plan.tasks.headOption.contains(task)
                   for
                     _ <- coderChat.ask(plan.taskPrompt(task))
                     _ <- reviewAndFixLoop(
                            Reviewers.minimal,
                            reviewSvc,
                            coderChat,
                            task.title,
                            git.diffAll,
                            lint = Some(if testsTask then buildGate else testGate),
                            parallelism = 1, // gemini's free tier 429s under concurrent reviewers
                          )
                     _ <- ZIO.when(testsTask) {
                            testGate.flatMap { r =>
                              ZIO.when(r.isClean)(
                                fail("the new tests pass before any implementation — they encode nothing")
                              )
                            }
                          }
                     _ <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
                   yield ()
                 }
    _         <- stage("Verify")(
                   testGate.flatMap { r =>
                     if r.isClean then ZIO.unit
                     else
                       fail(
                         s"acceptance criteria not met:\n${r.issues.map(i => s"${i.title}\n${i.description}".strip).mkString("\n\n")}"
                       )
                   }
                 )
  yield ()
