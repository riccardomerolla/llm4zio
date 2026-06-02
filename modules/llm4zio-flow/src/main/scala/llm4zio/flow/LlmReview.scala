package llm4zio.flow

import java.nio.file.Path

import zio.json.ast.Json
import zio.{ IO, ZIO }

import llm4zio.core.LlmService
import llm4zio.tools.JsonSchema

/** Strategy for choosing which reviewers run in a given review round. */
trait ReviewerSelector:
  def select(reviewers: List[LlmService], round: Int, previous: Option[ReviewResult]): List[LlmService]

object ReviewerSelector:
  /** Every reviewer, every round (default). */
  val allEveryRound: ReviewerSelector =
    (reviewers, _, _) => reviewers

  /** All reviewers on round 1; on later rounds, only if the previous round was still dirty. */
  val whileDirty: ReviewerSelector =
    (reviewers, round, previous) => if round == 1 || previous.exists(!_.isClean) then reviewers else Nil

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
  * skipped (fix the build first). Otherwise `selector` picks which `reviewers` run (in parallel; cross-agent review),
  * and their findings are merged. The fixer is the coder [[Chat]]; `currentDiff` is re-read each round so reviewers see
  * the latest state.
  */
def reviewAndFixLoop(
  reviewers: List[LlmService],
  coder: Chat,
  taskTitle: String,
  currentDiff: IO[FlowError, String],
  maxRounds: Int = 3,
  selector: ReviewerSelector = ReviewerSelector.allEveryRound,
  lint: Option[IO[FlowError, ReviewResult]] = None,
)(using events: FlowEvents
): IO[FlowError, ReviewResult] =

  def reviewOnce(round: Int, previous: Option[ReviewResult]): IO[FlowError, ReviewResult] =
    lint.getOrElse(ZIO.succeed(ReviewResult(Nil))).flatMap { lintResult =>
      if !lintResult.isClean then ZIO.succeed(lintResult)
      else
        currentDiff.flatMap { diff =>
          ZIO
            .foreachPar(selector.select(reviewers, round, previous)) { reviewer =>
              reviewer
                .executeStructured[ReviewResult](Reviewers.reviewPrompt(taskTitle, diff), Reviewers.schema)
                .mapError(e => FlowError.Llm(e.toString))
            }
            .map(Reviewers.merge)
        }
    }

  def loop(round: Int, previous: Option[ReviewResult]): IO[FlowError, ReviewResult] =
    reviewOnce(round, previous).flatMap { result =>
      val verdict = if result.isClean then "clean" else s"${result.issues.size} issue(s)"
      if result.isClean || round >= maxRounds then
        events.publish(FlowEvent.Info(s"review settled after round $round: $verdict")).as(result)
      else
        events.publish(FlowEvent.Info(s"review round $round: $verdict, fixing")) *>
          coder.ask(Reviewers.fixPrompt(result)).mapError(e => FlowError.Llm(e.toString)) *>
          loop(round + 1, Some(result))
    }

  loop(1, None)
