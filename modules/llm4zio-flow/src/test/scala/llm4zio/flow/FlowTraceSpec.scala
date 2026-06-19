package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.core.TokenUsage

object FlowTraceSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlowTrace")(
    test("maps FlowEvent cases to a kind + fields") {
      val started = TraceEvent.fromFlow(FlowEvent.StageStarted("branch"))
      val tokens  = TraceEvent.fromFlow(FlowEvent.TokensUsed("coder", Some("gemini-2.5-pro"), TokenUsage(10, 2, 12)))
      assertTrue(
        started.kind == "StageStarted",
        started.fields("stage") == "branch",
        tokens.kind == "TokensUsed",
        tokens.fields("agent") == "coder",
        tokens.fields("model") == "gemini-2.5-pro",
        tokens.fields("total") == "12",
      )
    },
    test("low-level RawLine and StreamError carry their payload") {
      val raw = TraceEvent.RawLine("gemini-cli", Some("gemini-2.5-pro"), """{"type":"error"}""")
      val err = TraceEvent.StreamError("gemini-cli", None, "Invalid stream: empty response")
      assertTrue(
        raw.kind == "RawLine",
        raw.fields("provider") == "gemini-cli",
        raw.fields("line") == """{"type":"error"}""",
        err.kind == "StreamError",
        err.fields("message") == "Invalid stream: empty response",
        !err.fields.contains("model"),
      )
    },
    test("TraceLine.toJson is one valid object with the envelope fields") {
      val line = TraceLine(7L, "2026-06-19T10:00:00Z", "rid", "Info", Map("message" -> "hi")).toJson
      assertTrue(
        line.startsWith("{") && line.endsWith("}"),
        line.contains("\"seq\":7"),
        line.contains("\"runId\":\"rid\""),
        line.contains("\"kind\":\"Info\""),
        line.contains("\"message\":\"hi\""),
        !line.contains("\n"),
      )
    },
    test("runId is a timestamp slug of the expected shape") {
      for id <- FlowTrace.runId
      yield assertTrue(id.matches("""\d{8}-\d{6}-\d{3}"""))
    },
    test("prune keeps the newest `keep` trace files and deletes older ones") {
      import java.nio.file.{ Files, attribute }
      for
        dir   <- ZIO.attemptBlocking(Files.createTempDirectory("prune-test")).orDie
        _     <- ZIO.attemptBlocking {
                   // three trace files + one unrelated file; stamp distinct mtimes
                   List("trace-a.jsonl" -> 1000L, "trace-b.jsonl" -> 2000L, "trace-c.jsonl" -> 3000L)
                     .foreach {
                       case (name, ms) =>
                         val p = dir.resolve(name)
                         Files.writeString(p, "x")
                         Files.setLastModifiedTime(p, attribute.FileTime.fromMillis(ms))
                     }
                   Files.writeString(dir.resolve("keep-me.txt"), "x")
                 }.orDie
        _     <- FlowTrace.prune(dir, keep = 1)
        names <- ZIO.attemptBlocking {
                   import scala.jdk.CollectionConverters.*
                   Files.list(dir).iterator.asScala.map(_.getFileName.toString).toSet
                 }.orDie
      yield assertTrue(
        names.contains("trace-c.jsonl"), // newest kept
        !names.contains("trace-a.jsonl"),
        !names.contains("trace-b.jsonl"),
        names.contains("keep-me.txt"), // non-trace files untouched
      )
    },
  )
