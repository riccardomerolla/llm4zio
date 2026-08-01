//> using dep "io.github.riccardomerolla::llm4zio-runner:4.1.0"
//> using scala "3.8.3"
//> using jvm 21

/** Legacy-modernization phase 5 of 5: review the delivered increment, produce fix specs, and
  * feed lessons back into the pack.
  *
  *   modernize-extract.sc → (human approves) → modernize-seed.sc → modernize-implement.sc
  *     → modernize-verify.sc → modernize-review.sc
  *
  * Runs ROOTED AT THE TARGET REPO (`--repo <target>`) on the implementation branch. This flow
  * FINDS and ROUTES — it does not fix:
  *
  *   1. The full reviewer roster (`Reviewers.all`) PLUS the pack's own lenses read the branch
  *      diff against the committed spec pack; an advisory judge quantifies spec compliance.
  *   2. `reasoning` distills the findings into three routed outputs:
  *      - FIX specs (implementation violates the spec) → documents under `docs/specs/fixes/`
  *        + appended as new tasks to `docs/modernization/plan.md` — rerunning
  *        modernize-implement.sc picks them up (the loop the gates promised).
  *      - IMPROVEMENTS (compliant but worth follow-up) → documents under `docs/specs/fixes/`.
  *      - LESSONS (generalizable to future runs) → `Pack.appendLesson` into the PACK's
  *        lessons.md — extract.sc and implement.sc inject lessons into their briefs, so the
  *        next modernization of this estate kind starts smarter. Commit the pack change like
  *        any other reviewed edit.
  *   3. Azure DevOps (optional): one work item per fix spec when ADO env vars are present.
  *
  * Run:  scala-cli run modernize-review.sc -- --repo ~/services/meridian-transfers
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.json.JsonCodec
import zio.{ IO, ZIO }

import llm4zio.core.SchemaDerivation
import llm4zio.eval.*
import llm4zio.flow.*
import llm4zio.runner.*

val ProModel   = "gemini-2.5-pro"   // point these at whatever your `gemini` CLI offers
val FlashModel = "gemini-3.5-flash"
val ModDir     = "docs/modernization"

/** The enforced clean-room wall: refuse to run with legacy source inside the target workspace. */
def assertWall(pack: Pack, root: Path, events: FlowEvents): IO[FlowError, Unit] =
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
              "The review must judge spec-driven work only; remove the files and rerun."
          ))
      }

val (coderCfg, reasoningCfg, reviewerCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") =>
      (gemini, gemini.withModel(ProModel).copy(readOnly = true), gemini.withModel(FlashModel).copy(readOnly = true))
    case Some(_)               =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true), agent.copy(readOnly = true))

/** One routed finding: a spec document plus the plan task that would address it. */
case class FixSpec(title: String, spec: String, taskTitle: String, taskDescription: String) derives JsonCodec

/** The distilled review: what must be fixed, what could be improved, what generalizes. */
case class ReviewOutcome(fixes: List[FixSpec], improvements: List[FixSpec], lessons: List[String]) derives JsonCodec

val complianceDims = List(
  Dimension(
    "spec-compliance",
    "Does the implementation satisfy every rule in the committed specs — exact values, validation order, " +
      "error paths — without weakening, deleting, or loosening any test or scenario?",
  ),
  Dimension(
    "scenario-coverage",
    "Is every BDD scenario in the seeded feature files exercised by an acceptance test on this branch?",
  ),
)

def writeFile(path: Path, content: String): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      ()
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

def gatherDir(root: Path, dir: Path): IO[FlowError, String] =
  ZIO
    .attemptBlocking {
      if !Files.isDirectory(dir) then ""
      else
        val stream = Files.walk(dir)
        try
          stream
            .iterator()
            .asScala
            .filter(Files.isRegularFile(_))
            .toList
            .sortBy(_.toString)
            .map(f => s"===== ${root.relativize(f)} =====\n${Files.readString(f)}")
            .mkString("\n\n")
        finally stream.close()
    }
    .mapError(e => FlowError.Persistence(s"failed to read $dir", Some(e)))

