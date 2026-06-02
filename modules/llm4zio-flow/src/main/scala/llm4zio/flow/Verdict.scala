package llm4zio.flow

import zio.json.{ DeriveJsonCodec, JsonCodec, jsonDiscriminator }

/** A worth-doing gate: either proceed with a value, or blocked with a reason. Used by [[Planner.assessThenPlan]]. */
@jsonDiscriminator("kind")
enum Verdict[+A]:
  case Proceed[A](value: A)    extends Verdict[A]
  case Blocked(reason: String) extends Verdict[Nothing]

object Verdict:
  given [A](using JsonCodec[A]): JsonCodec[Verdict[A]] = DeriveJsonCodec.gen[Verdict[A]]
