package gateway

import java.time.Instant

import zio.*
import zio.stream.ZStream
import zio.test.*

import gateway.models.*

object ResponseChunkerSpec extends ZIOSpecDefault:

  private val sampleMessage = NormalizedMessage(
    id = "m-1",
    channelName = "telegram",
    sessionKey = SessionKey("telegram", "conversation:42"),
    direction = MessageDirection.Outbound,
    role = MessageRole.Assistant,
    content = "",
    timestamp = Instant.EPOCH,
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ResponseChunkerSpec")(
    test("channel config uses telegram limit and web unlimited") {
      val tg  = ChunkingConfig.forChannel("telegram")
      val web = ChunkingConfig.forChannel("websocket")
      assertTrue(tg.maxChars.contains(4000), web.maxChars.isEmpty)
    },
    test("chunkText keeps web responses unbounded") {
      val content = "x" * 9000
      val chunks  = ResponseChunker.chunkText(content, ChunkingConfig.Web)
      assertTrue(chunks.length == 1, chunks.head.length == 9000)
    },
    test("chunkText splits telegram responses within max chars") {
      val content = ("hello world\n" * 500).trim
      val chunks  = ResponseChunker.chunkText(content, ChunkingConfig.Telegram)
      assertTrue(
        chunks.length >= 2,
        chunks.forall(_.length <= 4000),
        chunks.mkString == content,
      )
    },
    test("chunking avoids splitting inside markdown code fences") {
      val codeBlock =
        """Some intro.
          |
          |```scala
          |val x = 1
          |val y = x + 1
          |val z = y + 1
          |```
          |
          |Tail text.
          |""".stripMargin
      val content   = codeBlock + codeBlock + codeBlock
      val chunks    = ResponseChunker.chunkText(content, ChunkingConfig(maxChars = Some(90)))
      assertTrue(
        chunks.forall(chunk => chunk.sliding(3).count(_ == "```") % 2 == 0),
        chunks.mkString == content,
      )
    },
    test("chunkMessage annotates chunk metadata and ids") {
      val message = sampleMessage.copy(content = "a" * 4100)
      val chunks  = ResponseChunker.chunkMessageForChannel(message)
      assertTrue(
        chunks.length == 2,
        chunks.head.id == "m-1:1",
        chunks(1).id == "m-1:2",
        chunks.forall(_.metadata.get("chunked").contains("true")),
        chunks.forall(_.metadata.get("chunkTotal").contains("2")),
      )
    },
    test("aggregateAndChunk composes streaming aggregation with chunking") {
      val stream = ZStream("hello ", "world", "!" * 5000)
      for
        aggregated <- ResponseChunker.aggregateStream(stream)
        chunked    <- ResponseChunker.aggregateAndChunkForChannel(
                        ZStream("hello ", "world", "!" * 5000),
                        "telegram",
                      )
      yield assertTrue(
        aggregated.startsWith("hello world"),
        chunked.length >= 2,
        chunked.mkString == aggregated,
      )
    },
  )
