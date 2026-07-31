package llm4zio.flow

import zio.Scope
import zio.test.*

object ContextSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Context")(
    test("cap returns text at or under the limit untouched") {
      val short = "abcdef"
      val out   = Context.cap(short, 10)
      assertTrue(
        out.text == short,
        out.originalChars == 6,
        !out.truncated,
      )
    },
    test("cap keeps head and tail with an elision marker, never exceeding the limit") {
      val text = ("h" * 100) + ("t" * 100)
      val out  = Context.cap(text, 40)
      assertTrue(
        out.truncated,
        out.originalChars == 200,
        out.text.startsWith("h"),
        out.text.endsWith("t"),
        out.text.contains("[truncated]"),
        // The marker counts against the limit: room = 40 - 19 = 21, head = 21*3/4 = 15, tail = 6.
        out.text.length == 40,
        out.text.takeWhile(_ == 'h').length == 15,
        out.text.reverse.takeWhile(_ == 't').length == 6,
      )
    },
    test("cap handles a limit smaller than the marker without crashing") {
      val out = Context.cap("x" * 50, 4)
      assertTrue(out.truncated, out.text.nonEmpty, out.text.length <= 4)
    },
    test("cap never exceeds the limit, including at and below zero") {
      val zero = Context.cap("ab", 0)
      val neg  = Context.cap("ab", -5)
      assertTrue(
        zero.text.isEmpty,
        zero.truncated,
        zero.originalChars == 2,
        neg.text.isEmpty,
        neg.truncated,
      )
    },
    test("budget falls back to the 400k default") {
      // No env var set in the test JVM, and no llm4zio.* system property.
      assertTrue(Context.budget == 400_000)
    },
  )
