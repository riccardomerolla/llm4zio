//> using dep "io.github.riccardomerolla::llm4zio-runner:3.11.1"
//> using scala "3.8.3"
//> using jvm 21

/** A phased delivery pipeline — the full lifecycle as five gated stages.
  *
  * Where `sdd.sc` writes every acceptance test up front and grinds the whole suite
  * green, this script runs the lifecycle outside-in, one scenario at a time:
  *
  *   Specify → Design → Acceptance → Implement → Verify
  *
  *   - Specify (ProModel, read-only): turn the request into numbered, testable
  *     acceptance criteria (Given/When/Then). The runtime — not an agent — writes and
  *     commits `specs/<epicId>.md`.
  *   - Design (ProModel, read-only): a short architecture note (components, data model,
  *     key decisions). The runtime writes and commits `docs/design-<epicId>.md`.
  *     Spec + design together ride as the plan brief, so every task prompt carries them
  *     and a crashed run resumes with them.
  *   - Acceptance (FlashModel): encode ALL criteria as JUnit tests plus the minimal API
  *     surface they call (stub bodies that compile but do nothing). Every test is
  *     @Disabled except the first. The suite must compile (`mvn -q test-compile`) and the
  *     enabled test must be RED — a green test here encodes nothing, so the flow fails.
  *   - Implement (FlashModel): one task PER acceptance scenario. Each task un-disables its
  *     scenario and drives production code until `mvn -q test` is green, keeping every
  *     already-enabled scenario green. One scenario lands per commit (the outer loop);
  *     the coder's own edit/run cycles are the inner loop. Implement review rounds add the
  *     opt-in `Reviewers.tddDiscipline` lens (test integrity, wiring, no fixture theater).
  *   - Verify: rerun the full suite and assert no scenario was left @Disabled — the
  *     definition of done is "every criterion is green and none was skipped".
  *
  * The gates are code, not prompts: build/test commands decide pass/fail, the RED check
  * rejects vacuous tests, and the final verify fails the flow unless the spec is satisfied.
  *
  * The coder follows a bundled, library-defined `craftSkill` (test integrity, real wiring, domain
  * language, effect shape) — methodology carried in the script, not an ambient agent skill.
  *
  * Seats: default to all-gemini, per-role (Pro reasoning, Flash coder + reviewers). Set
  * LLM4ZIO_CODER=claude|codex|gemini|pi to run the WHOLE pipeline — reasoning, coder, and
  * reviewers — on that one provider instead (handy when gemini is unavailable). Implement review
  * rounds add the opt-in `Reviewers.tddDiscipline` lens (llm4zio 3.5.0+).
  *
  * Seed a starter:  examples/seed.sh pipeline
  * Run:             scala-cli run pipeline.sc -- "Support hashtags: 'add <text> #tag' stores tags, 'list --tag <tag>' filters, and a 'tags' command lists every tag with its count"
  *
  * Requires `gemini` logged in (plus the agent for LLM4ZIO_CODER if set) and `mvn` on PATH.
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.{ IO, UIO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

// The two models behind the three seats. Edit freely.
val ProModel   = "gemini-3-pro-preview"
val FlashModel = "gemini-2.5-flash"

// A library-defined skill bundled in the script (not an ambient codex/claude/IDE skill): the
// test-first delivery methodology every coder turn follows. Prepended to the coder's system prompt.
val craftSkill =
  """SKILL — test-first delivery. Apply this on every turn:
    |- Outside-in: the committed spec's acceptance criteria define "done"; make them pass without weakening them.
    |- Keep tests honest: never weaken, delete, or disable a test to reach green; test setup establishes
    |  preconditions, never the expected answer; a test must fail before the code exists and pass after.
    |- Wire it for real: "done" means the production code changed, not just tests, and the behaviour is
    |  reachable through the real entry points — not proven by a stubbed or self-satisfying fixture.
    |- Speak the domain: tests, scenario names, and criteria use business vocabulary and concrete, named
    |  values — not status codes, internal method names, or vague terms.
    |- Mind effects: keep a pure core with side effects at the edges; an operation that claims to be
    |  read-only must not mutate, and a dry-run/preview returns a plan rather than performing the change.
    |- Output only the change plus a terminating test run — no narration.""".stripMargin

val specInstructions =
  """You are a specification writer. Turn the change request into a precise spec for this repo:
    |context, goals, non-goals, and a numbered list of testable acceptance criteria
    |(Given/When/Then) in the project's domain vocabulary, each with at least one concrete, named
    |example. Explore the repo as needed. Plain Markdown, no task list — the plan comes later,
    |from this spec.""".stripMargin

val designInstructions =
  """You are a software architect. From the spec below, write a SHORT design note (about one
    |page): the classes/files to touch, the data model, and the key decisions — each with the
    |alternatives considered and its consequences. Note the effect shape of the main operations
    |(pure, bounded change, or preview-returns-a-plan) and any risks. Keep statements about
    |behaviour observable, not about internal methods. Plain Markdown — write no code and no
    |tests.""".stripMargin

val planInstructions =
  Planner.defaultInstructions +
    "\n\nThe request below is a SPEC with numbered acceptance criteria. Produce EXACTLY one task" +
    "\nper acceptance criterion, in order; each task's title names the scenario and its" +
    "\ndescription says to make that one scenario pass. Do NOT add a task for writing tests —" +
    "\nthe acceptance tests are authored separately, before implementation begins."

val acceptanceInstructions =
  """Author the acceptance tests for this feature as JUnit 5 tests under src/test/java — ONE
    |@Test method per numbered acceptance criterion in the spec, each method named after its
    |scenario in the project's domain vocabulary with concrete, named values, and covering the
    |error and boundary criteria, not only the happy path. Also add the MINIMAL production API
    |surface the tests call (method signatures
    |with stub bodies that compile but do not implement the behaviour) so the suite compiles
    |and the enabled test fails for the right reason. Annotate EVERY test with
    |@org.junit.jupiter.api.Disabled EXCEPT the first criterion — leave that one enabled.
    |Write NO real implementation. The committed spec is the contract:

""".stripMargin

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

def exists(path: Path): UIO[Boolean] =
  ZIO.attemptBlocking(Files.exists(path)).orElseSucceed(false)

// True if any test file under `dir` still carries an @Disabled annotation — i.e. a scenario
// the Implement loop never enabled. The "definition of done" check for the Verify stage.
def disabledRemain(dir: Path): UIO[Boolean] =
  ZIO
    .attemptBlocking {
      Files.exists(dir) && {
        val tree = Files.walk(dir)
        try
          tree.iterator().asScala.exists { p =>
            Files.isRegularFile(p) &&
            new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains("@Disabled")
          }
        finally tree.close()
      }
    }
    .orElseSucceed(false)

// Seats default to all-gemini, per-role (Pro reasoning, Flash coder + reviewers). Set
// LLM4ZIO_CODER=claude|codex|gemini|pi to run the WHOLE pipeline — reasoning, coder, AND
// reviewers — on that one provider (its own default model); reasoning + reviewers stay read-only.
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
    "Support hashtags: 'add <text> #tag' stores tags, 'list --tag <tag>' filters, and a 'tags' command lists every tag with its count"
  ),
):
  val planPath  = Plan.defaultPath(userPrompt)
  val testGate  = Reviewers.lintCommand(List("mvn", "-q", "test"), workDir)
  val buildGate = Reviewers.lintCommand(List("mvn", "-q", "test-compile"), workDir)
  val reviewSvc = reviewers.headOption.getOrElse(reasoning)
  // Implement rounds add the opt-in TDD-discipline lens (test integrity, wiring, no fixture
  // theater) on top of the minimal roster. Acceptance keeps the minimal roster — its
  // "production code changed" check would misfire in the tests-only phase.
  val implementLenses = Reviewers.minimal :+ Reviewers.tddDiscipline

  for
    plan      <- PlanStore.recoverOrCreate(planPath) {
                   for
                     spec   <- stage("Specify")(Planner.brief(reasoning, userPrompt, specInstructions))
                     design <- stage("Design")(Planner.brief(reasoning, spec, designInstructions))
                     p      <- Planner.from(reasoning, spec, planInstructions)
                   yield p.copy(brief = Some(s"$spec\n\n## Design\n\n$design"))
                 }
    _         <- stage("Branch")(git.checkoutOrCreate(plan.epicId))
    _         <- stage("Commit spec + design") {
                   val parts  = plan.brief.getOrElse("").split("\n\n## Design\n\n", 2)
                   val specMd = parts.headOption.getOrElse("")
                   val designMd = parts.lift(1).getOrElse("")
                   for
                     a <- writeIfAbsent(workDir.resolve(s"specs/${plan.epicId}.md"), specMd)
                     b <- writeIfAbsent(workDir.resolve(s"docs/design-${plan.epicId}.md"), designMd)
                     _ <- ZIO.when(a || b)(git.commitAll(s"${plan.epicId}: spec + design")).unit
                   yield ()
                 }
    coderChat <- Chat.start(
                   coder,
                   system = Some(
                     craftSkill +
                       "\n\nImplement one task at a time in the current repo; the committed spec is the contract."
                   ),
                 )
    _         <- stage("Acceptance") {
                   exists(workDir.resolve("src/test")).flatMap {
                     case true  => ZIO.unit // resuming — tests already authored
                     case false =>
                       for
                         _ <- coderChat.ask(acceptanceInstructions + plan.brief.getOrElse(""))
                         _ <- reviewAndFixLoop(
                                Reviewers.minimal,
                                reviewSvc,
                                coderChat,
                                "acceptance tests",
                                git.diffAll,
                                lint = Some(buildGate),
                                parallelism = 1, // gemini's free tier 429s under concurrent reviewers
                              )
                         _ <- testGate.flatMap { r =>
                                ZIO.when(r.isClean)(
                                  fail("the acceptance tests pass before any implementation — they encode nothing")
                                )
                              }
                         _ <- git.commitAll(s"${plan.epicId}: acceptance tests (red)").unit
                       yield ()
                   }
                 }
    _         <- implementTaskLoop(planPath, plan) { task =>
                   for
                     _ <- coderChat.ask(
                            s"Enable the acceptance test for this scenario (remove its @Disabled) and implement the" +
                              s" production code until it passes, keeping every already-enabled scenario green. Do not" +
                              s" weaken or delete any test.\n\n${plan.taskPrompt(task)}"
                          )
                     _ <- reviewAndFixLoop(
                            implementLenses,
                            reviewSvc,
                            coderChat,
                            task.title,
                            git.diffAll,
                            lint = Some(testGate),
                            parallelism = 1,
                          )
                     _ <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
                   yield ()
                 }
    _         <- stage("Verify") {
                   for
                     r <- testGate
                     _ <- ZIO.when(!r.isClean)(
                            fail(
                              s"acceptance criteria not met:\n${r.issues.map(i => s"${i.title}\n${i.description}".strip).mkString("\n\n")}"
                            )
                          )
                     left <- disabledRemain(workDir.resolve("src/test"))
                     _    <- ZIO.when(left)(
                               fail("a scenario was left @Disabled — not every criterion landed")
                             )
                   yield ()
                 }
  yield ()
