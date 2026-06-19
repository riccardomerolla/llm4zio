package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*

/** Build a [[ReplayConnector]] from a recorded trace file. */
object Replay:
  /** Read a `.jsonl` trace into [[TraceLine]]s. Blank lines are skipped; a line that fails to parse is skipped with a
    * warning (a crashed run can leave a torn final line) rather than aborting the read.
    */
  def read(path: Path): IO[FlowError, List[TraceLine]] =
    ZIO
      .attemptBlocking(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .mapError(e => FlowError.Persistence(s"failed to read trace at $path", Some(e)))
      .flatMap { content =>
        ZIO.foreach(content.linesIterator.filter(_.trim.nonEmpty).toList) { line =>
          line.fromJson[TraceLine] match
            case Right(tl) => ZIO.some(tl)
            case Left(err) => ZIO.logWarning(s"skipping unparseable trace line: $err").as(None)
        }
      }
      .map(_.flatten)

  /** Read + segment + build a connector with a fresh cursor. */
  def fromTrace(path: Path): IO[FlowError, ReplayConnector] =
    read(path).flatMap(lines => ReplayConnector.make(ReplayTurn.segment(lines)))
