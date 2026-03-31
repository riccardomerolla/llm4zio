package workspace.control

import zio.IO

import shared.errors.PersistenceError
import workspace.entity.WorkspaceError

private[control] object WorkspaceErrorSupport:

  def persistence(operation: String, error: PersistenceError): WorkspaceError =
    WorkspaceError.PersistenceFailure(RuntimeException(s"$operation failed: $error"))

  def worktree(operation: String, error: Throwable): WorkspaceError =
    val details = Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(error.toString)
    WorkspaceError.WorktreeError(s"$operation failed: $details")

  extension [A](effect: IO[PersistenceError, A])
    def mapWorkspacePersistence(operation: String): IO[WorkspaceError, A] =
      effect.mapError(error => persistence(operation, error))

  extension [A](effect: IO[Throwable, A])
    def mapWorktreeFailure(operation: String): IO[WorkspaceError, A] =
      effect.mapError(error => worktree(operation, error))
