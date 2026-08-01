//> using dep "io.github.riccardomerolla::llm4zio-runner:4.1.0"
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

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

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

def judgeFeedback(findings: ReviewResult): String =
  val lines = findings.issues.map(i => s"- ${i.title}: ${i.description}").mkString("\n")
  s"""The final spec-compliance review scored the branch below the bar. Close these gaps without
     |weakening any test, then stop:
     |$lines""".stripMargin

/** The spec'd programs: top-level `<NAME>.md` files under the pack's specs dir, indexes aside. */
def specPrograms(specsDir: Path): IO[FlowError, List[String]] =
  ZIO
    .attemptBlocking {
      if !Files.isDirectory(specsDir) then Nil
      else
        val stream = Files.list(specsDir)
        try
          stream
            .iterator()
            .asScala
            .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".md"))
            .map(_.getFileName.toString.stripSuffix(".md"))
            .filterNot(Set("traceability", "mapping", "README"))
            .toList
            .sorted
        finally stream.close()
    }
    .mapError(e => FlowError.Persistence(s"failed to list specs under $specsDir", Some(e)))

def readFileOr(path: Path, fallback: String): IO[FlowError, String] =
  ZIO
    .attemptBlocking(if Files.exists(path) then Files.readString(path) else fallback)
    .mapError(e => FlowError.Persistence(s"failed to read $path", Some(e)))

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

/** One bounded estate-wide pass: the traceability index plus the changed-file NAMES (never contents). Per-program
  * judging (`ProgramJudge` below) cannot see cross-program problems — a rule that moved between programs, a
  * scenario orphaned when two programs were merged — because each of its calls only ever sees one program's slice
  * of the diff. This pass is the compensating check. Carrying file names instead of their contents is what keeps
  * it affordable regardless of estate size: the traceability index already says what should live where, so the
  * judge only needs to see which files moved, not what's in them.
  */
def traceabilityPass(
  judge: Evaluator[Sample],
  dims: List[Dimension],
  specsDir: Path,
  base: String,
)(using ctx: FlowContext
): IO[FlowError, ReviewResult] =
  for
    trace   <- readFileOr(specsDir.resolve("traceability.md"), "")
    changed <- git.changedFilesVsBase(base)
    names    = changed.mkString("\n")
    result  <- Context.withShrink("judge[traceability]") { cap =>
                 for
                   t <- Context.capped("traceability", trace, cap)
                   r <- judge
                          .evaluate(Sample(
                            response = s"Files changed on this branch:\n$names",
                            context = Some(t),
                            query = Some(userPrompt),
                          ))
                          .mapError(e => FlowError.Llm(e.message, Some(e)))
                 yield r
               }
  yield
    val subBar = result.scores.filter(s => s.score < dims.find(_.name == s.name).fold(2)(_.maxScore))
    ReviewResult(
      subBar.map(s => ReviewIssue(Severity.Critical, s"judge[traceability]: ${s.name} scored ${s.score}", s.reasoning)),
      "judge:traceability",
    )

/** Per-program spec-compliance judging — mirrors `llm4zio.modernize.ProgramJudge`. That module isn't on this
  * script's classpath (only core/flow/runner/java are published; `modernize` is the operator-surface module), so
  * the logic is duplicated here rather than imported. A whole-branch judge call carries every spec and every diff
  * hunk in the estate — what blows a provider's input window. Judging one program at a time against only that
  * program's slice of the diff keeps each call small, and — wrapped in `ReviewCache` — makes the gate resumable.
  */
