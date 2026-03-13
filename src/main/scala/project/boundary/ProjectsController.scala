package project.boundary

import zio.*
import zio.http.*

import project.entity.ProjectRepository
import shared.errors.PersistenceError
import shared.web.ProjectsView

object ProjectsController:

  def routes(projectRepository: ProjectRepository): Routes[Any, Response] =
    Routes(
      Method.GET / "projects" -> handler {
        projectRepository.list
          .map(projects => htmlResponse(ProjectsView.page(projects)))
          .catchAll(error => ZIO.succeed(persistErr(error)))
      }
    )

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, id)          =>
        Response.text(s"$entity with id $id not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Database query failed: $cause").status(Status.InternalServerError)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Project data serialization failed: $cause").status(Status.InternalServerError)
