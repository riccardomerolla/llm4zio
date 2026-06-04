package llm4zio.flow

import java.nio.file.Path

import zio.json.JsonCodec
import zio.json.ast.Json
import zio.{ IO, ZIO }

import llm4zio.core.{ LlmService, SchemaDerivation }
import llm4zio.tools.JsonSchema

/** An LLM's choice of which reviewers to run (by name). */
final case class ReviewerPick(reviewers: List[String]) derives JsonCodec

/** Strategy for choosing which reviewers run in a given review round. Effectful so strategies like [[llmDriven]] can
  * consult a model. All built-in strategies first drop reviewers whose file scope doesn't match the changed files.
  */
trait ReviewerSelector:
  def select(
    reviewers: List[Reviewer],
    changedFiles: List[String],
    round: Int,
    previous: Option[ReviewResult],
  ): IO[FlowError, List[Reviewer]]

object ReviewerSelector:
  /** Every (file-matching) reviewer, every round (default). */
  val allEveryRound: ReviewerSelector =
    (reviewers, files, _, _) => ZIO.succeed(reviewers.filter(_.matches(files)))

  /** All (file-matching) reviewers on round 1; on later rounds, only if the previous round was still dirty. */
  val whileDirty: ReviewerSelector =
    (reviewers, files, round, previous) =>
      ZIO.succeed(if round == 1 || previous.exists(!_.isClean) then reviewers.filter(_.matches(files)) else Nil)

  /** A cheap model picks, from the file-matching reviewers, the ones relevant to the changed files. Falls back to all
    * file-matching reviewers on any parse/LLM failure, and never selects none.
    */
  def llmDriven(picker: LlmService): ReviewerSelector =
    (reviewers, files, _, _) =>
      val scoped = reviewers.filter(_.matches(files))
      if scoped.sizeIs <= 1 || files.isEmpty then ZIO.succeed(scoped)
      else
        val names  = scoped.map(_.name)
        val prompt =
          s"""Pick which code reviewers are relevant to these changed files. Available reviewers: ${names.mkString(
              ", "
            )}.
             |Changed files:
             |${files.mkString("\n")}
             |Respond ONLY with JSON: {"reviewers":["name", ...]} using only the available names.""".stripMargin
        picker
          .executeStructured[ReviewerPick](prompt, SchemaDerivation.derive[ReviewerPick])
          .map(pick => scoped.filter(r => pick.reviewers.contains(r.name)))
          .catchAll(_ => ZIO.succeed(scoped))
          .map(chosen => if chosen.isEmpty then scoped else chosen)

/** Prompts, schema, helpers, and the shipped reviewer roster. */
object Reviewers:

  /** The canonical reviewer lenses, loaded from classpath resources under `llm4zio/review/reviewers`. */
  lazy val all: List[Reviewer] =
    List("code-functionality", "test", "readability", "code-structure", "performance", "security", "scala-zio")
      .map(Reviewer.fromResource)

  /** A lighter default: correctness, readability, tests. */
  lazy val minimal: List[Reviewer] =
    List("code-functionality", "readability", "test").map(Reviewer.fromResource)

  val schema: JsonSchema =
    Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "issues"  -> Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(
              "severity"    -> Json.Obj("type" -> Json.Str("string")),
              "title"       -> Json.Obj("type" -> Json.Str("string")),
              "description" -> Json.Obj("type" -> Json.Str("string")),
            ),
          ),
        ),
        "summary" -> Json.Obj("type" -> Json.Str("string")),
      ),
    )

  def reviewPrompt(task: String, diff: String): String =
    s"""Review the change below for task "$task". Report problems as JSON:
       |{"issues":[{"severity":"Critical|Warning|Info","title":"...","description":"..."}],"summary":"..."}
       |An empty "issues" array means the change is acceptable. Respond with JSON only.
       |
       |Diff:
       |$diff""".stripMargin

  def fixPrompt(result: ReviewResult): String =
    val lines = result.issues.map(i => s"- [${i.severity}] ${i.title}: ${i.description}").mkString("\n")
    s"""Address these review findings, then stop:
       |$lines""".stripMargin

  /** Combine findings from several reviewers into one result. */
  def merge(results: List[ReviewResult]): ReviewResult =
    ReviewResult(
      issues = results.flatMap(_.issues),
      summary = results.map(_.summary).filter(_.nonEmpty).mkString("; "),
    )

  /** A cheap compile/lint sanity gate as a review step: runs `command` in `workDir`; a non-zero exit becomes a single
    * Critical issue, so the loop fixes a broken build before spending LLM turns on review.
    */
  def lintCommand(command: List[String], workDir: Path): IO[FlowError, ReviewResult] =
    command match
      case Nil         => ZIO.succeed(ReviewResult(Nil))
      case cmd :: args =>
        Proc.run(cmd, args, workDir).map { r =>
          if r.ok then ReviewResult(Nil, "lint passed")
          else
            ReviewResult(
              List(ReviewIssue(Severity.Critical, s"lint failed: ${command.mkString(" ")}", r.problem)),
              "lint failed",
            )
        }

