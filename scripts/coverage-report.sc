//> using scala "3.8.3"
//> using jvm 21

/** Summarise Scala 3 native statement coverage (scoverage v3.0 data).
  *
  *   scala-cli run scripts/coverage-report.sc -- <data-dir> [module ...]
  *
  * Reads <data-dir>/<module>/scoverage.coverage (the statement map) and the
  * scoverage.measurements.* files (runtime hits) written by an instrumented test run,
  * then prints overall % and the lowest-covered files. No dependencies — just the JDK.
  */

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*
import scala.util.Using

val base    = args.headOption.getOrElse("/tmp/llm4zio-cov")
val modules = if args.length > 1 then args.tail.toList else List("core", "flow", "runner")

/** id -> (source path, ignored) for every instrumented statement. */
def parseCoverage(file: Path): Map[Int, (String, Boolean)] =
  val content     = Files.readString(file)
  val afterHeader = content.indexOf("# ----") match
    case -1 => content
    case i  => content.substring(content.indexOf('\n', i) + 1)
  afterHeader
    .split("\f")
    .iterator
    .flatMap { block =>
      val lines = block.strip.linesIterator.toVector
      if lines.length < 15 then None
      else lines(0).toIntOption.map(id => id -> (lines(1).trim, lines(14).trim.equalsIgnoreCase("true")))
    }
    .toMap

/** The set of statement ids invoked at runtime, across all measurement files. */
def parseMeasurements(dir: Path): Set[Int] =
  if !Files.isDirectory(dir) then Set.empty
  else
    Using.resource(Files.list(dir)) { stream =>
      stream.iterator.asScala
        .filter(_.getFileName.toString.startsWith("scoverage.measurements."))
        .flatMap(f => Files.readAllLines(f).asScala)
        .flatMap(_.split("[ ;]").headOption)
        .flatMap(_.toIntOption)
        .toSet
    }

def pct(c: Int, t: Int): Double = if t == 0 then 0.0 else 100.0 * c / t

var total   = 0
var covered = 0
val perFile = scala.collection.mutable.Map.empty[String, (Int, Int)]

for m <- modules do
  val dir     = Path.of(base, m)
  val covFile = dir.resolve("scoverage.coverage")
  if !Files.exists(covFile) then println(s"$m: NO coverage data ($covFile)")
  else
    val stmts  = parseCoverage(covFile)
    val hit    = parseMeasurements(dir)
    val active = stmts.collect { case (id, (src, ignored)) if !ignored => id -> src }
    for (id, src) <- active do
      val (c, t) = perFile.getOrElse(src, (0, 0))
      perFile(src) = (c + (if hit(id) then 1 else 0), t + 1)
    val t = active.size
    val c = active.count((id, _) => hit(id))
    total += t
    covered += c
    println(f"== $m: $c/$t = ${pct(c, t)}%.1f%% statements ==")

println(f"%n==== OVERALL: $covered/$total = ${pct(covered, total)}%.1f%% statement coverage ====")
println("\n== Lowest-covered main files ==")
perFile.toList
  .filterNot((src, _) => src.contains("/src/test/"))
  .sortBy((_, ct) => (pct(ct._1, ct._2), -ct._2))
  .take(25)
  .foreach { case (src, (c, t)) =>
    val name = src.replace("modules/", "").replace("/src/main/scala/llm4zio/", ":")
    println(f"  ${pct(c, t)}%5.1f%%  $c%3d/$t%-4d  $name")
  }
