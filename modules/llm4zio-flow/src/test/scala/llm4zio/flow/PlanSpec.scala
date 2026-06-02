package llm4zio.flow

import zio.Scope
import zio.test.*

object PlanSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Plan")(
    test("render then parse round-trips a plan with mixed task state") {
      val plan = Plan(
        epicId = "add-multiply",
        tasks = List(
          Task("Add multiply function", "Implement multiply(a, b) in Calc.", completed = true),
          Task("Test multiply", "Cover positive, negative, and zero cases."),
        ),
      )
      assertTrue(Plan.parse(plan.render) == Right(plan))
    },
    test("nextIncomplete returns the first not-completed task, None when all done") {
      val plan    = Plan(
        "epic",
        List(
          Task("a", "", completed = true),
          Task("b", "second"),
          Task("c", ""),
        ),
      )
      val allDone = Plan("epic", plan.tasks.map(_.copy(completed = true)))
      assertTrue(
        plan.nextIncomplete.contains(plan.tasks(1)),
        allDone.nextIncomplete.isEmpty,
      )
    },
    test("complete marks the matching task done, leaves others, survives a round-trip") {
      val plan = Plan("epic", List(Task("a", "desc"), Task("b", "other")))
      val done = plan.complete("a")
      assertTrue(
        done.tasks.head.completed,
        !done.tasks(1).completed,
        Plan.parse(done.render) == Right(done),
      )
    },
  )