/** Review → fix → re-review until clean or `maxRounds` reached.
  *
  * Each round: an optional `lint` gate runs first — if it fails, that's the round's result and LLM reviewers are
  * skipped (fix the build first). Otherwise `selector` picks which `reviewers` run, and their findings are merged. The
  * fixer is the coder [[Chat]]; `currentDiff` is re-read each round so reviewers see the latest state.
  *
  * `parallelism` caps how many reviewer calls run at once: `0` (default) fans them all out concurrently; a positive
  * value throttles them, which a rate-limited backend (e.g. the gemini free tier) needs to avoid 429s.
  */
def reviewAndFixLoop(
  reviewers: List[Reviewer],
  reviewerService: LlmService,
  coder: Chat,
  taskTitle: String,
  currentDiff: IO[FlowError, String],
  changedFiles: IO[FlowError, List[String]] = ZIO.succeed(Nil),
  maxRounds: Int = 3,
  selector: ReviewerSelector = ReviewerSelector.allEveryRound,
  lint: Option[IO[FlowError, ReviewResult]] = None,
  parallelism: Int = 0,
)(using events: FlowEvents
): IO[FlowError, ReviewResult] =

  def reviewOnce(round: Int, previous: Option[ReviewResult]): IO[FlowError, ReviewResult] =
    lint.getOrElse(ZIO.succeed(ReviewResult(Nil))).flatMap { lintResult =>
      if !lintResult.isClean then ZIO.succeed(lintResult)
      else
        for
          diff   <- currentDiff
          files  <- changedFiles
          chosen <- selector.select(reviewers, files, round, previous)
          reviews = ZIO.foreachPar(chosen) { reviewer =>
                      reviewer
                        .asService(reviewerService)
                        .executeStructured[ReviewResult](Reviewers.reviewPrompt(taskTitle, diff), Reviewers.schema)
                        .mapError(e => FlowError.Llm(e.message))
                    }
          // `parallelism = 0` keeps the default unbounded fan-out (fine for high-throughput backends); a positive
          // cap throttles concurrent reviewer calls so rate-limited backends (e.g. the gemini free tier) don't 429.
          merged <- (if parallelism > 0 then reviews.withParallelism(parallelism) else reviews).map(Reviewers.merge)
        yield merged
    }

  def loop(round: Int, previous: Option[ReviewResult]): IO[FlowError, ReviewResult] =
    reviewOnce(round, previous).flatMap { result =>
      val verdict = if result.isClean then "clean" else s"${result.issues.size} issue(s)"
      if result.isClean || round >= maxRounds then
        events.publish(FlowEvent.Info(s"review settled after round $round: $verdict")).as(result)
      else
        events.publish(FlowEvent.Info(s"review round $round: $verdict, fixing")) *>
          coder.ask(Reviewers.fixPrompt(result)).mapError(e => FlowError.Llm(e.message)) *>
          loop(round + 1, Some(result))
    }

  loop(1, None)
