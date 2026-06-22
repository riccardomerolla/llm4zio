package llm4zio.eval

import zio.Scope
import zio.test.*

object ChecksSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Checks (Layer 1 deterministic)")(
    test("noPii scores max on clean text and 0 when PII is present") {
      for
        clean <- Checks.noPii().evaluate("The weather is fine today.")
        email <- Checks.noPii().evaluate("contact me at jane.doe@example.com")
        ssn   <- Checks.noPii().evaluate("my ssn is 123-45-6789")
        card  <- Checks.noPii().evaluate("card 4111 1111 1111 1111")
        ip    <- Checks.noPii().evaluate("host at 192.168.0.1")
      yield assertTrue(
        clean.score("no-pii").contains(2),
        email.score("no-pii").contains(0),
        ssn.score("no-pii").contains(0),
        card.score("no-pii").contains(0),
        ip.score("no-pii").contains(0),
        email.scores.head.reasoning.contains("email"),
      )
    },
    test("validJson passes valid JSON and fails non-JSON") {
      for
        ok  <- Checks.validJson().evaluate("""{"a":1}""")
        bad <- Checks.validJson().evaluate("not json at all")
      yield assertTrue(ok.score("valid-json").contains(2), bad.score("valid-json").contains(0))
    },
    test("matches checks a whole-string regex") {
      for
        ok  <- Checks.matches("[a-z]+", name = "lower").evaluate("abc")
        bad <- Checks.matches("[a-z]+", name = "lower").evaluate("abc123")
      yield assertTrue(ok.score("lower").contains(2), bad.score("lower").contains(0))
    },
    test("lengthBetween respects inclusive bounds") {
      for
        inside <- Checks.lengthBetween(2, 4).evaluate("abc")
        atMin  <- Checks.lengthBetween(2, 4).evaluate("ab")
        atMax  <- Checks.lengthBetween(2, 4).evaluate("abcd")
        over   <- Checks.lengthBetween(2, 4).evaluate("abcde")
      yield assertTrue(
        inside.score("length").contains(2),
        atMin.score("length").contains(2),
        atMax.score("length").contains(2),
        over.score("length").contains(0),
      )
    },
  )
