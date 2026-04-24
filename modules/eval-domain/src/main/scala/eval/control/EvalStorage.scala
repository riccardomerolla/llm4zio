package eval.control

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.time.format.DateTimeFormatter

import zio.*
import zio.json.*

import eval.entity.EvalRun

/** Persists and reads [[EvalRun]] JSON files under `<workspace>/evals/runs/`.
  *
  * File name encodes both a sortable timestamp and the runId so `eval compare` can find the two most recent runs via
  * `ls` ordering without reading every file.
  */
object EvalStorage:

  private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC)

  def runsDir(workspace: Path): Path = workspace.resolve("evals").resolve("runs")

  def write(workspace: Path, run: EvalRun): IO[String, Path] =
    ZIO
      .attemptBlocking {
        val dir   = runsDir(workspace)
        Files.createDirectories(dir)
        val stamp = fileTimestamp.format(run.startedAt)
        val file  = dir.resolve(s"run-$stamp-${run.runId.take(8)}.json")
        Files.write(file, run.toJsonPretty.getBytes(StandardCharsets.UTF_8))
        file
      }
      .mapError(e => s"EvalStorage.write failed: ${e.getMessage}")

  def read(file: Path): IO[String, EvalRun] =
    ZIO
      .attemptBlocking(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
      .mapError(e => s"EvalStorage.read($file) failed: ${e.getMessage}")
      .flatMap(s =>
        ZIO.fromEither(s.fromJson[EvalRun]).mapError(err => s"EvalStorage.read($file) bad JSON: $err")
      )
