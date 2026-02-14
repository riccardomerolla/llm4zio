package llm4zio.rag

import zio.*
import zio.json.*

enum VectorStoreError derives JsonCodec:
  case InvalidInput(message: String)
  case NotFound(id: String)
  case BackendError(message: String)

object VectorStoreError:
  extension (error: VectorStoreError)
    def message: String = error match
      case VectorStoreError.InvalidInput(msg) => msg
      case VectorStoreError.NotFound(id)       => s"Vector document not found: $id"
      case VectorStoreError.BackendError(msg)  => msg

case class VectorDocument(
  id: String,
  content: String,
  embedding: Vector[Double],
  metadata: Map[String, String] = Map.empty,
) derives JsonCodec

case class VectorSearchResult(
  document: VectorDocument,
  score: Double,
) derives JsonCodec

trait VectorStore:
  def upsert(document: VectorDocument): IO[VectorStoreError, Unit]
  def upsertBatch(documents: Chunk[VectorDocument]): IO[VectorStoreError, Unit]
  def search(
    queryEmbedding: Vector[Double],
    topK: Int,
    metadataFilter: Map[String, String] = Map.empty,
  ): IO[VectorStoreError, List[VectorSearchResult]]
  def delete(id: String): IO[VectorStoreError, Unit]
  def updateMetadata(id: String, metadata: Map[String, String]): IO[VectorStoreError, Unit]
  def get(id: String): IO[VectorStoreError, VectorDocument]
  def size: UIO[Int]
  def clear: UIO[Unit]

object VectorStore:
  def inMemory: UIO[VectorStore] =
    Ref.make(Map.empty[String, VectorDocument]).map(InMemoryVectorStore.apply)

  val inMemoryLayer: ULayer[VectorStore] = ZLayer.fromZIO(inMemory)

final case class InMemoryVectorStore(
  state: Ref[Map[String, VectorDocument]]
) extends VectorStore:

  override def upsert(document: VectorDocument): IO[VectorStoreError, Unit] =
    for
      _ <- validateDocument(document)
      _ <- state.update(_.updated(document.id, document))
    yield ()

  override def upsertBatch(documents: Chunk[VectorDocument]): IO[VectorStoreError, Unit] =
    ZIO.foreachDiscard(documents)(upsert)

  override def search(
    queryEmbedding: Vector[Double],
    topK: Int,
    metadataFilter: Map[String, String] = Map.empty,
  ): IO[VectorStoreError, List[VectorSearchResult]] =
    for
      _ <- validateEmbedding(queryEmbedding)
      _ <- ZIO.fail(VectorStoreError.InvalidInput("topK must be > 0")).when(topK <= 0)
      current <- state.get
      filtered = current.values.filter(matchesFilter(_, metadataFilter)).toList
      scored <- ZIO.foreach(filtered) { document =>
                  if document.embedding.length != queryEmbedding.length then
                    ZIO.fail(VectorStoreError.InvalidInput(s"Embedding dimension mismatch for document '${document.id}'"))
                  else
                    ZIO.succeed(VectorSearchResult(document, similarity(queryEmbedding, document.embedding)))
                }
    yield scored.sortBy(result => -result.score).take(topK)

  override def delete(id: String): IO[VectorStoreError, Unit] =
    state.modify { current =>
      if current.contains(id) then (ZIO.unit, current - id)
      else (ZIO.fail(VectorStoreError.NotFound(id)), current)
    }.flatten

  override def updateMetadata(id: String, metadata: Map[String, String]): IO[VectorStoreError, Unit] =
    state.modify { current =>
      current.get(id) match
        case Some(document) =>
          val updated = document.copy(metadata = document.metadata ++ metadata)
          (ZIO.unit, current.updated(id, updated))
        case None           =>
          (ZIO.fail(VectorStoreError.NotFound(id)), current)
    }.flatten

  override def get(id: String): IO[VectorStoreError, VectorDocument] =
    state.get.flatMap { current =>
      ZIO.fromOption(current.get(id)).orElseFail(VectorStoreError.NotFound(id))
    }

  override def size: UIO[Int] =
    state.get.map(_.size)

  override def clear: UIO[Unit] =
    state.set(Map.empty)

private def validateDocument(document: VectorDocument): IO[VectorStoreError, Unit] =
  if document.id.trim.isEmpty then ZIO.fail(VectorStoreError.InvalidInput("Document id must be non-empty"))
  else if document.content.trim.isEmpty then ZIO.fail(VectorStoreError.InvalidInput(s"Document '${document.id}' content must be non-empty"))
  else validateEmbedding(document.embedding)

private def validateEmbedding(embedding: Vector[Double]): IO[VectorStoreError, Unit] =
  if embedding.isEmpty then ZIO.fail(VectorStoreError.InvalidInput("Embedding must be non-empty"))
  else ZIO.unit

private def matchesFilter(document: VectorDocument, metadataFilter: Map[String, String]): Boolean =
  metadataFilter.forall { case (key, value) => document.metadata.get(key).contains(value) }

private def similarity(a: Vector[Double], b: Vector[Double]): Double =
  a.zip(b).map { case (x, y) => x * y }.sum