object ProgramJudge:

  /** Partition `changed` by which program's file regex matches. A file matching several programs is judged with
    * each of them; the remainder is returned separately for the unassigned pass.
    */
  def groupFiles(pack: Pack, programs: List[String], changed: List[String]): (Map[String, List[String]], List[String]) =
    val byProgram  = programs.map(p => p -> changed.filter(_.matches(pack.filesFor(p)))).toMap
    val assigned   = byProgram.values.flatten.toSet
    val unassigned = changed.filterNot(assigned.contains)
    (byProgram, unassigned)

  /** Judge every program whose files changed, plus one pass over the unassigned remainder. Each verdict is cached
    * at `gateDir/<NAME>.json`, fingerprinted over the spec, the diff slice, and the rubric it judged.
    */
  def judgeAll(
    pack: Pack,
    judge: Evaluator[Sample],
    dims: List[Dimension],
    gateDir: Path,
    base: String,
    programs: List[String],
    specFor: String => IO[FlowError, String],
    query: String,
  )(using ctx: FlowContext
  ): IO[FlowError, ReviewResult] =
    for
      changed              <- git.changedFilesVsBase(base)
      (byProgram, leftover) = groupFiles(pack, programs, changed)
      active                = programs.filter(p => byProgram.getOrElse(p, Nil).nonEmpty)
      perProgram           <- ZIO.foreach(active)(p =>
                                judgeOne(judge, dims, gateDir, base, p, byProgram(p), specFor, query)
                              )
      residual             <- ZIO.when(leftover.nonEmpty)(
                                judgeUnassigned(judge, dims, gateDir, base, leftover, specFor, programs, query)
                              )
    yield Reviewers.merge(perProgram ++ residual.toList)

  private def judgeOne(
    judge: Evaluator[Sample],
    dims: List[Dimension],
    gateDir: Path,
    base: String,
    program: String,
    files: List[String],
    specFor: String => IO[FlowError, String],
    query: String,
  )(using ctx: FlowContext
  ): IO[FlowError, ReviewResult] =
    for
      spec   <- specFor(program)
      diff   <- git.diffVsBase(base, files)
      rubric  = dims.map(d => s"${d.name} (0..${d.maxScore}): ${d.rubric}").mkString("\n")
      result <- ReviewCache.cached(gateDir.resolve(s"$program.json"), ReviewCache.fingerprint(spec, diff, rubric)) {
                  ctx.events.publish(FlowEvent.Info(s"judging $program")) *>
                    Context.withShrink(s"judge[$program]") { cap =>
                      for
                        s <- Context.capped(s"spec[$program]", spec, cap)
                        d <- Context.capped(s"diff[$program]", diff, cap)
                        r <- judge
                               .evaluate(Sample(response = d, context = Some(s), query = Some(query)))
                               .mapError(e => FlowError.Llm(e.message, Some(e)))
                      yield r
                    }.map(issues(_, dims, program))
                }
    yield result

  private def judgeUnassigned(
    judge: Evaluator[Sample],
    dims: List[Dimension],
    gateDir: Path,
    base: String,
    files: List[String],
    specFor: String => IO[FlowError, String],
    programs: List[String],
    query: String,
  )(using ctx: FlowContext
  ): IO[FlowError, ReviewResult] =
    for
      diff   <- git.diffVsBase(base, files)
      specs  <- ZIO.foreach(programs)(specFor).map(_.mkString("\n\n"))
      rubric  = dims.map(d => s"${d.name} (0..${d.maxScore}): ${d.rubric}").mkString("\n")
      result <- ReviewCache.cached(gateDir.resolve("unassigned.json"), ReviewCache.fingerprint(specs, diff, rubric)) {
                  ctx.events.publish(FlowEvent.Info(s"judging ${files.size} unassigned file(s)")) *>
                    Context.withShrink("judge[unassigned]") { cap =>
                      for
                        s <- Context.capped("spec[unassigned]", specs, cap)
                        d <- Context.capped("diff[unassigned]", diff, cap)
                        r <- judge
                               .evaluate(Sample(response = d, context = Some(s), query = Some(query)))
                               .mapError(e => FlowError.Llm(e.message, Some(e)))
                      yield r
                    }.map(issues(_, dims, "unassigned"))
                }
    yield result

  /** Sub-bar dimensions as Critical review issues, titled with the program they belong to. */
  private def issues(scored: EvalResult, dims: List[Dimension], program: String): ReviewResult =
    val subBar = scored.scores.filter(s => s.score < dims.find(_.name == s.name).fold(2)(_.maxScore))
    ReviewResult(
      subBar.map(s => ReviewIssue(Severity.Critical, s"judge[$program]: ${s.name} scored ${s.score}", s.reasoning)),
      s"judge:$program",
    )

/** Judge the branch diff against the committed specs: one `ProgramJudge` pass per program's own slice of the
  * diff, plus one bounded `traceabilityPass` over the whole estate. Feeds sub-bar reasoning back to the coder,
  * re-verifies, re-judges — bounded by `JudgeRounds`, failing the flow if the bar is never cleared.
  */
