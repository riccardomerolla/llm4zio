package issues.boundary

import zio.*
import zio.http.*
import zio.json.*

import issues.control.IssueTemplateService
import issues.entity.api.{ IssueTemplateUpsertRequest, PipelineCreateRequest }
import shared.errors.PersistenceError
import shared.web.{ ErrorHandlingMiddleware, HtmlViews }

/** Pure template + pipeline CRUD boundary. Extracted from [[IssueController]] in phase 4F.2 — depends only on
  * [[IssueTemplateService]] and rendering helpers.
  *
  * Routes owned:
  *   - GET    /settings/issues-templates            (HTML tab)
  *   - GET    /api/issue-templates                  (list)
  *   - POST   /api/issue-templates                  (create)
  *   - PUT    /api/issue-templates/:id              (update)
  *   - DELETE /api/issue-templates/:id              (delete)
  *   - GET    /api/pipelines                        (list)
  *   - POST   /api/pipelines                        (create)
  *
  * The `/issues/from-template/:templateId` route stays in [[IssueController]] because it creates issues and needs
  * the workspace board-issue helpers.
  */
trait IssueTemplatesController:
  def routes: Routes[Any, Response]

object IssueTemplatesController:

  val live: ZLayer[IssueTemplateService, Nothing, IssueTemplatesController] =
    ZLayer.fromFunction(IssueTemplatesControllerLive.apply)

final case class IssueTemplatesControllerLive(templateService: IssueTemplateService) extends IssueTemplatesController:

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def readBody(req: Request): IO[PersistenceError, String] =
    req.body.asString.mapError(err => PersistenceError.QueryFailed("request_body", err.getMessage))

  private def parseJson[A: JsonDecoder](body: String, label: String): IO[PersistenceError, A] =
    ZIO.fromEither(body.fromJson[A]).mapError(err => PersistenceError.QueryFailed(label, err))

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "settings" / "issues-templates" -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        templateService.listTemplates.map(templates => html(HtmlViews.settingsIssueTemplatesTab(templates)))
      }
    },
    Method.GET / "api" / "issue-templates" -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        templateService.listTemplates.map(templates => Response.json(templates.toJson))
      }
    },
    Method.POST / "api" / "issue-templates" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body      <- readBody(req)
          upsertReq <- parseJson[IssueTemplateUpsertRequest](body, "json_parse")
          template  <- templateService.createTemplate(upsertReq)
        yield Response.json(template.toJson).copy(status = Status.Created)
      }
    },
    Method.PUT / "api" / "issue-templates" / string("id") -> handler { (id: String, req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body      <- readBody(req)
          upsertReq <- parseJson[IssueTemplateUpsertRequest](body, "json_parse")
          template  <- templateService.updateTemplate(id, upsertReq)
        yield Response.json(template.toJson)
      }
    },
    Method.DELETE / "api" / "issue-templates" / string("id") -> handler { (id: String, _: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        templateService.deleteTemplate(id).as(Response(status = Status.NoContent))
      }
    },
    Method.GET / "api" / "pipelines" -> handler { (_: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        templateService.listPipelines.map(values => Response.json(values.toJson))
      }
    },
    Method.POST / "api" / "pipelines" -> handler { (req: Request) =>
      ErrorHandlingMiddleware.fromPersistence {
        for
          body     <- readBody(req)
          create   <- parseJson[PipelineCreateRequest](body, "json_parse")
          pipeline <- templateService.createPipeline(create)
        yield Response.json(pipeline.toJson).copy(status = Status.Created)
      }
    },
  )
