package llm4zio.flow

import zio.json.ast.Json
import zio.{ IO, ZIO }

import llm4zio.core.{ LlmService, SchemaDerivation, Streaming }
import llm4zio.tools.JsonSchema

/** Turn a free-form request into a structured [[Plan]] using a reasoning connector's structured-output capability. The
  * reasoning step is an API connector (the role split: reasoning over API, editing over CLI).
  */
object Planner:

  val defaultInstructions: String =
    """You are a planning assistant. Break the user's request into an ordered list of small,
      |independently-implementable tasks — each a thin slice that delivers an observable outcome
      |(split by outcome, not by technical layer), described in terms of behaviour, not mechanism.
      |Choose a short kebab-case epicId for the overall change. Respond ONLY with JSON of the form:
      |{"epicId":"kebab-case-id","tasks":[{"title":"...","description":"...","completed":false}]}""".stripMargin

  /** JSON-schema for the Plan shape, derived from the [[Plan]] type (used by [[from]]). */
  val schema: JsonSchema = SchemaDerivation.derive[Plan]

  /** A permissive schema for sum-typed structured calls ([[interactive]]'s `PlanningStep`, [[assessThenPlan]]'s
    * `Verdict[Plan]`): their shape is a discriminated union spelled out in the prompt, so we must not enforce a product
    * schema on backends that honour it (e.g. codex `--output-schema`).
    */
  private val freeform: JsonSchema = Json.Obj("type" -> Json.Str("object"))

  def from(
    reasoning: LlmService,
    prompt: String,
    instructions: String = defaultInstructions,
  ): IO[FlowError, Plan] =
    reasoning
      .executeStructured[Plan](s"$instructions\n\nRequest:\n$prompt", schema)
      .mapError(e => FlowError.Llm(e.toString))

  val reviewInstructions: String =
    """Review the draft implementation plan below and return an improved version. Focus on four dimensions:
      |  - Correctness: every task matches how the codebase actually works; ordering and inter-task dependencies are
      |    right; no step rests on a wrong assumption.
      |  - Completeness: the plan fully covers the request, with no missing task or step an implementer would need.
      |  - Simplicity: prefer the simplest approach that works; cut over-engineering; split or merge tasks so each is
      |    one coherent unit of work.
      |  - Conciseness: no redundant or busy-work tasks; each description carries only what an implementer needs.
      |Keep the same epicId unless it is clearly wrong. Return the COMPLETE improved plan (not just the changes),
      |ONLY as JSON: {"epicId":"kebab-case-id","tasks":[{"title":"...","description":"...","completed":false}]}""".stripMargin

  /** Have the reasoning connector critique its own draft [[Plan]] and return an improved one. */
  def reviewed(
    reasoning: LlmService,
    plan: Plan,
    instructions: String = reviewInstructions,
  ): IO[FlowError, Plan] =
    reasoning
      .executeStructured[Plan](s"$instructions\n\nDraft plan:\n${plan.render}", schema)
      .mapError(e => FlowError.Llm(e.toString))
      .map(_.copy(brief = plan.brief)) // a review must not drop an already-attached brief

  val briefInstructions: String =
    """Write a concise codebase brief for an engineer about to implement a change: the relevant modules, key file
      |paths, important APIs/types, conventions, and the build/test commands. Explore the repo as needed. Do NOT
      |restate the task list — just the orientation a cold agent would otherwise have to rediscover. Plain prose.""".stripMargin

  /** Ask the reasoning connector (a CLI agent that can explore the repo) for a one-off codebase brief. */
  def brief(
    reasoning: LlmService,
    prompt: String,
    instructions: String = briefInstructions,
  ): IO[FlowError, String] =
    Streaming
      .collect(reasoning.executeStream(s"$instructions\n\nChange request:\n$prompt"))
      .map(_.content.strip)
      .mapError(e => FlowError.Llm(e.toString))

  /** Attach a freshly-written codebase [[brief]] to `plan`, so `plan.taskPrompt` prepends it to every task. */
  def briefed(
    reasoning: LlmService,
    plan: Plan,
    prompt: String,
    instructions: String = briefInstructions,
  ): IO[FlowError, Plan] =
    brief(reasoning, prompt, instructions).map(b => plan.copy(brief = Some(b)))

  val assessThenPlanInstructions: String =
    """First decide whether this request is ready to implement. Respond ONLY with JSON using a "kind" discriminator,
      |exactly one of:
      |  {"kind":"Blocked","reason":"why it can't proceed yet"}
      |  {"kind":"Proceed","value":{"epicId":"kebab-id","tasks":[{"title":"...","description":"...","completed":false}]}}""".stripMargin

  /** Assess whether the request is ready to plan, then either propose a [[Plan]] ([[Verdict.Proceed]]) or decline with
    * a reason ([[Verdict.Blocked]]).
    */
  def assessThenPlan(
    reasoning: LlmService,
    prompt: String,
    instructions: String = assessThenPlanInstructions,
  ): IO[FlowError, Verdict[Plan]] =
    reasoning
      .executeStructured[Verdict[Plan]](s"$instructions\n\nRequest:\n$prompt", freeform)
      .mapError(e => FlowError.Llm(e.toString))

  val interactiveInstructions: String =
    """You are an interactive planner. Before proposing a plan, ask at least one
      |clarifying question UNLESS the request is already fully specified and
      |unambiguous — prefer asking whenever scope, priorities, or constraints are
      |open to interpretation, even if you could guess. Ask ONE question at a time.
      |Respond ONLY with JSON using a "kind" discriminator, exactly one of:
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
          .executeStructured[PlanningStep](turnPrompt(qa), freeform)
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
      .executeStructured[Triage](s"$instructions\n\nTitle: $title\n\n$body", freeform)
      .mapError(e => FlowError.Llm(e.toString))

/** Chain plan transforms off the planning effect, orca-style:
  * `Planner.from(reasoning, prompt).reviewed(reasoning).briefed(reasoning, prompt)`. Thin sugar over the
  * [[Planner.reviewed]] / [[Planner.briefed]] functions — the LLM stays an explicit argument because which model
  * critiques the plan is a real decision.
  */
extension [R](plan: ZIO[R, FlowError, Plan])
  def reviewed(reasoning: LlmService): ZIO[R, FlowError, Plan]                =
    plan.flatMap(p => Planner.reviewed(reasoning, p))
  def briefed(reasoning: LlmService, prompt: String): ZIO[R, FlowError, Plan] =
    plan.flatMap(p => Planner.briefed(reasoning, p, prompt))
