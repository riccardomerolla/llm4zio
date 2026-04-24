package eval.control

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.json.*

import eval.entity.EvalCase

/** Loads and parses JSONL eval datasets.
  *
  * Each non-blank line must decode as [[EvalCase]]. Comments starting with `#` and blank lines are ignored so datasets
  * can stay hand-editable.
  */
object EvalDataset:

  def load(path: Path): IO[String, List[EvalCase]] =
    ZIO
      .attempt {
        val raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        raw.linesIterator.toList
      }
      .mapError(e => s"Cannot read dataset $path: ${e.getMessage}")
      .flatMap(parseLines(path, _))

  private def parseLines(path: Path, lines: List[String]): IO[String, List[EvalCase]] =
    ZIO
      .foreach(lines.zipWithIndex) { case (line, idx) =>
        val trimmed = line.trim
        if trimmed.isEmpty || trimmed.startsWith("#") then ZIO.succeed(None)
        else
          ZIO
            .fromEither(trimmed.fromJson[EvalCase])
            .mapError(err => s"$path:${idx + 1} invalid JSONL: $err")
            .map(Some(_))
      }
      .map(_.flatten)
      .flatMap {
        case Nil   => ZIO.fail(s"Dataset $path has no cases")
        case cases => ZIO.succeed(cases)
      }

  /** Convenience for tests: parse from an in-memory iterable of lines. */
  def fromLines(source: String, lines: Iterable[String]): IO[String, List[EvalCase]] =
    parseLines(java.nio.file.Paths.get(source), lines.toList)
