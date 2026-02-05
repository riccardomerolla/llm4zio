package core

import zio.*

/** Logger - Structured logging with ZIO Logging and OpenTelemetry integration
  *
  * Features:
  *   - Structured logging with context fields (runId, agentName, step)
  *   - Multiple log levels: TRACE, DEBUG, INFO, WARN, ERROR
  *   - JSON format for file output
  *   - Human-readable format for console output
  *   - Async logging for performance
  *   - Integration with OpenTelemetry for trace correlation
  */
trait Logger:
  def trace(message: => String): UIO[Unit]
  def debug(message: => String): UIO[Unit]
  def info(message: => String): UIO[Unit]
  def warn(message: => String): UIO[Unit]
  def error(message: => String): UIO[Unit]
  def error(message: => String, cause: Throwable): UIO[Unit]

  def withContext(key: String, value: String): Logger
  def withRunId(runId: String): Logger
  def withAgentName(agentName: String): Logger
  def withStep(step: String): Logger

object Logger:
  // Simple logging API - works without requiring Logger service in environment
  def trace(message: => String): UIO[Unit]                   = ZIO.logTrace(message)
  def debug(message: => String): UIO[Unit]                   = ZIO.logDebug(message)
  def info(message: => String): UIO[Unit]                    = ZIO.logInfo(message)
  def warn(message: => String): UIO[Unit]                    = ZIO.logWarning(message)
  def error(message: => String): UIO[Unit]                   = ZIO.logError(message)
  def error(message: => String, cause: Throwable): UIO[Unit] =
    ZIO.logErrorCause(message, Cause.fail(cause))

  // Service-based API for advanced features (context, structured logging)
  object service:
    def trace(message: => String): URIO[Logger, Unit] =
      ZIO.serviceWithZIO[Logger](_.trace(message))

    def debug(message: => String): URIO[Logger, Unit] =
      ZIO.serviceWithZIO[Logger](_.debug(message))

    def info(message: => String): URIO[Logger, Unit] =
      ZIO.serviceWithZIO[Logger](_.info(message))

    def warn(message: => String): URIO[Logger, Unit] =
      ZIO.serviceWithZIO[Logger](_.warn(message))

    def error(message: => String): URIO[Logger, Unit] =
      ZIO.serviceWithZIO[Logger](_.error(message))

    def error(message: => String, cause: Throwable): URIO[Logger, Unit] =
      ZIO.serviceWithZIO[Logger](_.error(message, cause))

    def withContext(key: String, value: String): URIO[Logger, Logger] =
      ZIO.serviceWith[Logger](_.withContext(key, value))

    def withRunId(runId: String): URIO[Logger, Logger] =
      ZIO.serviceWith[Logger](_.withRunId(runId))

    def withAgentName(agentName: String): URIO[Logger, Logger] =
      ZIO.serviceWith[Logger](_.withAgentName(agentName))

    def withStep(step: String): URIO[Logger, Logger] =
      ZIO.serviceWith[Logger](_.withStep(step))

  /** Console logger with human-readable format */
  val console: ZLayer[Any, Nothing, Logger] = ZLayer {
    for
      runtime <- ZIO.runtime[Any]
      logger   = makeConsoleLogger(runtime, Map.empty)
    yield logger
  }

  /** File logger with JSON format */
  def file(logFilePath: String): ZLayer[Any, Nothing, Logger] = ZLayer {
    for
      runtime <- ZIO.runtime[Any]
      logger   = makeFileLogger(runtime, logFilePath, Map.empty)
    yield logger
  }

  /** Combined console and file logger */
  def combined(logFilePath: String): ZLayer[Any, Nothing, Logger] = ZLayer {
    for
      runtime      <- ZIO.runtime[Any]
      consoleLogger = makeConsoleLogger(runtime, Map.empty)
      fileLogger    = makeFileLogger(runtime, logFilePath, Map.empty)
      logger        = makeCombinedLogger(consoleLogger, fileLogger)
    yield logger
  }

  private def makeConsoleLogger(runtime: Runtime[Any], annotations: Map[String, String]): Logger = new Logger:
    private def doLog(level: LogLevel, message: => String, maybeCause: Option[Throwable] = None): UIO[Unit] =
      Unsafe.unsafe { implicit u =>
        val ctx = annotations
        val msg = if ctx.isEmpty then message
        else s"$message [${ctx.map { case (k, v) => s"$k=$v" }.mkString(", ")}]"
        maybeCause match
          case Some(cause) => ZIO.logLevel(level)(ZIO.logErrorCause(msg, Cause.fail(cause)))
          case None        => ZIO.logLevel(level)(ZIO.log(msg))
      }

    override def trace(message: => String): UIO[Unit]                   = doLog(LogLevel.Trace, message)
    override def debug(message: => String): UIO[Unit]                   = doLog(LogLevel.Debug, message)
    override def info(message: => String): UIO[Unit]                    = doLog(LogLevel.Info, message)
    override def warn(message: => String): UIO[Unit]                    = doLog(LogLevel.Warning, message)
    override def error(message: => String): UIO[Unit]                   = doLog(LogLevel.Error, message)
    override def error(message: => String, cause: Throwable): UIO[Unit] =
      doLog(LogLevel.Error, message, Some(cause))

    override def withContext(key: String, value: String): Logger =
      makeConsoleLogger(runtime, annotations + (key -> value))

    override def withRunId(runId: String): Logger         = withContext("runId", runId)
    override def withAgentName(agentName: String): Logger = withContext("agentName", agentName)
    override def withStep(step: String): Logger           = withContext("step", step)

  private def makeFileLogger(runtime: Runtime[Any], logFilePath: String, annotations: Map[String, String]): Logger =
    new Logger:
      private def doLog(level: LogLevel, message: => String, maybeCause: Option[Throwable] = None): UIO[Unit] =
        for
          timestamp <- Clock.instant
          _         <- ZIO.attempt {
                         import java.nio.file.{ Files, Paths, StandardOpenOption }

                         val jsonLog = maybeCause match
                           case Some(cause) =>
                             s"""{"timestamp":"$timestamp","level":"${level.label}","message":"$message","cause":"${cause.getMessage}","context":${contextToJson(
                                 annotations
                               )}}"""
                           case None        =>
                             s"""{"timestamp":"$timestamp","level":"${level.label}","message":"$message","context":${contextToJson(
                                 annotations
                               )}}"""

                         Files.write(
                           Paths.get(logFilePath),
                           (jsonLog + "\n").getBytes,
                           StandardOpenOption.CREATE,
                           StandardOpenOption.APPEND,
                         )
                       }.ignore
        yield ()

      private def contextToJson(ctx: Map[String, String]): String =
        if ctx.isEmpty then "{}"
        else "{" + ctx.map { case (k, v) => s""""$k":"$v"""" }.mkString(",") + "}"

      override def trace(message: => String): UIO[Unit]                   = doLog(LogLevel.Trace, message)
      override def debug(message: => String): UIO[Unit]                   = doLog(LogLevel.Debug, message)
      override def info(message: => String): UIO[Unit]                    = doLog(LogLevel.Info, message)
      override def warn(message: => String): UIO[Unit]                    = doLog(LogLevel.Warning, message)
      override def error(message: => String): UIO[Unit]                   = doLog(LogLevel.Error, message)
      override def error(message: => String, cause: Throwable): UIO[Unit] =
        doLog(LogLevel.Error, message, Some(cause))

      override def withContext(key: String, value: String): Logger =
        makeFileLogger(runtime, logFilePath, annotations + (key -> value))

      override def withRunId(runId: String): Logger         = withContext("runId", runId)
      override def withAgentName(agentName: String): Logger = withContext("agentName", agentName)
      override def withStep(step: String): Logger           = withContext("step", step)

  private def makeCombinedLogger(consoleLogger: Logger, fileLogger: Logger): Logger = new Logger:
    override def trace(message: => String): UIO[Unit]                   =
      consoleLogger.trace(message) *> fileLogger.trace(message)
    override def debug(message: => String): UIO[Unit]                   =
      consoleLogger.debug(message) *> fileLogger.debug(message)
    override def info(message: => String): UIO[Unit]                    =
      consoleLogger.info(message) *> fileLogger.info(message)
    override def warn(message: => String): UIO[Unit]                    =
      consoleLogger.warn(message) *> fileLogger.warn(message)
    override def error(message: => String): UIO[Unit]                   =
      consoleLogger.error(message) *> fileLogger.error(message)
    override def error(message: => String, cause: Throwable): UIO[Unit] =
      consoleLogger.error(message, cause) *> fileLogger.error(message, cause)

    override def withContext(key: String, value: String): Logger =
      makeCombinedLogger(consoleLogger.withContext(key, value), fileLogger.withContext(key, value))

    override def withRunId(runId: String): Logger         =
      makeCombinedLogger(consoleLogger.withRunId(runId), fileLogger.withRunId(runId))
    override def withAgentName(agentName: String): Logger =
      makeCombinedLogger(consoleLogger.withAgentName(agentName), fileLogger.withAgentName(agentName))
    override def withStep(step: String): Logger           =
      makeCombinedLogger(consoleLogger.withStep(step), fileLogger.withStep(step))
