package agent.entity

import zio.Scope
import zio.test.*

object EmployeeRoleSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("EmployeeRole")(
    test("fromString recognises canonical role names and personal aliases") {
      assertTrue(
        EmployeeRole.fromString("pm").contains(EmployeeRole.PM),
        EmployeeRole.fromString("Pat").contains(EmployeeRole.PM),
        EmployeeRole.fromString("frontend").contains(EmployeeRole.FrontendEng),
        EmployeeRole.fromString("alex").contains(EmployeeRole.FrontendEng),
        EmployeeRole.fromString("backend").contains(EmployeeRole.BackendEng),
        EmployeeRole.fromString("ben").contains(EmployeeRole.BackendEng),
        EmployeeRole.fromString("qa").contains(EmployeeRole.QA),
        EmployeeRole.fromString("Dana").contains(EmployeeRole.QA),
        EmployeeRole.fromString("reviewer").contains(EmployeeRole.Reviewer),
        EmployeeRole.fromString("rex").contains(EmployeeRole.Reviewer),
        EmployeeRole.fromString("").contains(EmployeeRole.Custom),
        EmployeeRole.fromString("custom").contains(EmployeeRole.Custom),
        EmployeeRole.fromString("unknown").isEmpty,
      )
    },
    test("displayName is human-readable for every role") {
      val names = EmployeeRole.values.toList.map(_.displayName)
      assertTrue(
        names.size == 6,
        names.forall(_.nonEmpty),
        names.contains("PM / Triage"),
        names.contains("Frontend Engineer"),
        names.contains("Reviewer"),
      )
    },
  )
