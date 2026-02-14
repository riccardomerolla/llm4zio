package llm4zio.rag

import zio.*
import zio.stream.*
import zio.json.*
import llm4zio.core.{ LlmError, LlmResponse, LlmService }

enum DocumentProcessingError:
  case InvalidInput(message: String)
  case EmbeddingFailed(error: EmbeddingError)
  case VectorStoreFailed(error: VectorStoreError)
  case GenerationFailed(error: LlmError)

case class SourceDocument(
  id: String,
  content: String,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

case class DocumentChunk(
  id: String,
  documentId: String,
  index: Int,
  content: String,
  startOffset: Int,
  endOffset: Int,
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

enum ChunkingStrategy derives JsonCodec:
  case FixedSize(maxChars: Int = 1000, overlapChars: Int = 120)
  case Semantic(maxChars: Int = 1200)
  case Recursive(maxChars: Int = 1500, minChunkChars: Int = 350)

case class IndexProgress(
  documentsTotal: Int,
  documentsProcessed: Int,
  chunksIndexed: Int,
) derives JsonCodec

case class RagQueryResult(
  query: String,
  expandedQueries: List[String],
  retrieved: List[VectorSearchResult],
  prompt: String,
  response: LlmResponse,
  citations: List[String],
) derives JsonCodec

trait DocumentProcessor:
  def extractMetadata(document: SourceDocument): UIO[Map[String, String]]
  def chunk(document: SourceDocument, strategy: ChunkingStrategy): IO[DocumentProcessingError, List[DocumentChunk]]

object DocumentProcessor:
  def default: UIO[DocumentProcessor] = ZIO.succeed(DefaultDocumentProcessor)

  def chunk(document: SourceDocument, strategy: ChunkingStrategy): ZIO[DocumentProcessor, DocumentProcessingError, List[DocumentChunk]] =
    ZIO.serviceWithZIO[DocumentProcessor](_.chunk(document, strategy))

  val defaultLayer: ULayer[DocumentProcessor] = ZLayer.succeed(DefaultDocumentProcessor)

private object DefaultDocumentProcessor extends DocumentProcessor:
  override def extractMetadata(document: SourceDocument): UIO[Map[String, String]] =
    val lines = document.content.linesIterator.toList
    val words = document.content.split("\\s+").count(_.nonEmpty)
    val base = Map(
      "documentId" -> document.id,
      "chars" -> document.content.length.toString,
      "lines" -> lines.length.toString,
      "words" -> words.toString,
    )
    ZIO.succeed(base ++ document.metadata)

  override def chunk(document: SourceDocument, strategy: ChunkingStrategy): IO[DocumentProcessingError, List[DocumentChunk]] =
    for
      _ <- validateDocument(document)
      chunks <- strategy match
                  case ChunkingStrategy.FixedSize(maxChars, overlapChars) =>
                    chunkFixed(document, maxChars, overlapChars)
                  case ChunkingStrategy.Semantic(maxChars)                 =>
                    chunkSemantic(document, maxChars)
                  case ChunkingStrategy.Recursive(maxChars, minChunkChars) =>
                    chunkRecursive(document, maxChars, minChunkChars)
    yield chunks

  private def validateDocument(document: SourceDocument): IO[DocumentProcessingError, Unit] =
    if document.id.trim.isEmpty then ZIO.fail(DocumentProcessingError.InvalidInput("Document id must be non-empty"))
    else if document.content.trim.isEmpty then ZIO.fail(DocumentProcessingError.InvalidInput(s"Document '${document.id}' content must be non-empty"))
    else ZIO.unit

  private def chunkFixed(document: SourceDocument, maxChars: Int, overlapChars: Int): IO[DocumentProcessingError, List[DocumentChunk]] =
    if maxChars <= 0 then ZIO.fail(DocumentProcessingError.InvalidInput("maxChars must be > 0"))
    else if overlapChars < 0 || overlapChars >= maxChars then
      ZIO.fail(DocumentProcessingError.InvalidInput("overlapChars must be >= 0 and < maxChars"))
    else
      ZIO.succeed {
        val text = document.content
        val step = (maxChars - overlapChars).max(1)

        Iterator
          .iterate(0)(_ + step)
          .takeWhile(_ < text.length)
          .zipWithIndex
          .map { case (start, index) =>
            val end = (start + maxChars).min(text.length)
            buildChunk(document, index, start, end, text.substring(start, end), "fixed")
          }
          .toList
      }

  private def chunkSemantic(document: SourceDocument, maxChars: Int): IO[DocumentProcessingError, List[DocumentChunk]] =
    if maxChars <= 0 then ZIO.fail(DocumentProcessingError.InvalidInput("maxChars must be > 0"))
    else
      ZIO.succeed {
        val paragraphs = document.content.split("\\n\\s*\\n").toList.map(_.trim).filter(_.nonEmpty)

        val (_, _, chunks) = paragraphs.foldLeft((0, 0, List.empty[DocumentChunk])) {
          case ((offset, idx, acc), paragraph) =>
            if paragraph.length <= maxChars then
              val start = findStart(document.content, paragraph, offset)
              val end = start + paragraph.length
              val chunk = buildChunk(document, idx, start, end, paragraph, "semantic")
              (end, idx + 1, acc :+ chunk)
            else
              val splits = splitBySentences(paragraph, maxChars)
              val (newOffset, newIdx, newAcc) = splits.foldLeft((offset, idx, acc)) {
                case ((innerOffset, innerIdx, innerAcc), part) =>
                  val start = findStart(document.content, part, innerOffset)
                  val end = start + part.length
                  val chunk = buildChunk(document, innerIdx, start, end, part, "semantic")
                  (end, innerIdx + 1, innerAcc :+ chunk)
              }
              (newOffset, newIdx, newAcc)
        }

        chunks
      }

  private def chunkRecursive(document: SourceDocument, maxChars: Int, minChunkChars: Int): IO[DocumentProcessingError, List[DocumentChunk]] =
    if maxChars <= 0 || minChunkChars <= 0 then
      ZIO.fail(DocumentProcessingError.InvalidInput("maxChars and minChunkChars must be > 0"))
    else if minChunkChars > maxChars then
      ZIO.fail(DocumentProcessingError.InvalidInput("minChunkChars must be <= maxChars"))
    else
      ZIO.succeed {
        val fragments = recursiveSplit(document.content, maxChars, minChunkChars)
        fragments.zipWithIndex.map { case ((start, end, text), idx) =>
          buildChunk(document, idx, start, end, text, "recursive")
        }
      }

  private def recursiveSplit(text: String, maxChars: Int, minChunkChars: Int): List[(Int, Int, String)] =
    def loop(start: Int, remaining: String): List[(Int, Int, String)] =
      if remaining.length <= maxChars then
        List((start, start + remaining.length, remaining))
      else
        val boundary = bestBoundary(remaining.take(maxChars), minChunkChars)
        val head = remaining.take(boundary)
        val tail = remaining.drop(boundary)
        (start, start + head.length, head) :: loop(start + head.length, tail)

    loop(0, text)

  private def bestBoundary(segment: String, minChunkChars: Int): Int =
    val candidates = List(
      segment.lastIndexOf("\n\n"),
      segment.lastIndexOf("\n"),
      segment.lastIndexOf(". "),
      segment.lastIndexOf(" "),
    ).filter(_ >= minChunkChars)

    candidates.headOption.map(_ + 1).getOrElse(segment.length)

  private def splitBySentences(text: String, maxChars: Int): List[String] =
    val sentences = text.split("(?<=[.!?])\\s+").toList.filter(_.nonEmpty)
    val (acc, current) = sentences.foldLeft((List.empty[String], "")) {
      case ((chunks, buffer), sentence) =>
        val candidate = if buffer.isEmpty then sentence else s"$buffer $sentence"
        if candidate.length <= maxChars then (chunks, candidate)
        else if buffer.isEmpty then (chunks :+ sentence.take(maxChars), "")
        else (chunks :+ buffer, sentence)
    }

    if current.nonEmpty then acc :+ current else acc

  private def findStart(fullText: String, target: String, from: Int): Int =
    val idx = fullText.indexOf(target, from)
    if idx >= 0 then idx else from

  private def buildChunk(
    document: SourceDocument,
    index: Int,
    start: Int,
    end: Int,
    content: String,
    strategy: String,
  ): DocumentChunk =
    DocumentChunk(
      id = s"${document.id}#${index}",
      documentId = document.id,
      index = index,
      content = content,
      startOffset = start,
      endOffset = end,
      metadata = document.metadata ++ Map(
        "strategy" -> strategy,
        "chunkIndex" -> index.toString,
      ),
    )

object RagPipeline:
  def indexDocuments(
    documents: List[SourceDocument],
    processor: DocumentProcessor,
    embeddingService: EmbeddingService,
    vectorStore: VectorStore,
    strategy: ChunkingStrategy,
  ): IO[DocumentProcessingError, IndexProgress] =
    for
      _ <- ZIO.fail(DocumentProcessingError.InvalidInput("At least one document is required for indexing")).when(documents.isEmpty)
      aggregated <- ZIO.foldLeft(documents.zipWithIndex)(IndexProgress(documents.length, 0, 0)) {
                      case (progress, (document, idx)) =>
                        for
                          metadata <- processor.extractMetadata(document)
                          chunks <- processor.chunk(document.copy(metadata = document.metadata ++ metadata), strategy)
                          embeddings <- embeddingService
                                          .embedBatch(Chunk.fromIterable(chunks.map(_.content)))
                                          .mapError(DocumentProcessingError.EmbeddingFailed.apply)
                          docs = chunks.zip(embeddings).map { case (chunk, embedding) =>
                                   VectorDocument(
                                     id = chunk.id,
                                     content = chunk.content,
                                     embedding = embedding,
                                     metadata = chunk.metadata ++ Map(
                                       "documentId" -> chunk.documentId,
                                       "startOffset" -> chunk.startOffset.toString,
                                       "endOffset" -> chunk.endOffset.toString,
                                     ),
                                   )
                                 }
                          _ <- vectorStore
                                 .upsertBatch(Chunk.fromIterable(docs))
                                 .mapError(DocumentProcessingError.VectorStoreFailed.apply)
                        yield progress.copy(
                          documentsProcessed = idx + 1,
                          chunksIndexed = progress.chunksIndexed + docs.length,
                        )
                    }
    yield aggregated

  def indexDocumentsStream(
    documents: ZStream[Any, DocumentProcessingError, SourceDocument],
    processor: DocumentProcessor,
    embeddingService: EmbeddingService,
    vectorStore: VectorStore,
    strategy: ChunkingStrategy,
  ): ZStream[Any, DocumentProcessingError, IndexProgress] =
    ZStream.unwrap {
      for
        counter <- Ref.make((0, 0))
      yield documents.mapZIO { document =>
        for
          metadata <- processor.extractMetadata(document)
          chunks <- processor.chunk(document.copy(metadata = document.metadata ++ metadata), strategy)
          embeddings <- embeddingService
                          .embedBatch(Chunk.fromIterable(chunks.map(_.content)))
                          .mapError(DocumentProcessingError.EmbeddingFailed.apply)
          docs = chunks.zip(embeddings).map { case (chunk, embedding) =>
                   VectorDocument(
                     id = chunk.id,
                     content = chunk.content,
                     embedding = embedding,
                     metadata = chunk.metadata ++ Map(
                       "documentId" -> chunk.documentId,
                       "startOffset" -> chunk.startOffset.toString,
                       "endOffset" -> chunk.endOffset.toString,
                     ),
                   )
                 }
          _ <- vectorStore.upsertBatch(Chunk.fromIterable(docs)).mapError(DocumentProcessingError.VectorStoreFailed.apply)
          snapshot <- counter.updateAndGet { case (docsProcessed, chunksIndexed) =>
                        (docsProcessed + 1, chunksIndexed + docs.length)
                      }
        yield IndexProgress(
          documentsTotal = -1,
          documentsProcessed = snapshot._1,
          chunksIndexed = snapshot._2,
        )
      }
    }

  def retrieveThenGenerate(
    query: String,
    llmService: LlmService,
    embeddingService: EmbeddingService,
    vectorStore: VectorStore,
    topK: Int = 5,
    queryExpansion: Boolean = true,
  ): IO[DocumentProcessingError, RagQueryResult] =
    for
      _ <- ZIO.fail(DocumentProcessingError.InvalidInput("RAG query must be non-empty")).when(query.trim.isEmpty)
      expanded = if queryExpansion then expandQuery(query) else List(query)
      queryEmbeddings <- embeddingService
                           .embedBatch(Chunk.fromIterable(expanded))
                           .mapError(DocumentProcessingError.EmbeddingFailed.apply)
      results <- ZIO.foreach(queryEmbeddings) { embedding =>
                   vectorStore.search(embedding, topK).mapError(DocumentProcessingError.VectorStoreFailed.apply)
                 }
      merged = mergeResults(results.flatten.toList, topK)
      context = renderContext(merged)
      prompt =
        s"""Answer the user query using the retrieved context.
           |If the context is insufficient, say what is missing.
           |
           |User query:
           |$query
           |
           |Retrieved context:
           |$context
           |
           |Include citations in the form [source:<documentId>#<chunkIndex>].
           |""".stripMargin
      response <- llmService.execute(prompt).mapError(DocumentProcessingError.GenerationFailed.apply)
      citations = merged.map(result => citationFor(result.document))
    yield RagQueryResult(
      query = query,
      expandedQueries = expanded,
      retrieved = merged,
      prompt = prompt,
      response = response,
      citations = citations,
    )

  private def expandQuery(query: String): List[String] =
    val trimmed = query.trim
    val lower = trimmed.toLowerCase
    List(
      trimmed,
      s"$trimmed best practices",
      s"$trimmed examples",
      if lower.contains("cobol") then s"$trimmed language reference" else s"COBOL $trimmed",
    ).distinct

  private def mergeResults(results: List[VectorSearchResult], topK: Int): List[VectorSearchResult] =
    results
      .groupBy(_.document.id)
      .values
      .map(group => group.maxBy(_.score))
      .toList
      .sortBy(result => -result.score)
      .take(topK)

  private def renderContext(results: List[VectorSearchResult]): String =
    if results.isEmpty then "No relevant context found."
    else
      results.map { result =>
        val citation = citationFor(result.document)
        s"$citation\n${result.document.content}"
      }.mkString("\n\n")

  private def citationFor(document: VectorDocument): String =
    val documentId = document.metadata.getOrElse("documentId", document.id.split("#").headOption.getOrElse(document.id))
    val chunkIndex = document.metadata.getOrElse("chunkIndex", document.id.split("#").lift(1).getOrElse("0"))
    s"[source:$documentId#$chunkIndex]"
