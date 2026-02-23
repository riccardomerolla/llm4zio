package app.boundary

import zio.*
import zio.http.*
import zio.json.*

import app.control.HealthMonitor
import shared.web.HealthDashboard

trait HealthController:
  def routes: Routes[Any, Response]

object HealthController:

  def routes: ZIO[HealthController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[HealthController](_.routes)

  val live: ZLayer[HealthMonitor, Nothing, HealthController] =
    ZLayer.fromFunction(HealthControllerLive.apply)

final case class HealthControllerLive(
  healthMonitor: HealthMonitor
) extends HealthController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "health"                     -> handler {
      ZIO.succeed(html(HealthDashboard.page))
    },
    Method.GET / "api" / "health"             -> handler {
      healthMonitor.snapshot.map(snapshot => Response.json(snapshot.toJson))
    },
    Method.GET / "api" / "health" / "history" -> handler { (req: Request) =>
      val limit = req.queryParam("limit").flatMap(_.toIntOption).getOrElse(30)
      healthMonitor.history(limit).map(items => Response.json(items.toJson))
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
