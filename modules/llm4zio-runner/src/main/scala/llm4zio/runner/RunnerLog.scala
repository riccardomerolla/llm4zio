package llm4zio.runner

import java.nio.file.{ Files, Path }

import zio.*
import zio.logging.{ FileLoggerConfig, LogFilter, LogFormat, fileLogger }

/** A temp log file + a zio-logging file-logger layer. Routing logs to a file (and removing the default console logger)
  * keeps stdout clean for the rendered tree. The file persists on exit for inspection.
  */
object RunnerLog:

  /** Create the log file path (the temp file is created on disk). */
  val newLogFile: UIO[Path] =
    ZIO.succeed(Files.createTempFile("llm4zio-", ".log"))

  /** A layer that sends all `ZIO.log*` output to `logPath` and silences the default console logger. */
  def fileOnly(logPath: Path): ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>> fileLogger(
      FileLoggerConfig(
        destination = logPath,
        format = LogFormat.default,
        filter = LogFilter.LogLevelByNameConfig.default,
      )
    )
