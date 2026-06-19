package llm4zio.flow

import java.nio.file.Files

import zio.*
import zio.test.*

object ReplaySpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Replay")(
    test("round-trip: a recorder-written trace (failure then success) replays as fail-then-success") {
      ZIO.scoped {
        for
          dir  <- ZIO.attemptBlocking(Files.createTempDirectory("replay-rt")).orDie
          file  = dir.resolve("trace-rid.jsonl")
          rec  <- FlowRecorder.open(file, "rid")
          // Record a flaky failure turn, then a success turn (as the live run would).
          _    <- rec.streamError("gemini-cli", None, "Invalid stream: empty response")
          _    <- rec.record(FlowEvent.TokensUsed("coder", Some("m"), llm4zio.core.TokenUsage(1, 1, 2)))
          _    <- rec.record(FlowEvent.AssistantMessage("recovered"))
          conn <- Replay.fromTrace(file)
          // First subscription replays the failure; second replays the success.
          e1   <- conn.executeStream("p").runCollect.exit
          out  <- conn.executeStream("p").runCollect
        yield assertTrue(e1.isFailure, out.map(_.delta).mkString == "recovered")
      }
    },
    test("read skips a torn/unparseable final line instead of failing") {
      ZIO.scoped {
        for
          dir <- ZIO.attemptBlocking(Files.createTempDirectory("replay-torn")).orDie
          file = dir.resolve("trace-x.jsonl")
          good = TraceLine(0L, "t", "rid", "AssistantMessage", Map("text" -> "ok")).toJson
          _   <- ZIO.attemptBlocking(Files.writeString(file, good + "\n{ this is not json")).orDie
          ls  <- Replay.read(file)
        yield assertTrue(ls.length == 1, ls.head.kind == "AssistantMessage")
      }
    },
  )
