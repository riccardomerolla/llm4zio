package memory

import zio.*

trait MemoryRepository:
  def save(entry: MemoryEntry): IO[Throwable, Unit]
  def searchRelevant(userId: UserId, query: String, limit: Int, filter: MemoryFilter): IO[Throwable, List[ScoredMemory]]
  def listForUser(userId: UserId, filter: MemoryFilter, page: Int, pageSize: Int): IO[Throwable, List[MemoryEntry]]
  def listAll(filter: MemoryFilter, page: Int, pageSize: Int): IO[Throwable, List[MemoryEntry]] =
    ZIO.fail(new UnsupportedOperationException("listAll is not supported by this MemoryRepository implementation"))
  def deleteById(userId: UserId, id: MemoryId): IO[Throwable, Unit]
  def deleteBySession(sessionId: SessionId): IO[Throwable, Unit]

object MemoryRepository:
  def save(entry: MemoryEntry): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.save(entry))

  def searchRelevant(
    userId: UserId,
    query: String,
    limit: Int,
    filter: MemoryFilter,
  ): ZIO[MemoryRepository, Throwable, List[ScoredMemory]] =
    ZIO.serviceWithZIO[MemoryRepository](_.searchRelevant(userId, query, limit, filter))

  def listForUser(
    userId: UserId,
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): ZIO[MemoryRepository, Throwable, List[MemoryEntry]] =
    ZIO.serviceWithZIO[MemoryRepository](_.listForUser(userId, filter, page, pageSize))

  def listAll(
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): ZIO[MemoryRepository, Throwable, List[MemoryEntry]] =
    ZIO.serviceWithZIO[MemoryRepository](_.listAll(filter, page, pageSize))

  def deleteById(userId: UserId, id: MemoryId): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.deleteById(userId, id))

  def deleteBySession(sessionId: SessionId): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.deleteBySession(sessionId))
