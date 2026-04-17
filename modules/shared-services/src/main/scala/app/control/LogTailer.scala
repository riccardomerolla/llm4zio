package app.control

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.stream.*

enum LogLevel derives JsonCodec:
  case Debug, Info, Warn, Error

object LogLevel:

  val all: Set[LogLevel] = Set(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error)

  def parse(raw: String): Option[LogLevel] =
    raw.trim.toUpperCase match
      case "DEBUG" => Some(LogLevel.Debug)
      case "INFO"  => Some(LogLevel.Info)
      case "WARN"  => Some(LogLevel.Warn)
      case "ERROR" => Some(LogLevel.Error)
      case _       => None

final case class LogEvent(line: String, level: Option[LogLevel], ts: Long) derives JsonCodec

enum LogTailerError:
  case InvalidPath(path: String, reason: String)
  case ReadFailed(path: String, reason: String)

trait LogTailer:
  def tail(
    path: Path,
    levels: Set[LogLevel],
    search: Option[String],
    initialLines: Int = 200,
  ): ZStream[Any, LogTailerError, LogEvent]

object LogTailer:

  def tail(
    path: Path,
    levels: Set[LogLevel],
    search: Option[String],
    initialLines: Int = 200,
  ): ZStream[LogTailer, LogTailerError, LogEvent] =
    ZStream.serviceWithStream[LogTailer](_.tail(path, levels, search, initialLines))

  val live: ZLayer[Any, Nothing, LogTailer] =
    ZLayer.succeed(LogTailerLive())

final private case class TailState(seenLines: Int, initialized: Boolean)

final case class LogTailerLive(
  pollInterval: Duration = 500.millis
) extends LogTailer:

  private val levelPattern = "\\b(DEBUG|INFO|WARN|ERROR)\\b".r

  override def tail(
    path: Path,
    levels: Set[LogLevel],
    search: Option[String],
    initialLines: Int,
  ): ZStream[Any, LogTailerError, LogEvent] =
    ZStream.fromZIO(Ref.make(TailState(seenLines = 0, initialized = false))).flatMap { stateRef =>
      ZStream
        .repeatWithSchedule((), Schedule.spaced(pollInterval))
        .mapZIO(_ => readNewLines(path, stateRef, initialLines, levels, search))
        .mapConcat(identity)
    }

  private def readNewLines(
    path: Path,
    stateRef: Ref[TailState],
    initialLines: Int,
    levels: Set[LogLevel],
    search: Option[String],
  ): IO[LogTailerError, List[LogEvent]] =
    for
      allLines <- readAllLines(path)
      state    <- stateRef.get
      selected  =
        if !state.initialized then allLines.takeRight(initialLines.max(0))
        else if allLines.length >= state.seenLines then allLines.drop(state.seenLines)
        else allLines
      _        <- stateRef.set(TailState(allLines.length, initialized = true))
      nowMs    <- Clock.instant.map(_.toEpochMilli)
    yield selected
      .filter(line => matchesFilters(line, levels, search))
      .map { line =>
        LogEvent(
          line = line,
          level = detectLevel(line),
          ts = nowMs,
        )
      }

  private def readAllLines(path: Path): IO[LogTailerError, List[String]] =
    ZIO
      .attemptBlocking {
        if Files.exists(path) then Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList
        else Nil
      }
      .mapError { err =>
        LogTailerError.ReadFailed(path.toString, Option(err.getMessage).getOrElse(err.getClass.getSimpleName))
      }

  private def matchesFilters(line: String, levels: Set[LogLevel], search: Option[String]): Boolean =
    val levelAllowed =
      detectLevel(line) match
        case Some(level) => levels.contains(level)
        case None        => true

    val searchAllowed =
      search match
        case Some(term) if term.trim.nonEmpty => line.toLowerCase.contains(term.trim.toLowerCase)
        case _                                => true

    levelAllowed && searchAllowed

  private def detectLevel(line: String): Option[LogLevel] =
    levelPattern.findFirstMatchIn(line).flatMap(m => LogLevel.parse(m.group(1)))
