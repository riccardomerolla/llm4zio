package llm4zio.runner

import java.nio.file.Paths

import zio.test.*
import zio.{ Cause, Scope }

import llm4zio.flow.FlowError

object BannerSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Banner")(
    test("formats version and log path") {
      val out = Banner.line("2.3.0", Paths.get("/tmp/llm4zio-abc.log"))
      assertTrue(out == "llm4zio 2.3.0, logs: /tmp/llm4zio-abc.log")
    },
    test("version falls back to dev when manifest is absent") {
      assertTrue(Banner.version.nonEmpty)
    },
    test("failMessage appends a quota hint for gemini's ambiguous catch-all") {
      val msg = Llm4zio.failMessage(
        Cause.fail(FlowError.Llm("Gemini CLI stream error: [API Error: An unknown error occurred.]", None))
      )
      assertTrue(
        msg.contains("Gemini CLI stream error"),
        msg.toLowerCase.contains("quota"),
        msg.contains("LLM4ZIO_USAGE_WAIT"),
      )
    },
    test("failMessage does not append the quota hint for unrelated errors") {
      val msg = Llm4zio.failMessage(Cause.fail(FlowError.Llm("something else broke", None)))
      assertTrue(!msg.toLowerCase.contains("quota"), !msg.contains("LLM4ZIO_USAGE_WAIT"))
    },
    test("failMessage appends the wait-or-switch hint for a classified quota exhaustion") {
      val msg = Llm4zio.failMessage(
        Cause.fail(FlowError.Llm(
          "You have exhausted your capacity on this model. Your quota will reset after 21h1m53s.",
          None,
        ))
      )
      assertTrue(
        msg.contains("exhausted your capacity"),
        msg.contains("LLM4ZIO_USAGE_WAIT"),
        msg.contains("remaining quota"),
      )
    },
  )
