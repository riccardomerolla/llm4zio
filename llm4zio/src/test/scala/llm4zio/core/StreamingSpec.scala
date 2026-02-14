package llm4zio.core

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.stream.*
import zio.json.*

object StreamingSpec extends ZIOSpecDefault:
  def spec = suite("Streaming")(
    suite("collect")(
      test("should aggregate chunks into complete response") {
        val chunks = ZStream(
          LlmChunk(delta = "Hello ", metadata = Map("provider" -> "test")),
          LlmChunk(delta = "world", usage = Some(TokenUsage(10, 5, 15))),
          LlmChunk(delta = "!", finishReason = Some("stop")),
        )

        for {
          response <- Streaming.collect(chunks)
        } yield assertTrue(
          response.content == "Hello world!",
          response.usage.contains(TokenUsage(10, 5, 15)),
          response.metadata("provider") == "test",
        )
      },
      test("should handle empty stream") {
        val emptyStream = ZStream.empty.mapError(_ => LlmError.ProviderError("test", None))
        
        for {
          response <- Streaming.collect(emptyStream)
        } yield assertTrue(
          response.content == "",
          response.usage.isEmpty,
        )
      },
    ),
    suite("trackProgress")(
      test("should track token count and throughput") {
        val chunks = ZStream(
          LlmChunk(delta = "test "),  // ~1 token
          LlmChunk(delta = "message "),  // ~2 tokens
          LlmChunk(delta = "content"),  // ~2 tokens
        )

        for {
          progressRef <- Ref.make(List.empty[StreamProgress])
          result      <- Streaming
                           .trackProgress(chunks, progress => progressRef.update(progress :: _))
                           .runCollect
          progress    <- progressRef.get
        } yield assertTrue(
          result.size == 3,
          progress.nonEmpty,
          progress.forall(_.tokensProcessed > 0),
        )
      },
    ),
    suite("buffered")(
      test("should buffer stream with backpressure") {
        val chunks = ZStream.range(0, 100).map(i => LlmChunk(delta = s"chunk-$i"))
        
        for {
          buffered <- Streaming.buffered(chunks, capacity = 10).runCollect
        } yield assertTrue(buffered.size == 100)
      },
    ),
    suite("batch")(
      test("should group chunks into batches") {
        val chunks = ZStream.range(0, 15).map(i => LlmChunk(delta = s"$i"))
        
        for {
          batches <- Streaming.batch(chunks, maxSize = 5, maxDuration = 1.second).runCollect
        } yield assertTrue(
          batches.size == 3,
          batches(0).size == 5,
          batches(1).size == 5,
          batches(2).size == 5,
        )
      },
    ),
    suite("withTimeout")(
      test("should complete within timeout") {
        val chunks = ZStream(
          LlmChunk(delta = "fast"),
          LlmChunk(delta = " response"),
        )
        
        for {
          result <- Streaming.withTimeout(chunks, timeout = 5.seconds).runCollect
        } yield assertTrue(result.size == 2)
      },
      test("should fail on timeout") {
        val slowChunks = ZStream(
          LlmChunk(delta = "slow"),
        ) ++ ZStream.fromZIO(ZIO.sleep(10.seconds) *> ZIO.succeed(LlmChunk(delta = "late")))
        
        for {
          fiber <- Streaming.withTimeout(slowChunks, timeout = 100.millis).runCollect.exit.fork
          _     <- TestClock.adjust(100.millis)
          result <- fiber.join
        } yield assertTrue(result.isFailure)
      },
    ),
    suite("cancellable")(
      test("should support cancellation") {
        val infiniteStream = ZStream.repeatZIO(
          ZIO.sleep(10.millis) *> ZIO.succeed(LlmChunk(delta = "chunk"))
        )
        
        for {
          tuple                      <- Streaming.cancellable(infiniteStream)
          (cancellableStream, cancel) = tuple
          fiber                      <- cancellableStream.runCollect.fork
          _                          <- TestClock.adjust(50.millis)
          _                          <- cancel
          result                     <- fiber.await
        } yield assertTrue(result.isSuccess)
      },
    ),
    suite("withSnapshots")(
      test("should emit periodic content snapshots") {
        val chunks = ZStream(
          LlmChunk(delta = "Hello "),
          LlmChunk(delta = "world "),
          LlmChunk(delta = "from "),
          LlmChunk(delta = "streaming"),
        ) ++ ZStream.fromZIO(ZIO.sleep(100.millis) *> ZIO.succeed(LlmChunk(delta = "")))
        
        for {
          fiber     <- Streaming.withSnapshots(chunks, snapshotInterval = 100.millis).runCollect.fork
          _         <- TestClock.adjust(200.millis)
          snapshots <- fiber.join
        } yield assertTrue(
          snapshots.nonEmpty,
          snapshots.last == "Hello world from streaming",
        )
      },
    ),
    suite("withFallback")(
      test("should use fallback on error") {
        val failingStream = ZStream.fail(LlmError.ProviderError("primary failed", None))
        val fallbackStream = ZStream(LlmChunk(delta = "fallback response"))
        
        for {
          result <- Streaming.withFallback(failingStream, fallbackStream).runCollect
        } yield assertTrue(
          result.size == 1,
          result.head.delta == "fallback response",
        )
      },
      test("should not use fallback on success") {
        val successStream = ZStream(LlmChunk(delta = "primary response"))
        val fallbackStream = ZStream(LlmChunk(delta = "fallback response"))
        
        for {
          result <- Streaming.withFallback(successStream, fallbackStream).runCollect
        } yield assertTrue(
          result.size == 1,
          result.head.delta == "primary response",
        )
      },
    ),
    suite("toSSE and fromSSE")(
      test("should convert to and from SSE format") {
        val chunks = ZStream(
          LlmChunk(delta = "Hello", metadata = Map("provider" -> "test")),
          LlmChunk(delta = " world", finishReason = Some("stop")),
        )
        
        for {
          sse       <- Streaming.toSSE(chunks).runCollect
          converted <- Streaming.fromSSE(ZStream.fromIterable(sse)).runCollect
        } yield assertTrue(
          sse.size == 2,
          sse.forall(_.startsWith("data: ")),
          converted.size == 2,
          converted(0).delta == "Hello",
          converted(1).delta == " world",
        )
      },
      test("should filter out [DONE] marker") {
        val sseLines = ZStream(
          "data: " + LlmChunk(delta = "test").toJson,
          "data: [DONE]",
        )
        
        for {
          chunks <- Streaming.fromSSE(sseLines).runCollect
        } yield assertTrue(chunks.size == 1)
      },
    ),
    suite("mergeAll")(
      test("should merge multiple streams") {
        val stream1 = ZStream(LlmChunk(delta = "stream1"))
        val stream2 = ZStream(LlmChunk(delta = "stream2"))
        val stream3 = ZStream(LlmChunk(delta = "stream3"))
        
        for {
          merged <- Streaming.mergeAll(List(stream1, stream2, stream3)).runCollect
        } yield assertTrue(merged.size == 3)
      },
    ),
    suite("parallelStream")(
      test("should process items in parallel") {
        val inputs = List(1, 2, 3, 4, 5)
        val processItem = (n: Int) => ZStream(LlmChunk(delta = s"item-$n"))
        
        for {
          results <- Streaming.parallelStream(inputs, parallelism = 3)(processItem).runCollect
        } yield assertTrue(results.size == 5)
      },
    ),
    suite("parsePartialJson")(
      test("should parse complete JSON from stream") {
        case class TestData(value: String) derives JsonCodec
        
        val jsonStream = ZStream(
          """{"val""",
          """ue":""",
          """"test"}""",
        )
        
        for {
          parsed <- Streaming.parsePartialJson[TestData](jsonStream).runCollect
        } yield assertTrue(
          parsed.size == 1,
          parsed.head.value == "test",
        )
      },
    ),
  )
