package core

import zio.*

import io.opentelemetry.api.trace.Span

/** ObservableLogger - Logger with OpenTelemetry trace correlation
  *
  * Features:
  *   - Automatic trace ID and span ID injection into logs
  *   - Structured logging with context propagation
  *   - JSON format with telemetry correlation fields
  *   - Integration with TracingService for distributed tracing
  */
trait ObservableLogger extends Logger:
  /** Log with automatic trace/span ID correlation */
  def logWithTraceContext(level: LogLevel, message: => String): UIO[Unit]

object ObservableLogger:
  def trace(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.trace(message))

  def debug(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.debug(message))

  def info(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.info(message))

  def warn(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.warn(message))

  def error(message: => String): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.error(message))

  def error(message: => String, cause: Throwable): URIO[ObservableLogger, Unit] =
    ZIO.serviceWithZIO[ObservableLogger](_.error(message, cause))

  /** Live implementation with OpenTelemetry trace correlation */
  def live(logFilePath: String): ZLayer[Any, Nothing, ObservableLogger] = ZLayer {
    for
      runtime <- ZIO.runtime[Any]
      logger   = makeObservableLogger(runtime, logFilePath, Map.empty)
    yield logger
  }

  private def makeObservableLogger(
    runtime: Runtime[Any],
    logFilePath: String,
    annotations: Map[String, String],
  ): ObservableLogger =
    new ObservableLogger:
      private def getCurrentTraceContext: UIO[Map[String, String]] =
        ZIO.succeed {
          val currentSpan = Span.current()
          val spanContext = currentSpan.getSpanContext

          if spanContext.isValid then
            Map(
              "trace_id"    -> spanContext.getTraceId,
              "span_id"     -> spanContext.getSpanId,
              "trace_flags" -> spanContext.getTraceFlags.asHex(),
            )
          else Map.empty
        }

      private def doLog(level: LogLevel, message: => String, maybeCause: Option[Throwable] = None): UIO[Unit] =
        for
          traceContext <- getCurrentTraceContext
          allContext    = annotations ++ traceContext
          timestamp    <- Clock.instant
          _            <- ZIO.attempt {
                            import java.nio.file.{ Files, Paths, StandardOpenOption }

                            val contextJson = if allContext.isEmpty then "{}"
                            else "{" + allContext.map { case (k, v) => s""""$k":"$v"""" }.mkString(",") + "}"

                            val jsonLog = maybeCause match
                              case Some(cause) =>
                                val escapedMsg   = message.replace("\"", "\\\"").replace("\n", "\\n")
                                val escapedCause = cause.getMessage.replace("\"", "\\\"").replace("\n", "\\n")
                                s"""{"timestamp":"$timestamp","level":"${level.label}","message":"$escapedMsg","cause":"$escapedCause","context":$contextJson}"""
                              case None        =>
                                val escapedMsg = message.replace("\"", "\\\"").replace("\n", "\\n")
                                s"""{"timestamp":"$timestamp","level":"${level.label}","message":"$escapedMsg","context":$contextJson}"""

                            Files.write(
                              Paths.get(logFilePath),
                              (jsonLog + "\n").getBytes,
                              StandardOpenOption.CREATE,
                              StandardOpenOption.APPEND,
                            )
                          }.catchAll { err =>
                            ZIO.logError(s"Failed to write log to file: ${err.getMessage}")
                          }
          // Also log to console for visibility
          _            <- Unsafe.unsafe { implicit u =>
                            val ctx = allContext
                            val msg = if ctx.isEmpty then message
                            else s"$message [${ctx.map { case (k, v) => s"$k=$v" }.mkString(", ")}]"
                            maybeCause match
                              case Some(cause) => ZIO.logLevel(level)(ZIO.logErrorCause(msg, Cause.fail(cause)))
                              case None        => ZIO.logLevel(level)(ZIO.log(msg))
                          }
        yield ()

      override def logWithTraceContext(level: LogLevel, message: => String): UIO[Unit] =
        doLog(level, message)

      override def trace(message: => String): UIO[Unit]                   = doLog(LogLevel.Trace, message)
      override def debug(message: => String): UIO[Unit]                   = doLog(LogLevel.Debug, message)
      override def info(message: => String): UIO[Unit]                    = doLog(LogLevel.Info, message)
      override def warn(message: => String): UIO[Unit]                    = doLog(LogLevel.Warning, message)
      override def error(message: => String): UIO[Unit]                   = doLog(LogLevel.Error, message)
      override def error(message: => String, cause: Throwable): UIO[Unit] =
        doLog(LogLevel.Error, message, Some(cause))

      override def withContext(key: String, value: String): ObservableLogger =
        makeObservableLogger(runtime, logFilePath, annotations + (key -> value))

      override def withRunId(runId: String): ObservableLogger         = withContext("runId", runId)
      override def withAgentName(agentName: String): ObservableLogger = withContext("agentName", agentName)
      override def withStep(step: String): ObservableLogger           = withContext("step", step)