def slug(title: String): String =
  title.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-").take(60)

/** The spec'd programs: top-level `<NAME>.md` files under the pack's specs dir, indexes aside. Copied verbatim from
  * `ImplementFlow`/`VerifyFlow` — flows deliberately do not depend on each other.
  */
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

/** Distill the raw reviewer findings and per-program judge issues into routed fixes/improvements/lessons.
  *
  * Deliberately does NOT re-append the branch diff: `findings` and `scored` already quote the offending code
  * (`ReviewIssue.description`), so a third full copy of the same diff on top of them was pure waste — this phase used
  * to ship `specText` + `diff` once per lens, once to the judge, AND again here. If a future finding turns out to
  * need more surrounding context than its issue description carries, scope that at the source (a wider match in
  * `Reviewer.files`, or a bigger slice from `ProgramJudge`) rather than reintroducing the whole diff here.
  */
def distillPrompt(packReviewPrompt: String, findings: ReviewResult, scored: ReviewResult): String =
  val findingLines = findings.issues.map(i => s"- [${i.severity}] ${i.title}: ${i.description}").mkString("\n")
  val scoreLines   = scored.issues.map(i => s"- ${i.title}: ${i.description}").mkString("\n")
  s"""$packReviewPrompt
     |
     |Below are the raw reviewer findings and judge results for a modernization increment.
     |Distill them:
     |- "fixes": findings where the implementation VIOLATES the committed specs. Each gets a
     |  short spec document (Markdown: what is wrong, the spec rule it violates, the expected
     |  behaviour) and a plan task (title + description naming the spec rules/scenarios).
     |- "improvements": worthwhile follow-ups that do NOT violate the specs.
     |- "lessons": rules of thumb that would help FUTURE modernizations of this kind — phrased
     |  generally (no file paths from this repo), one sentence each. Only include lessons that
     |  generalize; an empty list is a fine answer.
     |
     |Reviewer findings:
     |$findingLines
     |
     |Judge findings:
     |$scoreLines""".stripMargin

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
  defaultPrompt = Some("Review the modernization increment against its spec pack"),
):
  val packDir   = workspace.resolve(sys.env.getOrElse("LLM4ZIO_PACK", "packs/cobol-springboot"))
  val planFile  = workDir.resolve(ModDir).resolve("plan.md")
  val events    = summon[FlowEvents]
  val reviewSvc = reviewers.headOption.getOrElse(reasoning)

  for
    pack     <- stage("Pack")(Pack.load(packDir))
    _        <- stage("Wall")(assertWall(pack, workDir, events))
    base     <- git.defaultBase
    diff     <- git.diffVsBase(base)
    _        <- ZIO.when(diff.isBlank)(
                  ZIO.fail(FlowError.Aborted(s"nothing to review: no diff vs $base on this branch"))
                )
    files    <- git.changedFilesVsBase(base)
    specText <- gatherDir(workDir, workDir.resolve(pack.specsDir))
    findings <- stage("Review") {
                  val roster = (Reviewers.all ++ pack.lenses).filter(_.matches(files))
                  ZIO
                    .foreach(roster) { r => // sequential: gemini free tier 429s under concurrent reviewers
                      // Scope the diff to what this lens actually cares about — `files = None` means "no scope", not
                      // "unbounded"; it still sees the whole diff, just capped like everything else below.
                      val scoped = r.files.fold(files)(regex => files.filter(_.matches(regex)))
                      for
                        lensDiff <- git.diffVsBase(base, scoped)
                        result   <- Context.withShrink(s"review[${r.name}]") { cap =>
                                      for
                                        s <- Context.capped(s"specs[${r.name}]", specText, cap)
                                        d <- Context.capped(s"diff[${r.name}]", lensDiff, cap)
                                        o <- r.asService(reviewSvc)
                                               .executeStructured[ReviewResult](
                                                 Reviewers.reviewPrompt(
                                                   s"modernization increment vs committed specs\n\n$s",
                                                   d,
                                                 ),
                                                 Reviewers.schema,
                                               )
                                               .mapError(e => FlowError.Llm(e.message, Some(e)))
                                      yield o
                                    }
                      yield result
                    }
                    .map(Reviewers.merge)
                }
    scored   <- stage("Judge") {
                  for
                    programs <- specPrograms(workDir.resolve(pack.specsDir))
                    result   <- ProgramJudge.judgeAll(
                                  pack,
                                  Judge.of(reasoning, complianceDims),
                                  complianceDims,
                                  workDir.resolve(ModDir).resolve("gate"),
                                  base,
                                  programs,
                                  p => readFileOr(workDir.resolve(pack.specsDir).resolve(s"$p.md"), ""),
                                  userPrompt,
                                )
                  yield result
                }
    outcome  <- stage("Distill") {
                  Context.withShrink("distill") { cap =>
                    Context
                      .capped("distill prompt", distillPrompt(pack.prompt("review").getOrElse(""), findings, scored), cap)
                      .flatMap { prompt =>
                        reasoning
                          .executeStructured[ReviewOutcome](prompt, SchemaDerivation.derive[ReviewOutcome])
                          .mapError(e => FlowError.Llm(e.message, Some(e)))
                      }
                  }
                }
    _        <- stage("Fix specs") {
                  val all = outcome.fixes.map(("fix", _)) ++ outcome.improvements.map(("improvement", _))
                  ZIO.foreachDiscard(all) { (kind, f) =>
                    writeFile(
                      workDir.resolve(pack.specsDir).resolve("fixes").resolve(s"$kind-${slug(f.title)}.md"),
                      s"# ${f.title}\n\n${f.spec}\n",
                    )
                  } *> {
                    if outcome.fixes.isEmpty then events.publish(FlowEvent.Info("no spec violations — no plan increment"))
                    else
                      PlanStore
                        .load(planFile)
                        .someOrFail(FlowError.Aborted(s"no plan at $planFile — run modernize-seed.sc first"))
                        .flatMap { plan =>
                          val increment = outcome.fixes.map(f => Task(f.taskTitle, f.taskDescription))
                          PlanStore.save(planFile, plan.copy(tasks = plan.tasks ++ increment)) *>
                            events.publish(FlowEvent.Info(
                              s"${increment.size} fix task(s) appended — rerun modernize-implement.sc"
                            ))
                        }
                  }
                }
    _        <- stage("Lessons") {
                  if outcome.lessons.isEmpty then events.publish(FlowEvent.Info("no generalizable lessons this round"))
                  else
                    ZIO.foreachDiscard(outcome.lessons)(Pack.appendLesson(pack.dir, _)) *>
                      events.publish(FlowEvent.Info(
                        s"${outcome.lessons.size} lesson(s) appended to ${pack.dir.resolve("lessons.md")} — " +
                          "review and commit the pack change"
                      ))
                }
    _        <- stage("Provenance")(recordTruncations(workDir.resolve(ModDir).resolve("provenance.json")))
    _        <- stage("Commit")(
                  git.commitAll(s"modernize(${pack.name}): review — ${outcome.fixes.size} fix(es), " +
                    s"${outcome.improvements.size} improvement(s)").unit
                )
    _        <- stage("Boards") {
                  Ado.configFrom(sys.env) match
                    case Left(_)  => events.publish(FlowEvent.Info("Azure DevOps not configured — skipping work items"))
                    case Right(_) =>
                      Ado.withTool() { ado =>
                        ZIO.foreachDiscard(outcome.fixes) { f =>
                          ado
                            .createWorkItem("Task", f.taskTitle, Map("System.Description" -> f.taskDescription))
                            .flatMap(id => events.publish(FlowEvent.Info(s"work item $id: ${f.taskTitle}")))
                        }
                      }
                }
  yield s"fixes=${outcome.fixes.size} improvements=${outcome.improvements.size} lessons=${outcome.lessons.size}"
