package llm4zio.core

import zio.Scope
import zio.json.*
import zio.test.*
import zio.test.magnolia.*

/** Property-based round-trip laws for the core data model: every value of a `derives JsonCodec` type survives an
  * encode→decode cycle, and `LlmProvider.defaultBaseUrl` is total. These exercise the codecs over thousands of
  * generated shapes the example specs never reach.
  */
object CorePropertySpec extends ZIOSpecDefault:

  private def roundTrips[A: JsonCodec](a: A): TestResult =
    assertTrue(a.toJson.fromJson[A] == Right(a))

  private val genProvider: Gen[Sized, LlmProvider]       = DeriveGen[LlmProvider]
  private val genRole: Gen[Sized, MessageRole]           = DeriveGen[MessageRole]
  private val genMessage: Gen[Sized, Message]            = DeriveGen[Message]
  private val genUsage: Gen[Sized, TokenUsage]           = DeriveGen[TokenUsage]
  private val genCaps: Gen[Sized, ConnectorCapabilities] = DeriveGen[ConnectorCapabilities]

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("core property laws")(
    suite("JSON round-trips (encode∘decode == id)")(
      test("LlmProvider")(check(genProvider)(roundTrips(_))),
      test("MessageRole")(check(genRole)(roundTrips(_))),
      test("Message")(check(genMessage)(roundTrips(_))),
      test("TokenUsage")(check(genUsage)(roundTrips(_))),
      test("ConnectorCapabilities")(check(genCaps)(roundTrips(_))),
    ),
    test("LlmProvider.defaultBaseUrl is total (returns for every provider)") {
      check(genProvider)(p => assertTrue(LlmProvider.defaultBaseUrl(p).forall(_.nonEmpty)))
    },
  )
