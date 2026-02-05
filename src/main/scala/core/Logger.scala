package core

import zio.*

/** Logger - Logging and observability service
  *
  * Features:
  *   - Structured logging with context
  *   - Different log levels (debug, info, warn, error)
  *   - Integration with ZIO logging
  */
object Logger:
  def info(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.logInfo(message)

  def debug(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.logDebug(message)

  def warn(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.logWarning(message)

  def error(message: String, cause: Throwable): ZIO[Any, Nothing, Unit] =
    ZIO.logErrorCause(message, Cause.fail(cause))

  def error(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.logError(message)
