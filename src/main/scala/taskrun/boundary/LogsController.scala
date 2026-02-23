package taskrun.boundary

import zio.*
import zio.http.*

import shared.web.LogsView

trait LogsController:
  def routes: Routes[Any, Response]

object LogsController:

  def routes: ZIO[LogsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[LogsController](_.routes)

  val live: ZLayer[Any, Nothing, LogsController] =
    ZLayer.succeed(LogsControllerLive())

final case class LogsControllerLive() extends LogsController:

  private val DefaultLogPath = "logs/app.log"

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "logs" -> handler { (req: Request) =>
      val logPath = req.queryParam("path").filter(_.nonEmpty).getOrElse(DefaultLogPath)
      ZIO.succeed(html(LogsView.page(logPath)))
    }
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)
