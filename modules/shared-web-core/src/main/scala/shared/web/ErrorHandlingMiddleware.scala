package shared.web

import zio.*
import zio.http.*

import shared.errors.{ OrchestratorError, PersistenceError }

object ErrorHandlingMiddleware:

  def handle(
    effect: ZIO[Any, PersistenceError | OrchestratorError, Response]
  ): UIO[Response] =
    effect.catchAll {
      case err: PersistenceError  => ZIO.succeed(mapPersistence(err))
      case err: OrchestratorError => ZIO.succeed(mapOrchestrator(err))
    }

  def fromPersistence(effect: IO[PersistenceError, Response]): UIO[Response] =
    effect.catchAll(err => ZIO.succeed(mapPersistence(err)))

  def fromOrchestrator(effect: IO[OrchestratorError, Response]): UIO[Response] =
    effect.catchAll(err => ZIO.succeed(mapOrchestrator(err)))

  private def mapPersistence(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, id)          =>
        Response.text(s"$entity with id $id not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(cause)       =>
        Response.text(s"Storage unavailable: $cause").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Storage query failed: $cause").status(Status.InternalServerError)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Serialization failed: $cause").status(Status.InternalServerError)

  private def mapOrchestrator(error: OrchestratorError): Response =
    Response
      .text(s"Migration orchestration failed: ${error.message}")
      .status(Status.InternalServerError)
