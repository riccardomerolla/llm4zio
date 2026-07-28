package llm4zio.flow

import zio.IO

import llm4zio.core.Capability

/** A sensitive value that cannot *inadvertently* leave the flow (issue #716).
  *
  * Honesty first: the article's `Classified` rests on capture checking, which proves `transform` functions leak
  * nothing; stable Scala cannot verify purity, so this wrapper is **accident-proof, not adversary-proof**. What it
  * prevents is the realistic failure mode — a token string-interpolated into a coder prompt, a secret in a log line or
  * a JSON payload: `toString` is redacted, there is deliberately no codec instance, and no implicit widening back to
  * `A`. The only exit is [[declassify]] — witness-gated at compile time, grant-checked at runtime, and always audited
  * with a [[FlowEvent.Declassified]] event.
  */
final class Classified[A] private (private val value: A):

  override def toString: String = "Classified(…)"

  /** Transform without unwrapping — the result stays classified. (Not a purity proof; see the class note.) */
  def map[B](f: A => B): Classified[B] = new Classified(f(value))

  /** The one exit. `label` names what is being revealed in the audit trail — never the value itself. */
  def declassify(label: String)(using events: FlowEvents, cap: Caps.Declassify): IO[FlowError, A] =
    Caps.guarded(Capability.Declassify, s"declassify $label", events)(
      events.publish(FlowEvent.Declassified(label)).as(value)
    )

object Classified:
  /** Seal a value. Prefer sealing at the source ([[env]]) so secrets are born wrapped. */
  def of[A](value: A): Classified[A] = new Classified(value)

  /** Read an environment variable directly into a [[Classified]], so the secret never exists unwrapped in flow code. */
  def env(name: String): Option[Classified[String]] = sys.env.get(name).map(new Classified(_))
