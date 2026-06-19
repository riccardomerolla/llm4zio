package llm4zio.flow

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

import llm4zio.core.TokenUsage

object FlowRecorderSpec extends ZIOSpecDefault:

  private def linesOf(p: Path): List[String] =
    Files.readAllLines(p).asScala.toList.filter(_.nonEmpty)

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("FlowRecorder")(
    test("serializes high-level and low-level events to JSONL in monotonic seq order") {
      ZIO.scoped {
        for
          dir <- ZIO.attemptBlocking(Files.createTempDirectory("trace-test")).orDie
          file = dir.resolve("trace-rid.jsonl")
          rec <- FlowRecorder.open(file, "rid")
          _   <- rec.record(FlowEvent.StageStarted("branch"))
          _   <- rec.rawLine("gemini-cli", Some("gemini-2.5-pro"), """{"type":"error"}""")
          _   <- rec.streamError("gemini-cli", None, "Invalid stream: empty response")
          _   <- rec.record(FlowEvent.TokensUsed("coder", Some("m"), TokenUsage(1, 2, 3)))
          ls  <- ZIO.attemptBlocking(linesOf(file)).orDie
        yield assertTrue(
          ls.length == 4,
          ls.head.contains("\"seq\":0") && ls.head.contains("\"kind\":\"StageStarted\""),
          ls(1).contains("\"seq\":1") && ls(1).contains("\"kind\":\"RawLine\""),
          ls(2).contains("\"seq\":2") && ls(2).contains("Invalid stream"),
          ls(3).contains("\"seq\":3") && ls(3).contains("\"kind\":\"TokensUsed\""),
        )
      }
    },
    test("never fails when the file cannot be written (degrades silently)") {
      ZIO.scoped {
        for
          dir  <- ZIO.attemptBlocking(Files.createTempDirectory("trace-test")).orDie
          // A path whose parent is a regular file => writes fail. The recorder must still succeed.
          clash = dir.resolve("afile")
          _    <- ZIO.attemptBlocking(Files.writeString(clash, "x")).orDie
          file  = clash.resolve("trace-rid.jsonl")
          rec  <- FlowRecorder.open(file, "rid")
          _    <- rec.record(FlowEvent.Info("noise"))
          _    <- rec.rawLine("gemini-cli", None, "x")
        yield assertCompletes
      }
    },
    test("consume(hub) records events published to the hub") {
      ZIO.scoped {
        for
          dir <- ZIO.attemptBlocking(Files.createTempDirectory("trace-test")).orDie
          file = dir.resolve("trace-rid.jsonl")
          rec <- FlowRecorder.open(file, "rid")
          hub <- FlowEvents.hub()
          _   <- rec.consume(hub)
          _   <- hub.publish(FlowEvent.StageStarted("design"))
          _   <- hub.publish(FlowEvent.StageCompleted("design"))
          // give the forked subscriber a moment to drain
          _   <- ZIO.sleep(50.millis)
          ls  <- ZIO.attemptBlocking(linesOf(file)).orDie
        yield assertTrue(ls.exists(_.contains("\"design\"")), ls.length == 2)
      }
    } @@ TestAspect.withLiveClock,
    test("install prunes, opens under .llm4zio naming, subscribes to the hub, and installs the ambient recorder") {
      import llm4zio.observability.StreamRecorder
      ZIO.scoped {
        for
          dir     <- ZIO.attemptBlocking(Files.createTempDirectory("install-test")).orDie
          hub     <- FlowEvents.hub()
          rec     <- FlowRecorder.install(hub, dir, keep = 20)
          ambient <- StreamRecorder.current.get
          _       <- hub.publish(FlowEvent.StageStarted("specify"))
          _       <- ZIO.sleep(50.millis)
          files   <- ZIO.attemptBlocking(Files.list(dir).iterator.asScala.map(_.getFileName.toString).toList).orDie
          // also exercise the ambient low-level channel
          _       <- ambient.rawLine("gemini-cli", None, "raw-x")
          _       <- ZIO.sleep(20.millis)
          trace    = files.find(n => n.startsWith("trace-") && n.endsWith(".jsonl")).get
          lines   <- ZIO.attemptBlocking(linesOf(dir.resolve(trace))).orDie
        yield assertTrue(
          ambient eq rec,
          files.exists(n => n.startsWith("trace-") && n.endsWith(".jsonl")),
          lines.exists(_.contains("specify")),
          lines.exists(_.contains("raw-x")),
        )
      }
    } @@ TestAspect.withLiveClock,
    test("install with a rawTerminalSink tees raw provider lines to file AND sink; tracePath points at the file") {
      import llm4zio.observability.StreamRecorder
      ZIO.scoped {
        for
          dir     <- ZIO.attemptBlocking(Files.createTempDirectory("install-tee")).orDie
          hub     <- FlowEvents.hub()
          sinkBuf <- Ref.make(Chunk.empty[String])
          rec     <-
            FlowRecorder.install(hub, dir, keep = 20, rawTerminalSink = Some((l: String) => sinkBuf.update(_ :+ l)))
          ambient <- StreamRecorder.current.get
          _       <- ambient.rawLine("gemini-cli", None, "raw-y")
          _       <- ZIO.sleep(20.millis)
          sb      <- sinkBuf.get
          lines   <- ZIO.attemptBlocking(linesOf(rec.tracePath)).orDie
        yield assertTrue(
          rec.tracePath.getFileName.toString.startsWith("trace-"),
          sb.exists(_.contains("raw-y")), // teed to the sink
          lines.exists(_.contains("raw-y")), // still in the file
        )
      }
    } @@ TestAspect.withLiveClock,
  )
