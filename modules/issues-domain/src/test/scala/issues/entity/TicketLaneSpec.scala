package issues.entity

import zio.Scope
import zio.test.*

object TicketLaneSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TicketLane")(
    test("fromString recognises canonical lane names and common aliases") {
      assertTrue(
        TicketLane.fromString("frontend").contains(TicketLane.Frontend),
        TicketLane.fromString("UI").contains(TicketLane.Frontend),
        TicketLane.fromString("backend").contains(TicketLane.Backend),
        TicketLane.fromString("api").contains(TicketLane.Backend),
        TicketLane.fromString("testing").contains(TicketLane.Testing),
        TicketLane.fromString("qa").contains(TicketLane.Testing),
        TicketLane.fromString("triage").contains(TicketLane.Triage),
        TicketLane.fromString("intake").contains(TicketLane.Triage),
        TicketLane.fromString("review").contains(TicketLane.Review),
        TicketLane.fromString("").contains(TicketLane.Custom),
        TicketLane.fromString("custom").contains(TicketLane.Custom),
        TicketLane.fromString("unknown").isEmpty,
      )
    },
    test("displayName is human-readable for every lane") {
      val names = TicketLane.values.toList.map(_.displayName)
      assertTrue(
        names.size == 6,
        names.forall(_.nonEmpty),
        names.contains("Frontend"),
        names.contains("Review"),
      )
    },
  )
