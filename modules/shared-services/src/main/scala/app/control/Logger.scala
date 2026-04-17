package app.control

import zio.*

/** Logger - Simple delegation to ZIO logging backed by SLF4J/Logback
  *
  * Features:
  *   - ZIO effect-based logging with multiple log levels
  *   - SLF4J bridge to Logback backend
  *   - JSON formatting via logstash-logback-encoder (configured in logback.xml)
  *   - OTEL trace correlation via zio-opentelemetry-zio-logging
  *   - Automatic async logging for performance
  *
  * Configuration:
  *   - logback.xml: Controls output format and destinations
  *   - OTEL setup: Adds trace context to logs automatically
  */
trait Logger:
  def trace(message: => String): UIO[Unit]
  def debug(message: => String): UIO[Unit]
  def info(message: => String): UIO[Unit]
  def warn(message: => String): UIO[Unit]
  def error(message: => String): UIO[Unit]
  def error(message: => String, cause: Throwable): UIO[Unit]

object Logger:
  /** Static logging API - delegates to ZIO logging */
  def trace(message: => String): UIO[Unit]                   = ZIO.logTrace(message)
  def debug(message: => String): UIO[Unit]                   = ZIO.logDebug(message)
  def info(message: => String): UIO[Unit]                    = ZIO.logInfo(message)
  def warn(message: => String): UIO[Unit]                    = ZIO.logWarning(message)
  def error(message: => String): UIO[Unit]                   = ZIO.logError(message)
  def error(message: => String, cause: Throwable): UIO[Unit] =
    ZIO.logErrorCause(message, Cause.fail(cause))

  /** Live implementation - simple delegation to ZIO logging */
  val live: ZLayer[Any, Nothing, Logger] = ZLayer.succeed(new Logger:
    def trace(message: => String): UIO[Unit]                   = Logger.trace(message)
    def debug(message: => String): UIO[Unit]                   = Logger.debug(message)
    def info(message: => String): UIO[Unit]                    = Logger.info(message)
    def warn(message: => String): UIO[Unit]                    = Logger.warn(message)
    def error(message: => String): UIO[Unit]                   = Logger.error(message)
    def error(message: => String, cause: Throwable): UIO[Unit] = Logger.error(message, cause))
