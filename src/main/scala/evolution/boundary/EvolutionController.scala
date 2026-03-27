package evolution.boundary

import zio.*
import zio.http.*

import evolution.entity.EvolutionProposalRepository
import shared.errors.PersistenceError
import shared.web.EvolutionView

object EvolutionController:

  def routes(repository: EvolutionProposalRepository): Routes[Any, Response] =
    Routes(
      Method.GET / "evolution" -> handler { (_: Request) =>
        repository
          .list()
          .map(proposals => htmlResponse(EvolutionView.page(proposals)))
          .catchAll(error => ZIO.succeed(persistErr(error)))
      }
    )

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Evolution query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Evolution serialization failed: $cause").status(Status.InternalServerError)
