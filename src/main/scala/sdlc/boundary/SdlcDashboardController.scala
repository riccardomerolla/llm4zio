package sdlc.boundary

import zio.*
import zio.http.*

import sdlc.control.SdlcDashboardService
import shared.errors.PersistenceError
import shared.web.HtmlViews

trait SdlcDashboardController:
  def routes: Routes[Any, Response]

object SdlcDashboardController:

  def routes: ZIO[SdlcDashboardController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[SdlcDashboardController](_.routes)

  val live: ZLayer[SdlcDashboardService, Nothing, SdlcDashboardController] =
    ZLayer.fromFunction(SdlcDashboardControllerLive.apply)

final case class SdlcDashboardControllerLive(
  dashboardService: SdlcDashboardService
) extends SdlcDashboardController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "sdlc"              -> handler {
      dashboardService.snapshot
        .map(snapshot => html(HtmlViews.sdlcDashboard(snapshot)))
        .catchAll(error => ZIO.succeed(persistErr(error)))
    },
    Method.GET / "sdlc" / "fragment" -> handler {
      dashboardService.snapshot
        .map(snapshot => html(HtmlViews.sdlcDashboardFragment(snapshot)))
        .catchAll(error => ZIO.succeed(persistErr(error)))
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"SDLC dashboard query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"SDLC dashboard serialization failed: $cause").status(Status.InternalServerError)
