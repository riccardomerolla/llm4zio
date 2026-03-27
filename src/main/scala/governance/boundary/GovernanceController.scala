package governance.boundary

import zio.*
import zio.http.*

import governance.entity.GovernancePolicyRepository
import shared.errors.PersistenceError
import shared.web.GovernanceView

object GovernanceController:

  def routes(policyRepository: GovernancePolicyRepository): Routes[Any, Response] =
    Routes(
      Method.GET / "governance" -> handler { (_: Request) =>
        listPage(policyRepository).catchAll(error => ZIO.succeed(persistErr(error)))
      }
    )

  private def listPage(policyRepository: GovernancePolicyRepository): IO[PersistenceError, Response] =
    policyRepository.list.map { policies =>
      val active   = policies.filter(_.archivedAt.isEmpty)
      val archived = policies.filter(_.archivedAt.isDefined)
      htmlResponse(GovernanceView.page(active, archived))
    }

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Governance query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Governance serialization failed: $cause").status(Status.InternalServerError)
