package llm4zio.rag

import zio.*
import zio.test.*

object DocumentProcessorSpec extends ZIOSpecDefault:
  private val sample = SourceDocument(
    id = "doc-1",
    content =
      """IDENTIFICATION DIVISION.
        |PROGRAM-ID. HELLO.
        |
        |PROCEDURE DIVISION.
        |DISPLAY 'HELLO'.
        |
        |STOP RUN.
        |""".stripMargin,
    metadata = Map("source" -> "cobol-guide"),
  )

  def spec = suite("DocumentProcessor")(
    test("extract metadata includes base fields") {
      for
        processor <- DocumentProcessor.default
        metadata <- processor.extractMetadata(sample)
      yield assertTrue(
        metadata.get("documentId").contains("doc-1"),
        metadata.contains("lines"),
        metadata.get("source").contains("cobol-guide"),
      )
    },
    test("fixed-size chunking preserves overlap") {
      for
        processor <- DocumentProcessor.default
        chunks <- processor.chunk(sample.copy(content = "A" * 50), ChunkingStrategy.FixedSize(maxChars = 20, overlapChars = 5))
      yield assertTrue(
        chunks.length == 4,
        chunks.head.endOffset > chunks(1).startOffset,
      )
    },
    test("semantic chunking follows paragraph boundaries") {
      for
        processor <- DocumentProcessor.default
        chunks <- processor.chunk(sample, ChunkingStrategy.Semantic(maxChars = 80))
      yield assertTrue(
        chunks.length >= 2,
        chunks.head.metadata.get("strategy").contains("semantic"),
      )
    },
    test("recursive chunking splits oversized text") {
      for
        processor <- DocumentProcessor.default
        chunks <- processor.chunk(
                    sample.copy(content = ("COBOL RULES. " * 400).trim),
                    ChunkingStrategy.Recursive(maxChars = 240, minChunkChars = 60),
                  )
      yield assertTrue(
        chunks.length > 1,
        chunks.forall(_.content.length <= 240),
      )
    },
  )
