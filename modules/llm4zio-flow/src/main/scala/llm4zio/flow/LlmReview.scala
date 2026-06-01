package llm4zio.flow

import zio.{IO, ZIO}
import zio.json.ast.Json

import llm4zio.core.LlmService
import llm4zio.tools.JsonSchema

/** Prompts + schema for an LLM reviewer. */
object Reviewers:
  val schema: JsonSchema =
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(
        "issues" -> Json.Obj(
          "type" -> Json.Str("array"),
          "items" -> Json.Obj(
            "type" -> Json.Str("object"),
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

/** Review → fix → re-review until clean or `maxRounds` reached.
  *
  * `reviewers` run in parallel each round (cross-agent review: route reviews
  * through different backends than the implementer) and their findings are
  * merged; the fixer is the coder [[Chat]]. `currentDiff` is re-read each round
  * so reviewers see the latest state. Built on the generic [[fixLoop]].
  */
def reviewAndFixLoop(
  reviewers: List[LlmService],
  coder: Chat,
  taskTitle: String,
  currentDiff: IO[FlowError, String],
  maxRounds: Int = 3,
)(using FlowEvents): IO[FlowError, ReviewResult] =
  val evaluate: IO[FlowError, ReviewResult] =
    currentDiff.flatMap { diff =>
      ZIO
        .foreachPar(reviewers) { reviewer =>
          reviewer
            .executeStructured[ReviewResult](Reviewers.reviewPrompt(taskTitle, diff), Reviewers.schema)
            .mapError(e => FlowError.Llm(e.toString))
        }
        .map(Reviewers.merge)
    }

  val fix: ReviewResult => IO[FlowError, Unit] = result =>
    coder.ask(Reviewers.fixPrompt(result)).mapError(e => FlowError.Llm(e.toString)).unit

  fixLoop(evaluate, fix, maxRounds)
