package decision.entity

import zio.Scope
import zio.test.*

object QuickOptionSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("QuickOption")(
    test("default set has Approve, RequestRework, and Acknowledge (no Escalate)") {
      val keys = QuickOption.defaults.map(_.key).toSet
      assertTrue(
        keys == Set("approve", "rework", "ack"),
        QuickOption.defaults.map(_.resolution).toSet == Set(
          DecisionResolutionKind.Approved,
          DecisionResolutionKind.ReworkRequested,
          DecisionResolutionKind.Acknowledged,
        ),
      )
    },
    test("each preset option carries a non-empty default rationale") {
      assertTrue(
        List(QuickOption.Approve, QuickOption.RequestRework, QuickOption.Acknowledge, QuickOption.Escalate)
          .forall(_.rationale.nonEmpty)
      )
    },
    test("findByKey matches case-insensitively and trims whitespace") {
      assertTrue(
        QuickOption.findByKey(QuickOption.defaults, "approve").contains(QuickOption.Approve),
        QuickOption.findByKey(QuickOption.defaults, " APPROVE ").contains(QuickOption.Approve),
        QuickOption.findByKey(QuickOption.defaults, "missing").isEmpty,
      )
    },
  )
