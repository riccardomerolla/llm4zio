package web.controllers

import zio.*
import zio.http.*
import zio.json.*

import memory.*
import web.views.MemoryView

trait MemoryController:
  def routes: Routes[Any, Response]

object MemoryController:

  def routes: ZIO[MemoryController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[MemoryController](_.routes)

  val live: ZLayer[MemoryRepository, Nothing, MemoryController] =
    ZLayer.fromFunction(MemoryControllerLive.apply)

final case class MemoryControllerLive(
  repository: MemoryRepository
) extends MemoryController:

  private val defaultUserId      = UserId("web:default")
  private val defaultPageSize    = 20
  private val defaultLimit       = 10
  private val defaultRetentionDs = 90

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "memory"                           -> handler { (req: Request) =>
      execute {
        val userId = readUserId(req)
        for
          entries <-
            repository.listForUser(userId, MemoryFilter(userId = Some(userId)), page = 0, pageSize = defaultPageSize)
          total   <-
            repository.listForUser(userId, MemoryFilter(userId = Some(userId)), page = 0, pageSize = 10000).map(_.size)
          oldest   = entries.lastOption.map(_.createdAt.toString)
          newest   = entries.headOption.map(_.createdAt.toString)
        yield html(MemoryView.page(userId, entries, total, oldest, newest, defaultRetentionDs))
      }
    },
    Method.GET / "api" / "memory" / "search"        -> handler { (req: Request) =>
      execute {
        val userId = readUserId(req)
        val q      = req.queryParam("q").map(_.trim).getOrElse("")
        val limit  = req.queryParam("limit").flatMap(_.toIntOption).map(v => Math.max(1, v)).getOrElse(defaultLimit)
        val filter = MemoryFilter(userId = Some(userId), kind = parseKind(req.queryParam("kind")))

        val effect =
          if q.isEmpty then
            repository.listForUser(userId, filter, page = 0, pageSize = limit).map(_.map(entry =>
              ScoredMemory(entry, 0.0f)
            ))
          else repository.searchRelevant(userId, q, limit, filter)

        effect.map { results =>
          req.queryParam("format").map(_.trim.toLowerCase) match
            case Some("html") => html(MemoryView.searchFragment(results, userId))
            case _            =>
              Response.json(SearchResponse(results.map(SearchItem.fromScored)).toJson)
        }
      }
    },
    Method.GET / "api" / "memory" / "list"          -> handler { (req: Request) =>
      execute {
        val userId   = readUserId(req)
        val page     = req.queryParam("page").flatMap(_.toIntOption).map(v => Math.max(0, v)).getOrElse(0)
        val pageSize =
          req.queryParam("pageSize").flatMap(_.toIntOption).map(v => Math.max(1, v)).getOrElse(defaultPageSize)
        val filter   = MemoryFilter(userId = Some(userId), kind = parseKind(req.queryParam("kind")))
        repository.listForUser(userId, filter, page, pageSize).map { entries =>
          req.queryParam("format").map(_.trim.toLowerCase) match
            case Some("html") => html(MemoryView.entriesFragment(entries, userId))
            case _            => Response.json(ListResponse(entries.map(ListItem.fromEntry), page, pageSize).toJson)
        }
      }
    },
    Method.DELETE / "api" / "memory" / string("id") -> handler { (id: String, req: Request) =>
      execute {
        val userId = readUserId(req)
        repository.deleteById(userId, MemoryId(id)).as(
          Response(
            status = Status.NoContent,
            headers = Headers(
              Header.Custom("HX-Reswap", "delete")
            ),
          )
        )
      }
    },
  )

  private def parseKind(raw: Option[String]): Option[MemoryKind] =
    raw.map(_.trim).filter(v => v.nonEmpty && !v.equalsIgnoreCase("all")).map(MemoryKind.apply)

  private def readUserId(req: Request): UserId =
    req.queryParam("userId").map(_.trim).filter(_.nonEmpty).map(UserId.apply).getOrElse(defaultUserId)

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def execute(effect: IO[Throwable, Response]): UIO[Response] =
    effect.catchAll { error =>
      val message = Option(error.getMessage).getOrElse(error.toString)
      ZIO.logWarning(s"memory controller error: $message") *>
        ZIO.succeed(
          Response.json(Map("error" -> message).toJson).status(Status.InternalServerError)
        )
    }

  final private case class SearchResponse(results: List[SearchItem]) derives JsonCodec

  final private case class SearchItem(
    id: String,
    userId: String,
    sessionId: String,
    text: String,
    tags: List[String],
    kind: String,
    createdAt: String,
    lastAccessedAt: String,
    score: Float,
  ) derives JsonCodec

  private object SearchItem:
    def fromScored(value: ScoredMemory): SearchItem =
      SearchItem(
        id = value.entry.id.value,
        userId = value.entry.userId.value,
        sessionId = value.entry.sessionId.value,
        text = value.entry.text,
        tags = value.entry.tags,
        kind = value.entry.kind.value,
        createdAt = value.entry.createdAt.toString,
        lastAccessedAt = value.entry.lastAccessedAt.toString,
        score = value.score,
      )

  final private case class ListResponse(
    items: List[ListItem],
    page: Int,
    pageSize: Int,
  ) derives JsonCodec

  final private case class ListItem(
    id: String,
    userId: String,
    sessionId: String,
    text: String,
    tags: List[String],
    kind: String,
    createdAt: String,
    lastAccessedAt: String,
  ) derives JsonCodec

  private object ListItem:
    def fromEntry(entry: MemoryEntry): ListItem =
      ListItem(
        id = entry.id.value,
        userId = entry.userId.value,
        sessionId = entry.sessionId.value,
        text = entry.text,
        tags = entry.tags,
        kind = entry.kind.value,
        createdAt = entry.createdAt.toString,
        lastAccessedAt = entry.lastAccessedAt.toString,
      )
