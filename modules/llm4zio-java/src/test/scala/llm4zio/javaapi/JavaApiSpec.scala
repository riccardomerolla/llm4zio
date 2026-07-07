package llm4zio.javaapi

import zio.Scope
import zio.test.*

import llm4zio.flow.{ Plan, Task }

/** Java-ergonomics contracts of the facade's value types: the accessors a Java flow branches with, in place of the
  * pattern matching Scala would use.
  */
object JavaApiSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & Scope, Any] = suite("javaapi surface")(
    test("JavaAssessment accessors branch without instanceof: Blocked carries the reason, Proceed the plan") {
      val plan    = Plan("epic-1", List(Task("t", "d")))
      val proceed = JavaAssessment.Proceed(plan)
      val blocked = JavaAssessment.Blocked("too vague")
      assertTrue(
        !proceed.isBlocked,
        blocked.isBlocked,
        blocked.getReason == "too vague",
        proceed.getPlan == plan,
      )
    },
    test("JavaAssessment accessors throw IllegalStateException on the wrong case") {
      val wrongPlan   = scala.util.Try(JavaAssessment.Blocked("no").getPlan)
      val wrongReason = scala.util.Try(JavaAssessment.Proceed(Plan("e", Nil)).getReason)
      assertTrue(
        wrongPlan.failed.toOption.exists(_.isInstanceOf[IllegalStateException]),
        wrongReason.failed.toOption.exists(_.isInstanceOf[IllegalStateException]),
      )
    },
    test("BuildResult predicates identify exactly their own case") {
      assertTrue(
        BuildResult.Success.isSuccess,
        !BuildResult.Success.isFailure,
        BuildResult.Failure.isFailure,
        BuildResult.Pending.isPending,
        BuildResult.TimedOut.isTimedOut,
        !BuildResult.TimedOut.isSuccess,
      )
    },
    test("CommitResult predicates identify exactly their own case") {
      assertTrue(
        CommitResult.Committed.isCommitted,
        !CommitResult.Committed.isNothingToCommit,
        CommitResult.NothingToCommit.isNothingToCommit,
        !CommitResult.NothingToCommit.isCommitted,
      )
    },
    test("Plans.withBrief attaches the brief a Java caller can't copy() in") {
      val plan = Plan("epic-1", List(Task("t", "d")))
      val got  = Plans.withBrief(plan, "the spec")
      assertTrue(got.brief.contains("the spec"), got.epicId == plan.epicId, plan.brief.isEmpty)
    },
    test("Evals builders: sample nulls become absent, dimension defaults, evalCase bar") {
      // the Java-null contract is exactly what's under test here
      val s = Evals.sample("resp", null, "expected") // scalafix:ok DisableSyntax.null
      val d = Evals.dimension("correctness", "does it work")
      val c = Evals.evalCase("case-1", s)
      assertTrue(
        s.query.isEmpty,
        s.expected.contains("expected"),
        d.maxScore == 2,
        c.minPerDimension == 1,
        Evals.evalCase("strict", s, 2).minPerDimension == 2,
      )
    },
    test("Evals.belowBar filters exactly the failing dimensions") {
      val result = llm4zio.eval.EvalResult(
        List(
          llm4zio.eval.DimensionScore("correctness", 2),
          llm4zio.eval.DimensionScore("scope", 1),
          llm4zio.eval.DimensionScore("safety", 0),
        )
      )
      val below  = Evals.belowBar(result, 2)
      assertTrue(below.size == 2, below.get(0).name == "scope", below.get(1).name == "safety", result.meets(0))
    },
    test("Reviewers.plus extends a roster without mutating it") {
      val base     = Reviewers.minimal()
      val extended = Reviewers.plus(base, Reviewers.tddDiscipline())
      assertTrue(
        extended.size == base.size + 1,
        extended.get(extended.size - 1) == Reviewers.tddDiscipline(),
        Reviewers.minimal().size == base.size, // source roster untouched
      )
    },
  )
