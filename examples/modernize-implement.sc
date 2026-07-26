//> using dep "io.github.riccardomerolla::llm4zio-runner:3.23.0"
//> using scala "3.8.3"
//> using jvm 21

/** Legacy-modernization phase 3 of 5: implement the seeded plan, gated until green.
  *
  *   modernize-extract.sc → (human approves) → modernize-seed.sc → modernize-implement.sc
  *     → modernize-verify.sc → modernize-review.sc
  *
  * The clean-room wall is ENFORCED here: the flow refuses to start if anything in the target
  * workspace matches the pack's legacy `sources:` regex — the coder is driven by specs alone.
  *
  * Runs ROOTED AT THE TARGET REPO (`--repo <target>`), resuming the plan modernize-seed.sc
  * committed at `docs/modernization/plan.md` (PlanStore — a crashed or halted run picks up at the
  * first incomplete task; task completion is persisted into the committed plan file itself, so
  * progress is auditable in git history).
  *
  * The sdd.sc harness, pack-parameterized:
  *   - Task 1 encodes the seeded BDD scenarios as FAILING acceptance tests — gated on the pack's
  *     `build` command, then required RED (green new tests encode nothing → the flow fails).
  *   - Every later task implements toward green: `reviewAndFixLoop` runs the pack's `test`
  *     command as the lint gate plus `Reviewers.minimal` and the PACK'S OWN LENSES (e.g.
  *     cobol-fidelity: BigDecimal/HALF_UP, validation order, reason codes) on the flash reviewer.
  *   - One commit per task; non-convergence halts with the plan persisted — fix or rerun.
  *   - Final gate: the pack's `verify` command must be clean, then an LLM-as-a-Judge on
  *     `reasoning` scores the WHOLE branch diff against the committed spec pack
  *     (spec-compliance + scenario-coverage, full marks or the flow fails after a bounded
  *     feedback round).
  *   - Push + PR: Azure DevOps when configured, GitHub otherwise; a missing forge/remote
  *     degrades to an Info event so the local demo still completes.
  *
  * Run:  scala-cli run modernize-implement.sc -- --repo ~/services/meridian-transfers
  */

import zio.{ IO, ZIO }

import llm4zio.eval.*
import llm4zio.flow.*
import llm4zio.runner.*

val ProModel    = "gemini-2.5-pro"   // point these at whatever your `gemini` CLI offers
val FlashModel  = "gemini-3.5-flash"
val JudgeRounds = 2
val ModDir      = "docs/modernization"

/** The enforced clean-room wall: refuse to run with legacy source inside the target workspace, so the
  * coder provably works from the specs alone.
  */
def assertWall(pack: Pack, root: java.nio.file.Path, events: FlowEvents): IO[FlowError, Unit] =
  pack.sources match
    case None        => events.publish(FlowEvent.Info("pack has no sources regex — wall check skipped"))
    case Some(regex) =>
      Wall.check(root, regex).flatMap {
        case Wall.Result.Clean           =>
          events.publish(FlowEvent.Info("clean-room wall: no legacy source in the target workspace"))
        case Wall.Result.Breached(paths) =>
          val shown = paths.take(10).mkString(", ")
          val more  = if paths.size > 10 then s" (+${paths.size - 10} more)" else ""
          ZIO.fail(FlowError.Aborted(
            s"clean-room wall breached — legacy source inside the target workspace: $shown$more. " +
              "The implementation must be driven by the specs alone; remove the files and rerun."
          ))
      }

val (coderCfg, reasoningCfg, reviewerCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") =>
      (gemini, gemini.withModel(ProModel).copy(readOnly = true), gemini.withModel(FlashModel).copy(readOnly = true))
    case Some(_)               =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true), agent.copy(readOnly = true))

val complianceDims = List(
  Dimension(
    "spec-compliance",
    "Does the implementation satisfy every rule in the committed specs — exact values, validation order, " +
      "error paths — without weakening, deleting, or loosening any test or scenario?",
  ),
  Dimension(
    "scenario-coverage",
    "Is every BDD scenario in the seeded feature files exercised by an acceptance test in this diff?",
  ),
)

def judgeFeedback(below: List[DimensionScore]): String =
  val lines = below.map(d => s"- ${d.name} (${d.score}/2): ${d.reasoning}").mkString("\n")
  s"""The final spec-compliance review scored the branch below the bar. Close these gaps without
     |weakening any test, then stop:
     |$lines""".stripMargin

/** Concatenate the committed specs + features — the judge's contract text. */
def gatherSpecs(root: java.nio.file.Path, specsDir: String): IO[FlowError, String] =
  ZIO
    .attemptBlocking {
      import scala.jdk.CollectionConverters.*
      val dir = root.resolve(specsDir)
      if !java.nio.file.Files.isDirectory(dir) then ""
      else
        val stream = java.nio.file.Files.walk(dir)
        try
          stream
            .iterator()
            .asScala
            .filter(java.nio.file.Files.isRegularFile(_))
            .toList
            .sortBy(_.toString)
            .map(f => s"===== ${root.relativize(f)} =====\n${java.nio.file.Files.readString(f)}")
            .mkString("\n\n")
        finally stream.close()
    }
    .mapError(e => FlowError.Persistence("failed to read the committed specs", Some(e)))

/** Judge the branch diff against the committed specs; feed sub-bar reasoning back to the coder,
  * re-verify, re-judge — bounded by `JudgeRounds`, failing the flow if the bar is never cleared.
  */
