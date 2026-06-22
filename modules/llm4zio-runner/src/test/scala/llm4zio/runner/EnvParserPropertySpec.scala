package llm4zio.runner

import zio.Scope
import zio.test.*

/** Property-based laws shared by the integer env parsers (`LLM4ZIO_RETRIES` / `_FLAKY_RETRIES` / `_TRACE_KEEP`): they
  * are total, never negative, round-trip a non-negative integer, and fall back to their default on blank or invalid
  * input. The example specs check a few points; these check the whole input space.
  */
object EnvParserPropertySpec extends ZIOSpecDefault:

  private val parsers: List[(String, Option[String] => Int)] = List(
    "RetryEnv"      -> RetryEnv.parse,
    "FlakyRetryEnv" -> FlakyRetryEnv.parse,
    "TraceKeepEnv"  -> TraceKeepEnv.parse,
  )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("integer env parser laws")(
    parsers.map { (name, parse) =>
      suite(name)(
        test("total and non-negative for any input")(
          check(Gen.option(Gen.string))(o => assertTrue(parse(o) >= 0))
        ),
        test("round-trips a non-negative integer")(
          check(Gen.int(0, 1000000))(n => assertTrue(parse(Some(n.toString)) == n))
        ),
        test("blank input falls back to the default")(
          check(Gen.stringBounded(0, 4)(Gen.elements(' ', '\t')))(s => assertTrue(parse(Some(s)) == parse(None)))
        ),
        test("non-numeric input falls back to the default")(
          check(Gen.alphaNumericStringBounded(1, 8).filter(_.toIntOption.isEmpty))(s =>
            assertTrue(parse(Some(s)) == parse(None))
          )
        ),
      )
    }*
  )
