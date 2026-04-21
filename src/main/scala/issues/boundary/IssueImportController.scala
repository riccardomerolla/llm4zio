package issues.boundary

import zio.*
import zio.http.*
import zio.json.*

import issues.control.IssueImportService
import issues.entity.api.{ FolderImportRequest, GitHubImportPreviewRequest }
import shared.errors.PersistenceError
import shared.web.ErrorHandlingMiddleware

/** Folder + GitHub issue-import boundary — extracted from [[IssueController]] in phase 4F.4.
  *
  * Routes owned:
  *   - POST /issues/import (onboarding — imports the configured folder, 303 redirect)
  *   - POST /api/issues/import/folder/preview (list parsed items, no persistence)
  *   - POST /api/issues/import/folder (persist parsed items)
  *   - POST /api/issues/import/github/preview (list GH issues via `gh` CLI)
  *   - POST /api/issues/import/github (persist GH issues as Created + ExternalRefLinked events)
  *
  * Business logic lives in [[IssueImportService]]; this controller only handles HTTP body parsing and JSON/redirect
  * responses.
  */
trait IssueImportController:
  def routes: Routes[Any, Response]

object IssueImportController:

  val live: ZLayer[IssueImportService, Nothing, IssueImportController] =
    ZLayer.fromFunction(IssueImportControllerLive.apply)

final case class IssueImportControllerLive(importService: IssueImportService) extends IssueImportController:

  private def readBody(req: Request): IO[PersistenceError, String] =
    req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))

  private def parseJson[A: JsonDecoder](body: String): IO[PersistenceError, A] =
    ZIO.fromEither(body.fromJson[A]).mapError(err => PersistenceError.QueryFailed("json_parse", err))

  override val routes: Routes[Any, Response] = Routes(
    Method.POST / "issues" / "import"                                -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for imported <- importService.importConfiguredFolder
        yield Response(
          status = Status.SeeOther,
          headers = Headers(Header.Custom("Location", s"/board?mode=list&imported=${imported.succeeded}")),
        )
      }
    },
    Method.POST / "api" / "issues" / "import" / "folder" / "preview" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body    <- readBody(req)
          request <- parseJson[FolderImportRequest](body)
          items   <- importService.previewFolder(request)
        yield Response.json(items.toJson)
      }
    },
    Method.POST / "api" / "issues" / "import" / "folder"             -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body    <- readBody(req)
          request <- parseJson[FolderImportRequest](body)
          result  <- importService.importFolder(request)
        yield Response.json(result.toJson)
      }
    },
    Method.POST / "api" / "issues" / "import" / "github" / "preview" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body    <- readBody(req)
          request <- parseJson[GitHubImportPreviewRequest](body)
          items   <- importService.previewGitHub(request)
        yield Response.json(items.toJson)
      }
    },
    Method.POST / "api" / "issues" / "import" / "github"             -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body    <- readBody(req)
          request <- parseJson[GitHubImportPreviewRequest](body)
          result  <- importService.importGitHub(request)
        yield Response.json(result.toJson)
      }
    },
  )
