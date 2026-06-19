package llm4zio.flow

import zio.*
import zio.test.*

import llm4zio.observability.StreamRecorder

object TeeSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Tee")(
    test("forwards rawLine and streamError to both primary and sink") {
      for
        primRaw <- Ref.make(Chunk.empty[String])
        primErr <- Ref.make(Chunk.empty[String])
        sinkBuf <- Ref.make(Chunk.empty[String])
        primary  = new StreamRecorder:
                     def rawLine(p: String, m: Option[String], l: String): UIO[Unit]     = primRaw.update(_ :+ l)
                     def streamError(p: String, m: Option[String], s: String): UIO[Unit] = primErr.update(_ :+ s)
        tee      = new Tee(primary, line => sinkBuf.update(_ :+ line))
        _       <- tee.rawLine("gemini-cli", Some("m"), """{"type":"x"}""")
        _       <- tee.streamError("gemini-cli", None, "Invalid stream")
        pr      <- primRaw.get
        pe      <- primErr.get
        sb      <- sinkBuf.get
      yield assertTrue(
        pr == Chunk("""{"type":"x"}"""),
        pe == Chunk("Invalid stream"),
        sb.exists(_.contains("""{"type":"x"}""")),
        sb.exists(_.contains("Invalid stream")),
      )
    }
  )
