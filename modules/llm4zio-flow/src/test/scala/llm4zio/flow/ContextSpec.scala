package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.LlmError

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
    test("capped publishes an event and records the truncation") {
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        out             <- Context.capped("specs", "x" * 1000, 100)
        seen            <- events.recorded
        recs            <- Context.truncations
      yield assertTrue(
        out.length <= 100,
        seen.exists { case FlowEvent.Info(m) => m.contains("specs") && m.contains("1000"); case _ => false },
        recs.size == 1,
        recs.head.label == "specs",
        recs.head.originalChars == 1000,
        recs.head.keptChars <= 100,
      )
    },
    test("capped records nothing when the text fits") {
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        out             <- Context.capped("specs", "small", 100)
        seen            <- events.recorded
        recs            <- Context.truncations
      yield assertTrue(out == "small", seen.isEmpty, recs.isEmpty)
    },
    test("withShrink retries at half budget after a context overflow and records the shrink") {
      val overflow = LlmError.ProviderError(
        """[API Error: {"error":{"code":400,"message":"The input token count exceeds the maximum """ +
          """number of tokens allowed 1048576.","status":"INVALID_ARGUMENT"}}]""",
        None,
      )
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        calls           <- Ref.make(List.empty[Int])
        out             <- Context.withShrink("judge", start = 1000) { cap =>
                             calls.update(_ :+ cap) *>
                               (if cap > 500 then ZIO.fail(FlowError.Llm(overflow.message, Some(overflow)))
                                else ZIO.succeed(s"ok@$cap"))
                           }
        seen            <- calls.get
        recs            <- Context.truncations
      yield assertTrue(
        out == "ok@500",
        seen == List(1000, 500),
        recs.exists(_.label == "judge"),
      )
    },
    test("withShrink retries on an empty response too") {
      val empty = LlmError.ProviderError("Invalid stream: empty response", None)
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        calls           <- Ref.make(0)
        out             <- Context.withShrink("judge", start = 1000) { cap =>
                             calls.updateAndGet(_ + 1).flatMap { n =>
                               if n == 1 then ZIO.fail(FlowError.Llm(empty.message, Some(empty)))
                               else ZIO.succeed(s"ok@$cap")
                             }
                           }
      yield assertTrue(out == "ok@500")
    },
    test("withShrink fails with a budget-naming message once the ladder is exhausted") {
      val overflow = LlmError.ProviderError("input token count exceeds the limit", None)
      for
        events          <- FlowEvents.collecting
        given FlowEvents = events
        res             <- Context
                             .withShrink("judge", start = 1000)(_ => ZIO.fail(FlowError.Llm(overflow.message, Some(overflow))))
                             .either
      yield assertTrue(
        res.isLeft,
        res.left.exists(_.message.contains("LLM4ZIO_CONTEXT_BUDGET")),
      )
    },
  )
