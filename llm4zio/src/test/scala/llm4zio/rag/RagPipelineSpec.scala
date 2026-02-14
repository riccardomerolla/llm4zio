package llm4zio.rag

import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.json.*
import llm4zio.core.*

object RagPipelineSpec extends ZIOSpecDefault:

  private final class MockLlmService extends LlmService:
    override def execute(prompt: String): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(content = s"ANSWER\n$prompt"))

    override def executeStream(prompt: String): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(execute(prompt).map(response => LlmChunk(response.content, finishReason = Some("stop"))))

    override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
      ZIO.succeed(LlmResponse(content = messages.map(_.content).mkString("\n")))

    override def executeStreamWithHistory(messages: List[Message]): zio.stream.Stream[LlmError, LlmChunk] =
      ZStream.fromZIO(executeWithHistory(messages).map(response => LlmChunk(response.content, finishReason = Some("stop"))))

    override def executeWithTools(prompt: String, tools: List[llm4zio.tools.AnyTool]): IO[LlmError, ToolCallResponse] =
      ZIO.succeed(ToolCallResponse(content = Some(prompt), toolCalls = Nil, finishReason = "stop"))

    override def executeStructured[A: JsonCodec](prompt: String, schema: llm4zio.tools.JsonSchema): IO[LlmError, A] =
      ZIO.fail(LlmError.InvalidRequestError("not used"))

    override def isAvailable: UIO[Boolean] = ZIO.succeed(true)

  def spec = suite("RagPipeline")(
    test("indexes documents and reports progress") {
      for
        processor <- DocumentProcessor.default
        embeddings <- EmbeddingService.deterministic(dimensions = 32)
        store <- VectorStore.inMemory
        docs = List(
                 SourceDocument("cobol-1", "IDENTIFICATION DIVISION. PROCEDURE DIVISION."),
                 SourceDocument("cobol-2", "PERFORM UNTIL EOF. MOVE WS-VAR TO OUT."),
               )
        progress <- RagPipeline.indexDocuments(
                      documents = docs,
                      processor = processor,
                      embeddingService = embeddings,
                      vectorStore = store,
                      strategy = ChunkingStrategy.FixedSize(maxChars = 64, overlapChars = 8),
                    )
        size <- store.size
      yield assertTrue(
        progress.documentsProcessed == 2,
        progress.chunksIndexed == size,
        size > 0,
      )
    },
    test("retrieve-then-generate returns cited context") {
      for
        processor <- DocumentProcessor.default
        embeddings <- EmbeddingService.deterministic(dimensions = 64)
        store <- VectorStore.inMemory
        _ <- RagPipeline.indexDocuments(
               documents = List(
                 SourceDocument("doc-cobol", "COBOL uses IDENTIFICATION DIVISION and PROCEDURE DIVISION."),
                 SourceDocument("doc-java", "Java classes are compiled with javac and run on JVM."),
               ),
               processor = processor,
               embeddingService = embeddings,
               vectorStore = store,
               strategy = ChunkingStrategy.Semantic(maxChars = 160),
             )
        result <- RagPipeline.retrieveThenGenerate(
                    query = "What is IDENTIFICATION DIVISION in COBOL?",
                    llmService = new MockLlmService,
                    embeddingService = embeddings,
                    vectorStore = store,
                    topK = 2,
                  )
      yield assertTrue(
        result.retrieved.nonEmpty,
        result.citations.nonEmpty,
        result.prompt.contains("Retrieved context"),
        result.response.content.contains("ANSWER"),
      )
    },
    test("stream indexing emits incremental progress") {
      for
        processor <- DocumentProcessor.default
        embeddings <- EmbeddingService.deterministic(dimensions = 16)
        store <- VectorStore.inMemory
        updates <- RagPipeline
                     .indexDocumentsStream(
                       documents = ZStream(
                         SourceDocument("a", "COPYBOOK A"),
                         SourceDocument("b", "COPYBOOK B"),
                         SourceDocument("c", "COPYBOOK C"),
                       ),
                       processor = processor,
                       embeddingService = embeddings,
                       vectorStore = store,
                       strategy = ChunkingStrategy.FixedSize(maxChars = 20, overlapChars = 2),
                     )
                     .runCollect
      yield assertTrue(
        updates.nonEmpty,
        updates.last.documentsProcessed == 3,
      )
    },
  )
