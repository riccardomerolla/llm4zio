package llm4zio.javaapi

import llm4zio.flow.Plan

/** The Java-facing result of [[JavaFlow.assessThenPlan]]: proceed with a plan, or blocked with a reason. A non-generic
  * sealed enum, mirroring the flow layer's covariant `Verdict[Plan]` — which doesn't interop with Java's invariant
  * generics (`verdict instanceof Verdict.Blocked` won't compile against a `Verdict<Plan>`).
  *
  * The `isBlocked`/`getReason`/`getPlan` accessors let straight-line Java branch without `instanceof` + cast:
  *
  * {{{
  * var a = flow.assessThenPlan(payload);
  * if (a.isBlocked()) { flow.gh().writeIssueComment(ref, a.getReason()); return; }
  * var plan = a.getPlan();
  * }}}
  */
enum JavaAssessment:
  case Proceed(plan: Plan)
  case Blocked(reason: String)

  /** True when the assessment blocked the request. */
  def isBlocked: Boolean = this match
    case _: Blocked => true
    case _          => false

  /** The blocking reason. Throws [[IllegalStateException]] on a [[Proceed]] — check [[isBlocked]] first. */
  def getReason: String = this match
    case Blocked(why) => why
    case _: Proceed   => throw new IllegalStateException(
        "getReason() on Proceed — check isBlocked() first"
      ) // scalafix:ok DisableSyntax.throw

  /** The approved plan. Throws [[IllegalStateException]] on a [[Blocked]] — check [[isBlocked]] first. */
  def getPlan: Plan = this match
    case Proceed(p) => p
    case _: Blocked => throw new IllegalStateException(
        "getPlan() on Blocked — check isBlocked() first"
      ) // scalafix:ok DisableSyntax.throw
