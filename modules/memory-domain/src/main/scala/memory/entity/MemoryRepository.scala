package memory.entity

import zio.{ IO, ZIO }

trait MemoryRepository:
  def save(entry: MemoryEntry): IO[Throwable, Unit]
  def searchRelevant(scope: Scope, query: String, limit: Int, filter: MemoryFilter): IO[Throwable, List[ScoredMemory]]
  def listByScope(scope: Scope, filter: MemoryFilter, page: Int, pageSize: Int): IO[Throwable, List[MemoryEntry]]
  def listAll(filter: MemoryFilter, page: Int, pageSize: Int): IO[Throwable, List[MemoryEntry]] =
    ZIO.fail(new UnsupportedOperationException("listAll is not supported by this MemoryRepository implementation"))
  def deleteById(scope: Scope, id: MemoryId): IO[Throwable, Unit]
  def deleteBySession(sessionId: SessionId): IO[Throwable, Unit]

object MemoryRepository:
  def save(entry: MemoryEntry): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.save(entry))

  def searchRelevant(
    scope: Scope,
    query: String,
    limit: Int,
    filter: MemoryFilter,
  ): ZIO[MemoryRepository, Throwable, List[ScoredMemory]] =
    ZIO.serviceWithZIO[MemoryRepository](_.searchRelevant(scope, query, limit, filter))

  def listByScope(
    scope: Scope,
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): ZIO[MemoryRepository, Throwable, List[MemoryEntry]] =
    ZIO.serviceWithZIO[MemoryRepository](_.listByScope(scope, filter, page, pageSize))

  def listAll(
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): ZIO[MemoryRepository, Throwable, List[MemoryEntry]] =
    ZIO.serviceWithZIO[MemoryRepository](_.listAll(filter, page, pageSize))

  def deleteById(scope: Scope, id: MemoryId): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.deleteById(scope, id))

  def deleteBySession(sessionId: SessionId): ZIO[MemoryRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[MemoryRepository](_.deleteBySession(sessionId))
