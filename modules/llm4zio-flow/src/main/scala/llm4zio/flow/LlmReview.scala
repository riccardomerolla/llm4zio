package llm4zio.flow

import zio.IO
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

/** Review → fix → re-review until the reviewer is clean or `maxRounds` reached.
  *
  * The reviewer is the reasoning connector (structured `ReviewResult`); the
  * fixer is the coder [[Chat]]. `currentDiff` is re-read each round so the
  * reviewer sees the latest state. Built on the generic [[fixLoop]].
  */
def reviewAndFixLoop(
  reasoning: LlmService,
  coder: Chat,
  taskTitle: String,
  currentDiff: IO[FlowError, String],
  maxRounds: Int = 3,
)(using FlowEvents): IO[FlowError, ReviewResult] =
  val evaluate: IO[FlowError, ReviewResult] =
    currentDiff.flatMap { diff =>
      reasoning
        .executeStructured[ReviewResult](Reviewers.reviewPrompt(taskTitle, diff), Reviewers.schema)
        .mapError(e => FlowError.Llm(e.toString))
    }

  val fix: ReviewResult => IO[FlowError, Unit] = result =>
    coder.ask(Reviewers.fixPrompt(result)).mapError(e => FlowError.Llm(e.toString)).unit

  fixLoop(evaluate, fix, maxRounds)
