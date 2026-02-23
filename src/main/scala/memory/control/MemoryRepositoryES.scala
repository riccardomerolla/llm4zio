package memory.control

import zio.*

import io.github.riccardomerolla.zio.eclipsestore.gigamap.domain.GigaMapQuery
import io.github.riccardomerolla.zio.eclipsestore.gigamap.error.GigaMapError
import io.github.riccardomerolla.zio.eclipsestore.gigamap.service.GigaMap
import io.github.riccardomerolla.zio.eclipsestore.gigamap.vector.{
  SimilarityFunction,
  VectorIndex,
  VectorIndexConfig,
  VectorIndexService,
  Vectorizer,
}
import memory.entity.*
import shared.store.{ MemoryId as StoreMemoryId, MemoryStoreModule }

final case class MemoryRepositoryES(
  memoryMap: GigaMap[StoreMemoryId, MemoryEntry],
  vectorIndex: VectorIndexService,
  embedService: EmbeddingService,
  dimension: Int,
) extends MemoryRepository:

  private val indexName = "memory-embeddings"

  override def save(entry: MemoryEntry): IO[Throwable, Unit] =
    for
      persisted <- if entry.embedding.nonEmpty then ZIO.succeed(entry)
                   else embedService.embed(entry.text).map(vec => entry.copy(embedding = vec))
      _         <- memoryMap.put(StoreMemoryId(persisted.id.value), persisted).mapError(toThrowable)
      idx       <- ensureIndex
      _         <- idx.add(persisted.id.value.hashCode.toLong, persisted).mapError(toThrowable)
    yield ()

  override def searchRelevant(
    userId: UserId,
    query: String,
    limit: Int,
    filter: MemoryFilter,
  ): IO[Throwable, List[ScoredMemory]] =
    for
      queryVec <- embedService.embed(query)
      idx      <- ensureIndex
      results  <- idx
                    .search(vectorChunk(queryVec), limit * 3, Some(0.5f))
                    .mapError(toThrowable)
      filtered  = results
                    .filter(r => r.entity.userId == userId)
                    .filter(r => filter.kind.forall(_ == r.entity.kind))
                    .filter(r => filter.sessionId.forall(_ == r.entity.sessionId))
                    .filter(r => filter.tags.forall(tag => r.entity.tags.contains(tag)))
                    .take(limit)
                    .map(r => ScoredMemory(r.entity, r.score))
    yield filtered

  override def listForUser(
    userId: UserId,
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): IO[Throwable, List[MemoryEntry]] =
    queryEntriesForUser(userId)
      .map(
        _.toList
          .filter(entry => filter.sessionId.forall(_ == entry.sessionId))
          .filter(entry => filter.kind.forall(_ == entry.kind))
          .filter(entry => filter.tags.forall(tag => entry.tags.contains(tag)))
          .sortBy(_.createdAt)(Ordering[java.time.Instant].reverse)
          .slice(math.max(0, page) * math.max(1, pageSize), math.max(0, page + 1) * math.max(1, pageSize))
      )

  override def listAll(
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): IO[Throwable, List[MemoryEntry]] =
    memoryMap
      .query(GigaMapQuery.All[MemoryEntry]())
      .mapError(toThrowable)
      .map(
        _.toList
          .filter(entry => filter.userId.forall(_ == entry.userId))
          .filter(entry => filter.sessionId.forall(_ == entry.sessionId))
          .filter(entry => filter.kind.forall(_ == entry.kind))
          .filter(entry => filter.tags.forall(tag => entry.tags.contains(tag)))
          .sortBy(_.createdAt)(Ordering[java.time.Instant].reverse)
          .slice(math.max(0, page) * math.max(1, pageSize), math.max(0, page + 1) * math.max(1, pageSize))
      )

  override def deleteById(userId: UserId, id: MemoryId): IO[Throwable, Unit] =
    for
      existing <- memoryMap.get(StoreMemoryId(id.value)).mapError(toThrowable)
      _        <- existing match
                    case Some(entry) if entry.userId == userId =>
                      memoryMap.remove(StoreMemoryId(id.value)).unit.mapError(toThrowable)
                    case Some(_)                               =>
                      ZIO.fail(new RuntimeException(s"Memory $id does not belong to user ${userId.value}"))
                    case None                                  => ZIO.unit
      idx      <- ensureIndex
      _        <- idx.remove(id.value.hashCode.toLong).mapError(toThrowable).ignore
    yield ()

  override def deleteBySession(sessionId: SessionId): IO[Throwable, Unit] =
    for
      entries <- memoryMap.query(GigaMapQuery.All[MemoryEntry]()).mapError(toThrowable)
      toDrop   = entries.toList.filter(_.sessionId == sessionId)
      _       <- ZIO.foreachDiscard(toDrop) { entry =>
                   memoryMap.remove(StoreMemoryId(entry.id.value)).unit.mapError(toThrowable)
                 }
      idx     <- ensureIndex
      _       <- ZIO.foreachDiscard(toDrop)(entry => idx.remove(entry.id.value.hashCode.toLong).mapError(toThrowable).ignore)
    yield ()

  private def ensureIndex: IO[Throwable, VectorIndex[MemoryEntry]] =
    vectorIndex
      .getIndex[MemoryEntry](indexName)
      .orElse(
        vectorIndex.createIndex(
          mapName = "memoryEntries",
          indexName = indexName,
          config = VectorIndexConfig(
            dimension = dimension,
            similarityFunction = SimilarityFunction.Cosine,
          ),
          vectorizer = MemoryVectorizer,
        )
      )
      .mapError(toThrowable)

  private def queryEntriesForUser(userId: UserId): IO[Throwable, Chunk[MemoryEntry]] =
    memoryMap
      .query(GigaMapQuery.ByIndex("userId", userId.value))
      .catchSome {
        case GigaMapError.IndexNotDefined("userId") =>
          ZIO.logWarning("memoryEntries 'userId' index missing; falling back to full scan") *>
            memoryMap.query(GigaMapQuery.All[MemoryEntry]()).map(_.filter(_.userId == userId))
      }
      .mapError(toThrowable)

  private def vectorChunk(values: Vector[Float]): Chunk[Float] =
    Chunk.fromIterable(values)

  private def toThrowable(error: Any): Throwable =
    error match
      case t: Throwable => t
      case other        => new RuntimeException(other.toString)

object MemoryRepositoryES:
  private val defaultDimension = 1536

  val live
    : ZLayer[MemoryStoreModule.MemoryEntriesStore & VectorIndexService & EmbeddingService, Nothing, MemoryRepository] =
    ZLayer.fromZIO {
      for
        map <- MemoryStoreModule.memoryEntriesMap
        vsi <- ZIO.service[VectorIndexService]
        emb <- ZIO.service[EmbeddingService]
        dim  = sys.env
                 .get("AI_EMBEDDING_DIMENSION")
                 .flatMap(_.toIntOption)
                 .filter(_ > 0)
                 .getOrElse(defaultDimension)
      yield MemoryRepositoryES(map, vsi, emb, dim)
    }

object MemoryVectorizer extends Vectorizer[MemoryEntry]:
  override def vectorize(entity: MemoryEntry): Chunk[Float] =
    Chunk.fromIterable(entity.embedding)

  override def isEmbedded: Boolean = true
