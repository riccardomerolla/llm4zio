package llm4zio.runner

import zio.*

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig, Grants }
import llm4zio.flow.{ Caps, FlowContext, FlowError, FlowEvent, UsageLimitPolicy }

/** Script entry point — a thin frame over [[Llm4zio.unsafeMain]], the runner's shared process entry. A flow script is
  * two lines of frame:
  *
  * {{{
  * import llm4zio.flow.*
  * import llm4zio.runner.*
  *
  * flow(args, defaultPrompt = Some("Add a multiply function")):
  *   for
  *     plan <- Planner.from(reasoning, userPrompt)
  *     ...
  *   yield ()
  * }}}
  *
  * The body is an ordinary ZIO effect with the [[FlowContext]] in given scope (so `git`/`gh`/`coder`/`reasoning`/
  * `userPrompt` resolve bare, and `stage`/`implementTaskLoop` find their event sink) plus a full [[Caps.All]] witness —
  * plain `flow()` grants every capability, exactly as before capabilities existed. The coder defaults to the
  * `LLM4ZIO_CODER` selection (claude|codex|gemini, claude when unset); reasoning defaults to the coder's read-only
  * twin.
  *
  * [[flow.restricted]] is the tracked variant (issue #716): the type parameter declares the flow's capabilities once,
  * providing both the compile-time witnesses and (via [[Caps.grantsFor]]) the runtime [[llm4zio.core.Grants]] the flow
  * runs under.
  *
  * Process behaviour: Ctrl-C interrupts the flow fiber (stages unwind, the ✖ banner renders, JVM exits 130); a missing
  * prompt prints usage and exits 2; a failed flow exits 1 (the runner has already rendered the failure).
  */
object flow:

  def apply(
    args: Array[String],
    coder: CliConnectorConfig = Connectors.coderFromEnv(),
    reasoning: Option[ConnectorConfig] = None,
    defaultPrompt: Option[String] = None,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
    verbosity: Option[Verbosity] = None,
  )(
    body: (FlowContext, Caps.All) ?=> ZIO[Any, FlowError, Any]
  ): Unit =
    Llm4zio.unsafeMain(
      Llm4zio.script(args.toList, coder, reasoning, defaultPrompt, reviewers, usageLimit, verbosity) {
        body(using summon[FlowContext], Caps.grantAll)
      }
    )

  /** The tracked entry: `flow.restricted[Caps.GitRead & Caps.Reasoning](args) { body }`.
    *
    * The type parameter `C` is the single source of truth — it puts exactly those witnesses in the body's scope (a
    * summon outside `C` is a compile error) and derives the runtime grants the whole flow executes under, so the two
    * layers cannot drift. `refine` narrows the value-level data further (notably the `Exec` allowlist:
    * `refine = _.copy(exec = Grants.ExecGrant.Allow(Set("ls")))`); it is intersected with the derived grants, so it can
    * only narrow, never widen. Note that `Caps.Exec` unrefined grants the full allowlist — always pair it with a
    * `refine`.
    *
    * The grants are also translated onto the coder CLI itself via [[CoderPolicy]] (best-effort — the trust-boundary
    * statement lives on that object). Restrictions the connector cannot express surface as `CapabilityUnenforceable`
    * events; with `strictPolicy = true` the flow aborts instead of starting.
    */
  def restricted[C](
    args: Array[String],
    coder: CliConnectorConfig = Connectors.coderFromEnv(),
    reasoning: Option[ConnectorConfig] = None,
    defaultPrompt: Option[String] = None,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
    verbosity: Option[Verbosity] = None,
    refine: Grants => Grants = identity,
    strictPolicy: Boolean = false,
  )(
    body: (FlowContext, C) ?=> ZIO[Any, FlowError, Any]
  )(using
    tag: EnvironmentTag[C],
    ev: Caps.All <:< C,
  ): Unit =
    val derived = Caps.grantsFor[C]
    val granted = derived.intersect(refine(derived))
    val policy  = CoderPolicy(coder, granted)
    Llm4zio.unsafeMain(
      Llm4zio.script(args.toList, policy.config, reasoning, defaultPrompt, reviewers, usageLimit, verbosity) {
        val ctx  = summon[FlowContext]
        val gate =
          if strictPolicy && policy.unenforceable.nonEmpty then
            ZIO.fail(FlowError.Aborted(
              s"strict capability policy: ${policy.unenforceable.mkString("; ")} — " +
                "grant the capability, switch coder, or configure CliSandbox"
            ))
          else
            ZIO.foreachDiscard(policy.unenforceable)(d => ctx.events.publish(FlowEvent.CapabilityUnenforceable(d)))
        gate *> Grants.restricted(granted)(body(using ctx, ev(Caps.grantAll)))
      }
    )