def specComplianceLoop(
  system: String,
  judge: Evaluator[Sample],
  verGate: IO[FlowError, ReviewResult],
  pack: Pack,
  epicId: String,
)(using ctx: FlowContext
): IO[FlowError, Unit] =
  val specsDir = workDir.resolve(pack.specsDir)
  val gateDir  = workDir.resolve(ModDir).resolve("gate")
  def round(n: Int): IO[FlowError, Unit] =
    for
      base     <- git.defaultBase
      programs <- specPrograms(specsDir)
      perProg  <- ProgramJudge.judgeAll(
                    pack,
                    judge,
                    complianceDims,
                    gateDir,
                    base,
                    programs,
                    p => readFileOr(specsDir.resolve(s"$p.md"), ""),
                    userPrompt,
                  )
      trace    <- traceabilityPass(judge, complianceDims, specsDir, base)
      merged    = Reviewers.merge(List(perProg, trace))
      _        <- if merged.isClean then ctx.events.publish(FlowEvent.Info("spec-compliance judge: branch cleared the bar"))
                  else if n >= JudgeRounds then
                    fail(
                      s"spec-compliance judge not cleared after $JudgeRounds round(s):\n" +
                        merged.issues.map(i => s"- ${i.title}: ${i.description}").mkString("\n")
                    )
                  else
                    Chat.start(coder, system = Some(system)).flatMap(_.ask(judgeFeedback(merged))) *>
                      verGate.flatMap(r =>
                        ZIO.unless(r.isClean)(fail("verify gate broke while addressing judge feedback")).unit
                      ) *>
                      git.commitAll(s"$epicId: address spec-compliance feedback").unit *>
                      round(n + 1)
    yield ()
  round(1)

/** Append this fiber's recorded context truncations to the manifest, so a verdict rendered on a partially-read spec
  * pack says so in the evidence chain rather than only in the console log. A repo seeded before provenance existed
  * simply has no manifest — skip rather than fail, the same way the verify flow does.
  */
def recordTruncations(manifest: Path): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking(Files.exists(manifest))
    .orDie
    .flatMap {
      case false => ZIO.unit
      case true  =>
        Context.truncations.flatMap { ts =>
          ZIO.when(ts.nonEmpty)(
            Provenance
              .extend(manifest)(p => p.copy(contextTruncations = p.contextTruncations ++ ts.map(_.render).toList))
              .unit
          )
        }.unit
    }

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
    cards    <- Patterns
                  .load(packDir.resolve("patterns"))
                  .zipWith(Patterns.load(workspace.resolve("patterns")))(_ ++ _)
    specText <- gatherSpecs(workDir, pack.specsDir)
    cited     = Patterns.tagged(specText) // extraction tagged the fragments; specs cite the ids
    playbook  = cards.filter(c => cited.contains(c.id))
    system    = List(
                  pack.prompt("implement"),
                  pack.lessons.map(l => s"Lessons from previous modernization runs — apply them:\n$l"),
                  Option.when(playbook.nonEmpty)(
                    "Pattern cards cited by the specs — the translation playbook (advisory, the specs win):\n\n" +
                      playbook.map(c => s"### ${c.id}\n${c.body}").mkString("\n\n")
                  ),
                ).flatten.mkString("\n\n")
    _         <- implementTaskLoop(planFile, plan) { task =>
                   val testsTask = plan.tasks.headOption.contains(task)
                   for
                     coderChat <- Chat.start(coder, system = Some(system))
                     _         <- coderChat.ask(plan.taskPrompt(task))
                     _         <- reviewAndFixLoop(
                                    Reviewers.minimal ++ pack.lenses,
                                    reviewSvc,
                                    coderChat,
                                    task.title,
                                    git.diffAll,
                                    lint = Some(if testsTask then buildGate else testGate),
                                    parallelism = 1, // gemini free tier 429s under concurrent reviewers
                                  )
                     _         <- ZIO.when(testsTask) {
                                    testGate.flatMap { r =>
                                      ZIO.when(r.isClean)(
                                        fail("the new acceptance tests pass before any implementation — they encode nothing")
                                      )
                                    }
                                  }
                     _         <- git.commitAll(s"${plan.epicId}: ${task.title}").unit
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
    _         <- stage("Judge")(
                   specComplianceLoop(system, Judge.of(reasoning, complianceDims), verGate, pack, plan.epicId)
                 )
    _         <- stage("Provenance")(recordTruncations(workDir.resolve(ModDir).resolve("provenance.json")))
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
