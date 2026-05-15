package agent.entity

import zio.Scope
import zio.test.*

object DefaultRosterSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DefaultRoster")(
    test("ships the locked 5-employee cast in canonical order") {
      val names = DefaultRoster.all.map(_.name)
      assertTrue(
        DefaultRoster.all.size == 5,
        names == List("pat", "alex", "ben", "dana", "rex"),
      )
    },
    test("every default employee has maxConcurrentRuns = 1 (one-in-flight invariant)") {
      assertTrue(
        DefaultRoster.all.forall(_.maxConcurrentRuns == 1)
      )
    },
    test("every default employee carries a typed role (no Custom in the seed)") {
      val roles = DefaultRoster.all.map(_.role).toSet
      assertTrue(
        roles == Set(
          EmployeeRole.PM,
          EmployeeRole.FrontendEng,
          EmployeeRole.BackendEng,
          EmployeeRole.QA,
          EmployeeRole.Reviewer,
        )
      )
    },
    test("every default employee has a non-empty system prompt persona") {
      assertTrue(
        DefaultRoster.all.forall(_.systemPrompt.exists(_.trim.nonEmpty))
      )
    },
    test("forRole resolves each typed role to its canonical employee") {
      assertTrue(
        DefaultRoster.forRole(EmployeeRole.PM).contains(DefaultRoster.pat),
        DefaultRoster.forRole(EmployeeRole.FrontendEng).contains(DefaultRoster.alex),
        DefaultRoster.forRole(EmployeeRole.BackendEng).contains(DefaultRoster.ben),
        DefaultRoster.forRole(EmployeeRole.QA).contains(DefaultRoster.dana),
        DefaultRoster.forRole(EmployeeRole.Reviewer).contains(DefaultRoster.rex),
        DefaultRoster.forRole(EmployeeRole.Custom).isEmpty,
      )
    },
  )
