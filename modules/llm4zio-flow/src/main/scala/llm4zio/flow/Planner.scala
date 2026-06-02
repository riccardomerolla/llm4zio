package llm4zio.flow

import zio.json.ast.Json
import zio.{ IO, ZIO }

import llm4zio.core.LlmService
import llm4zio.tools.JsonSchema

/** Turn a free-form request into a structured [[Plan]] using a reasoning connector's structured-output capability. The
  * reasoning step is an API connector (the role split: reasoning over API, editing over CLI).
  */
object Planner:

  val defaultInstructions: String =
    """You are a planning assistant. Break the user's request into an ordered list
      |of small, independently-implementable tasks. Choose a short kebab-case epicId
      |for the overall change. Respond ONLY with JSON of the form:
      |{"epicId":"kebab-case-id","tasks":[{"title":"...","description":"...","completed":false}]}""".stripMargin

  /** JSON-schema hint for the Plan shape (advisory; connectors that derive structured output from text ignore it, API
    * connectors may use it).
    */
  val schema: JsonSchema =
    Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj(
        "epicId" -> Json.Obj("type" -> Json.Str("string")),
        "tasks"  -> Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(
              "title"       -> Json.Obj("type" -> Json.Str("string")),
              "description" -> Json.Obj("type" -> Json.Str("string")),
              "completed"   -> Json.Obj("type" -> Json.Str("boolean")),
            ),
          ),
        ),
      ),
    )

  def from(
    reasoning: LlmService,
    prompt: String,
    instructions: String = defaultInstructions,
  ): IO[FlowError, Plan] =
    reasoning
      .executeStructured[Plan](s"$instructions\n\nRequest:\n$prompt", schema)
      .mapError(e => FlowError.Llm(e.toString))

  val interactiveInstructions: String =
    """You are an interactive planner. If the request is underspecified, ask ONE
      |clarifying question; otherwise propose the plan. Respond ONLY with JSON
      |using a "kind" discriminator, exactly one of:
      |  {"kind":"AskUser","question":"..."}
      |  {"kind":"Proposed","plan":{"epicId":"kebab-id","tasks":[{"title":"...","description":"...","completed":false}]}}""".stripMargin

  /** Plan interactively: the model may ask the user clarifying questions (via `interaction`) before proposing a
    * [[Plan]]. Loops until a plan is proposed or `maxTurns` is reached.
    */
  def interactive(
    reasoning: LlmService,
    prompt: String,
    interaction: Interaction,
    maxTurns: Int = 6,
    instructions: String = interactiveInstructions,
  ): IO[FlowError, Plan] =
    def turnPrompt(qa: List[(String, String)]): String =
      val convo = qa.map((q, a) => s"Q: $q\nA: $a").mkString("\n\n")
      s"$instructions\n\nRequest:\n$prompt\n\n$convo".strip

    def loop(qa: List[(String, String)], turn: Int): IO[FlowError, Plan] =
      if turn > maxTurns then
        ZIO.fail(FlowError.Aborted(s"planner did not propose a plan within $maxTurns turns"))
      else
        reasoning
          .executeStructured[PlanningStep](turnPrompt(qa), schema)
          .mapError(e => FlowError.Llm(e.toString))
          .flatMap {
            case PlanningStep.Proposed(plan) => ZIO.succeed(plan)
            case PlanningStep.AskUser(q)     => interaction.ask(q).flatMap(a => loop(qa :+ (q -> a), turn + 1))
          }

    loop(Nil, 1)

  val triageInstructions: String =
    """Triage this bug report. Decide exactly one verdict and respond ONLY with JSON
      |using a "kind" discriminator:
      |  {"kind":"NotABug","explanation":"..."}
      |  {"kind":"Untestable","summary":"...","reproductionSteps":"..."}
      |  {"kind":"Testable","summary":"...","branchName":"fix/...","failingTestPath":"..."}""".stripMargin

  /** Triage a bug report into [[Triage]] via the reasoning connector. */
  def triage(
    reasoning: LlmService,
    title: String,
    body: String,
    instructions: String = triageInstructions,
  ): IO[FlowError, Triage] =
    reasoning
      .executeStructured[Triage](s"$instructions\n\nTitle: $title\n\n$body", Json.Obj("type" -> Json.Str("object")))
      .mapError(e => FlowError.Llm(e.toString))
