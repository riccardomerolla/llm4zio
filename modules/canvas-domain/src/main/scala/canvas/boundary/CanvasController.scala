package canvas.boundary

import zio.*
import zio.http.*

import canvas.entity.ReasonsCanvasRepository
import shared.errors.PersistenceError
import shared.ids.Ids.{ CanvasId, ProjectId }

trait CanvasController:
  def routes: Routes[Any, Response]

object CanvasController:
  def routes: ZIO[CanvasController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[CanvasController](_.routes)

  val live: ZLayer[ReasonsCanvasRepository, Nothing, CanvasController] =
    ZLayer {
      ZIO.service[ReasonsCanvasRepository].map(make)
    }

  def make(canvasRepo: ReasonsCanvasRepository): CanvasController =
    new CanvasController:
      override val routes: Routes[Any, Response] = Routes(
        Method.GET / "canvases"             -> handler { (req: Request) =>
          listPage(req, canvasRepo).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.GET / "canvases" / string("canvasId") -> handler { (canvasId: String, _: Request) =>
          detailPage(CanvasId(canvasId), canvasRepo).catchAll(error => ZIO.succeed(persistErr(error)))
        },
      )

  private def listPage(
    req: Request,
    canvasRepo: ReasonsCanvasRepository,
  ): IO[PersistenceError, Response] =
    val projectFilter = req.queryParam("projectId").map(_.trim).filter(_.nonEmpty)
    for
      all      <- canvasRepo.list
      filtered  = projectFilter.fold(all)(pid => all.filter(_.projectId == ProjectId(pid)))
      projects  = all.map(_.projectId.value).distinct.sorted
    yield Response
      .text(CanvasView.listPage(filtered, projectFilter, projects))
      .contentType(MediaType.text.html)

  private def detailPage(
    id: CanvasId,
    canvasRepo: ReasonsCanvasRepository,
  ): IO[PersistenceError, Response] =
    canvasRepo.get(id).map { canvas =>
      Response.text(CanvasView.detailPage(canvas)).contentType(MediaType.text.html)
    }

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Canvas store unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Canvas query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Canvas serialization failed: $cause").status(Status.InternalServerError)
