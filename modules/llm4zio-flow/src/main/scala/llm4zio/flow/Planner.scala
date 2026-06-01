package llm4zio.flow

import zio.IO
import zio.json.ast.Json

import llm4zio.core.LlmService
import llm4zio.tools.JsonSchema

/** Turn a free-form request into a structured [[Plan]] using a reasoning
  * connector's structured-output capability. The reasoning step is an API
  * connector (the role split: reasoning over API, editing over CLI).
  */
object Planner:

  val defaultInstructions: String =
    """You are a planning assistant. Break the user's request into an ordered list
      |of small, independently-implementable tasks. Choose a short kebab-case epicId
      |for the overall change. Respond ONLY with JSON of the form:
      |{"epicId":"kebab-case-id","tasks":[{"title":"...","description":"...","completed":false}]}""".stripMargin

  /** JSON-schema hint for the Plan shape (advisory; connectors that derive
    * structured output from text ignore it, API connectors may use it).
    */
  val schema: JsonSchema =
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(
        "epicId" -> Json.Obj("type" -> Json.Str("string")),
        "tasks" -> Json.Obj(
          "type" -> Json.Str("array"),
          "items" -> Json.Obj(
            "type" -> Json.Str("object"),
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
