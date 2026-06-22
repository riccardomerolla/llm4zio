package llm4zio.flow

import zio.json.{ JsonCodec, jsonDiscriminator }
import zio.{ IO, ZIO }

/** A channel for asking the user a question mid-flow (e.g. an interactive planner clarifying an open-ended request).
  * Implementations: a terminal/stdin prompt (in the runner), or a scripted queue (tests).
  */
trait Interaction:
  def ask(question: String): IO[FlowError, String]

  /** Whether a human is actually reachable (a TTY). Interactive by default; the non-interactive channel reports `false`
    * so gates can halt with guidance instead of failing an `ask` in CI.
    */
  def interactive: Boolean = true

object Interaction:
  /** Refuses to ask — for non-interactive contexts. */
  val noninteractive: Interaction = new Interaction:
    def ask(question: String): IO[FlowError, String] =
      ZIO.fail(FlowError.Aborted(s"interaction required but unavailable: $question"))
    override def interactive: Boolean                = false

/** One step of interactive planning: either ask the user a question, or propose the finished plan.
  * `"kind"`-discriminated for structured output.
  */
@jsonDiscriminator("kind")
enum PlanningStep derives JsonCodec:
  case AskUser(question: String)
  case Proposed(plan: Plan)
