package llm4zio.flow

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object FormatterSpec extends ZIOSpecDefault:
  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-fmt-")).orDie)(d =>
      ZIO.attempt(Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))).orDie
    )

  private def infos(c: Chunk[FlowEvent]): List[String] = c.collect { case FlowEvent.Info(m) => m }.toList

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Formatter.step")(
    test("None or blank is a no-op with no events") {
      for
        ev              <- FlowEvents.collecting
        given FlowEvents = ev
        dir             <- tempDir
        _               <- Formatter.step(None, dir)
        _               <- Formatter.step(Some("   "), dir)
        rec             <- ev.recorded
      yield assertTrue(rec.isEmpty)
    },
    test("a succeeding command runs and announces, without surfacing an error") {
      for
        ev              <- FlowEvents.collecting
        given FlowEvents = ev
        dir             <- tempDir
        res             <- Formatter.step(Some("true"), dir).exit
        rec             <- ev.recorded
      yield assertTrue(
        res.isSuccess,
        infos(rec).exists(_.contains("format: true")),
        !infos(rec).exists(m => m.contains("exited") || m.contains("failed")),
      )
    },
    test("a non-zero formatter is surfaced but never fails the step") {
      for
        ev              <- FlowEvents.collecting
        given FlowEvents = ev
        dir             <- tempDir
        res             <- Formatter.step(Some("false"), dir).exit
        rec             <- ev.recorded
      yield assertTrue(res.isSuccess, infos(rec).exists(_.contains("format exited")))
    },
    test("a missing command is surfaced but never fails the step") {
      for
        ev              <- FlowEvents.collecting
        given FlowEvents = ev
        dir             <- tempDir
        res             <- Formatter.step(Some("llm4zio-no-such-cmd-xyz"), dir).exit
        rec             <- ev.recorded
      yield assertTrue(res.isSuccess, infos(rec).exists(_.contains("format")))
    },
  )
