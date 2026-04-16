package memory.boundary

import zio.*
import zio.http.*
import zio.json.*

import memory.entity.{ Scope, * }

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

  private val defaultPageSize    = 20
  private val defaultLimit       = 10
  private val defaultRetentionDs = 90

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "memory"                           -> handler { (req: Request) =>
      execute {
        val scope  = readScopeOpt(req)
        val filter = baseFilter(req, scope)
        for
          entries <- listEntries(filter, page = 0, pageSize = defaultPageSize)
          total   <- listEntries(filter, page = 0, pageSize = 10000).map(_.size)
          oldest   = entries.lastOption.map(_.createdAt.toString)
          newest   = entries.headOption.map(_.createdAt.toString)
        yield html(MemoryView.page(scope, entries, total, oldest, newest, defaultRetentionDs))
      }
    },
    Method.GET / "api" / "memory" / "search"        -> handler { (req: Request) =>
      execute {
        val scope  = readScopeOpt(req)
        val q      = req.queryParam("q").map(_.trim).getOrElse("")
        val limit  = req.queryParam("limit").flatMap(_.toIntOption).map(v => Math.max(1, v)).getOrElse(defaultLimit)
        val filter = baseFilter(req, scope)

        val effect =
          if q.isEmpty then
            listEntries(filter, page = 0, pageSize = limit).map(_.map(entry =>
              ScoredMemory(entry, 0.0f)
            ))
          else
            scope match
              case Some(s) => repository.searchRelevant(s, q, limit, filter)
              case None    =>
                listEntries(filter, page = 0, pageSize = 10000).map { entries =>
                  val needle = q.toLowerCase
                  entries
                    .filter(entry => entry.text.toLowerCase.contains(needle))
                    .take(limit)
                    .map(entry => ScoredMemory(entry, 0.0f))
                }

        effect.map { results =>
          req.queryParam("format").map(_.trim.toLowerCase) match
            case Some("html") => html(MemoryView.searchFragment(results, scope))
            case _            =>
              Response.json(SearchResponse(results.map(SearchItem.fromScored)).toJson)
        }
      }
    },
    Method.GET / "api" / "memory" / "list"          -> handler { (req: Request) =>
      execute {
        val scope    = readScopeOpt(req)
        val page     = req.queryParam("page").flatMap(_.toIntOption).map(v => Math.max(0, v)).getOrElse(0)
        val pageSize =
          req.queryParam("pageSize").flatMap(_.toIntOption).map(v => Math.max(1, v)).getOrElse(defaultPageSize)
        val filter   = baseFilter(req, scope)
        listEntries(filter, page, pageSize).map { entries =>
          req.queryParam("format").map(_.trim.toLowerCase) match
            case Some("html") => html(MemoryView.entriesFragment(entries, scope))
            case _            => Response.json(ListResponse(entries.map(ListItem.fromEntry), page, pageSize).toJson)
        }
      }
    },
    Method.DELETE / "api" / "memory" / string("id") -> handler { (id: String, req: Request) =>
      execute {
        readScopeOpt(req) match
          case Some(scope) =>
            repository.deleteById(scope, MemoryId(id)).as(
              Response(
                status = Status.NoContent,
                headers = Headers(
                  Header.Custom("HX-Reswap", "delete")
                ),
              )
            )
          case None        =>
            ZIO.succeed(
              Response.json(Map("error" -> "scope query parameter is required").toJson).status(Status.BadRequest)
            )
      }
    },
  )

  private def parseKind(raw: Option[String]): Option[MemoryKind] =
    raw.map(_.trim).filter(v => v.nonEmpty && !v.equalsIgnoreCase("all")).map(MemoryKind.apply)

  private def readScopeOpt(req: Request): Option[Scope] =
    req.queryParam("scope").map(_.trim).filter(_.nonEmpty).map(Scope.apply)

  private def readSessionIdOpt(req: Request): Option[SessionId] =
    req.queryParam("sessionId").map(_.trim).filter(_.nonEmpty).map(SessionId.apply)

  private def baseFilter(req: Request, scope: Option[Scope]): MemoryFilter =
    MemoryFilter(
      scope = scope,
      sessionId = readSessionIdOpt(req),
      kind = parseKind(req.queryParam("kind")),
    )

  private def listEntries(
    filter: MemoryFilter,
    page: Int,
    pageSize: Int,
  ): IO[Throwable, List[MemoryEntry]] =
    filter.scope match
      case Some(scope) =>
        repository.listByScope(scope, filter, page, pageSize)
      case None        =>
        repository.listAll(filter, page, pageSize)

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
    scope: String,
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
        scope = value.entry.scope.value,
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
    scope: String,
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
        scope = entry.scope.value,
        sessionId = entry.sessionId.value,
        text = entry.text,
        tags = entry.tags,
        kind = entry.kind.value,
        createdAt = entry.createdAt.toString,
        lastAccessedAt = entry.lastAccessedAt.toString,
      )
