package llm4zio.core

import zio.*
import zio.stream.*
import zio.json.*

/** Streaming utilities for LLM operations with backpressure and progress tracking */
object Streaming:

  /** Collect a stream of chunks into a complete response
    *
    * @param stream ZStream of LlmChunk
    * @return Complete LlmResponse with aggregated content
    */
  def collect(stream: Stream[LlmError, LlmChunk]): IO[LlmError, LlmResponse] =
    stream.runFold(
      LlmResponse(
        content = "",
        usage = None,
        metadata = Map.empty,
      )
    ) { (acc, chunk) =>
      acc.copy(
        content = acc.content + chunk.delta,
        usage = chunk.usage.orElse(acc.usage),
        metadata = acc.metadata ++ chunk.metadata,
      )
    }

  /** Track progress during streaming with token-level metrics
    *
    * @param stream ZStream of LlmChunk
    * @param onProgress Callback for progress updates
    * @return Stream with progress tracking side effects
    */
  def trackProgress(
    stream: Stream[LlmError, LlmChunk],
    onProgress: StreamProgress => UIO[Unit],
  ): Stream[LlmError, LlmChunk] =
    stream
      .mapAccumZIO((0, java.lang.System.currentTimeMillis())) { case ((tokensProcessed, startTime), chunk) =>
        val now           = java.lang.System.currentTimeMillis()
        val elapsedMs     = now - startTime
        val elapsedSec    = elapsedMs / 1000.0
        val newTokenCount = tokensProcessed + estimateTokens(chunk.delta)
        val tokensPerSec  = if elapsedSec > 0 then newTokenCount / elapsedSec else 0.0

        val progress = StreamProgress(
          tokensProcessed = newTokenCount,
          tokensPerSecond = tokensPerSec,
          elapsedMs = elapsedMs,
          estimatedRemainingMs = None, // Could be enhanced with model-specific estimates
        )

        onProgress(progress).as(((newTokenCount, startTime), chunk))
      }

  /** Estimate token count from text (rough approximation)
    *
    * Uses simple heuristic: ~4 characters per token for English text
    */
  private def estimateTokens(text: String): Int =
    math.max(1, text.length / 4)

  /** Extract partial results from incomplete JSON in streaming responses
    *
    * Attempts to parse JSON chunks and extract any valid complete objects
    *
    * @param stream Stream of text chunks
    * @return Stream of successfully parsed objects
    */
  def parsePartialJson[A: JsonCodec](
    stream: Stream[LlmError, String],
  ): Stream[LlmError, A] =
    stream
      .scan("")(_ + _)  // Accumulate chunks
      .map(accumulated => accumulated.fromJson[A])
      .collect { case Right(a) => a } // Emit only when a complete JSON object is available

  /** Retry streaming with exponential backoff on specific errors
    *
    * @param stream Stream to retry
    * @param policy Retry schedule
    * @return Stream with retry logic
    */
  def retryStream[E, A](
    stream: Stream[E, A],
    policy: Schedule[Any, E, Any],
  ): Stream[E, A] =
    stream.retry(policy)

  /** Buffer stream with bounded queue for backpressure management
    *
    * @param stream Source stream
    * @param capacity Buffer capacity
    * @return Buffered stream with backpressure
    */
  def buffered[E, A](
    stream: Stream[E, A],
    capacity: Int,
  ): Stream[E, A] =
    stream.buffer(capacity)

  /** Aggregate chunks into batches for more efficient processing
    *
    * @param stream Source stream
    * @param maxSize Max batch size (by count)
    * @param maxDuration Max time to wait for batch
    * @return Stream of chunk batches
    */
  def batch(
    stream: Stream[LlmError, LlmChunk],
    maxSize: Int = 10,
    maxDuration: Duration = 100.millis,
  ): Stream[LlmError, Chunk[LlmChunk]] =
    stream.groupedWithin(maxSize, maxDuration)

  /** Add timeout to streaming operations
    *
    * @param stream Source stream
    * @param timeout Maximum duration
    * @return Stream that fails on timeout
    */
  def withTimeout(
    stream: Stream[LlmError, LlmChunk],
    timeout: Duration,
  ): Stream[LlmError, LlmChunk] =
    stream.timeoutFail(LlmError.TimeoutError(timeout))(timeout)

  /** Merge multiple streams with fair round-robin scheduling
    *
    * Useful for parallel processing of multiple LLM requests
    *
    * @param streams Collection of streams to merge
    * @return Single merged stream
    */
  def mergeAll(
    streams: Iterable[Stream[LlmError, LlmChunk]],
  ): Stream[LlmError, LlmChunk] =
    ZStream.mergeAllUnbounded()(streams.toSeq*)

  /** Add cancellation support to streaming operations
    *
    * Returns a tuple of (stream, cancel effect)
    *
    * @param stream Source stream
    * @return (Cancellable stream, cancellation effect)
    */
  def cancellable(
    stream: Stream[LlmError, LlmChunk],
  ): UIO[(Stream[LlmError, LlmChunk], UIO[Unit])] =
    for
      promise <- Promise.make[Nothing, Unit]
      cancellableStream = stream.interruptWhen(promise.await)
      cancel            = promise.succeed(()).unit
    yield (cancellableStream, cancel)

  /** Accumulate stream content with periodic snapshots
    *
    * Useful for showing partial results during long streaming operations
    *
    * @param stream Source stream
    * @param snapshotInterval How often to emit snapshots
    * @return Stream of cumulative content snapshots
    */
  def withSnapshots(
    stream: Stream[LlmError, LlmChunk],
    snapshotInterval: Duration = 500.millis,
  ): Stream[LlmError, String] =
    stream
      .scan("")((acc, chunk) => acc + chunk.delta)
      .debounce(snapshotInterval)

  /** Handle streaming errors with fallback recovery
    *
    * @param stream Primary stream
    * @param fallback Recovery stream
    * @return Stream that falls back on error
    */
  def withFallback(
    stream: Stream[LlmError, LlmChunk],
    fallback: => Stream[LlmError, LlmChunk],
  ): Stream[LlmError, LlmChunk] =
    stream.catchAll(_ => fallback)

  /** Rate limit streaming operations
    *
    * @param stream Source stream
    * @param maxPerSecond Maximum items per second
    * @return Rate-limited stream
    */
  def rateLimit(
    stream: Stream[LlmError, LlmChunk],
    maxPerSecond: Int,
  ): Stream[LlmError, LlmChunk] =
    val intervalMs = 1000 / maxPerSecond
    stream.schedule(Schedule.fixed(intervalMs.millis))

  /** Convert stream to SSE (Server-Sent Events) format
    *
    * @param stream Source stream
    * @return Stream of SSE-formatted strings
    */
  def toSSE(stream: Stream[LlmError, LlmChunk]): Stream[LlmError, String] =
    stream.map { chunk =>
      val data = chunk.toJson
      s"data: $data\n\n"
    }

  /** Parse SSE format stream into chunks
    *
    * @param sseStream Stream of SSE lines
    * @return Stream of parsed chunks
    */
  def fromSSE(sseStream: Stream[LlmError, String]): Stream[LlmError, LlmChunk] =
    sseStream
      .filter(_.startsWith("data: "))
      .map(_.stripPrefix("data: ").trim)
      .filter(_.nonEmpty)
      .filter(_ != "[DONE]")  // OpenAI sends [DONE] as final marker
      .mapZIO { jsonStr =>
        ZIO
          .fromEither(jsonStr.fromJson[LlmChunk])
          .mapError(err => LlmError.ParseError(s"Failed to parse SSE chunk: $err", jsonStr))
      }

  /** Monitor stream health with heartbeat timeout
    *
    * Fails if no items received within timeout period
    *
    * @param stream Source stream
    * @param heartbeatTimeout Max time between items
    * @return Stream with heartbeat monitoring
    */
  def withHeartbeat(
    stream: Stream[LlmError, LlmChunk],
    heartbeatTimeout: Duration,
  ): Stream[LlmError, LlmChunk] =
    stream
      .timeoutFail(LlmError.TimeoutError(heartbeatTimeout))(heartbeatTimeout)
      .rechunk(1)  // Ensure items are processed individually

  /** Parallel streaming with controlled concurrency
    *
    * Process multiple items in parallel while maintaining order
    *
    * @param inputs Input items to process
    * @param parallelism Level of parallelism
    * @param f Function that produces a stream for each input
    * @return Merged output stream
    */
  def parallelStream[A, B](
    inputs: List[A],
    parallelism: Int,
  )(f: A => Stream[LlmError, B]): Stream[LlmError, B] =
    ZStream
      .fromIterable(inputs)
      .mapZIOPar(parallelism)(a => f(a).runCollect.map(_.toList))
      .flatMap(ZStream.fromIterable(_))

end Streaming
