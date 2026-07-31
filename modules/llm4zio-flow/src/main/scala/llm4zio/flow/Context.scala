package llm4zio.flow

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
