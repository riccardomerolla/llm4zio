package daemon.boundary

import zio.*
import zio.http.*

import daemon.control.DaemonAgentScheduler
import shared.errors.PersistenceError
import shared.ids.Ids.DaemonAgentSpecId

trait DaemonsController:
  def routes: Routes[Any, Response]

object DaemonsController:

  def routes: ZIO[DaemonsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[DaemonsController](_.routes)

  val live: ZLayer[DaemonAgentScheduler, Nothing, DaemonsController] =
    ZLayer.fromFunction(DaemonsControllerLive.apply)

final case class DaemonsControllerLive(
  scheduler: DaemonAgentScheduler
) extends DaemonsController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "daemons"                             -> handler {
      scheduler.list
        .map(statuses => html(DaemonsView.page(statuses)))
        .catchAll(error => ZIO.succeed(persistErr(error)))
    },
    Method.POST / "daemons" / string("id") / "start"   -> handler { (id: String, _: Request) =>
      scheduler.start(DaemonAgentSpecId(id)).as(redirect("/daemons")).catchAll(error => ZIO.succeed(persistErr(error)))
    },
    Method.POST / "daemons" / string("id") / "stop"    -> handler { (id: String, _: Request) =>
      scheduler.stop(DaemonAgentSpecId(id)).as(redirect("/daemons")).catchAll(error => ZIO.succeed(persistErr(error)))
    },
    Method.POST / "daemons" / string("id") / "restart" -> handler { (id: String, _: Request) =>
      scheduler.restart(DaemonAgentSpecId(id)).as(redirect("/daemons")).catchAll(error =>
        ZIO.succeed(persistErr(error))
      )
    },
    Method.POST / "daemons" / string("id") / "enable"  -> handler { (id: String, _: Request) =>
      scheduler
        .setEnabled(DaemonAgentSpecId(id), enabled = true)
        .as(redirect("/daemons"))
        .catchAll(error => ZIO.succeed(persistErr(error)))
    },
    Method.POST / "daemons" / string("id") / "disable" -> handler { (id: String, _: Request) =>
      scheduler
        .setEnabled(DaemonAgentSpecId(id), enabled = false)
        .as(redirect("/daemons"))
        .catchAll(error => ZIO.succeed(persistErr(error)))
    },
  )

  private def html(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def redirect(location: String): Response =
    Response(status = Status.SeeOther, headers = Headers(Header.Location(URL.decode(location).getOrElse(URL.root))))

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Daemon query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Daemon serialization failed: $cause").status(Status.InternalServerError)
