package llm4zio.core

import zio.*
import zio.test.*

/** The ambient runtime gate: a FiberRef holding the current [[Grants]], narrowed only via `locallyWith` intersection.
  * Copy-on-fork covers forked fibers; a parent-wins join means a child can never widen its parent — even one that
  * mutates the gate directly through the package-private escape this spec abuses on purpose.
  */
object GrantsGateSpec extends ZIOSpecDefault:

  private val gitReadOnly = Grants.none.copy(git = Grants.Level.Read, reasoning = true)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Grants gate")(
    test("current defaults to all (plain flows behave exactly as before)") {
      Grants.current.map(g => assertTrue(g == Grants.all))
    },
    test("restricted narrows inside the block and restores afterwards") {
      for
        inside <- Grants.restricted(gitReadOnly)(Grants.current)
        after  <- Grants.current
      yield assertTrue(inside == gitReadOnly, after == Grants.all)
    },
    test("nested restricted can only narrow further — an inner 'all' cannot widen") {
      Grants
        .restricted(gitReadOnly) {
          Grants.restricted(Grants.all)(Grants.current)
        }
        .map(g => assertTrue(g == gitReadOnly))
    },
    test("forked fibers inherit the narrowed grants (zipPar/race are covered by copy-on-fork)") {
      Grants.restricted(gitReadOnly) {
        for
          fiber <- Grants.current.fork
          seen  <- fiber.join
        yield assertTrue(seen == gitReadOnly)
      }
    },
    test("a child that widens the gate directly cannot leak the widening into its parent via join") {
      Grants.restricted(gitReadOnly) {
        for
          fiber <- (Grants.gate.set(Grants.all) *> Grants.current).fork
          child <- fiber.join
          seen  <- Grants.current
        yield assertTrue(child == Grants.all, seen == gitReadOnly)
      }
    },
  )
