package llm4zio.flow

import zio.*

/** Context budgeting for LLM prompts: bound what a call ships, and make every truncation visible.
  *
  * Budgets are in CHARACTERS, not tokens — deterministic, no tokenizer dependency, and what the flows already used.
  * Rule of thumb ~3.5 chars/token for code, so the 400k default is ~115k tokens: conservative against every provider.
  */
object Context:

  private val Marker = "\n\n… [truncated] …\n\n"

  /** The result of [[cap]]: the (possibly shortened) text plus what it cost. */
  final case class Capped(text: String, originalChars: Int, truncated: Boolean)

  /** Bound `text` to `limit` characters — the result is NEVER longer than `limit`, marker included. Keeps the head (3/4
    * of the remaining room) and the tail (1/4) so both the entry points and the trailing rules survive; the middle is
    * where boilerplate lives. Text at or under the limit is returned untouched.
    *
    * NB the marker counts against `limit`. `ExtractFlow.capText`, the prior art this generalises, let the marker sit on
    * top — a ~19-char overshoot. That was an accident, not a design choice, and callers here reason about fitting under
    * a hard provider ceiling, so a method called `cap` must actually cap. For `limit <= 0` — reachable once a caller's
    * remaining budget is exhausted — the closest achievable result is the empty string, since length can't go negative.
    */
  def cap(text: String, limit: Int): Capped =
    if text.length <= limit then Capped(text, text.length, truncated = false)
    else if limit <= Marker.length then Capped(text.take(math.max(limit, 0)), text.length, truncated = true)
    else
      val room = limit - Marker.length
      val head = room * 3 / 4
      val tail = room - head
      Capped(s"${text.take(head)}$Marker${text.takeRight(tail)}", text.length, truncated = true)

  /** The default character budget: `LLM4ZIO_CONTEXT_BUDGET`, else the deprecated `LLM4ZIO_JUDGE_SOURCES_LIMIT`, else
    * 400_000. Both are read from the environment first and then from `llm4zio.<NAME>` system properties, mirroring
    * `modernize.Env` so a `modernize.conf` setting still reaches the flow layer (which cannot depend on modernize).
    */
  def budget: Int =
    def lookup(name: String): Option[String] = sys.env.get(name).orElse(sys.props.get(s"llm4zio.$name"))
    lookup("LLM4ZIO_CONTEXT_BUDGET")
      .orElse(lookup("LLM4ZIO_JUDGE_SOURCES_LIMIT"))
      .flatMap(_.trim.toIntOption)
      .filter(_ > 0)
      .getOrElse(400_000)

  /** Which kind of shrinking produced a [[Truncation]] — the two are NOT interchangeable, and `provenance.json` readers
    * must be able to tell them apart.
    */
  enum Kind:
    /** Real text was shortened: `originalChars` and `keptChars` are literal character counts. */
    case Capped

    /** A retry budget was lowered after an oversized prompt failed: the numbers are attempted CEILINGS, not the size of
      * any actual text. The prompt that failed was larger than `originalChars`; what finally succeeded was at most
      * `keptChars`.
      */
    case Shrunk

  /** One recorded truncation: what was shortened, and by how much. `kind` disambiguates [[capped]]'s literal char
    * counts from [[withShrink]]'s attempted budget ceilings — conflating them would misreport how much content a
    * clean-room audit actually lost.
    */
  final case class Truncation(label: String, originalChars: Int, keptChars: Int, kind: Kind):
    def render: String = kind match
      case Kind.Capped => s"$label: truncated $originalChars → $keptChars chars"
      case Kind.Shrunk =>
        s"$label: retried at a lower budget, $originalChars → $keptChars chars (ceilings, not text size)"

  /** Fiber-local truncation log. Fiber-local (not global) so concurrent flows don't cross-contaminate, and so a phase
    * reads back exactly what its own calls truncated. Written ONLY by [[capped]] and [[withShrink]].
    */
  private val recorded: FiberRef[Chunk[Truncation]] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Chunk.empty[Truncation]))

  /** Truncations recorded on this fiber so far. Phases write these into `provenance.json`. */
  def truncations: UIO[Chunk[Truncation]] = recorded.get

  private def record(t: Truncation): UIO[Unit] = recorded.update(_ :+ t)

  /** [[cap]], publishing a [[FlowEvent.Info]] and recording the truncation when one happens. `label` names what was
    * shortened, so the event and the provenance entry are readable ("specs", "branch diff", "judge context").
    */
  def capped(label: String, text: String, limit: Int)(using events: FlowEvents): UIO[String] =
    val out = cap(text, limit)
    if !out.truncated then ZIO.succeed(out.text)
    else
      events.publish(
        FlowEvent.Info(s"⚠ context: $label truncated ${out.originalChars} → ${out.text.length} chars")
      ) *> record(Truncation(label, out.originalChars, out.text.length, Kind.Capped)).as(out.text)

  /** True for the two failure classes a smaller prompt can fix: a deterministic context overflow, and the empty
    * response gemini returns when a prompt is too large for it to even start. The overflow check delegates to
    * [[TransientRetry.isContextOverflowMessage]] — the same phrasing list [[TransientRetry.isContextOverflow]] matches
    * on a typed cause — so a message-only failure (no surviving typed `LlmError`) is still caught; keeping a second,
    * shorter copy here previously let four of the six overflow phrasings slip through unmatched.
    */
  private def shrinkable(e: FlowError): Boolean = e match
    case FlowError.Llm(message, cause) =>
      cause.exists(TransientRetry.isContextOverflow) ||
      TransientRetry.isContextOverflowMessage(message) ||
      message.toLowerCase.contains("empty response")
    case _                             => false

  /** Run `f` at `start` characters; on a shrinkable failure retry at 1/2, then 1/4, then give up. Repeating the same
    * oversized prompt cannot succeed, so shrinking is the only retry that makes sense for this failure class — this is
    * why context overflow is deliberately excluded from [[TransientRetry]]'s budget.
    *
    * Each shrink publishes a [[FlowEvent.Info]] and is recorded like any other truncation.
    */
  def withShrink[A](
    label: String,
    start: Int = budget,
  )(
    f: Int => IO[FlowError, A]
  )(using events: FlowEvents
  ): IO[FlowError, A] =
    def attempt(atChars: Int, rest: List[Int]): IO[FlowError, A] =
      f(atChars).catchSome {
        case e if shrinkable(e) && rest.nonEmpty =>
          events.publish(
            FlowEvent.Info(
              s"⚠ context: $label did not fit at $atChars chars — shrinking to ${rest.head}: ${e.message}"
            )
          ) *> record(Truncation(label, atChars, rest.head, Kind.Shrunk)) *> attempt(rest.head, rest.tail)
        case e if shrinkable(e)                  =>
          // cause = None deliberately: AutoResume.shouldResume only re-enters the whole flow when a FlowError.Llm
          // carries Some(cause), and would classify a "empty response" cause as flaky (isFlakyStream) and resumable.
          // Keeping the cause here would make AutoResume re-run the flow from the top, replaying this exact,
          // permanently-failing budget sequence forever — reintroducing the crash this ladder exists to fix.
          ZIO.fail(FlowError.Llm(
            s"$label exceeded the model's input limit even after shrinking to $atChars chars — " +
              s"lower LLM4ZIO_CONTEXT_BUDGET or scope this phase further (cause: ${e.message})",
            None,
          ))
      }
    attempt(start, List(start / 2, start / 4))
