package web.controllers

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import memory.*

object MemoryControllerSpec extends ZIOSpecDefault:

  private val seeded =
    MemoryEntry(
      id = MemoryId("mem-1"),
      userId = UserId("web:default"),
      sessionId = SessionId("conversation:1"),
      text = "Scala 3 and ZIO project preference",
      embedding = Vector(0.1f, 0.2f),
      tags = List("scala", "zio"),
      kind = MemoryKind.Preference,
      createdAt = Instant.parse("2026-02-19T10:00:00Z"),
      lastAccessedAt = Instant.parse("2026-02-19T10:00:00Z"),
    )

  private val repository: MemoryRepository = new MemoryRepository:
    override def save(entry: MemoryEntry): IO[Throwable, Unit] =
      ZIO.unit

    override def searchRelevant(
      userId: UserId,
      query: String,
      limit: Int,
      filter: MemoryFilter,
    ): IO[Throwable, List[ScoredMemory]] =
      ZIO.succeed(List(ScoredMemory(seeded, 0.91f)).take(limit))

    override def listForUser(
      userId: UserId,
      filter: MemoryFilter,
      page: Int,
      pageSize: Int,
    ): IO[Throwable, List[MemoryEntry]] =
      ZIO.succeed(List(seeded).slice(page * pageSize, (page + 1) * pageSize))

    override def listAll(
      filter: MemoryFilter,
      page: Int,
      pageSize: Int,
    ): IO[Throwable, List[MemoryEntry]] =
      ZIO.succeed(List(seeded).slice(page * pageSize, (page + 1) * pageSize))

    override def deleteById(userId: UserId, id: MemoryId): IO[Throwable, Unit] =
      ZIO.unit

    override def deleteBySession(sessionId: SessionId): IO[Throwable, Unit] =
      ZIO.unit

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryControllerSpec")(
    test("GET /api/memory/search returns JSON results") {
      val controller = MemoryControllerLive(repository)
      for
        response <- controller.routes.runZIO(Request.get(URL.decode("/api/memory/search?q=scala&limit=1").toOption.get))
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("\"results\""),
        body.contains("Scala 3 and ZIO project preference"),
      )
    }
  )
