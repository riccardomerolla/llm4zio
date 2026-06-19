package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.{ LlmError, TokenUsage }

object ReplayConnectorSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ReplayConnector")(
    test("replays success turns in order with text + usage") {
      for
        conn <- ReplayConnector.make(
                  List(
                    ReplayTurn.Success("first", Some(TokenUsage(1, 2, 3)), Some("m")),
                    ReplayTurn.Success("second", None, None),
                  )
                )
        a    <- conn.executeStream("p").runCollect
        b    <- conn.executeStream("p").runCollect
      yield assertTrue(
        a.map(_.delta).mkString == "first",
        a.head.usage.contains(TokenUsage(1, 2, 3)),
        b.map(_.delta).mkString == "second",
      )
    },
    test("a Failure turn fails with a ProviderError carrying the recorded message") {
      for
        conn <- ReplayConnector.make(List(ReplayTurn.Failure("Invalid stream: empty response", None)))
        exit <- conn.executeStream("p").runCollect.exit
      yield assertTrue(
        exit.isFailure,
        exit.causeOption.exists(_.failureOption.exists {
          case LlmError.ProviderError(m, _) => m.contains("Invalid stream")
          case _                            => false
        }),
      )
    },
    test("a turn past the end fails with 'replay trace exhausted'") {
      for
        conn <- ReplayConnector.make(Nil)
        exit <- conn.executeStream("p").runCollect.exit
      yield assertTrue(exit.causeOption.exists(_.failureOption.exists(_.message.contains("exhausted"))))
    },
    test("wrapped in TransientRetry, a recorded flaky failure then success reproduces recovery") {
      given FlowEvents = FlowEvents.noop
      for
        conn <- ReplayConnector.make(
                  List(
                    ReplayTurn.Failure("Gemini CLI stream error: Invalid stream: empty response", None),
                    ReplayTurn.Success("ok", None, None),
                  )
                )
        out  <- TransientRetry(conn, flakyRetries = 2, flakyDelay = Duration.Zero).executeStream("p").runCollect
      yield assertTrue(out.map(_.delta).mkString == "ok")
    },
  )
