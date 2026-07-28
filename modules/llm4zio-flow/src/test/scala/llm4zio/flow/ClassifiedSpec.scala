package llm4zio.flow

import zio.Scope
import zio.test.*

import llm4zio.core.{ Capability, Grants }

/** The minimal information-flow wrapper (issue #716): accident-proof, not adversary-proof — sensitive values cannot
  * *inadvertently* reach a prompt, log line, or JSON payload, and the only exit is an audited, witness-gated
  * `declassify`.
  */
object ClassifiedSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Classified")(
    test("toString never reveals the value (no accidental interpolation into prompts or logs)") {
      val secret = Classified.of("hunter2")
      assertTrue(
        secret.toString == "Classified(…)",
        s"token: $secret" == "token: Classified(…)",
      )
    },
    test("map transforms without unwrapping — the result stays classified") {
      val secret = Classified.of("hunter2")
      val upper  = secret.map(_.toUpperCase)
      assertTrue(upper.toString == "Classified(…)")
    },
    test("declassify without the witness does not compile") {
      assertTrue(
        !scala.compiletime.testing.typeChecks(
          "import llm4zio.flow.*\ndef leak(c: Classified[String])(using FlowEvents): Any = c.declassify(\"x\")"
        ),
        scala.compiletime.testing.typeChecks(
          "import llm4zio.flow.*\ndef ok(c: Classified[String])(using FlowEvents, Caps.Declassify): Any = c.declassify(\"x\")"
        ),
      )
    },
    test("declassify with witness + grant returns the value and emits the audit event") {
      given Caps.All = Caps.grantAll
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        value           <- Classified.of("hunter2").declassify("test-secret")
        seen            <- events.recorded
      yield assertTrue(
        value == "hunter2",
        seen.contains(FlowEvent.Declassified("test-secret")),
      )
    },
    test("declassify under narrowed grants fails typed, and the value stays sealed") {
      given Caps.All = Caps.grantAll
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        res             <- Grants.restricted(Grants.none)(Classified.of("hunter2").declassify("blocked")).either
      yield assertTrue(res == Left(FlowError.CapabilityDenied(Capability.Declassify, "declassify blocked")))
    },
  )
