package app.boundary

import zio.*
import zio.http.*
import zio.json.*

import _root_.config.control.ModelService
import app.control.HealthMonitor
import shared.web.HtmlViews

trait HealthController:
  def routes: Routes[Any, Response]

object HealthController:

  def routes: ZIO[HealthController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[HealthController](_.routes)

  val live: ZLayer[HealthMonitor & ModelService, Nothing, HealthController] =
    ZLayer.fromFunction(HealthControllerLive.apply)

final case class HealthControllerLive(
  healthMonitor: HealthMonitor,
  modelService: ModelService,
) extends HealthController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "health"                       -> handler {
      ZIO.succeed(Response(
        status = Status.Found,
        headers = Headers(Header.Location(URL.decode("/settings/system").getOrElse(URL.root))),
      ))
    },
    Method.GET / "settings" / "system"          -> handler {
      ZIO.succeed(html(HtmlViews.settingsSystemTab))
    },
    Method.GET / "api" / "health"               -> handler {
      healthMonitor.snapshot.map(snapshot => Response.json(snapshot.toJson))
    },
    Method.GET / "api" / "health" / "providers" -> handler {
      modelService.probeProviders.map(items => Response.json(items.toJson))
    },
    Method.GET / "api" / "health" / "channels"  -> handler {
      healthMonitor.snapshot.map(snapshot => Response.json(snapshot.channels.toJson))
    },
    Method.GET / "api" / "health" / "history"   -> handler { (req: Request) =>
      val limit = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(30)
      healthMonitor.history(limit).map(items => Response.json(items.toJson))
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
