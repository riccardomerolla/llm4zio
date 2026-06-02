package llm4zio.flow

import zio.*
import zio.test.*

object ReviewLoopSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("fixLoop")(
    test("stops as soon as evaluate reports clean; fixes once") {
      for
        ev     <- FlowEvents.collecting
        rounds <- Ref.make(0)
        fixes  <- Ref.make(0)
        dirty   = ReviewResult(List(ReviewIssue(Severity.Warning, "x")))
        eval    = rounds.updateAndGet(_ + 1).map(n => if n == 1 then dirty else ReviewResult(Nil))
        res    <- fixLoop(eval, _ => fixes.update(_ + 1), maxRounds = 5)(using ev)
        nF     <- fixes.get
        nR     <- rounds.get
      yield assertTrue(res.isClean, nR == 2, nF == 1)
    },
    test("gives up after maxRounds when never clean; evaluates N, fixes N-1") {
      for
        ev     <- FlowEvents.collecting
        rounds <- Ref.make(0)
        fixes  <- Ref.make(0)
        dirty   = ReviewResult(List(ReviewIssue(Severity.Critical, "x")))
        eval    = rounds.update(_ + 1).as(dirty)
        res    <- fixLoop(eval, _ => fixes.update(_ + 1), maxRounds = 3)(using ev)
        nF     <- fixes.get
        nR     <- rounds.get
      yield assertTrue(!res.isClean, nR == 3, nF == 2)
    },
    test("clean on the first evaluate never calls fix") {
      for
        ev    <- FlowEvents.collecting
        fixes <- Ref.make(0)
        res   <- fixLoop(ZIO.succeed(ReviewResult(Nil)), _ => fixes.update(_ + 1))(using ev)
        nF    <- fixes.get
      yield assertTrue(res.isClean, nF == 0)
    },
  )