def specComplianceLoop(
  coderChat: Chat,
  judge: Evaluator[Sample],
  verGate: IO[FlowError, ReviewResult],
  specText: String,
  epicId: String,
)(using ctx: FlowContext
): IO[FlowError, Unit] =
  def round(n: Int): IO[FlowError, Unit] =
    for
      base   <- git.defaultBase
      diff   <- git.diffVsBase(base)
      result <- judge
                  .evaluate(Sample(response = diff, context = Some(specText), query = Some(userPrompt)))
                  .mapError(e => FlowError.Llm(e.message, Some(e)))
      below   = result.scores.filter(_.score < 2)
      _      <- if below.isEmpty then ctx.events.publish(FlowEvent.Info("spec-compliance judge: branch cleared the bar"))
                else if n >= JudgeRounds then
                  fail(
                    s"spec-compliance judge not cleared after $JudgeRounds round(s):\n" +
                      below.map(d => s"- ${d.name} ${d.score}/2: ${d.reasoning}").mkString("\n")
                  )
                else
                  coderChat.ask(judgeFeedback(below)) *>
                    verGate.flatMap(r =>
                      ZIO.unless(r.isClean)(fail("verify gate broke while addressing judge feedback")).unit
                    ) *>
                    git.commitAll(s"$epicId: address spec-compliance feedback").unit *>
                    round(n + 1)
    yield ()
  round(1)

flow(
  args,
  coder = coderCfg,
  reasoning = Some(reasoningCfg),
  reviewers = List(reviewerCfg),
  defaultPrompt = Some("Implement the seeded modernization plan"),
):
  val packDir   = workspace.resolve(sys.env.getOrElse("LLM4ZIO_PACK", "packs/cobol-springboot"))
  val planFile  = workDir.resolve(ModDir).resolve("plan.md")
  val events    = summon[FlowEvents]
  val reviewSvc = reviewers.headOption.getOrElse(reasoning)

  for
    pack     <- stage("Pack")(Pack.load(packDir))
    _        <- stage("Wall")(assertWall(pack, workDir, events))
    plan     <- PlanStore
                  .load(planFile)
                  .someOrFail(FlowError.Aborted(s"no plan at $planFile — run modernize-seed.sc first"))
    buildGate = pack.gate("build").fold(ZIO.succeed(ReviewResult(Nil)))(Reviewers.lintCommand(_, workDir))
    testGate  = pack.gate("test").fold(ZIO.succeed(ReviewResult(Nil)))(Reviewers.lintCommand(_, workDir))
    verGate   = pack.gate("verify").orElse(pack.gate("test")).fold(ZIO.succeed(ReviewResult(Nil)))(
                  Reviewers.lintCommand(_, workDir)
                )
    _        <- stage("Branch")(git.checkoutOrCreate(plan.epicId))
    system    = List(
                  pack.prompt("implement"),
                  pack.lessons.map(l => s"Lessons from previous modernization runs — apply them:\n$l"),
                ).flatten.mkString("\n\n")
    coderChat <- Chat.start(coder, system = Some(system))
    _         <- implementTaskLoop(planFile, plan) { task =>
                   val testsTask = plan.tasks.headOption.contains(task)
                   for
                     _ <- coderChat.ask(plan.taskPrompt(task))
                     _ <- reviewAndFixLoop(
                            Reviewers.minimal ++ pack.lenses,
                            reviewSvc,
                            coderChat,
                            task.title,
                            git.diffAll,
                            lint = Some(if testsTask then buildGate else testGate),
                            parallelism = 1, // gemini free tier 429s under concurrent reviewers
                          )
                     _ <- ZIO.when(testsTask) {
                            testGate.flatMap { r =>
                              ZIO.when(r.isClean)(
                                fail("the new acceptance tests pass before any implementation — they encode nothing")
                              )
                            }
                          }
                     _ <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
                   yield ()
                 }
    _         <- stage("Verify") {
                   verGate.flatMap { r =>
                     if r.isClean then ZIO.unit
                     else
                       fail(
                         s"verify gate failed:\n${r.issues.map(i => s"${i.title}\n${i.description}".strip).mkString("\n\n")}"
                       )
                   }
                 }
    specText  <- gatherSpecs(workDir, pack.specsDir)
    _         <- stage("Judge")(
                   specComplianceLoop(coderChat, Judge.of(reasoning, complianceDims), verGate, specText, plan.epicId)
                 )
    _         <- stage("Publish") {
                   val push = git.push("origin", plan.epicId)
                   val pr   = Ado.configFrom(sys.env) match
                     case Right(_) =>
                       Ado.withTool() { ado =>
                         ado
                           .createPr(
                             s"refs/heads/${plan.epicId}",
                             "refs/heads/main",
                             s"modernize: ${plan.epicId}",
                             s"Implements the approved spec pack. Plan: $ModDir/plan.md — all gates green.",
                           )
                           .flatMap(p => events.publish(FlowEvent.Info(s"ADO PR: ${p.webUrl}")))
                       }
                     case Left(_)  =>
                       gh.createPr(
                         plan.epicId,
                         body = s"Implements the approved spec pack. Plan: $ModDir/plan.md — all gates green.",
                         base = Some("main"),
                       ).flatMap(p => events.publish(FlowEvent.Info(s"PR: ${p.url}")))
                   (push *> pr).catchAll(e =>
                     events.publish(FlowEvent.Info(s"publish skipped (no remote/forge configured): ${e.message}"))
                   )
                 }
  yield plan.epicId
