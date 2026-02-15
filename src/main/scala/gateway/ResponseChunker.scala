package gateway

import zio.*
import zio.stream.ZStream

import gateway.models.{ ChunkingConfig, NormalizedMessage }

object ResponseChunker:
  def chunkText(content: String, config: ChunkingConfig): List[String] =
    config.maxChars match
      case None                                   => List(content)
      case Some(limit) if limit <= 0              => List(content)
      case Some(limit) if content.length <= limit => List(content)
      case Some(limit)                            => split(content, limit, config.protectMarkdownCodeFences)

  def chunkTextForChannel(content: String, channelName: String): List[String] =
    chunkText(content, ChunkingConfig.forChannel(channelName))

  def chunkMessage(message: NormalizedMessage, config: ChunkingConfig): List[NormalizedMessage] =
    val parts = chunkText(message.content, config)
    if parts.length <= 1 then List(message)
    else
      parts.zipWithIndex.map {
        case (part, idx) =>
          val oneBased = idx + 1
          message.copy(
            id = s"${message.id}:$oneBased",
            content = part,
            metadata = message.metadata ++ Map(
              "chunkIndex" -> oneBased.toString,
              "chunkTotal" -> parts.length.toString,
              "chunked"    -> "true",
            ),
          )
      }

  def chunkMessageForChannel(message: NormalizedMessage): List[NormalizedMessage] =
    chunkMessage(message, ChunkingConfig.forChannel(message.channelName))

  def aggregateStream(stream: ZStream[Any, Nothing, String]): UIO[String] =
    stream.runFold(new StringBuilder) { (acc, part) =>
      acc.append(part)
    }.map(_.toString)

  def aggregateAndChunk(
    stream: ZStream[Any, Nothing, String],
    config: ChunkingConfig,
  ): UIO[List[String]] =
    aggregateStream(stream).map(chunkText(_, config))

  def aggregateAndChunkForChannel(
    stream: ZStream[Any, Nothing, String],
    channelName: String,
  ): UIO[List[String]] =
    aggregateAndChunk(stream, ChunkingConfig.forChannel(channelName))

  private def split(content: String, limit: Int, protectCodeFences: Boolean): List[String] =
    @annotation.tailrec
    def loop(remaining: String, acc: List[String]): List[String] =
      if remaining.isEmpty then acc.reverse
      else if remaining.length <= limit then (remaining :: acc).reverse
      else
        val splitAt = selectSplitIndex(remaining, limit, protectCodeFences)
        if splitAt <= 0 then loop(remaining.drop(limit), remaining.take(limit) :: acc)
        else
          val head = remaining.substring(0, splitAt)
          val tail = remaining.substring(splitAt)
          loop(tail, head :: acc)

    loop(content, Nil)

  private def selectSplitIndex(text: String, limit: Int, protectCodeFences: Boolean): Int =
    val max = Math.min(limit, text.length)
    final case class ScanState(
      idx: Int,
      inFence: Boolean,
      lastDoubleNewLine: Int,
      lastNewLine: Int,
      lastSentenceEnd: Int,
      lastSpace: Int,
      lastSafe: Int,
    )

    @annotation.tailrec
    def scan(state: ScanState): ScanState =
      if state.idx >= max then state
      else if protectCodeFences && state.idx + 3 <= text.length && text.startsWith("```", state.idx) then
        scan(state.copy(idx = state.idx + 3, inFence = !state.inFence))
      else
        val ch      = text.charAt(state.idx)
        val updated =
          if !state.inFence then
            val safe          = state.idx + 1
            val space         = if ch == ' ' then safe else state.lastSpace
            val newLine       = if ch == '\n' then safe else state.lastNewLine
            val doubleNewLine =
              if ch == '\n' && state.idx > 0 && text.charAt(state.idx - 1) == '\n' then safe
              else state.lastDoubleNewLine
            val sentenceEnd   =
              if (ch == '.' || ch == '!' || ch == '?') && state.idx + 1 < max && text.charAt(state.idx + 1) == ' '
              then safe
              else state.lastSentenceEnd
            state.copy(
              idx = state.idx + 1,
              lastDoubleNewLine = doubleNewLine,
              lastNewLine = newLine,
              lastSentenceEnd = sentenceEnd,
              lastSpace = space,
              lastSafe = safe,
            )
          else state.copy(idx = state.idx + 1)
        scan(updated)

    val scanned = scan(
      ScanState(
        idx = 0,
        inFence = false,
        lastDoubleNewLine = -1,
        lastNewLine = -1,
        lastSentenceEnd = -1,
        lastSpace = -1,
        lastSafe = -1,
      )
    )

    if scanned.lastDoubleNewLine > 0 then scanned.lastDoubleNewLine
    else if scanned.lastNewLine > 0 then scanned.lastNewLine
    else if scanned.lastSentenceEnd > 0 then scanned.lastSentenceEnd
    else if scanned.lastSpace > 0 then scanned.lastSpace
    else if scanned.lastSafe > 0 then scanned.lastSafe
    else max
