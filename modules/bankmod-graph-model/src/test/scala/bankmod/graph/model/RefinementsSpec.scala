package bankmod.graph.model

import zio.test.*

object RefinementsSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("Refinements")(
    suite("SemVer")(
      test("accepts valid semver") {
        assertTrue(Refinements.SemVer.from("1.0.0").isRight)
      },
      test("accepts semver with pre-release") {
        assertTrue(Refinements.SemVer.from("2.3.1-alpha.1").isRight)
      },
      test("rejects non-semver string") {
        assertTrue(Refinements.SemVer.from("v1.banana").isLeft)
      },
    ),
    suite("LatencyMs")(
      test("accepts zero latency") {
        assertTrue(Refinements.LatencyMs.from(0).isRight)
      },
      test("accepts positive latency") {
        assertTrue(Refinements.LatencyMs.from(500).isRight)
      },
      test("accepts max latency") {
        assertTrue(Refinements.LatencyMs.from(3_600_000).isRight)
      },
      test("rejects negative latency") {
        assertTrue(Refinements.LatencyMs.from(-1).isLeft)
      },
      test("rejects latency exceeding max") {
        assertTrue(Refinements.LatencyMs.from(3_600_001).isLeft)
      },
    ),
    suite("Percentage")(
      test("accepts 0") {
        assertTrue(Refinements.Percentage.from(0).isRight)
      },
      test("accepts 100") {
        assertTrue(Refinements.Percentage.from(100).isRight)
      },
      test("rejects -1") {
        assertTrue(Refinements.Percentage.from(-1).isLeft)
      },
      test("rejects 101") {
        assertTrue(Refinements.Percentage.from(101).isLeft)
      },
    ),
    suite("BoundedRetries")(
      test("accepts 0") {
        assertTrue(Refinements.BoundedRetries.from(0).isRight)
      },
      test("accepts 10") {
        assertTrue(Refinements.BoundedRetries.from(10).isRight)
      },
      test("rejects -1") {
        assertTrue(Refinements.BoundedRetries.from(-1).isLeft)
      },
      test("rejects 11") {
        assertTrue(Refinements.BoundedRetries.from(11).isLeft)
      },
    ),
    suite("UrlLike")(
      test("accepts http URL") {
        assertTrue(Refinements.UrlLike.from("http://example.com/api").isRight)
      },
      test("accepts https URL with port") {
        assertTrue(Refinements.UrlLike.from("https://payments.svc:8080/v1").isRight)
      },
      test("rejects empty string") {
        assertTrue(Refinements.UrlLike.from("").isLeft)
      },
      test("rejects plain text") {
        assertTrue(Refinements.UrlLike.from("not a url at all!!!").isLeft)
      },
    ),
  )
