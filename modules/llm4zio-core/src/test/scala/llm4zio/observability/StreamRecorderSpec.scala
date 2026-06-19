package llm4zio.observability

import zio.*
import zio.test.*

object StreamRecorderSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("StreamRecorder")(
    test("default ambient recorder is the no-op") {
      for rec <- StreamRecorder.current.get
      yield assertTrue(rec eq StreamRecorder.noop)
    },
    test("no-op methods succeed and do nothing") {
      for
        _ <- StreamRecorder.noop.rawLine("gemini-cli", Some("m"), "x")
        _ <- StreamRecorder.noop.streamError("gemini-cli", None, "boom")
      yield assertCompletes
    },
    test("a locally-installed recorder is visible to current.get within the scope") {
      val probe = new StreamRecorder:
        def rawLine(p: String, m: Option[String], l: String): UIO[Unit]     = ZIO.unit
        def streamError(p: String, m: Option[String], s: String): UIO[Unit] = ZIO.unit
      ZIO.scoped {
        StreamRecorder.current.locallyScoped(probe) *>
          StreamRecorder.current.get.map(seen => assertTrue(seen eq probe))
      }
    },
  )
