package llm4zio.modernize

import java.nio.file.Path

import zio.{ IO, ZIO }

import llm4zio.eval.{ Dimension, EvalResult, Evaluator, Sample }
import llm4zio.flow.*

/** Per-program spec-compliance judging, shared by [[ImplementFlow]] and [[ReviewFlow]].
  *
  * A whole-branch judge call carries every spec and every diff hunk in the estate, which is what blows a provider's
  * input window. Judging one program at a time against only that program's slice of the diff keeps each call small, and
  * — wrapped in [[ReviewCache]] — makes the gate resumable: an unchanged program reuses its stored verdict with no LLM
  * call, exactly as `ExtractFlow`'s gate already does.
  */
object ProgramJudge:

  // Judging touches git read-only (diff/changed-files) as a full-power library helper; the two flows that call this
  // (ImplementFlow, ReviewFlow) already mint Caps.All at their own entry points, but that given is a member of THEIR
  // object, not this file's — every bypass is greppable via Caps.grantAll.
  private[modernize] given llm4zio.flow.Caps.All = llm4zio.flow.Caps.grantAll

  /** Partition `changed` by which program's file regex matches. A file matching several programs is judged with each of
    * them (a shared bridge class is genuinely part of both). The remainder — build files, shared utilities — is
    * returned separately for the unassigned pass.
    */
  def groupFiles(
    pack: Pack,
    programs: List[String],
    changed: List[String],
  ): (Map[String, List[String]], List[String]) =
    val byProgram  = programs.map(p => p -> changed.filter(_.matches(pack.filesFor(p)))).toMap
    val assigned   = byProgram.values.flatten.toSet
    val unassigned = changed.filterNot(assigned.contains)
    (byProgram, unassigned)

  /** Judge every program whose files changed, plus one pass over the unassigned remainder. Each verdict is cached at
    * `gateDir/<NAME>.json`, fingerprinted over the spec, the diff slice, and the rubric it judged — so re-running after
    * a crash re-judges only what changed.
    *
    * `specFor` supplies a program's spec text; the caller owns where specs live (the two flows differ).
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
      // `files` comes from groupFiles — do NOT re-derive it here; judgeAll already computed the grouping and
      // re-running changedFilesVsBase per program would be N+1 git invocations for the same answer.
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

  /** Sub-bar dimensions as Critical review issues, titled with the program they belong to — the same shape
    * `ExtractFlow.judgeIssues` produces, so `fixLoop` and `ReviewResult.isClean` work unchanged.
    */
  private def issues(scored: EvalResult, dims: List[Dimension], program: String): ReviewResult =
    val subBar = scored.scores.filter(s => s.score < dims.find(_.name == s.name).fold(2)(_.maxScore))
    ReviewResult(
      subBar.map(s => ReviewIssue(Severity.Critical, s"judge[$program]: ${s.name} scored ${s.score}", s.reasoning)),
      s"judge:$program",
    )
