package web

import zio.*
import zio.http.*

import db.PersistenceError
import models.OrchestratorError

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
      case PersistenceError.NotFound(entity, id)    =>
        Response.text(s"$entity with id $id not found").status(Status.NotFound)
      case PersistenceError.ConnectionFailed(cause) =>
        Response.text(s"Database unavailable: $cause").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)   =>
        Response.text(s"Database query failed: $cause").status(Status.InternalServerError)
      case PersistenceError.SchemaInitFailed(cause) =>
        Response.text(s"Database initialization failed: $cause").status(Status.InternalServerError)

  private def mapOrchestrator(error: OrchestratorError): Response =
    Response
      .text(s"Migration orchestration failed: ${error.message}")
      .status(Status.InternalServerError)
