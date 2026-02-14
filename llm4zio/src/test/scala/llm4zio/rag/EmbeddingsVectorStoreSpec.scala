package llm4zio.rag

import zio.*
import zio.test.*

object EmbeddingsVectorStoreSpec extends ZIOSpecDefault:
  def spec = suite("EmbeddingsVectorStore")(
    test("deterministic embeddings are stable and normalized") {
      for
        service <- EmbeddingService.deterministic(dimensions = 32)
        a <- service.embed("MOVE A TO B")
        b <- service.embed("MOVE A TO B")
        norm = math.sqrt(a.map(x => x * x).sum)
      yield assertTrue(
        a == b,
        math.abs(norm - 1.0) < 1e-9,
      )
    },
    test("cached embeddings avoid repeated base calls") {
      for
        calls <- Ref.make(0)
        base = new EmbeddingService:
                 override def embed(text: String): IO[EmbeddingError, Vector[Double]] =
                   calls.updateAndGet(_ + 1).as(Vector(1.0, 0.0))

                 override def embedBatch(texts: Chunk[String]): IO[EmbeddingError, Chunk[Vector[Double]]] =
                   ZIO.foreach(texts)(embed).map(Chunk.fromIterable)
        cached <- EmbeddingService.cached(base)
        _ <- cached.embed("copybook")
        _ <- cached.embed("copybook")
        count <- calls.get
      yield assertTrue(count == 1)
    },
    test("vector search ranks more similar document first") {
      for
        store <- VectorStore.inMemory
        _ <- store.upsert(VectorDocument("d1", "alpha", Vector(1.0, 0.0), Map("domain" -> "cobol")))
        _ <- store.upsert(VectorDocument("d2", "beta", Vector(0.0, 1.0), Map("domain" -> "java")))
        results <- store.search(Vector(0.9, 0.1), topK = 2)
      yield assertTrue(
        results.head.document.id == "d1",
        results.length == 2,
      )
    },
    test("vector search supports metadata filter") {
      for
        store <- VectorStore.inMemory
        _ <- store.upsert(VectorDocument("d1", "alpha", Vector(1.0, 0.0), Map("domain" -> "cobol")))
        _ <- store.upsert(VectorDocument("d2", "beta", Vector(0.9, 0.1), Map("domain" -> "java")))
        results <- store.search(Vector(1.0, 0.0), topK = 5, metadataFilter = Map("domain" -> "cobol"))
      yield assertTrue(
        results.length == 1,
        results.head.document.id == "d1",
      )
    },
  )
