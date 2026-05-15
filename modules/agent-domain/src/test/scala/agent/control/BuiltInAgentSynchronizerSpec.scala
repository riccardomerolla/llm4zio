package agent.control

import java.time.Instant

import zio.Scope
import zio.test.*

import agent.entity.{ DefaultRoster, EmployeeRole }

object BuiltInAgentSynchronizerSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-05-15T12:00:00Z")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("BuiltInAgentSynchronizer")(
    test("seedBuiltInAgents includes the typed default roster") {
      val seeded     = BuiltInAgentSynchronizer.seedBuiltInAgents(now)
      val rosterNames = DefaultRoster.all.map(_.name).toSet
      val seededNames = seeded.map(_.name).toSet
      assertTrue(
        rosterNames.subsetOf(seededNames),
        seeded.exists(_.name == "pat"),
        seeded.exists(_.name == "alex"),
        seeded.exists(_.name == "ben"),
        seeded.exists(_.name == "dana"),
        seeded.exists(_.name == "rex"),
      )
    },
    test("roster employees carry their typed roles") {
      val seeded   = BuiltInAgentSynchronizer.seedBuiltInAgents(now)
      val pat      = seeded.find(_.name == "pat").get
      val alex     = seeded.find(_.name == "alex").get
      val ben      = seeded.find(_.name == "ben").get
      val dana     = seeded.find(_.name == "dana").get
      val rex      = seeded.find(_.name == "rex").get
      assertTrue(
        pat.role == EmployeeRole.PM,
        alex.role == EmployeeRole.FrontendEng,
        ben.role == EmployeeRole.BackendEng,
        dana.role == EmployeeRole.QA,
        rex.role == EmployeeRole.Reviewer,
      )
    },
    test("roster employees honour the one-in-flight invariant") {
      val seeded = BuiltInAgentSynchronizer.seedBuiltInAgents(now)
      val roster = seeded.filter(a => DefaultRoster.all.map(_.name).contains(a.name))
      assertTrue(roster.forall(_.maxConcurrentRuns == 1))
    },
    test("seed produces createdAt/updatedAt at the given instant for all rows") {
      val seeded = BuiltInAgentSynchronizer.seedBuiltInAgents(now)
      assertTrue(
        seeded.nonEmpty,
        seeded.forall(_.createdAt == now),
        seeded.forall(_.updatedAt == now),
      )
    },
  )
