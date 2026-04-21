package issues.boundary

import zio.*
import zio.http.*
import zio.json.*

import issues.control.IssueBulkService
import issues.entity.api.{
  BulkIssueAssignRequest,
  BulkIssueDeleteRequest,
  BulkIssueStatusRequest,
  BulkIssueTagsRequest,
}
import shared.errors.PersistenceError
import shared.web.ErrorHandlingMiddleware

/** Bulk-operation boundary for issues — extracted from [[IssueController]] in phase 4F.3.
  *
  * Owns four routes:
  *   - POST /api/issues/bulk/assign
  *   - POST /api/issues/bulk/status
  *   - POST /api/issues/bulk/tags
  *   - DELETE /api/issues/bulk
  *
  * Business logic lives in [[IssueBulkService]]; this controller only handles HTTP body parsing and JSON rendering.
  */
trait IssueBulkController:
  def routes: Routes[Any, Response]

object IssueBulkController:

  val live: ZLayer[IssueBulkService, Nothing, IssueBulkController] =
    ZLayer.fromFunction(IssueBulkControllerLive.apply)

final case class IssueBulkControllerLive(bulkService: IssueBulkService) extends IssueBulkController:

  private def readBody(req: Request): IO[PersistenceError, String] =
    req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))

  private def parseJson[A: JsonDecoder](body: String): IO[PersistenceError, A] =
    ZIO.fromEither(body.fromJson[A]).mapError(err => PersistenceError.QueryFailed("json_parse", err))

  override val routes: Routes[Any, Response] = Routes(
    Method.POST / "api" / "issues" / "bulk" / "assign" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body     <- readBody(req)
          request  <- parseJson[BulkIssueAssignRequest](body)
          response <- bulkService.bulkAssign(request)
        yield Response.json(response.toJson)
      }
    },
    Method.POST / "api" / "issues" / "bulk" / "status" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body     <- readBody(req)
          request  <- parseJson[BulkIssueStatusRequest](body)
          response <- bulkService.bulkUpdateStatus(request)
        yield Response.json(response.toJson)
      }
    },
    Method.POST / "api" / "issues" / "bulk" / "tags"   -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body     <- readBody(req)
          request  <- parseJson[BulkIssueTagsRequest](body)
          response <- bulkService.bulkUpdateTags(request)
        yield Response.json(response.toJson)
      }
    },
    Method.DELETE / "api" / "issues" / "bulk"          -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body     <- readBody(req)
          request  <- parseJson[BulkIssueDeleteRequest](body)
          response <- bulkService.bulkDelete(request)
        yield Response.json(response.toJson)
      }
    },
  )
